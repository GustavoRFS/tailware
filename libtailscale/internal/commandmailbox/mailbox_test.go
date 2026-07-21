// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package commandmailbox

import (
	"errors"
	"testing"
	"time"
)

type testCommand struct {
	kind       string
	generation int64
	owner      string
}

func TestSubmitNeverNeedsConsumerAndPreservesLatestOrder(t *testing.T) {
	mailbox := New[testCommand](3)
	mailbox.Begin()

	submitted := make(chan struct{})
	go func() {
		defer close(submitted)
		for _, command := range []testCommand{
			{kind: "start", generation: 1, owner: "old"},
			{kind: "disconnect", generation: 2, owner: "middle"},
			{kind: "start", generation: 3, owner: "new"},
		} {
			if err := mailbox.Submit(command); err != nil {
				t.Errorf("Submit: %v", err)
				return
			}
		}
	}()

	select {
	case <-submitted:
	case <-time.After(time.Second):
		t.Fatal("producer waited for a backend consumer")
	}

	for generation := int64(1); generation <= 3; generation++ {
		command, ok := mailbox.Pop()
		if !ok {
			t.Fatalf("Pop(%d) returned no command", generation)
		}
		if command.generation != generation {
			t.Fatalf("Pop(%d) generation = %d", generation, command.generation)
		}
	}
}

func TestFullMailboxDropsOldestAndAlwaysRetainsNewest(t *testing.T) {
	mailbox := New[testCommand](2)
	mailbox.Begin()
	for generation := int64(1); generation <= 4; generation++ {
		if err := mailbox.Submit(testCommand{generation: generation}); err != nil {
			t.Fatal(err)
		}
	}

	first, ok := mailbox.Pop()
	if !ok || first.generation != 3 {
		t.Fatalf("first retained command = %#v, %v; want generation 3", first, ok)
	}
	latest, ok := mailbox.Pop()
	if !ok || latest.generation != 4 {
		t.Fatalf("latest retained command = %#v, %v; want generation 4", latest, ok)
	}
}

func TestFailureReturnsExactPendingOwnersAndRejectsNewOwner(t *testing.T) {
	startupErr := errors.New("native startup failed")
	mailbox := New[testCommand](4)
	mailbox.Begin()
	old := testCommand{kind: "start", generation: 7, owner: "old-service"}
	current := testCommand{kind: "start", generation: 8, owner: "current-service"}
	if err := mailbox.Submit(old); err != nil {
		t.Fatal(err)
	}
	if err := mailbox.Submit(current); err != nil {
		t.Fatal(err)
	}

	pending, won := mailbox.Fail(startupErr)
	if !won {
		t.Fatal("first failure did not win")
	}
	if len(pending) != 2 || pending[0] != old || pending[1] != current {
		t.Fatalf("failed owners = %#v; want %#v, %#v", pending, old, current)
	}

	late := testCommand{kind: "start", generation: 9, owner: "late-service"}
	if err := mailbox.Submit(late); !errors.Is(err, startupErr) {
		t.Fatalf("late Submit error = %v; want %v", err, startupErr)
	}
}

func TestFailureIncludesCommandAlreadyDeliveredDuringStartup(t *testing.T) {
	startupErr := errors.New("LocalBackend start failed")
	mailbox := New[testCommand](4)
	mailbox.Begin()
	delivered := testCommand{kind: "start", generation: 8, owner: "current-service"}
	waiting := testCommand{kind: "disconnect", generation: 9, owner: "current-service"}
	if err := mailbox.Submit(delivered); err != nil {
		t.Fatal(err)
	}
	if _, ok := mailbox.Pop(); !ok {
		t.Fatal("startup command was not delivered")
	}
	if err := mailbox.Submit(waiting); err != nil {
		t.Fatal(err)
	}

	failed, won := mailbox.Fail(startupErr)
	if !won {
		t.Fatal("startup failure did not win")
	}
	if len(failed) != 2 || failed[0] != delivered || failed[1] != waiting {
		t.Fatalf("failed owners = %#v; want delivered then waiting", failed)
	}
}

