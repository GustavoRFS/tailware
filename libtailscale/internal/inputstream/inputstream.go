// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package inputstream adapts gomobile input streams without hiding terminal
// errors or losing ownership of their Java-side resources.
package inputstream

import (
	"errors"
	"fmt"
	"io"
	"sync"
	"sync/atomic"
)

// Source is the gomobile-facing stream contract.
type Source interface {
	Read() ([]byte, error)
	Close() error
}

// New returns an io.ReadCloser that owns source. Closing it closes source
// exactly once and may run concurrently with Read to unblock a Java read.
func New(source Source) io.ReadCloser {
	if source == nil {
		return nil
	}
	return &reader{source: source}
}

type reader struct {
	source Source

	pending    []byte
	pendingErr error

	closed    atomic.Bool
	closeOnce sync.Once
	closeErr  error
}

func (r *reader) Read(dst []byte) (int, error) {
	if len(dst) == 0 {
		return 0, nil
	}
	if r.closed.Load() {
		return 0, io.ErrClosedPipe
	}

	if len(r.pending) != 0 {
		n := copy(dst, r.pending)
		r.pending = r.pending[n:]
		if len(r.pending) == 0 && r.pendingErr != nil {
			err := r.pendingErr
			r.pendingErr = nil
			return n, err
		}
		return n, nil
	}

	for {
		chunk, err := r.source.Read()
		if len(chunk) == 0 {
			if err != nil {
				return 0, err
			}
			if chunk == nil {
				return 0, io.EOF
			}
			continue
		}

		n := copy(dst, chunk)
		if n < len(chunk) {
			r.pending = chunk[n:]
			r.pendingErr = err
			return n, nil
		}
		return n, err
	}
}

func (r *reader) Close() error {
	r.closeOnce.Do(func() {
		r.closed.Store(true)
		r.closeErr = closeSource(r.source)
	})
	return r.closeErr
}

func closeSource(source Source) (err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = fmt.Errorf("close panic: %v", recovered)
		}
	}()
	return source.Close()
}

// Owner owns a fixed collection of streams, including streams that multipart
// encoding never reaches after an earlier failure.
type Owner struct {
	readers []io.ReadCloser
	once    sync.Once
	err     error
}

// Own adapts and takes ownership of every source.
func Own(sources ...Source) *Owner {
	o := &Owner{readers: make([]io.ReadCloser, len(sources))}
	for i, source := range sources {
		o.readers[i] = New(source)
	}
	return o
}

// Reader returns the adapted stream at index.
func (o *Owner) Reader(index int) io.ReadCloser { return o.readers[index] }

// Close closes every source exactly once, continuing after close errors or
// panics so one broken Java stream cannot leak later parts.
func (o *Owner) Close() error {
	o.once.Do(func() {
		var errs []error
		for i, reader := range o.readers {
			if reader == nil {
				continue
			}
			if err := reader.Close(); err != nil {
				errs = append(errs, fmt.Errorf("input stream %d: %w", i, err))
			}
		}
		o.err = errors.Join(errs...)
	})
	return o.err
}
