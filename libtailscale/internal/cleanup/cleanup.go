// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package cleanup runs a complete, idempotent resource teardown sequence.
package cleanup

import (
	"errors"
	"fmt"
	"sync"
)

// Step is one independently recoverable teardown operation.
type Step struct {
	Name string
	Run  func() error
}

// Group runs its teardown sequence at most once. Concurrent callers wait for
// the first sequence and receive the same joined error.
type Group struct {
	once sync.Once
	done chan struct{}
	err  error
}

// NewGroup returns an unused cleanup group.
func NewGroup() *Group { return &Group{done: make(chan struct{})} }

// Close runs every step in order, even when an earlier step returns an error or
// panics. A panic is converted to an error so later resources are still closed.
func (g *Group) Close(steps ...Step) error {
	g.once.Do(func() {
		defer close(g.done)
		var errs []error
		for _, step := range steps {
			if err := runStep(step); err != nil {
				errs = append(errs, err)
			}
		}
		g.err = errors.Join(errs...)
	})
	<-g.done
	return g.err
}

func runStep(step Step) (err error) {
	name := step.Name
	if name == "" {
		name = "unnamed cleanup step"
	}
	defer func() {
		if recovered := recover(); recovered != nil {
			err = fmt.Errorf("%s panicked: %v", name, recovered)
		}
	}()
	if step.Run == nil {
		return fmt.Errorf("%s has no function", name)
	}
	if err := step.Run(); err != nil {
		return fmt.Errorf("%s: %w", name, err)
	}
	return nil
}
