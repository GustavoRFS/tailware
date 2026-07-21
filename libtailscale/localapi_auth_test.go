// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"errors"
	"net/http"
	"sync/atomic"
	"testing"
	"time"

	"github.com/tailscale/tailscale-android/libtailscale/internal/authepoch"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnlocal"
)

func readyLocalAPIApp(t *testing.T, handler http.Handler) *App {
	t.Helper()
	app := &App{authentication: authepoch.New()}
	attempt := app.beginBackendAttempt(1)
	app.publishBackend(attempt, &ipnlocal.LocalBackend{}, handler)
	if !attempt.ready.Complete(nil) {
		t.Fatal("failed to publish test backend readiness")
	}
	t.Cleanup(attempt.cancel)
	return app
}

func drainLocalAPIResponse(t *testing.T, response LocalAPIResponse) {
	t.Helper()
	if response == nil {
		t.Fatal("LocalAPI response is nil")
	}
	if _, err := response.BodyBytes(); err != nil {
		t.Fatalf("reading LocalAPI response: %v", err)
	}
}

func TestCallLocalAPIForAuthenticationEpochUsesExactLatestSequence(t *testing.T) {
	var handlerCalls atomic.Int32
	app := readyLocalAPIApp(t, http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		handlerCalls.Add(1)
	}))

	if got := app.AuthenticationSequence(); got != 0 {
		t.Fatalf("initial authentication sequence = %d, want 0", got)
	}
	response, err := app.CallLocalAPIForAuthenticationEpoch(0, 1000, http.MethodPatch, "/baseline", nil)
	if err != nil {
		t.Fatalf("baseline LocalAPI call failed: %v", err)
	}
	drainLocalAPIResponse(t, response)

	epochA := app.authentication.Begin()
	if !app.authentication.Cancel(epochA, func() {}) {
		t.Fatal("failed to cancel epoch A")
	}
	if got := app.AuthenticationSequence(); got != epochA {
		t.Fatalf("sequence after cancellation = %d, want %d", got, epochA)
	}
	response, err = app.CallLocalAPIForAuthenticationEpoch(epochA, 1000, http.MethodPatch, "/cleanup-a", nil)
	if err != nil {
		t.Fatalf("exact cancelled epoch LocalAPI call failed: %v", err)
	}
	drainLocalAPIResponse(t, response)

	epochB := app.authentication.Begin()
	response, err = app.CallLocalAPIForAuthenticationEpoch(epochA, 1000, http.MethodPatch, "/stale-a", nil)
	if response != nil {
		t.Fatal("stale authentication sequence returned a response")
	}
	if !errors.Is(err, errAuthenticationEpochSuperseded) {
		t.Fatalf("stale authentication error = %v, want superseded", err)
	}
	response, err = app.CallLocalAPIForAuthenticationEpoch(epochB, 1000, http.MethodPatch, "/current-b", nil)
	if err != nil {
		t.Fatalf("current epoch LocalAPI call failed: %v", err)
	}
	drainLocalAPIResponse(t, response)

	if got := handlerCalls.Load(); got != 3 {
		t.Fatalf("handler calls = %d, want 3 accepted sequences only", got)
	}
}

func TestAuthenticationFenceLastsUntilServeHTTPReturns(t *testing.T) {
	handlerEntered := make(chan struct{})
	allowHandlerReturn := make(chan struct{})
	app := readyLocalAPIApp(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		close(handlerEntered)
		w.(http.Flusher).Flush()
		<-allowHandlerReturn
	}))

	response, err := app.CallLocalAPIForAuthenticationEpoch(0, 1000, http.MethodPatch, "/guarded", nil)
	if err != nil {
		t.Fatalf("guarded LocalAPI call failed: %v", err)
	}
	select {
	case <-handlerEntered:
	default:
		t.Fatal("LocalAPI returned before the handler entered")
	}

	beginDone := make(chan int64, 1)
	beginAttempted := make(chan struct{})
	go func() {
		close(beginAttempted)
		app.authenticationMu.Lock()
		epoch := app.authentication.Begin()
		app.authenticationMu.Unlock()
		beginDone <- epoch
	}()
	<-beginAttempted
	select {
	case epoch := <-beginDone:
		close(allowHandlerReturn)
		t.Fatalf("authentication epoch %d began before ServeHTTP returned", epoch)
	case <-time.After(20 * time.Millisecond):
	}

	close(allowHandlerReturn)
	select {
	case epoch := <-beginDone:
		if epoch == 0 {
			t.Fatal("post-handler Begin returned epoch zero")
		}
	case <-time.After(time.Second):
		t.Fatal("authentication Begin remained blocked after ServeHTTP returned")
	}
	drainLocalAPIResponse(t, response)
}

type countingNotificationCallback struct {
	calls atomic.Int32
}

func (c *countingNotificationCallback) OnNotify([]byte) error {
	c.calls.Add(1)
	return nil
}

