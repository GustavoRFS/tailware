// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package readiness

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestWaitReturnsPublishedFailure(t *testing.T) {
	startupErr := errors.New("backend failed")
	ready := New()
	if !ready.Complete(startupErr) {
		t.Fatal("failure did not complete readiness")
	}
	if err := ready.Wait(context.Background()); !errors.Is(err, startupErr) {
		t.Fatalf("Wait error = %v; want %v", err, startupErr)
	}
}

func TestWaitIsBoundedByCallerContext(t *testing.T) {
	ready := New()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if err := ready.Wait(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("Wait error = %v; want deadline exceeded", err)
	}
}

func TestOnlyFirstOutcomeWins(t *testing.T) {
	startupErr := errors.New("startup timeout")
	ready := New()
	if !ready.Complete(startupErr) {
		t.Fatal("failure did not win")
	}
	if ready.Complete(nil) {
		t.Fatal("late success overwrote failure")
	}
	if err := ready.Wait(context.Background()); !errors.Is(err, startupErr) {
		t.Fatalf("Wait error = %v; want %v", err, startupErr)
	}
}

func TestFreshReadinessCanSucceedAfterFailedAttempt(t *testing.T) {
	first := New()
	first.Complete(errors.New("first attempt failed"))

	retry := New()
	if !retry.Complete(nil) {
		t.Fatal("fresh attempt did not complete")
	}
	if err := retry.Wait(context.Background()); err != nil {
		t.Fatalf("fresh attempt Wait: %v", err)
	}
}
