// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"sync/atomic"
	"testing"
	"time"
)

type teardownTestService struct {
	id         string
	generation atomic.Int64
	authorized atomic.Bool
	acks       chan int64
}

func newTeardownTestService(id string, generation int64, authorized bool) *teardownTestService {
	service := &teardownTestService{id: id, acks: make(chan int64, 8)}
	service.generation.Store(generation)
	service.authorized.Store(authorized)
	return service
}

func (s *teardownTestService) ID() string               { return s.id }
func (s *teardownTestService) CommandGeneration() int64 { return s.generation.Load() }
func (s *teardownTestService) IsCommandAuthorized(generation int64) bool {
	return s.authorized.Load() && generation == s.generation.Load()
}
func (s *teardownTestService) HandleVpnServiceError(int64) bool { return false }
func (s *teardownTestService) Protect(int32) bool               { return false }
func (s *teardownTestService) BindSocketToNetwork(int32) bool   { return false }
func (s *teardownTestService) NewBuilder() VPNServiceBuilder    { return nil }
func (s *teardownTestService) Close()                           {}
func (s *teardownTestService) DisconnectVPN()                   {}
func (s *teardownTestService) UpdateVpnStatus(bool, int64)      {}
func (s *teardownTestService) NativeVpnTeardownComplete(generation int64) {
	s.acks <- generation
}

type blockingTeardownTarget struct {
	entered chan struct{}
	release chan struct{}
	closed  atomic.Bool
	calls   atomic.Int32
}

func newBlockingTeardownTarget() *blockingTeardownTarget {
	return &blockingTeardownTarget{
		entered: make(chan struct{}),
		release: make(chan struct{}),
	}
}

func (t *blockingTeardownTarget) teardownOwnedVPN() {
	if t.calls.Add(1) == 1 {
		close(t.entered)
	}
	<-t.release
	t.closed.Store(true)
}

func resetVPNLifecycleForTest(t *testing.T) {
	t.Helper()
	vpnService.mu.Lock()
	if vpnService.teardown != nil {
		vpnService.mu.Unlock()
		t.Fatal("previous VPN teardown is still in flight")
	}
	vpnService.service = nil
	vpnService.serviceID = ""
	vpnService.commandGeneration = 0
	vpnService.commandKind = vpnServiceCommandNone
	vpnService.target = nil
	vpnService.lastAckGeneration = 0
	vpnService.lastAckServices = nil
	vpnService.completedTeardowns = nil
	vpnService.completedTeardownOrder = nil
	vpnService.mu.Unlock()
	t.Cleanup(func() {
		vpnService.mu.Lock()
		vpnService.service = nil
		vpnService.serviceID = ""
		vpnService.commandGeneration = 0
		vpnService.commandKind = vpnServiceCommandNone
		vpnService.target = nil
		vpnService.teardown = nil
		vpnService.lastAckGeneration = 0
		vpnService.lastAckServices = nil
		vpnService.completedTeardowns = nil
		vpnService.completedTeardownOrder = nil
		vpnService.mu.Unlock()
	})
}

func installTeardownTestOwner(service IPNService, generation int64, target vpnTeardownTarget) {
	vpnService.mu.Lock()
	vpnService.service = service
	vpnService.serviceID = service.ID()
	vpnService.commandGeneration = generation
	vpnService.commandKind = vpnServiceCommandStart
	vpnService.target = target
	vpnService.mu.Unlock()
}

func receiveTeardownAck(t *testing.T, service *teardownTestService) int64 {
	t.Helper()
	select {
	case generation := <-service.acks:
		return generation
	case <-time.After(time.Second):
		t.Fatal("native teardown acknowledgement did not arrive")
		return 0
	}
}