func TestGuardedHandlerAuthURLDoesNotAutoBeginOrDeadlock(t *testing.T) {
	app := &App{authentication: authepoch.New()}
	session := app.authentication.RegisterWatcher()
	callback := &countingNotificationCallback{}
	authURL := "https://login.example.test/"

	type executionResult struct {
		accepted   bool
		dispatched bool
	}
	executionDone := make(chan executionResult, 1)
	go func() {
		var dispatched bool
		accepted := app.runIfAuthenticationSequence(context.Background(), 0, func() {
			dispatched = app.dispatchNotification(
				session,
				callback,
				false,
				&ipn.Notify{BrowseToURL: &authURL},
			)
		})
		executionDone <- executionResult{accepted: accepted, dispatched: dispatched}
	}()
	select {
	case result := <-executionDone:
		if !result.accepted {
			t.Fatal("baseline execution sequence was rejected")
		}
		if !result.dispatched {
			t.Fatal("current watcher was rejected inside guarded handler")
		}
	case <-time.After(time.Second):
		t.Fatal("AuthURL callback deadlocked on guarded handler boundary")
	}
	if got := app.AuthenticationSequence(); got != 0 {
		t.Fatalf("guarded AuthURL advanced sequence to %d, want baseline 0", got)
	}
	if got := callback.calls.Load(); got != 1 {
		t.Fatalf("notification callbacks = %d, want 1", got)
	}

	if !app.dispatchNotification(
		session,
		callback,
		false,
		&ipn.Notify{BrowseToURL: &authURL},
	) {
		t.Fatal("post-boundary AuthURL watcher was rejected")
	}
	if got := app.AuthenticationSequence(); got == 0 {
		t.Fatal("post-boundary AuthURL did not auto-begin an epoch")
	}
}

func TestWantRunningAuthenticationBoundaryCancelsWithoutWaitingForHandler(t *testing.T) {
	handlerEntered := make(chan struct{})
	handlerReturned := make(chan struct{})
	app := readyLocalAPIApp(t, http.HandlerFunc(func(_ http.ResponseWriter, request *http.Request) {
		close(handlerEntered)
		<-request.Context().Done()
		close(handlerReturned)
	}))
	if !app.AdvanceWantRunningGeneration(10) {
		t.Fatal("initial WantRunning generation was rejected")
	}

	callDone := make(chan error, 1)
	go func() {
		_, err := app.CallLocalAPIForWantRunningGeneration(10, 0, 5000, http.MethodPatch, "/want-running", nil)
		callDone <- err
	}()
	select {
	case <-handlerEntered:
	case <-time.After(time.Second):
		t.Fatal("WantRunning handler did not enter")
	}

	beginDone := make(chan int64, 1)
	go func() {
		app.authenticationMu.Lock()
		epoch := app.authentication.Begin()
		app.authenticationMu.Unlock()
		beginDone <- epoch
	}()
	select {
	case epoch := <-beginDone:
		if epoch == 0 {
			t.Fatal("authentication boundary returned epoch zero")
		}
	case <-time.After(time.Second):
		t.Fatal("authentication boundary waited for WantRunning ServeHTTP")
	}
	select {
	case err := <-callDone:
		if !errors.Is(err, errAuthenticationEpochSuperseded) {
			t.Fatalf("WantRunning cancellation error = %v, want authentication superseded", err)
		}
	case <-time.After(time.Second):
		t.Fatal("WantRunning call did not return after authentication boundary")
	}
	select {
	case <-handlerReturned:
	case <-time.After(time.Second):
		t.Fatal("cancelled WantRunning handler did not observe request cancellation")
	}
}

func TestWantRunningGenerationAdvanceCancelsOlderRequest(t *testing.T) {
	handlerEntered := make(chan struct{})
	app := readyLocalAPIApp(t, http.HandlerFunc(func(_ http.ResponseWriter, request *http.Request) {
		close(handlerEntered)
		<-request.Context().Done()
	}))
	if !app.AdvanceWantRunningGeneration(20) {
		t.Fatal("initial WantRunning generation was rejected")
	}
	callDone := make(chan error, 1)
	go func() {
		_, err := app.CallLocalAPIForWantRunningGeneration(20, -1, 5000, http.MethodPatch, "/want-running", nil)
		callDone <- err
	}()
	select {
	case <-handlerEntered:
	case <-time.After(time.Second):
		t.Fatal("WantRunning handler did not enter")
	}

	started := time.Now()
	if !app.AdvanceWantRunningGeneration(21) {
		t.Fatal("newer WantRunning generation was rejected")
	}
	if elapsed := time.Since(started); elapsed > 100*time.Millisecond {
		t.Fatalf("generation advance blocked for %v", elapsed)
	}
	select {
	case err := <-callDone:
		if !errors.Is(err, errWantRunningGenerationStale) {
			t.Fatalf("WantRunning cancellation error = %v, want stale generation", err)
		}
	case <-time.After(time.Second):
		t.Fatal("older WantRunning request was not cancelled")
	}
	if app.AdvanceWantRunningGeneration(20) {
		t.Fatal("stale WantRunning generation was accepted")
	}
}

func TestWantRunningAuthenticationSentinelAndValidation(t *testing.T) {
	app := readyLocalAPIApp(t, http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	if !app.AdvanceWantRunningGeneration(30) {
		t.Fatal("WantRunning generation was rejected")
	}
	response, err := app.CallLocalAPIForWantRunningGeneration(30, -1, 1000, http.MethodPatch, "/unfenced", nil)
	if err != nil {
		t.Fatalf("unfenced WantRunning call failed: %v", err)
	}
	drainLocalAPIResponse(t, response)
	if response, err := app.CallLocalAPIForWantRunningGeneration(30, -2, 1000, http.MethodPatch, "/invalid", nil); response != nil || !errors.Is(err, errAuthenticationSequenceInvalid) {
		t.Fatalf("invalid authentication sequence returned response=%v error=%v", response, err)
	}
}
