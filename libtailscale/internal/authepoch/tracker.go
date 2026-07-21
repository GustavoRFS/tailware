// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package authepoch assigns ownership to interactive-authentication
// notifications crossing the Go/Android boundary.
package authepoch

import (
	"context"
	"sync"
)

// Tracker owns one active authentication epoch and tracks notifications that
// already entered the Android callback. Epoch zero is deliberately unowned.
type Tracker struct {
	mu                 sync.Mutex
	cond               *sync.Cond
	next               int64
	active             int64
	closing            bool
	executionCtx       context.Context
	executionCancel    context.CancelFunc
	inFlight           map[int64]int
	idle               map[int64]chan struct{}
	nextWatcherSession uint64
	watcherSessions    map[WatcherSession]struct{}
}

// WatcherSession immutably identifies one physical native notification
// subscription. It is captured when the watcher is created, before any
// callback can be queued.
type WatcherSession uint64

// New returns an initialized Tracker.
func New() *Tracker {
	executionCtx, executionCancel := context.WithCancel(context.Background())
	t := &Tracker{
		executionCtx:    executionCtx,
		executionCancel: executionCancel,
		inFlight:        map[int64]int{},
		idle:            map[int64]chan struct{}{},
		watcherSessions: map[WatcherSession]struct{}{},
	}
	t.cond = sync.NewCond(&t.mu)
	return t
}

// cancelExecutionLocked invalidates cancellable work that captured the
// current authentication boundary. Callers must hold t.mu.
func (t *Tracker) cancelExecutionLocked() {
	if t.executionCancel != nil {
		t.executionCancel()
	}
	t.executionCtx = nil
	t.executionCancel = nil
}

// renewExecutionLocked publishes a fresh cancellable context for the current
// sequence after an authentication boundary has completed. Callers must hold
// t.mu and must not call this while t.closing is true.
func (t *Tracker) renewExecutionLocked() {
	if t.closing {
		panic("renewing authentication execution context during a boundary")
	}
	t.executionCtx, t.executionCancel = context.WithCancel(context.Background())
}

// Begin starts and returns a fresh authentication epoch. A concurrent
// cancellation finishes its callback fence before the replacement epoch is
// made visible.
func (t *Tracker) Begin() int64 {
	return t.ReplaceContext(context.Background(), func() {})
}

// RegisterWatcher returns a fresh immutable identity for one physical native
// watcher. Authentication boundaries retire all registered identities before
// exposing the replacement epoch.
func (t *Tracker) RegisterWatcher() WatcherSession {
	t.mu.Lock()
	defer t.mu.Unlock()
	for t.closing {
		t.cond.Wait()
	}
	t.nextWatcherSession++
	if t.nextWatcherSession == 0 {
		t.nextWatcherSession++
	}
	session := WatcherSession(t.nextWatcherSession)
	t.watcherSessions[session] = struct{}{}
	return session
}

// UnregisterWatcher retires one physical watcher. A callback that has not yet
// entered Tailware will subsequently be rejected before it can read an epoch.
func (t *Tracker) UnregisterWatcher(session WatcherSession) {
	t.mu.Lock()
	delete(t.watcherSessions, session)
	t.mu.Unlock()
}

func (t *Tracker) retireWatchersLocked() {
	clear(t.watcherSessions)
}

// ReplaceContext retires any current epoch, fences its entered callbacks, and
// starts a replacement while preventing an AuthURL callback from auto-starting
// an epoch in the middle of that boundary.
func (t *Tracker) ReplaceContext(ctx context.Context, disconnect func()) int64 {
	t.mu.Lock()
	for t.closing {
		t.cond.Wait()
	}
	previousEpoch := t.active
	t.active = 0
	t.closing = true
	t.cancelExecutionLocked()
	t.retireWatchersLocked()
	waitForIdle := t.idle[previousEpoch]
	t.mu.Unlock()

	if previousEpoch != 0 {
		disconnect()
	}
	if waitForIdle != nil {
		select {
		case <-waitForIdle:
		case <-ctx.Done():
		}
	}

	t.mu.Lock()
	t.next++
	t.active = t.next
	t.closing = false
	t.renewExecutionLocked()
	t.cond.Broadcast()
	epoch := t.active
	t.mu.Unlock()
	return epoch
}

