// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package firstresult

import (
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestTrySetBeforeWaitDoesNotBlock(t *testing.T) {
	f := New[int]()
	done := make(chan bool, 1)
	go func() { done <- f.TrySet(7, nil) }()
	select {
	case won := <-done:
		if !won {
			t.Fatal("first TrySet lost")
		}
	case <-time.After(time.Second):
		t.Fatal("TrySet blocked before Wait")
	}
	if got, err := f.Wait(); got != 7 || err != nil {
		t.Fatalf("Wait = %v, %v", got, err)
	}
}

func TestLateTrySetAfterWaitReturnsFalseWithoutBlocking(t *testing.T) {
	f := New[int]()
	f.TrySet(1, nil)
	f.Wait()
	done := make(chan bool, 1)
	go func() { done <- f.TrySet(2, nil) }()
	select {
	case won := <-done:
		if won {
			t.Fatal("late TrySet won")
		}
	case <-time.After(time.Second):
		t.Fatal("late TrySet blocked")
	}
}

func TestConcurrentTrySetHasExactlyOneWinner(t *testing.T) {
	f := New[int]()
	var winners atomic.Int32
	var wg sync.WaitGroup
	for i := range 64 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if f.TrySet(i, nil) {
				winners.Add(1)
			}
		}()
	}
	wg.Wait()
	if winners.Load() != 1 {
		t.Fatalf("winners = %d; want 1", winners.Load())
	}
	f.Wait()
}

func TestWinnerPreservesValueAndError(t *testing.T) {
	sentinel := errors.New("winner")
	f := New[string]()
	f.TrySet("value", sentinel)
	value, err := f.Wait()
	if value != "value" || !errors.Is(err, sentinel) {
		t.Fatalf("Wait = %q, %v", value, err)
	}
}
