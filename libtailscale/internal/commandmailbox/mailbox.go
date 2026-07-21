// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package commandmailbox provides a bounded, non-blocking producer mailbox.
package commandmailbox

import (
	"errors"
	"sync"
)

var errInvalidCapacity = errors.New("command mailbox capacity must be positive")

// Mailbox retains commands in submission order while never making a producer
// wait for a consumer. When full, the oldest command is discarded so the
// newest desired-state command is always retained.
//
// Fail atomically rejects future submissions and returns every command that
// was already pending. This lets the bridge report an asynchronous native
// startup failure to the exact Android command owners.
type Mailbox[T any] struct {
	mu       sync.Mutex
	capacity int
	pending  []T
	inflight []T
	wake     chan struct{}
	failure  error
	ready    bool
}

func New[T any](capacity int) *Mailbox[T] {
	if capacity <= 0 {
		panic(errInvalidCapacity)
	}
	return &Mailbox[T]{
		capacity: capacity,
		pending:  make([]T, 0, capacity),
		inflight: make([]T, 0, capacity),
		wake:     make(chan struct{}, 1),
	}
}

// Begin starts a fresh native-backend attempt. It is intended to be called
// before Android can submit commands for that attempt.
func (m *Mailbox[T]) Begin() {
	m.mu.Lock()
	m.pending = m.pending[:0]
	m.inflight = m.inflight[:0]
	m.failure = nil
	m.ready = false
	for {
		select {
		case <-m.wake:
		default:
			m.mu.Unlock()
			return
		}
	}
}

// Retry prepares the mailbox for a replacement native-backend attempt without
// losing Android desired-state commands. Commands already delivered to the
// failed attempt are put back in front of commands that arrived while it was
// shutting down. Capacity remains bounded and, if necessary, the oldest
// commands are discarded so the newest Android generation is always retained.
//
// Retry must only be called after the old backend consumer has completely
// stopped. Submit remains producer-non-blocking while that cleanup is in
// progress.
func (m *Mailbox[T]) Retry() {
	m.mu.Lock()
	combined := make([]T, 0, len(m.inflight)+len(m.pending))
	combined = append(combined, m.inflight...)
	combined = append(combined, m.pending...)
	if len(combined) > m.capacity {
		combined = combined[len(combined)-m.capacity:]
	}
	clear(m.pending)
	clear(m.inflight)
	m.pending = append(m.pending[:0], combined...)
	m.inflight = m.inflight[:0]
	m.failure = nil
	m.ready = false
	for {
		select {
		case <-m.wake:
		default:
			if len(m.pending) > 0 {
				select {
				case m.wake <- struct{}{}:
				default:
				}
			}
			m.mu.Unlock()
			return
		}
	}
}

// Submit retains item without waiting for the backend consumer. If native
// startup already failed, it returns that failure and does not retain item.
func (m *Mailbox[T]) Submit(item T) error {
	m.mu.Lock()
	if m.failure != nil {
		err := m.failure
		m.mu.Unlock()
		return err
	}
	if len(m.pending) == m.capacity {
		copy(m.pending, m.pending[1:])
		m.pending[len(m.pending)-1] = item
	} else {
		m.pending = append(m.pending, item)
	}
	m.mu.Unlock()
	m.signal()
	return nil
}

// Wake is signaled whenever Pop may be able to return a command.
func (m *Mailbox[T]) Wake() <-chan struct{} {
	return m.wake
}

// Pop returns the oldest retained command. If commands remain, it re-signals
// Wake so a select-based consumer can continue draining without producer help.
func (m *Mailbox[T]) Pop() (item T, ok bool) {
	m.mu.Lock()
	if len(m.pending) == 0 {
		m.mu.Unlock()
		return item, false
	}
	item = m.pending[0]
	copy(m.pending, m.pending[1:])
	var zero T
	m.pending[len(m.pending)-1] = zero
	m.pending = m.pending[:len(m.pending)-1]
	if !m.ready {
		if len(m.inflight) == m.capacity {
			copy(m.inflight, m.inflight[1:])
			m.inflight[len(m.inflight)-1] = item
		} else {
			m.inflight = append(m.inflight, item)
		}
	}
	more := len(m.pending) > 0
	m.mu.Unlock()
	if more {
		m.signal()
	}
	return item, true
}

// MarkReady acknowledges every command the consumer received during native
// startup. Later commands no longer need startup-failure bookkeeping.
func (m *Mailbox[T]) MarkReady() {
	m.mu.Lock()
	m.ready = true
	clear(m.inflight)
	m.inflight = m.inflight[:0]
	m.mu.Unlock()
}

// Fail records a terminal startup failure for this attempt and atomically
// drains both commands already delivered during startup and commands still
// waiting for the backend.
func (m *Mailbox[T]) Fail(err error) (pending []T, won bool) {
	if err == nil {
		panic("command mailbox failure must be non-nil")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.failure != nil {
		return nil, false
	}
	m.failure = err
	pending = append(pending, m.inflight...)
	pending = append(pending, m.pending...)
	clear(m.inflight)
	m.inflight = m.inflight[:0]
	clear(m.pending)
	m.pending = m.pending[:0]
	return pending, true
}

func (m *Mailbox[T]) signal() {
	select {
	case m.wake <- struct{}{}:
	default:
	}
}
