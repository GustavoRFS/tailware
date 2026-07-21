// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/tailscale/wireguard-go/tun"
)

type gatedTUN struct {
	events chan tun.Event

	readEntered     chan struct{}
	readEnteredOnce sync.Once
	readRelease     chan struct{}

	eventsCalled     chan struct{}
	eventsCalledOnce sync.Once

	closeEntered     chan struct{}
	closeEnteredOnce sync.Once
	closeRelease     chan struct{}
	closeCalls       atomic.Int32
}

func newGatedTUN() *gatedTUN {
	return &gatedTUN{
		events:       make(chan tun.Event),
		readEntered:  make(chan struct{}),
		readRelease:  make(chan struct{}),
		eventsCalled: make(chan struct{}),
		closeEntered: make(chan struct{}),
		closeRelease: make(chan struct{}),
	}
}

func (d *gatedTUN) File() *os.File { return nil }

func (d *gatedTUN) Read(_ [][]byte, _ []int, _ int) (int, error) {
	d.readEnteredOnce.Do(func() { close(d.readEntered) })
	<-d.readRelease
	return 0, os.ErrClosed
}

func (d *gatedTUN) Write(bufs [][]byte, _ int) (int, error) {
	return len(bufs), nil
}

func (d *gatedTUN) MTU() (int, error)     { return 1280, nil }
func (d *gatedTUN) Name() (string, error) { return "test", nil }

func (d *gatedTUN) Events() <-chan tun.Event {
	d.eventsCalledOnce.Do(func() { close(d.eventsCalled) })
	return d.events
}

func (d *gatedTUN) Close() error {
	d.closeCalls.Add(1)
	d.closeEnteredOnce.Do(func() { close(d.closeEntered) })
	<-d.closeRelease
	return nil
}

func (d *gatedTUN) BatchSize() int { return 1 }

func release(ch chan struct{}) {
	select {
	case <-ch:
	default:
		close(ch)
	}
}

func await(t *testing.T, ch <-chan struct{}, what string) {
	t.Helper()
	select {
	case <-ch:
	case <-time.After(5 * time.Second):
		t.Fatalf("timed out waiting for %s", what)
	}
}

func TestSupersededQueuedDeviceIsClosed(t *testing.T) {
	multi := newTUNDevices()
	a := newGatedTUN()
	b := newGatedTUN()
	c := newGatedTUN()
	// B and C never need to block their Close implementations.
	release(b.closeRelease)
	release(c.closeRelease)
	t.Cleanup(func() {
		release(a.readRelease)
		release(a.closeRelease)
		release(b.readRelease)
		release(c.readRelease)
		closed := make(chan struct{})
		go func() {
			_ = multi.Close()
			close(closed)
		}()
		await(t, closed, "multiTUN shutdown")
	})

	multi.add(a)
	readReturned := make(chan struct{})
	go func() {
		defer close(readReturned)
		_, _ = multi.Read([][]byte{make([]byte, 1280)}, make([]int, 1), 0)
	}()
	await(t, a.readEntered, "the first device read")

	multi.add(b)
	await(t, a.closeEntered, "the first device close")
	// A's blocked Close prevents runDone, so B is still queued when C
	// supersedes it.
	multi.add(c)
	release(a.readRelease)

	await(t, c.eventsCalled, "the newest device promotion")
	await(t, b.closeEntered, "the superseded queued device close")
	if got := b.closeCalls.Load(); got != 1 {
		t.Fatalf("queued device Close calls = %d; want 1", got)
	}
	release(a.closeRelease)
	await(t, readReturned, "the first device read result")
}

func TestCloseWaitsForRemovedDevicePump(t *testing.T) {
	multi := newTUNDevices()
	a := newGatedTUN()
	b := newGatedTUN()
	release(b.closeRelease)
	t.Cleanup(func() {
		release(a.readRelease)
		release(a.closeRelease)
		release(b.readRelease)
		_ = multi.Close()
	})

	multi.add(a)
	readReturned := make(chan struct{})
	go func() {
		defer close(readReturned)
		_, _ = multi.Read([][]byte{make([]byte, 1280)}, make([]int, 1), 0)
	}()
	await(t, a.readEntered, "the first device read")

	multi.add(b)
	await(t, a.closeEntered, "the removed device close")
	release(a.readRelease)
	await(t, b.eventsCalled, "the replacement device promotion")

	closeReturned := make(chan struct{})
	go func() {
		_ = multi.Close()
		close(closeReturned)
	}()
	await(t, b.closeEntered, "the replacement device close")
	select {
	case <-closeReturned:
		t.Fatal("multiTUN Close returned while a removed device pump was still closing")
	case <-time.After(200 * time.Millisecond):
		// The timer is only a failure guard: A's gated Close is the causal fence.
	}

	release(a.closeRelease)
	await(t, closeReturned, "multiTUN close after removed device completion")
	await(t, readReturned, "the removed device read result")
}
