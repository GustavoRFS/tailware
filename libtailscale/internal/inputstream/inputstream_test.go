// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package inputstream

import (
	"errors"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

type sourceResult struct {
	data []byte
	err  error
}

type scriptedSource struct {
	results    []sourceResult
	closeErr   error
	closeCount atomic.Int32
	panicClose bool
}

func (s *scriptedSource) Read() ([]byte, error) {
	if len(s.results) == 0 {
		return nil, nil
	}
	result := s.results[0]
	s.results = s.results[1:]
	return result.data, result.err
}

func (s *scriptedSource) Close() error {
	s.closeCount.Add(1)
	if s.panicClose {
		panic("boom")
	}
	return s.closeErr
}

func TestBufferedChunkPreservesTerminalError(t *testing.T) {
	sentinel := errors.New("sentinel")
	r := New(&scriptedSource{results: []sourceResult{{[]byte("abcd"), sentinel}}})
	buf := make([]byte, 2)
	if n, err := r.Read(buf); n != 2 || err != nil || string(buf) != "ab" {
		t.Fatalf("first read = %q, %v, %v", buf[:n], n, err)
	}
	if n, err := r.Read(buf); n != 2 || !errors.Is(err, sentinel) || string(buf) != "cd" {
		t.Fatalf("second read = %q, %v, %v", buf[:n], n, err)
	}
}

func TestNilChunkMeansEOF(t *testing.T) {
	r := New(&scriptedSource{results: []sourceResult{{nil, nil}}})
	if n, err := r.Read(make([]byte, 1)); n != 0 || !errors.Is(err, io.EOF) {
		t.Fatalf("Read = %v, %v; want 0, EOF", n, err)
	}
}

func TestReadErrorPropagates(t *testing.T) {
	sentinel := errors.New("read failed")
	r := New(&scriptedSource{results: []sourceResult{{nil, sentinel}}})
	if n, err := r.Read(make([]byte, 1)); n != 0 || !errors.Is(err, sentinel) {
		t.Fatalf("Read = %v, %v; want sentinel", n, err)
	}
}

type blockingSource struct {
	started    chan struct{}
	unblock    chan struct{}
	closeCount atomic.Int32
	closeErr   error
	startOnce  sync.Once
	closeOnce  sync.Once
}

func (s *blockingSource) Read() ([]byte, error) {
	s.startOnce.Do(func() { close(s.started) })
	<-s.unblock
	return nil, io.ErrClosedPipe
}

func (s *blockingSource) Close() error {
	s.closeCount.Add(1)
	s.closeOnce.Do(func() { close(s.unblock) })
	return s.closeErr
}

func TestCloseUnblocksReadAndClosesSourceOnce(t *testing.T) {
	sentinel := errors.New("close failed")
	source := &blockingSource{started: make(chan struct{}), unblock: make(chan struct{}), closeErr: sentinel}
	r := New(source)
	readDone := make(chan error, 1)
	go func() {
		_, err := r.Read(make([]byte, 1))
		readDone <- err
	}()
	<-source.started

	var wg sync.WaitGroup
	errs := make(chan error, 16)
	for range 16 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			errs <- r.Close()
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if !errors.Is(err, sentinel) {
			t.Fatalf("Close error = %v; want sentinel", err)
		}
	}
	if source.closeCount.Load() != 1 {
		t.Fatalf("source Close count = %d; want 1", source.closeCount.Load())
	}
	if err := <-readDone; !errors.Is(err, io.ErrClosedPipe) {
		t.Fatalf("blocked Read error = %v; want closed pipe", err)
	}
}

func TestOwnerClosesVisitedAndAllUnvisitedOnce(t *testing.T) {
	sources := []*scriptedSource{{}, {}, {}}
	owner := Own(sources[0], sources[1], sources[2])
	if err := owner.Reader(0).Close(); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() { defer wg.Done(); _ = owner.Close() }()
	}
	wg.Wait()
	for i, source := range sources {
		if source.closeCount.Load() != 1 {
			t.Fatalf("source %d Close count = %d; want 1", i, source.closeCount.Load())
		}
	}
}

func TestOwnerContinuesAfterClosePanic(t *testing.T) {
	first := &scriptedSource{panicClose: true}
	second := &scriptedSource{}
	err := Own(first, second).Close()
	if err == nil || !strings.Contains(err.Error(), "close panic: boom") {
		t.Fatalf("Owner.Close error = %v; want recovered panic", err)
	}
	if second.closeCount.Load() != 1 {
		t.Fatalf("later source Close count = %d; want 1", second.closeCount.Load())
	}
}