func TestForceVPNTeardownIsNonBlockingAndAcknowledgesOnlyAfterClose(t *testing.T) {
	resetVPNLifecycleForTest(t)
	service := newTeardownTestService("service-a", 1, true)
	target := newBlockingTeardownTarget()
	installTeardownTestOwner(service, 1, target)
	service.generation.Store(2)
	service.authorized.Store(false)

	started := time.Now()
	if !ForceVPNTeardown(service, 2) {
		t.Fatal("exact force teardown was rejected")
	}
	if elapsed := time.Since(started); elapsed > 100*time.Millisecond {
		t.Fatalf("ForceVPNTeardown blocked for %v", elapsed)
	}
	select {
	case <-target.entered:
	case <-time.After(time.Second):
		t.Fatal("force teardown worker did not start")
	}
	select {
	case generation := <-service.acks:
		t.Fatalf("generation %d was acknowledged before descriptor closure", generation)
	default:
	}
	if _, ok := currentVPNServiceSnapshot(); ok {
		t.Fatal("old VPN owner remained publishable after force barrier")
	}

	close(target.release)
	if generation := receiveTeardownAck(t, service); generation != 2 {
		t.Fatalf("teardown acknowledgement = %d, want 2", generation)
	}
	if !target.closed.Load() {
		t.Fatal("teardown acknowledgement preceded target closure")
	}
}

func TestNativeAcknowledgementWaitsForInFlightTUNProducer(t *testing.T) {
	resetVPNLifecycleForTest(t)
	service := newTeardownTestService("service-a", 1, true)
	b := &backend{devices: newTUNDevices()}
	t.Cleanup(func() { _ = b.devices.Close() })
	b.tunOps.Add(1)
	installTeardownTestOwner(service, 1, b)
	service.generation.Store(2)
	service.authorized.Store(false)

	ServiceDisconnect(service, 2)
	select {
	case generation := <-service.acks:
		t.Fatalf("generation %d acknowledged while a TUN producer was in flight", generation)
	case <-time.After(25 * time.Millisecond):
	}
	b.tunOps.Done()
	if got := receiveTeardownAck(t, service); got != 2 {
		t.Fatalf("teardown acknowledgement = %d, want 2", got)
	}
}

func TestNativeAcknowledgementFencesEnteredProtectBindOperation(t *testing.T) {
	resetVPNLifecycleForTest(t)
	service := newTeardownTestService("service-a", 1, true)
	b := &backend{devices: newTUNDevices()}
	t.Cleanup(func() { _ = b.devices.Close() })
	installTeardownTestOwner(service, 1, b)
	leaveHook, entered := enterVPNHookOperation(service.ID(), 1)
	if !entered {
		t.Fatal("exact owner hook operation was rejected")
	}
	service.generation.Store(2)
	service.authorized.Store(false)

	ServiceDisconnect(service, 2)
	select {
	case generation := <-service.acks:
		t.Fatalf("generation %d acknowledged while protect/bind work was entered", generation)
	case <-time.After(25 * time.Millisecond):
	}
	if _, entered := enterVPNHookOperation(service.ID(), 1); entered {
		t.Fatal("old-generation hook work entered after the OFF barrier")
	}
	leaveHook()
	if got := receiveTeardownAck(t, service); got != 2 {
		t.Fatalf("teardown acknowledgement = %d, want 2", got)
	}
}

func TestForceVPNTeardownReplayReemitsAckWithoutClosingTwice(t *testing.T) {
	resetVPNLifecycleForTest(t)
	service := newTeardownTestService("service-a", 4, false)
	target := newBlockingTeardownTarget()
	installTeardownTestOwner(service, 3, target)
	if !ForceVPNTeardown(service, 4) {
		t.Fatal("force teardown was rejected")
	}
	<-target.entered
	close(target.release)
	if got := receiveTeardownAck(t, service); got != 4 {
		t.Fatalf("first acknowledgement = %d, want 4", got)
	}
	if !ForceVPNTeardown(service, 4) {
		t.Fatal("exact completed replay was rejected")
	}
	if got := receiveTeardownAck(t, service); got != 4 {
		t.Fatalf("replayed acknowledgement = %d, want 4", got)
	}
	if calls := target.calls.Load(); calls != 1 {
		t.Fatalf("teardown calls = %d, want 1", calls)
	}
}

