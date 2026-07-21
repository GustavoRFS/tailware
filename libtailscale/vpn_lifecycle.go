// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"fmt"
	"log"
	"runtime/debug"
	"strconv"

	"github.com/tailscale/tailscale-android/libtailscale/internal/vpnauthorization"
	"tailscale.com/net/netns"
)

// vpnTeardownTarget owns the native descriptors driven by one backend
// attempt. teardownOwnedVPN must not return until every descriptor it owned at
// the teardown barrier has been closed.
type vpnTeardownTarget interface {
	teardownOwnedVPN()
}

type vpnOperationTarget interface {
	beginOwnedVPNOperation() func()
}

type vpnTeardownWaiter struct {
	service    IPNService
	serviceID  string
	generation int64
}

type vpnTeardown struct {
	target     vpnTeardownTarget
	generation int64
	waiters    map[string]vpnTeardownWaiter
	done       chan struct{}
}

func vpnTeardownWaiterKey(serviceID string, generation int64) string {
	return serviceID + "\x00" + strconv.FormatInt(generation, 10)
}

func (t *vpnTeardown) addWaiter(service IPNService, serviceID string, generation int64) {
	if t.waiters == nil {
		t.waiters = map[string]vpnTeardownWaiter{}
	}
	t.waiters[vpnTeardownWaiterKey(serviceID, generation)] = vpnTeardownWaiter{
		service:    service,
		serviceID:  serviceID,
		generation: generation,
	}
	if generation > t.generation {
		t.generation = generation
	}
}

func (b *backend) teardownOwnedVPN() {
	if b == nil {
		return
	}
	if b.devices != nil {
		b.devices.Down()
	}
	// Close descriptors already published at the barrier, then fence builders
	// that entered before it. A second close handles any publication that won
	// the state mutex immediately before the barrier was installed.
	if b.devices != nil {
		b.CloseTUNs()
	}
	b.tunOps.Wait()
	if b.devices != nil {
		b.CloseTUNs()
	}
}

func (b *backend) beginOwnedVPNOperation() func() {
	b.tunOps.Add(1)
	return b.tunOps.Done
}

type vpnStartReservation struct {
	snapshot    vpnServiceSnapshot
	replacement bool
}

// reserveVPNStart atomically publishes the exact native owner before any TUN
// or process-global hook can be installed. The caller performs the Android
// desired-generation and capability checks immediately before calling it.
func reserveVPNStart(command vpnServiceCommand, target vpnTeardownTarget) (vpnStartReservation, string) {
	serviceID := command.service.ID()
	vpnService.mu.Lock()
	defer vpnService.mu.Unlock()
	if vpnService.teardown != nil {
		return vpnStartReservation{}, "teardown in progress"
	}
	if command.generation < vpnService.commandGeneration {
		return vpnStartReservation{}, "stale generation"
	}

	sameService := vpnService.service != nil && vpnService.serviceID == serviceID
	if command.generation == vpnService.commandGeneration {
		switch vpnService.commandKind {
		case vpnServiceCommandDisconnect:
			if vpnService.serviceID == serviceID {
				return vpnStartReservation{}, "generation already disconnected"
			}
		case vpnServiceCommandStart:
			if sameService {
				return vpnStartReservation{}, "duplicate start"
			}
		}
	}

	replacement := vpnService.service != nil && !sameService
	vpnService.service = command.service
	vpnService.serviceID = serviceID
	vpnService.commandGeneration = command.generation
	vpnService.commandKind = vpnServiceCommandStart
	vpnService.target = target
	vpnService.lastAckGeneration = 0
	vpnService.lastAckServices = nil
	return vpnStartReservation{
		snapshot: vpnServiceSnapshot{
			service:           command.service,
			serviceID:         serviceID,
			commandGeneration: command.generation,
		},
		replacement: replacement,
	}, ""
}

func installVPNHooks(reservation vpnStartReservation) bool {
	snapshot := reservation.snapshot
	if !snapshot.isDesiredCommandCurrent() {
		return false
	}
	leaveInstallation, current := enterVPNHookOperation(snapshot.serviceID, snapshot.commandGeneration)
	if !current {
		return false
	}
	defer leaveInstallation()
	s := snapshot.service
	generation := snapshot.commandGeneration
	serviceID := snapshot.serviceID
	netns.SetAndroidProtectFunc(func(fd int) error {
		leave, current := enterVPNHookOperation(serviceID, generation)
		if !current {
			return fmt.Errorf("VPN protect rejected for superseded generation %d", generation)
		}
		defer leave()
		if !vpnauthorization.IsCommandAuthorized(s, generation) {
			return fmt.Errorf("VPN protect rejected for superseded generation %d", generation)
		}
		if !s.Protect(int32(fd)) {
			return fmt.Errorf("VpnService.protect(%d) rejected for generation %d", fd, generation)
		}
		return nil
	})
	netns.SetAndroidBindToNetworkFunc(func(fd int) error {
		leave, current := enterVPNHookOperation(serviceID, generation)
		if !current {
			return fmt.Errorf("VPN bind rejected for superseded generation %d", generation)
		}
		defer leave()
		if !vpnauthorization.IsCommandAuthorized(s, generation) {
			return fmt.Errorf("VPN bind rejected for superseded generation %d", generation)
		}
		if !s.BindSocketToNetwork(int32(fd)) {
			return fmt.Errorf("VPN bind(%d) rejected for generation %d", fd, generation)
		}
		return nil
	})
	if snapshot.isCurrent() {
		return true
	}
	// A teardown barrier may have won while the process-global hooks were being
	// installed. The operation lease makes its acknowledgement wait for this
	// cleanup, without imposing a state-mutex/netns lock-order dependency.
	netns.SetAndroidProtectFunc(nil)
	netns.SetAndroidBindToNetworkFunc(nil)
	return false
}

