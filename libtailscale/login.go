// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"log"
	"time"
)

const authenticationCallbackFenceTimeout = 5 * time.Second

// AuthenticationSequence snapshots the newest explicit or backend-generated
// authentication sequence. Zero is a valid baseline before the first epoch;
// cancelling or finishing an epoch does not advance it.
func (app *App) AuthenticationSequence() int64 {
	app.authenticationMu.Lock()
	defer app.authenticationMu.Unlock()
	return app.authentication.Sequence()
}

// runIfAuthenticationSequence serializes action with every explicit
// authentication boundary. EnterExecution also prevents a synchronous AuthURL
// notification emitted by action from auto-beginning a replacement epoch; the
// notification is allowed through unowned instead of waiting and deadlocking.
func (app *App) runIfAuthenticationSequence(ctx context.Context, sequence int64, action func()) bool {
	app.authenticationMu.Lock()
	defer app.authenticationMu.Unlock()
	if ctx.Err() != nil {
		return false
	}
	leave, accepted := app.authentication.EnterExecution(sequence)
	if !accepted {
		return false
	}
	defer leave()
	if ctx.Err() != nil {
		return false
	}
	action()
	return true
}

// BeginAuthentication starts a fresh owner for native login notifications.
func (app *App) BeginAuthentication() int64 {
	ctx, cancel := context.WithTimeout(context.Background(), nativeBackendReadinessTimeout)
	defer cancel()
	backend, _, err := app.waitForBackend(ctx)
	if err != nil {
		log.Printf("BeginAuthentication ignored because native backend is unavailable: %v", err)
		return 0
	}
	app.authenticationMu.Lock()
	defer app.authenticationMu.Unlock()
	managers := app.snapshotNotificationManagers()
	pauseNotificationManagers(managers)
	fenceCtx, fenceCancel :=
		context.WithTimeout(context.Background(), authenticationCallbackFenceTimeout)
	defer fenceCancel()
	epoch := app.authentication.ReplaceContext(fenceCtx, backend.DisconnectControl)
	registrations := resumeNotificationManagers(backend, managers)
	if !waitForNotificationRegistrations(ctx, registrations) {
		log.Printf("BeginAuthentication replacement notification watcher did not register")
	}
	return epoch
}

// CancelInteractiveLogin disconnects the active control client. LocalBackend
// clears its pending AuthURL as part of that operation, while leaving profiles,
// node keys, and preferences untouched. The callback fence ensures replacement
// work cannot start until callbacks that already captured this epoch returned.
func (app *App) CancelInteractiveLogin(authenticationEpoch int64) bool {
	ctx, cancel := context.WithTimeout(context.Background(), nativeBackendReadinessTimeout)
	defer cancel()
	backend, _, err := app.waitForBackend(ctx)
	if err != nil {
		log.Printf("CancelInteractiveLogin ignored because native backend is unavailable: %v", err)
		return false
	}
	app.authenticationMu.Lock()
	defer app.authenticationMu.Unlock()
	if app.authentication.Current() != authenticationEpoch {
		return false
	}
	managers := app.snapshotNotificationManagers()
	pauseNotificationManagers(managers)
	fenceCtx, fenceCancel :=
		context.WithTimeout(context.Background(), authenticationCallbackFenceTimeout)
	defer fenceCancel()
	cancelled := app.authentication.CancelContext(
		fenceCtx,
		authenticationEpoch,
		backend.DisconnectControl,
	)
	registrations := resumeNotificationManagers(backend, managers)
	if !waitForNotificationRegistrations(ctx, registrations) {
		log.Printf("CancelInteractiveLogin replacement notification watcher did not register")
	}
	return cancelled
}

// FinishAuthentication retires a successful notification owner.
func (app *App) FinishAuthentication(authenticationEpoch int64) bool {
	ctx, cancel := context.WithTimeout(context.Background(), nativeBackendReadinessTimeout)
	defer cancel()
	backend, _, err := app.waitForBackend(ctx)
	if err != nil {
		log.Printf("FinishAuthentication ignored because native backend is unavailable: %v", err)
		return false
	}
	app.authenticationMu.Lock()
	defer app.authenticationMu.Unlock()
	if app.authentication.Current() != authenticationEpoch {
		return false
	}
	managers := app.snapshotNotificationManagers()
	pauseNotificationManagers(managers)
	fenceCtx, fenceCancel :=
		context.WithTimeout(context.Background(), authenticationCallbackFenceTimeout)
	defer fenceCancel()
	finished := app.authentication.FinishContext(fenceCtx, authenticationEpoch)
	registrations := resumeNotificationManagers(backend, managers)
	if !waitForNotificationRegistrations(ctx, registrations) {
		log.Printf("FinishAuthentication replacement notification watcher did not register")
	}
	return finished
}
