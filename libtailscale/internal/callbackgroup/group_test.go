// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package callbackgroup

import (
	"context"
	"sync/atomic"
	"testing"
	"time"
)

func TestStopCancelsBlockedCallbacksAndRejectsNewWork(t *testing.T) {
	group := New(context.Background())
	entered := make(chan struct{})
	returned := make(chan struct{})
	if !group.Go(func(ctx context.Context) {
		close(entered)
		<-ctx.Done()
		close(returned)
	}) {
		t.Fatal("first callback was rejected")
	}
	<-entered

	group.Stop()
	group.Wait()
	select {
	case <-returned:
	default:
		t.Fatal("Wait returned before the cancelled callback")
	}
	if group.Go(func(context.Context) {}) {
		t.Fatal("callback entered after Stop")
	}
	if _, leave, ok := group.Enter(); ok {
		leave()
		t.Fatal("synchronous callback entered after Stop")
	}
}

func TestWaitFencesSynchronousCallback(t *testing.T) {
	group := New(context.Background())
	ctx, leave, ok := group.Enter()
	if !ok {
		t.Fatal("callback was rejected")
	}
	exited := make(chan struct{})
	go func() {
		defer close(exited)
		<-ctx.Done()
		time.Sleep(10 * time.Millisecond)
		leave()
	}()

	group.Stop()
	group.Wait()
	select {
	case <-exited:
	default:
		t.Fatal("Wait did not fence the synchronous callback")
	}
}

func TestStopIsIdempotent(t *testing.T) {
	group := New(context.Background())
	var returned atomic.Int32
	if !group.Go(func(ctx context.Context) {
		<-ctx.Done()
		returned.Add(1)
	}) {
		t.Fatal("callback was rejected")
	}
	group.Stop()
	group.Stop()
	group.Wait()
	if got := returned.Load(); got != 1 {
		t.Fatalf("callback returns = %d, want 1", got)
	}
}