// enterVPNHookOperation admits protect/bind work only for the exact published
// owner and registers it before releasing the ownership mutex. Teardown first
// revokes that owner under the same mutex and then waits on the target, so its
// acknowledgement also fences hook calls that had already crossed the gate.
func enterVPNHookOperation(serviceID string, generation int64) (func(), bool) {
	vpnService.mu.Lock()
	defer vpnService.mu.Unlock()
	if vpnService.service == nil ||
		vpnService.serviceID != serviceID ||
		vpnService.commandGeneration != generation ||
		vpnService.commandKind != vpnServiceCommandStart ||
		vpnService.teardown != nil {
		return func() {}, false
	}
	if target, ok := vpnService.target.(vpnOperationTarget); ok {
		return target.beginOwnedVPNOperation(), true
	}
	return func() {}, true
}

func vpnServiceOwnerCurrent(serviceID string, generation int64) bool {
	vpnService.mu.Lock()
	defer vpnService.mu.Unlock()
	return vpnService.service != nil &&
		vpnService.serviceID == serviceID &&
		vpnService.commandGeneration == generation &&
		vpnService.commandKind == vpnServiceCommandStart &&
		vpnService.teardown == nil
}

// waitForVPNTeardown prevents a newer START from sharing the process-wide
// protect/bind hooks or multiTUN while an accepted teardown is still closing
// the prior owner's descriptors.
func waitForVPNTeardown(ctx context.Context) bool {
	for {
		vpnService.mu.Lock()
		teardown := vpnService.teardown
		vpnService.mu.Unlock()
		if teardown == nil {
			return true
		}
		select {
		case <-teardown.done:
		case <-ctx.Done():
			return false
		}
	}
}

const maxCompletedVPNTeardowns = 64

func recordCompletedVPNTeardownLocked(serviceID string, generation int64) {
	key := vpnTeardownWaiterKey(serviceID, generation)
	if vpnService.completedTeardowns == nil {
		vpnService.completedTeardowns = map[string]struct{}{}
	}
	if _, exists := vpnService.completedTeardowns[key]; exists {
		return
	}
	vpnService.completedTeardowns[key] = struct{}{}
	vpnService.completedTeardownOrder = append(vpnService.completedTeardownOrder, key)
	if len(vpnService.completedTeardownOrder) <= maxCompletedVPNTeardowns {
		return
	}
	oldest := vpnService.completedTeardownOrder[0]
	delete(vpnService.completedTeardowns, oldest)
	vpnService.completedTeardownOrder = vpnService.completedTeardownOrder[1:]
}

// acceptedVPNTeardown returns an already-installed or already-completed exact
// service/generation barrier. It deliberately ignores the service's current
// desired generation: a newer Connect must not revoke an OFF teardown that was
// accepted synchronously before the successor appeared.
func acceptedVPNTeardown(service IPNService, generation int64) (*vpnTeardown, bool) {
	if service == nil || generation <= 0 {
		return nil, false
	}
	key := vpnTeardownWaiterKey(service.ID(), generation)
	vpnService.mu.Lock()
	defer vpnService.mu.Unlock()
	if vpnService.teardown != nil {
		if _, exists := vpnService.teardown.waiters[key]; exists {
			return vpnService.teardown, true
		}
	}
	_, completed := vpnService.completedTeardowns[key]
	return nil, completed
}