// EnterNotification captures ownership at the first line of the native
// notification callback. The returned function must be called after Android's
// callback returns.
//
// If autoBegin is true, an otherwise-unowned backend-generated AuthURL starts
// an epoch of its own. This covers reauthentication initiated by LocalBackend
// rather than by an Android login button.
func (t *Tracker) EnterNotification(session WatcherSession, autoBegin bool) (epoch int64, done func(), accepted bool) {
	t.mu.Lock()
	if _, ok := t.watcherSessions[session]; !ok {
		t.mu.Unlock()
		return 0, func() {}, false
	}
	if autoBegin && t.active == 0 && !t.closing {
		t.cancelExecutionLocked()
		t.next++
		t.active = t.next
		t.renewExecutionLocked()
	}
	epoch = t.active
	if epoch != 0 {
		if t.inFlight[epoch] == 0 {
			t.idle[epoch] = make(chan struct{})
		}
		t.inFlight[epoch]++
	}
	t.mu.Unlock()

	return epoch, func() {
		if epoch == 0 {
			return
		}
		t.mu.Lock()
		t.inFlight[epoch]--
		if t.inFlight[epoch] == 0 {
			delete(t.inFlight, epoch)
			close(t.idle[epoch])
			delete(t.idle, epoch)
			t.cond.Broadcast()
		}
		t.mu.Unlock()
	}, true
}

// EnterExecution establishes an execution-time ownership boundary for one
// exact authentication sequence. Sequence zero is the valid process baseline
// before the first explicit or backend-generated authentication epoch.
//
// Begin, Cancel, Finish, and another execution boundary wait until done is
// called. An AuthURL notification never waits because it may have been emitted
// synchronously by the guarded operation; while the boundary is active it is
// delivered without auto-starting a replacement epoch. The caller must call
// done exactly once when the guarded operation returns.
func (t *Tracker) EnterExecution(sequence int64) (done func(), accepted bool) {
	t.mu.Lock()
	for t.closing {
		t.cond.Wait()
	}
	if t.next != sequence {
		t.mu.Unlock()
		return func() {}, false
	}
	t.closing = true
	t.cancelExecutionLocked()
	t.mu.Unlock()

	return func() {
		t.mu.Lock()
		t.closing = false
		t.renewExecutionLocked()
		t.cond.Broadcast()
		t.mu.Unlock()
	}, true
}

// Cancel atomically invalidates epoch, runs disconnect, and waits for every
// Android callback that already captured epoch to return. It returns false
// without running disconnect when epoch no longer owns authentication.
func (t *Tracker) Cancel(epoch int64, disconnect func()) bool {
	return t.CancelContext(context.Background(), epoch, disconnect)
}

// CancelContext is Cancel with a bounded quiescence wait. Expiry does not
// restore ownership: callbacks already captured the retired epoch and remain
// rejectable even if Android's callback is abnormally slow.
func (t *Tracker) CancelContext(ctx context.Context, epoch int64, disconnect func()) bool {
	t.mu.Lock()
	for t.closing {
		t.cond.Wait()
	}
	if epoch == 0 || t.active != epoch {
		t.mu.Unlock()
		return false
	}
	t.active = 0
	t.closing = true
	t.cancelExecutionLocked()
	t.retireWatchersLocked()
	waitForIdle := t.idle[epoch]
	t.mu.Unlock()

	disconnect()

	if waitForIdle != nil {
		select {
		case <-waitForIdle:
		case <-ctx.Done():
		}
	}

	t.mu.Lock()
	t.closing = false
	t.renewExecutionLocked()
	t.cond.Broadcast()
	t.mu.Unlock()
	return true
}

// Finish invalidates a successfully completed epoch. Notifications that
// already entered Android retain the old epoch and are rejected by a later
// attempt.
func (t *Tracker) Finish(epoch int64) bool {
	return t.Cancel(epoch, func() {})
}

// FinishContext is Finish with a bounded callback-quiescence wait.
func (t *Tracker) FinishContext(ctx context.Context, epoch int64) bool {
	return t.CancelContext(ctx, epoch, func() {})
}

// Current returns the active epoch, or zero when authentication has no owner.
func (t *Tracker) Current() int64 {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.active
}

// Sequence returns the newest authentication sequence observed by the
// tracker. Unlike Current, zero is a meaningful baseline value and cancelling
// or finishing an epoch does not change the sequence.
func (t *Tracker) Sequence() int64 {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.next
}

// ContextForSequence returns a cancellable context for work owned by the
// exact latest authentication sequence. It rejects work while an
// authentication boundary is in progress. Begin, backend AuthURL auto-begin,
// Cancel, Finish, and legacy execution boundaries cancel every context
// captured before that boundary without waiting for the work to return.
//
// Cancellation and finish do not advance Sequence. Once either boundary has
// completed, a fresh context for that same latest sequence is published so
// exact-sequence cleanup work can still be started afterward.
func (t *Tracker) ContextForSequence(sequence int64) (context.Context, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closing || t.next != sequence || t.executionCtx == nil {
		return nil, false
	}
	return t.executionCtx, true
}
