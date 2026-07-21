// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package authepoch

import (
	"context"
	"sync/atomic"
	"testing"
	"time"
)

func TestStaleNotificationRetainsOldEpochAcrossReplacement(t *testing.T) {
	tracker := New()
	epochA := tracker.Begin()
	sessionA := tracker.RegisterWatcher()
	stampedA, releaseA, accepted := tracker.EnterNotification(sessionA, false)
	if !accepted {
		t.Fatal("current watcher was rejected")
	}
	if stampedA != epochA {
		t.Fatalf("notification epoch = %d, want %d", stampedA, epochA)
	}

	cancelDone := make(chan bool, 1)
	go func() {
		cancelDone <- tracker.Cancel(epochA, func() {})
	}()
	select {
	case <-cancelDone:
		t.Fatal("cancellation returned before the old callback quiesced")
	case <-time.After(20 * time.Millisecond):
	}

	releaseA()
	if !<-cancelDone {
		t.Fatal("current epoch cancellation was rejected")
	}
	epochB := tracker.Begin()
	if epochB == epochA {
		t.Fatal("replacement authentication reused an epoch")
	}
	if stampedA == epochB {
		t.Fatal("old callback was relabeled as the replacement epoch")
	}
}

func TestStaleCancellationCannotDisconnectReplacement(t *testing.T) {
	tracker := New()
	epochA := tracker.Begin()
	if !tracker.Finish(epochA) {
		t.Fatal("failed to finish epoch A")
	}
	epochB := tracker.Begin()
	var disconnects atomic.Int32
	if tracker.Cancel(epochA, func() { disconnects.Add(1) }) {
		t.Fatal("stale cancellation claimed epoch B")
	}
	if disconnects.Load() != 0 {
		t.Fatal("stale cancellation disconnected the replacement")
	}
	if tracker.Current() != epochB {
		t.Fatalf("active epoch = %d, want %d", tracker.Current(), epochB)
	}
}

func TestBackendAuthURLCanStartOwnedEpoch(t *testing.T) {
	tracker := New()
	session := tracker.RegisterWatcher()
	epoch, done, accepted := tracker.EnterNotification(session, true)
	if !accepted {
		t.Fatal("current watcher was rejected")
	}
	defer done()
	if epoch == 0 {
		t.Fatal("backend-generated AuthURL remained unowned")
	}
	if tracker.Current() != epoch {
		t.Fatalf("active epoch = %d, want %d", tracker.Current(), epoch)
	}
}

func TestNotificationDuringCancellationStaysUnowned(t *testing.T) {
	tracker := New()
	epoch := tracker.Begin()
	session := tracker.RegisterWatcher()
	var stamped int64
	if !tracker.Cancel(epoch, func() {
		stamped, _, _ = tracker.EnterNotification(session, true)
	}) {
		t.Fatal("current cancellation was rejected")
	}
	if stamped != 0 {
		t.Fatalf("notification emitted by disconnect got epoch %d, want 0", stamped)
	}
}