// beginVPNTeardown installs the disconnect barrier before any potentially
// blocking descriptor work begins. A new barrier requires the exact current
// Android desired generation. An already-accepted exact service/generation is
// replayable even after a successor changes that desired generation. The
// return values are the teardown to execute, whether this caller created it,
// and whether the request was accepted.
func beginVPNTeardown(service IPNService, generation int64, target vpnTeardownTarget) (*vpnTeardown, bool, bool) {
	if service == nil || generation <= 0 {
		return nil, false, false
	}
	desiredGeneration := service.CommandGeneration()
	serviceID := service.ID()
	waiterKey := vpnTeardownWaiterKey(serviceID, generation)
	vpnService.mu.Lock()
	defer vpnService.mu.Unlock()

	if vpnService.teardown != nil {
		if _, exists := vpnService.teardown.waiters[waiterKey]; exists {
			return vpnService.teardown, false, true
		}
		if desiredGeneration != generation {
			return nil, false, false
		}
		if generation < vpnService.teardown.generation {
			return nil, false, false
		}
		vpnService.service = nil
		vpnService.serviceID = serviceID
		vpnService.commandGeneration = generation
		vpnService.commandKind = vpnServiceCommandDisconnect
		vpnService.teardown.addWaiter(service, serviceID, generation)
		return vpnService.teardown, false, true
	}
	if _, completed := vpnService.completedTeardowns[waiterKey]; completed {
		return nil, false, true
	}
	if desiredGeneration != generation || generation < vpnService.commandGeneration {
		return nil, false, false
	}

	if generation == vpnService.commandGeneration &&
		vpnService.commandKind == vpnServiceCommandDisconnect {
		if vpnService.lastAckGeneration == generation {
			if _, ok := vpnService.lastAckServices[serviceID]; ok {
				recordCompletedVPNTeardownLocked(serviceID, generation)
				return nil, false, true
			}
			// An equal-generation replacement service inherits only the proven
			// closed state, never the old service reference.
			vpnService.serviceID = serviceID
			vpnService.lastAckServices[serviceID] = struct{}{}
			recordCompletedVPNTeardownLocked(serviceID, generation)
			return nil, false, true
		}
	}

	if generation == vpnService.commandGeneration &&
		vpnService.commandKind == vpnServiceCommandStart &&
		(vpnService.service == nil || vpnService.serviceID != serviceID) {
		return nil, false, false
	}
	if target == nil {
		target = vpnService.target
	}
	if vpnService.service != nil && target == nil {
		// An active owner without a teardown target cannot prove descriptor
		// closure, so retain the fail-closed owner and withhold acknowledgement.
		return nil, false, false
	}
	teardown := &vpnTeardown{
		target:     target,
		generation: generation,
		waiters:    map[string]vpnTeardownWaiter{},
		done:       make(chan struct{}),
	}
	teardown.addWaiter(service, serviceID, generation)

	// This is the fail-closed publication point. Every hook and TUN publication
	// path rechecks these fields under the same mutex, so no old-generation work
	// can be admitted after this assignment.
	vpnService.service = nil
	vpnService.serviceID = serviceID
	vpnService.commandGeneration = generation
	vpnService.commandKind = vpnServiceCommandDisconnect
	vpnService.teardown = teardown
	vpnService.lastAckGeneration = 0
	vpnService.lastAckServices = nil
	return teardown, true, true
}

func completeVPNTeardown(teardown *vpnTeardown) {
	defer func() {
		if recovered := recover(); recovered != nil {
			// Do not acknowledge partial teardown. Keep the published disconnect
			// barrier in place so a newer START cannot inherit uncertain resources.
			log.Printf("panic during native VPN teardown: %v\n%s", recovered, debug.Stack())
		}
	}()

	if teardown.target != nil {
		teardown.target.teardownOwnedVPN()
	}
	// Hook removal deliberately follows descriptor closure. A successful
	// acknowledgement therefore covers devices, TUN descriptors, and both
	// process-global JNI hooks.
	netns.SetAndroidProtectFunc(nil)
	netns.SetAndroidBindToNetworkFunc(nil)

	vpnService.mu.Lock()
	if vpnService.teardown != teardown {
		vpnService.mu.Unlock()
		return
	}
	waiters := make([]vpnTeardownWaiter, 0, len(teardown.waiters))
	latestServices := map[string]struct{}{}
	for _, waiter := range teardown.waiters {
		waiters = append(waiters, waiter)
		recordCompletedVPNTeardownLocked(waiter.serviceID, waiter.generation)
		if waiter.generation == vpnService.commandGeneration {
			latestServices[waiter.serviceID] = struct{}{}
		}
	}
	vpnService.teardown = nil
	vpnService.target = nil
	vpnService.lastAckGeneration = vpnService.commandGeneration
	vpnService.lastAckServices = latestServices
	close(teardown.done)
	vpnService.mu.Unlock()

	for _, waiter := range waiters {
		reportNativeVPNTeardownComplete(waiter.service, waiter.generation)
	}
}

func reportNativeVPNTeardownComplete(service IPNService, generation int64) {
	go func() {
		defer func() {
			if recovered := recover(); recovered != nil {
				log.Printf(
					"panic reporting native VPN teardown completion for generation=%d: %v\n%s",
					generation,
					recovered,
					debug.Stack(),
				)
			}
		}()
		service.NativeVpnTeardownComplete(generation)
	}()
}

func forceVPNTeardown(service IPNService, generation int64) bool {
	if service == nil || generation <= 0 {
		return false
	}
	if teardown, accepted := acceptedVPNTeardown(service, generation); accepted {
		if teardown == nil {
			reportNativeVPNTeardownComplete(service, generation)
		}
		return true
	}
	if service.CommandGeneration() != generation || service.IsCommandAuthorized(generation) {
		return false
	}
	teardown, created, accepted := beginVPNTeardown(service, generation, nil)
	if !accepted {
		return false
	}
	if teardown == nil {
		// This exact generation was already fully torn down. Re-emit the earned
		// acknowledgement so a replay can recover from a lost Android callback.
		reportNativeVPNTeardownComplete(service, generation)
		return true
	}
	if created {
		go completeVPNTeardown(teardown)
	}
	return true
}
