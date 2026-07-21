// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package cleanup

import (
	"errors"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

func TestCloseRunsEveryStepAfterErrorsAndPanics(t *testing.T) {
	group := NewGroup()
	var order []string
	err := group.Close(
		Step{Name: "first", Run: func() error {
			order = append(order, "first")
			return errors.New("failed")
		}},
		Step{Name: "second", Run: func() error {
			order = append(order, "second")
			panic("broken closer")
		}},
		Step{Name: "third", Run: func() error {
			order = append(order, "third")
			return nil
		}},
	)
	if got, want := strings.Join(order, ","), "first,second,third"; got != want {
		t.Fatalf("cleanup order = %q, want %q", got, want)
	}
	if err == nil || !strings.Contains(err.Error(), "first: failed") ||
		!strings.Contains(err.Error(), "second panicked: broken closer") {
		t.Fatalf("Close error = %v", err)
	}
}

func TestCloseIsConcurrentAndIdempotent(t *testing.T) {
	group := NewGroup()
	var calls atomic.Int32
	steps := []Step{{Name: "only", Run: func() error {
		calls.Add(1)
		return nil
	}}}
	var waiters sync.WaitGroup
	for range 8 {
		waiters.Add(1)
		go func() {
			defer waiters.Done()
			if err := group.Close(steps...); err != nil {
				t.Errorf("Close: %v", err)
			}
		}()
	}
	waiters.Wait()
	if got := calls.Load(); got != 1 {
		t.Fatalf("cleanup calls = %d, want 1", got)
	}
}