func TestCancellationTimeoutDoesNotRestoreOldOwnership(t *testing.T) {
	tracker := New()
	epoch := tracker.Begin()
	session := tracker.RegisterWatcher()
	stamped, release, accepted := tracker.EnterNotification(session, false)
	if !accepted {
		t.Fatal("current watcher was rejected")
	}
	if stamped != epoch {
		t.Fatalf("notification epoch = %d, want %d", stamped, epoch)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if !tracker.CancelContext(ctx, epoch, func() {}) {
		t.Fatal("current cancellation was rejected")
	}
	if tracker.Current() != 0 {
		t.Fatalf("active epoch = %d, want 0", tracker.Current())
	}
	release()
}

func TestReplacementBlocksAuthURLAutoBeginInsideBoundary(t *testing.T) {
	tracker := New()
	epochA := tracker.Begin()
	session := tracker.RegisterWatcher()
	var boundaryEpoch int64
	epochB :=
		tracker.ReplaceContext(context.Background(), func() {
			boundaryEpoch, _, _ = tracker.EnterNotification(session, true)
		})
	if boundaryEpoch != 0 {
		t.Fatalf("boundary AuthURL got epoch %d, want 0", boundaryEpoch)
	}
	if epochB == 0 || epochB == epochA {
		t.Fatalf("replacement epoch = %d, previous = %d", epochB, epochA)
	}
}

func TestQueuedOldWatcherCannotCaptureReplacementEpoch(t *testing.T) {
	tracker := New()
	epochA := tracker.Begin()
	sessionA := tracker.RegisterWatcher()
	epochB := tracker.ReplaceContext(context.Background(), func() {})
	if epochA == epochB {
		t.Fatal("replacement reused epoch")
	}
	if epoch, _, accepted := tracker.EnterNotification(sessionA, true); accepted || epoch != 0 {
		t.Fatalf("retired watcher entered as epoch %d, accepted=%v", epoch, accepted)
	}
	if tracker.Current() != epochB {
		t.Fatalf("retired AuthURL changed active epoch to %d; want %d", tracker.Current(), epochB)
	}

	sessionB := tracker.RegisterWatcher()
	if epoch, done, accepted := tracker.EnterNotification(sessionB, false); !accepted || epoch != epochB {
		t.Fatalf("fresh watcher entered as epoch %d, accepted=%v; want %d", epoch, accepted, epochB)
	} else {
		done()
	}
}

func TestCancelAndFinishRetireQueuedWatcherBeforeAutoBegin(t *testing.T) {
	for _, finish := range []bool{false, true} {
		name := "cancel"
		if finish {
			name = "finish"
		}
		t.Run(name, func(t *testing.T) {
			tracker := New()
			epoch := tracker.Begin()
			session := tracker.RegisterWatcher()
			var ok bool
			if finish {
				ok = tracker.Finish(epoch)
			} else {
				ok = tracker.Cancel(epoch, func() {})
			}
			if !ok {
				t.Fatal("current boundary was rejected")
			}
			if got, _, accepted := tracker.EnterNotification(session, true); accepted || got != 0 {
				t.Fatalf("retired watcher entered as epoch %d, accepted=%v", got, accepted)
			}
			if tracker.Current() != 0 {
				t.Fatalf("retired watcher auto-began ghost epoch %d", tracker.Current())
			}
		})
	}
}

func TestUnregisterWatcherRejectsQueuedCallback(t *testing.T) {
	tracker := New()
	tracker.Begin()
	session := tracker.RegisterWatcher()
	tracker.UnregisterWatcher(session)
	if epoch, _, accepted := tracker.EnterNotification(session, false); accepted || epoch != 0 {
		t.Fatalf("unregistered watcher entered as epoch %d, accepted=%v", epoch, accepted)
	}
}

func TestExecutionAcceptsOnlyTheExactLatestSequence(t *testing.T) {
	tracker := New()
	if done, accepted := tracker.EnterExecution(0); !accepted {
		t.Fatal("baseline sequence zero was rejected")
	} else {
		done()
	}

	epochA := tracker.Begin()
	if !tracker.Cancel(epochA, func() {}) {
		t.Fatal("failed to cancel epoch A")
	}
	if tracker.Sequence() != epochA {
		t.Fatalf("sequence after cancellation = %d, want %d", tracker.Sequence(), epochA)
	}
	if done, accepted := tracker.EnterExecution(epochA); !accepted {
		t.Fatal("exact cancelled epoch was rejected before a replacement began")
	} else {
		done()
	}

	epochB := tracker.Begin()
	if _, accepted := tracker.EnterExecution(epochA); accepted {
		t.Fatal("stale epoch A executed after epoch B began")
	}
	if _, accepted := tracker.EnterExecution(0); accepted {
		t.Fatal("baseline sequence executed after an authentication epoch began")
	}
	if done, accepted := tracker.EnterExecution(epochB); !accepted {
		t.Fatal("latest epoch B was rejected")
	} else {
		done()
	}
}

func TestExecutionSerializesExplicitAuthenticationBoundaries(t *testing.T) {
	for _, test := range []struct {
		name       string
		transition func(*Tracker, int64) bool
	}{
		{name: "begin", transition: func(tracker *Tracker, _ int64) bool { return tracker.Begin() != 0 }},
		{name: "cancel", transition: func(tracker *Tracker, epoch int64) bool { return tracker.Cancel(epoch, func() {}) }},
		{name: "finish", transition: func(tracker *Tracker, epoch int64) bool { return tracker.Finish(epoch) }},
	} {
		t.Run(test.name, func(t *testing.T) {
			tracker := New()
			epoch := tracker.Begin()
			leave, accepted := tracker.EnterExecution(epoch)
			if !accepted {
				t.Fatal("latest sequence was rejected")
			}

			transitionDone := make(chan bool, 1)
			transitionAttempted := make(chan struct{})
			go func() {
				close(transitionAttempted)
				transitionDone <- test.transition(tracker, epoch)
			}()
			<-transitionAttempted
			select {
			case <-transitionDone:
				leave()
				t.Fatal("authentication boundary passed an executing handler")
			case <-time.After(20 * time.Millisecond):
			}

			leave()
			if !<-transitionDone {
				t.Fatal("authentication boundary failed after execution returned")
			}
		})
	}
}

func TestExecutionSuppressesSynchronousAuthURLAutoBeginWithoutBlocking(t *testing.T) {
	tracker := New()
	session := tracker.RegisterWatcher()
	leave, accepted := tracker.EnterExecution(0)
	if !accepted {
		t.Fatal("baseline sequence was rejected")
	}

	type notificationEntry struct {
		epoch    int64
		done     func()
		accepted bool
	}
	entered := make(chan notificationEntry, 1)
	go func() {
		epoch, done, accepted := tracker.EnterNotification(session, true)
		entered <- notificationEntry{epoch: epoch, done: done, accepted: accepted}
	}()
	select {
	case entry := <-entered:
		if !entry.accepted || entry.epoch != 0 {
			leave()
			t.Fatalf("boundary AuthURL entered as epoch %d, accepted=%v; want unowned", entry.epoch, entry.accepted)
		}
		entry.done()
	case <-time.After(time.Second):
		leave()
		t.Fatal("AuthURL callback blocked on its own execution boundary")
	}
	if tracker.Sequence() != 0 {
		leave()
		t.Fatalf("boundary AuthURL advanced sequence to %d", tracker.Sequence())
	}
	leave()

	epoch, done, accepted := tracker.EnterNotification(session, true)
	if !accepted || epoch == 0 {
		t.Fatalf("post-boundary AuthURL entered as epoch %d, accepted=%v", epoch, accepted)
	}
	done()
}

func TestSequenceContextCancelledAndReplacedAcrossBoundaries(t *testing.T) {
	tracker := New()
	baseline, accepted := tracker.ContextForSequence(0)
	if !accepted {
		t.Fatal("baseline sequence context was rejected")
	}

	epoch := tracker.Begin()
	select {
	case <-baseline.Done():
	default:
		t.Fatal("Begin did not cancel the baseline sequence context")
	}
	current, accepted := tracker.ContextForSequence(epoch)
	if !accepted {
		t.Fatal("new epoch sequence context was rejected")
	}

	if !tracker.Cancel(epoch, func() {}) {
		t.Fatal("Cancel rejected the current epoch")
	}
	select {
	case <-current.Done():
	default:
		t.Fatal("Cancel did not cancel in-flight sequence work")
	}
	postCancel, accepted := tracker.ContextForSequence(epoch)
	if !accepted {
		t.Fatal("Cancel did not publish a fresh context for exact-sequence cleanup")
	}
	if postCancel.Err() != nil {
		t.Fatalf("post-cancel context is already cancelled: %v", postCancel.Err())
	}
	if _, accepted := tracker.ContextForSequence(epoch - 1); accepted {
		t.Fatal("stale sequence context was accepted")
	}
}

func TestSequenceContextDoesNotMakeCancelWaitForRequestReturn(t *testing.T) {
	tracker := New()
	epoch := tracker.Begin()
	requestCtx, accepted := tracker.ContextForSequence(epoch)
	if !accepted {
		t.Fatal("current sequence context was rejected")
	}

	cancelDone := make(chan bool, 1)
	go func() { cancelDone <- tracker.Cancel(epoch, func() {}) }()
	select {
	case cancelled := <-cancelDone:
		if !cancelled {
			t.Fatal("Cancel rejected the current epoch")
		}
	case <-time.After(time.Second):
		t.Fatal("Cancel waited for cancellable request work to return")
	}
	select {
	case <-requestCtx.Done():
	default:
		t.Fatal("Cancel returned without cancelling the request context")
	}
}

func TestFinishCancelsAndRenewsExactSequenceContext(t *testing.T) {
	tracker := New()
	epoch := tracker.Begin()
	requestCtx, accepted := tracker.ContextForSequence(epoch)
	if !accepted {
		t.Fatal("current sequence context was rejected")
	}
	if !tracker.Finish(epoch) {
		t.Fatal("Finish rejected the current epoch")
	}
	select {
	case <-requestCtx.Done():
	default:
		t.Fatal("Finish did not cancel in-flight sequence work")
	}
	postFinish, accepted := tracker.ContextForSequence(epoch)
	if !accepted || postFinish.Err() != nil {
		t.Fatalf("post-finish exact sequence context = %v, accepted=%v", postFinish, accepted)
	}
}

func TestAuthURLAutoBeginCancelsBaselineSequenceContext(t *testing.T) {
	tracker := New()
	baseline, accepted := tracker.ContextForSequence(0)
	if !accepted {
		t.Fatal("baseline sequence context was rejected")
	}
	session := tracker.RegisterWatcher()
	epoch, done, accepted := tracker.EnterNotification(session, true)
	if !accepted || epoch == 0 {
		t.Fatalf("AuthURL entry = epoch %d, accepted=%v", epoch, accepted)
	}
	defer done()
	select {
	case <-baseline.Done():
	default:
		t.Fatal("AuthURL auto-begin did not cancel baseline request context")
	}
	if _, accepted := tracker.ContextForSequence(epoch); !accepted {
		t.Fatal("AuthURL epoch context was not published")
	}
}