func TestMarkReadyForgetsDeliveredStartupCommands(t *testing.T) {
	mailbox := New[testCommand](2)
	mailbox.Begin()
	command := testCommand{kind: "start", generation: 8}
	if err := mailbox.Submit(command); err != nil {
		t.Fatal(err)
	}
	if _, ok := mailbox.Pop(); !ok {
		t.Fatal("startup command was not delivered")
	}
	mailbox.MarkReady()

	failed, won := mailbox.Fail(errors.New("post-start event loop stopped"))
	if !won {
		t.Fatal("failure did not win")
	}
	if len(failed) != 0 {
		t.Fatalf("ready command was incorrectly retained as startup-owned: %#v", failed)
	}
}

func TestDeliveredStartupBookkeepingIsAlsoBoundedAndKeepsNewest(t *testing.T) {
	mailbox := New[testCommand](2)
	mailbox.Begin()
	for generation := int64(1); generation <= 4; generation++ {
		if err := mailbox.Submit(testCommand{kind: "start", generation: generation}); err != nil {
			t.Fatal(err)
		}
		if _, ok := mailbox.Pop(); !ok {
			t.Fatalf("startup command %d was not delivered", generation)
		}
	}

	failed, _ := mailbox.Fail(errors.New("startup failed"))
	if len(failed) != 2 || failed[0].generation != 3 || failed[1].generation != 4 {
		t.Fatalf("bounded startup owners = %#v; want generations 3 and 4", failed)
	}
}

func TestBeginAllowsFreshAttemptAfterFailure(t *testing.T) {
	mailbox := New[testCommand](1)
	mailbox.Begin()
	mailbox.Fail(errors.New("first attempt"))
	mailbox.Begin()

	retry := testCommand{kind: "start", generation: 11, owner: "retry"}
	if err := mailbox.Submit(retry); err != nil {
		t.Fatalf("retry Submit: %v", err)
	}
	got, ok := mailbox.Pop()
	if !ok || got != retry {
		t.Fatalf("retry command = %#v, %v; want %#v", got, ok, retry)
	}
}

func TestRetryRequeuesDeliveredCommandsBeforeNewerPendingCommands(t *testing.T) {
	mailbox := New[testCommand](4)
	mailbox.Begin()
	delivered := testCommand{kind: "start", generation: 21, owner: "first"}
	newer := testCommand{kind: "disconnect", generation: 22, owner: "second"}
	if err := mailbox.Submit(delivered); err != nil {
		t.Fatal(err)
	}
	if got, ok := mailbox.Pop(); !ok || got != delivered {
		t.Fatalf("delivered command = %#v, %v; want %#v", got, ok, delivered)
	}
	if err := mailbox.Submit(newer); err != nil {
		t.Fatal(err)
	}

	mailbox.Retry()

	for index, want := range []testCommand{delivered, newer} {
		got, ok := mailbox.Pop()
		if !ok || got != want {
			t.Fatalf("retry command %d = %#v, %v; want %#v", index, got, ok, want)
		}
	}
}

func TestRetryRemainsBoundedAndRetainsNewestGeneration(t *testing.T) {
	mailbox := New[testCommand](2)
	mailbox.Begin()
	for generation := int64(30); generation <= 33; generation++ {
		if err := mailbox.Submit(testCommand{generation: generation}); err != nil {
			t.Fatal(err)
		}
		if generation <= 31 {
			if _, ok := mailbox.Pop(); !ok {
				t.Fatalf("generation %d was not delivered", generation)
			}
		}
	}

	mailbox.Retry()

	for _, wantGeneration := range []int64{32, 33} {
		got, ok := mailbox.Pop()
		if !ok || got.generation != wantGeneration {
			t.Fatalf("retry generation = %d, %v; want %d", got.generation, ok, wantGeneration)
		}
	}
}
