// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package callbackgroup fences callbacks owned by one backend attempt.
package callbackgroup

import (
	"context"
	"sync"
)

// Group owns a cancellable context and tracks every callback that entered
// before Stop. Once stopped, no new callback can enter. Callers may therefore
// close the resources that drive callbacks and then Wait to establish a hard
// quiescence boundary before constructing a replacement backend.
type Group struct {
	ctx    context.Context
	cancel context.CancelFunc

	mu       sync.Mutex
	stopping bool
	wg       sync.WaitGroup
}

// New returns a callback group whose lifetime is bounded by parent.
func New(parent context.Context) *Group {
	ctx, cancel := context.WithCancel(parent)
	return &Group{ctx: ctx, cancel: cancel}
}

// Context returns the context shared by callbacks in the group.
func (g *Group) Context() context.Context { return g.ctx }

// Enter registers one synchronous callback. The returned context must be used
// for all potentially blocking work and leave must be called exactly once.
func (g *Group) Enter() (ctx context.Context, leave func(), ok bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.stopping {
		return g.ctx, func() {}, false
	}
	g.wg.Add(1)
	return g.ctx, g.wg.Done, true
}

// Go starts and tracks fn unless the group is already stopping.
func (g *Group) Go(fn func(context.Context)) bool {
	ctx, leave, ok := g.Enter()
	if !ok {
		return false
	}
	go func() {
		defer leave()
		fn(ctx)
	}()
	return true
}

// Stop prevents new callbacks from entering and cancels callbacks already in
// progress. It is safe to call repeatedly and concurrently.
func (g *Group) Stop() {
	g.mu.Lock()
	if !g.stopping {
		g.stopping = true
		g.cancel()
	}
	g.mu.Unlock()
}

// Wait blocks until callbacks admitted before Stop have returned. Stop must be
// called first; separating the two operations lets owners cancel callbacks,
// close the resources needed to unblock them, and only then wait.
func (g *Group) Wait() { g.wg.Wait() }
