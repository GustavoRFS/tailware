// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package readiness represents the one bounded outcome of a native startup attempt.
package readiness

import (
	"context"
	"sync"
)

type Readiness struct {
	mu       sync.RWMutex
	done     chan struct{}
	finished bool
	err      error
}

func New() *Readiness {
	return &Readiness{done: make(chan struct{})}
}

// Complete publishes the startup result once. It reports whether this caller
// won the race against another success, failure, or timeout.
func (r *Readiness) Complete(err error) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.finished {
		return false
	}
	r.finished = true
	r.err = err
	close(r.done)
	return true
}

func (r *Readiness) Done() <-chan struct{} {
	return r.done
}

func (r *Readiness) Wait(ctx context.Context) error {
	select {
	case <-r.done:
		r.mu.RLock()
		defer r.mu.RUnlock()
		return r.err
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (r *Readiness) Result() (finished bool, err error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.finished, r.err
}
