// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"log"
	"runtime/debug"
	"sync"
	"sync/atomic"

	"github.com/tailscale/tailscale-android/libtailscale/internal/authepoch"
	"github.com/tailscale/tailscale-android/libtailscale/internal/notifymarshal"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnlocal"
)

func (app *App) WatchNotifications(mask int, cb NotificationCallback) NotificationManager {
	readyCtx, readyCancel := context.WithTimeout(context.Background(), nativeBackendReadinessTimeout)
	defer readyCancel()
	backend, _, err := app.waitForBackend(readyCtx)
	if err != nil {
		log.Printf("WatchNotifications not started because native backend is unavailable: %v", err)
		return &notificationManager{app: app, stopped: true}
	}

	manager := &notificationManager{
		app:  app,
		mask: ipn.NotifyWatchOpt(mask),
		cb:   cb,
	}
	// New registration is serialized with the entire pause/boundary/resume
	// sequence so it either belongs wholly before or wholly after that boundary.
	app.authenticationMu.Lock()
	app.notificationMu.Lock()
	if app.notificationManagers == nil {
		app.notificationManagers = map[*notificationManager]struct{}{}
	}
	app.notificationManagers[manager] = struct{}{}
	app.notificationMu.Unlock()
	registration := manager.resume(backend)
	if !waitForNotificationRegistrations(readyCtx, []*notificationRegistration{registration}) {
		log.Printf("WatchNotifications replacement did not register before its readiness deadline")
	}
	app.authenticationMu.Unlock()
	return manager
}

func (app *App) dispatchNotification(
	session authepoch.WatcherSession,
	cb NotificationCallback,
	authenticationBootstrap bool,
	notify *ipn.Notify,
) bool {
	// EnterNotification never waits for authenticationMu. A guarded LocalAPI
	// handler can synchronously emit this callback while holding that mutex;
	// Tracker.EnterExecution suppresses AuthURL auto-begin for exactly that
	// interval, so taking authenticationMu here would deadlock instead of adding
	// ownership safety.
	authenticationEpoch, leaveAuthenticationCallback, accepted :=
		app.authentication.EnterNotification(session, notify.BrowseToURL != nil)
	if !accepted {
		return false
	}
	defer leaveAuthenticationCallback()
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in WatchNotifications %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	b, err := notifymarshal.Marshal(notify, authenticationEpoch, authenticationBootstrap)
	if err != nil {
		log.Printf("error: WatchNotifications: marshal notify: %s", err)
		return true
	}
	if err := cb.OnNotify(b); err != nil {
		log.Printf("error: WatchNotifications: OnNotify: %s", err)
	}
	return true
}

type notificationManager struct {
	app  *App
	mask ipn.NotifyWatchOpt
	cb   NotificationCallback

	mu      sync.Mutex
	session authepoch.WatcherSession
	cancel  context.CancelFunc
	stopped bool
}

type notificationRegistration struct {
	ready <-chan struct{}
	done  <-chan struct{}
}

func (nm *notificationManager) resume(backend *ipnlocal.LocalBackend) *notificationRegistration {
	nm.mu.Lock()
	defer nm.mu.Unlock()
	if nm.stopped {
		return nil
	}
	if nm.cancel != nil {
		panic("notification manager resumed without being paused")
	}
	session := nm.app.authentication.RegisterWatcher()
	ctx, cancel := context.WithCancel(context.Background())
	nm.session = session
	nm.cancel = cancel
	ready := make(chan struct{})
	done := make(chan struct{})
	var readyOnce sync.Once
	var firstNotification atomic.Bool
	firstNotification.Store(true)
	go func() {
		defer close(done)
		backend.WatchNotifications(
			ctx,
			nm.mask,
			func() { readyOnce.Do(func() { close(ready) }) },
			func(notify *ipn.Notify) bool {
				bootstrap := firstNotification.CompareAndSwap(true, false)
				return nm.app.dispatchNotification(session, nm.cb, bootstrap, notify)
			},
		)
	}()
	return &notificationRegistration{ready: ready, done: done}
}

func (nm *notificationManager) pause() {
	nm.mu.Lock()
	defer nm.mu.Unlock()
	nm.pauseLocked()
}

func (nm *notificationManager) pauseLocked() {
	if nm.session != 0 {
		nm.app.authentication.UnregisterWatcher(nm.session)
		nm.session = 0
	}
	if nm.cancel != nil {
		nm.cancel()
		nm.cancel = nil
	}
}

func (nm *notificationManager) Stop() {
	// Never acquire authenticationMu here: an authentication boundary may hold
	// it while waiting for an entered Android callback to return.
	nm.app.notificationMu.Lock()
	delete(nm.app.notificationManagers, nm)
	nm.app.notificationMu.Unlock()

	nm.mu.Lock()
	if !nm.stopped {
		nm.stopped = true
		nm.pauseLocked()
	}
	nm.mu.Unlock()
}

func (app *App) snapshotNotificationManagers() []*notificationManager {
	app.notificationMu.Lock()
	defer app.notificationMu.Unlock()
	managers := make([]*notificationManager, 0, len(app.notificationManagers))
	for manager := range app.notificationManagers {
		managers = append(managers, manager)
	}
	return managers
}

func pauseNotificationManagers(managers []*notificationManager) {
	for _, manager := range managers {
		manager.pause()
	}
}

func resumeNotificationManagers(
	backend *ipnlocal.LocalBackend,
	managers []*notificationManager,
) []*notificationRegistration {
	registrations := make([]*notificationRegistration, 0, len(managers))
	for _, manager := range managers {
		if registration := manager.resume(backend); registration != nil {
			registrations = append(registrations, registration)
		}
	}
	return registrations
}

func waitForNotificationRegistrations(
	ctx context.Context,
	registrations []*notificationRegistration,
) bool {
	for _, registration := range registrations {
		select {
		case <-registration.ready:
		case <-registration.done:
			return false
		case <-ctx.Done():
			return false
		}
	}
	return true
}