func TestTeardownBarrierDelaysNewGenerationWithoutClosingItsTarget(t *testing.T) {
	resetVPNLifecycleForTest(t)
	oldService := newTeardownTestService("service-a", 6, false)
	oldTarget := newBlockingTeardownTarget()
	installTeardownTestOwner(oldService, 5, oldTarget)
	if !ForceVPNTeardown(oldService, 6) {
		t.Fatal("old-generation force teardown was rejected")
	}
	<-oldTarget.entered

	newService := newTeardownTestService("service-a", 7, true)
	newTarget := newBlockingTeardownTarget()
	reserved := make(chan string, 1)
	go func() {
		if !waitForVPNTeardown(context.Background()) {
			reserved <- "wait failed"
			return
		}
		_, rejection := reserveVPNStart(vpnServiceCommand{
			service:    newService,
			generation: 7,
			kind:       vpnServiceCommandStart,
		}, newTarget)
		reserved <- rejection
	}()
	select {
	case rejection := <-reserved:
		t.Fatalf("new generation crossed teardown barrier early: %q", rejection)
	case <-time.After(25 * time.Millisecond):
	}

	close(oldTarget.release)
	if got := receiveTeardownAck(t, oldService); got != 6 {
		t.Fatalf("old acknowledgement = %d, want 6", got)
	}
	select {
	case rejection := <-reserved:
		if rejection != "" {
			t.Fatalf("new generation was rejected after teardown: %s", rejection)
		}
	case <-time.After(time.Second):
		t.Fatal("new generation remained blocked after teardown")
	}
	if calls := newTarget.calls.Load(); calls != 0 {
		t.Fatalf("old teardown touched newer target %d times", calls)
	}
	if !vpnServiceOwnerCurrent(newService.ID(), 7) {
		t.Fatal("new generation did not become the exact owner")
	}
}

func TestAcceptedServiceDisconnectSurvivesRapidSuccessorAndForceReplay(t *testing.T) {
	resetVPNLifecycleForTest(t)
	service := newTeardownTestService("service-a", 1, true)
	oldTarget := newBlockingTeardownTarget()
	installTeardownTestOwner(service, 1, oldTarget)

	service.generation.Store(2)
	service.authorized.Store(false)
	ServiceDisconnect(service, 2)
	select {
	case <-oldTarget.entered:
	case <-time.After(time.Second):
		t.Fatal("synchronously accepted ServiceDisconnect did not start closure")
	}

	// A rapid successor changes Android's current generation before old closure
	// finishes. The previously earned OFF barrier and force replay must remain
	// valid, while the successor waits behind descriptor closure.
	service.generation.Store(3)
	service.authorized.Store(true)
	if !ForceVPNTeardown(service, 2) {
		t.Fatal("force replay rejected the already-accepted OFF generation")
	}
	unrelated := newTeardownTestService("unrelated-service", 1, false)
	if ForceVPNTeardown(unrelated, 1) {
		t.Fatal("unrelated stale generation joined another service's teardown")
	}

	newTarget := newBlockingTeardownTarget()
	reserved := make(chan string, 1)
	go func() {
		if !waitForVPNTeardown(context.Background()) {
			reserved <- "wait failed"
			return
		}
		_, rejection := reserveVPNStart(vpnServiceCommand{
			service:    service,
			generation: 3,
			kind:       vpnServiceCommandStart,
		}, newTarget)
		reserved <- rejection
	}()
	select {
	case rejection := <-reserved:
		t.Fatalf("rapid successor crossed the OFF barrier early: %q", rejection)
	case <-time.After(25 * time.Millisecond):
	}

	close(oldTarget.release)
	if got := receiveTeardownAck(t, service); got != 2 {
		t.Fatalf("accepted OFF acknowledgement = %d, want 2", got)
	}
	select {
	case rejection := <-reserved:
		if rejection != "" {
			t.Fatalf("rapid successor was rejected after closure: %s", rejection)
		}
	case <-time.After(time.Second):
		t.Fatal("rapid successor remained blocked after closure")
	}
	if calls := newTarget.calls.Load(); calls != 0 {
		t.Fatalf("old teardown touched successor target %d times", calls)
	}
}

func TestForceVPNTeardownRejectsStaleAndStillAuthorizedGenerations(t *testing.T) {
	resetVPNLifecycleForTest(t)
	service := newTeardownTestService("service-a", 9, true)
	target := newBlockingTeardownTarget()
	installTeardownTestOwner(service, 9, target)
	if ForceVPNTeardown(service, 9) {
		t.Fatal("force teardown accepted a still-authorized generation")
	}
	service.authorized.Store(false)
	service.generation.Store(8)
	if ForceVPNTeardown(service, 8) {
		t.Fatal("force teardown accepted a generation older than the native owner")
	}
	select {
	case <-target.entered:
		t.Fatal("rejected force teardown touched the descriptor target")
	default:
	}
}
