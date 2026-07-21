// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package firstresult arbitrates one terminal result among concurrent owners.
package firstresult

import "sync/atomic"

type outcome[T any] struct {
	value T
	err   error
}

// First accepts exactly one result. Losing TrySet calls never block, including
// after Wait has drained the buffered winner.
type First[T any] struct {
	claimed atomic.Bool
	ready   chan outcome[T]
}

func New[T any]() *First[T] { return &First[T]{ready: make(chan outcome[T], 1)} }

func (f *First[T]) TrySet(value T, err error) bool {
	if !f.claimed.CompareAndSwap(false, true) {
		return false
	}
	f.ready <- outcome[T]{value: value, err: err}
	return true
}

func (f *First[T]) Wait() (T, error) {
	result := <-f.ready
	return result.value, result.err
}
