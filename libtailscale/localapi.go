// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"maps"
	"mime/multipart"
	"net"
	"net/http"
	"net/textproto"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/tailscale/tailscale-android/libtailscale/internal/firstresult"
	"github.com/tailscale/tailscale-android/libtailscale/internal/inputstream"
	"tailscale.com/ipn"
)

var (
	errAuthenticationEpochSuperseded = errors.New("authentication epoch was superseded before LocalAPI execution")
	errWantRunningGenerationInvalid  = errors.New("WantRunning generation must be positive")
	errWantRunningGenerationStale    = errors.New("WantRunning generation was superseded")
	errAuthenticationSequenceInvalid = errors.New("authentication sequence must be -1 or greater")
)

type localAPIExecutionGate func(context.Context, func()) bool

// CallLocalAPI is the method for making localapi calls from Kotlin. It calls
// the given endpoint on the local API using the given HTTP method and
// optionally sending the given body. It returns a Response representing the
// result of the call and an error if the call could not be completed or the
// local API returned a status code in the 400 series or greater.
// Note - Response includes a response body available from the Body method, it
// is the caller's responsibility to close this.
func (app *App) CallLocalAPI(timeoutMillis int, method, endpoint string, body InputStream) (LocalAPIResponse, error) {
	adapted := adaptInputStream(body)
	if adapted != nil {
		defer adapted.Close()
	}
	return app.callLocalAPI(timeoutMillis, method, endpoint, nil, adapted)
}

// CallLocalAPIForAuthenticationEpoch is CallLocalAPI with an execution-time
// authentication sequence fence. The handler is not invoked unless
// authenticationEpoch is still the exact latest sequence when execution can
// begin. Sequence zero is the valid baseline before the first authentication
// epoch. The fence remains held until ServeHTTP returns, even if this method
// has already returned a streaming response to its caller.
func (app *App) CallLocalAPIForAuthenticationEpoch(authenticationEpoch int64, timeoutMillis int, method, endpoint string, body InputStream) (LocalAPIResponse, error) {
	adapted := adaptInputStream(body)
	if adapted != nil {
		defer adapted.Close()
	}
	response, executed, err := app.callLocalAPIWithExecutionGate(
		timeoutMillis,
		method,
		endpoint,
		nil,
		adapted,
		func(ctx context.Context, action func()) bool {
			return app.runIfAuthenticationSequence(ctx, authenticationEpoch, action)
		},
	)
	if err != nil {
		return nil, err
	}
	if !executed {
		return nil, fmt.Errorf("%w: %d", errAuthenticationEpochSuperseded, authenticationEpoch)
	}
	return response, nil
}

// AdvanceWantRunningGeneration advances the process-local cancellation
// boundary used by generation-tagged WantRunning requests. Cancelling a
// context is non-blocking; no LocalAPI or authentication lock is acquired.
func (app *App) AdvanceWantRunningGeneration(commandGeneration int64) bool {
	if commandGeneration <= 0 {
		return false
	}
	app.wantRunningMu.Lock()
	defer app.wantRunningMu.Unlock()
	if commandGeneration < app.wantRunningGeneration {
		return false
	}
	if commandGeneration == app.wantRunningGeneration && app.wantRunningCtx != nil {
		return true
	}
	if app.wantRunningCancel != nil {
		app.wantRunningCancel(fmt.Errorf("%w: %d", errWantRunningGenerationStale, app.wantRunningGeneration))
	}
	app.wantRunningGeneration = commandGeneration
	app.wantRunningCtx, app.wantRunningCancel = context.WithCancelCause(context.Background())
	return true
}

func (app *App) wantRunningContext(commandGeneration int64) (context.Context, bool) {
	app.wantRunningMu.Lock()
	defer app.wantRunningMu.Unlock()
	if commandGeneration <= 0 ||
		commandGeneration != app.wantRunningGeneration ||
		app.wantRunningCtx == nil ||
		app.wantRunningCtx.Err() != nil {
		return nil, false
	}
	return app.wantRunningCtx, true
}

// CallLocalAPIForWantRunningGeneration executes a LocalAPI request under two
// independently cancellable ownership contexts. Advancing the desired-state
// generation or crossing the selected authentication boundary cancels the
// HTTP request without either registry waiting for ServeHTTP to return.
func (app *App) CallLocalAPIForWantRunningGeneration(commandGeneration, authenticationSequence int64, timeoutMillis int, method, endpoint string, body InputStream) (LocalAPIResponse, error) {
	if commandGeneration <= 0 {
		return nil, fmt.Errorf("%w: %d", errWantRunningGenerationInvalid, commandGeneration)
	}
	if authenticationSequence < -1 {
		return nil, fmt.Errorf("%w: %d", errAuthenticationSequenceInvalid, authenticationSequence)
	}
	wantCtx, ok := app.wantRunningContext(commandGeneration)
	if !ok {
		return nil, fmt.Errorf("%w: %d", errWantRunningGenerationStale, commandGeneration)
	}

	combinedCtx, cancelCombined := context.WithCancelCause(wantCtx)
	defer cancelCombined(nil)
	var stopAuthenticationCancellation func() bool
	if authenticationSequence >= 0 {
		authenticationCtx, accepted := app.authentication.ContextForSequence(authenticationSequence)
		if !accepted {
			return nil, fmt.Errorf("%w: %d", errAuthenticationEpochSuperseded, authenticationSequence)
		}
		stopAuthenticationCancellation = context.AfterFunc(authenticationCtx, func() {
			cancelCombined(fmt.Errorf("%w: %d", errAuthenticationEpochSuperseded, authenticationSequence))
		})
		if authenticationCtx.Err() != nil {
			cancelCombined(fmt.Errorf("%w: %d", errAuthenticationEpochSuperseded, authenticationSequence))
		}
		defer stopAuthenticationCancellation()
	}

	adapted := adaptInputStream(body)
	if adapted != nil {
		defer adapted.Close()
	}
	response, executed, err := app.callLocalAPIWithContextAndExecutionGate(
		combinedCtx,
		timeoutMillis,
		method,
		endpoint,
		nil,
		adapted,
		func(ctx context.Context, action func()) bool {
			if ctx.Err() != nil {
				return false
			}
			action()
			return true
		},
	)
	if err != nil {
		return nil, err
	}
	if !executed {
		if cause := context.Cause(combinedCtx); cause != nil {
			return nil, cause
		}
		return nil, fmt.Errorf("%w: %d", errWantRunningGenerationStale, commandGeneration)
	}
	return response, nil
}

// CallLocalAPIMultipart is like CallLocalAPI, but instead of uploading a
// generic body, it uploads a multipart/form-encoded body consisting of the
// supplied parts.
func (app *App) CallLocalAPIMultipart(timeoutMillis int, method, endpoint string, parts FileParts) (LocalAPIResponse, error) {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in CallLocalAPIMultipart %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	type uploadPart struct {
		contentLength int64
		filename      string
		contentType   string
	}

	partCount := parts.Len()
	uploads := make([]uploadPart, 0, partCount)
	sources := make([]inputstream.Source, 0, partCount)
	var snapshotErr error
	for i := int32(0); i < partCount; i++ {
		part := parts.Get(i)
		if part == nil {
			if snapshotErr == nil {
				snapshotErr = fmt.Errorf("multipart part %d is nil", i)
			}
			uploads = append(uploads, uploadPart{})
			sources = append(sources, nil)
			continue
		}
		uploads = append(uploads, uploadPart{
			contentLength: part.ContentLength,
			filename:      part.Filename,
			contentType:   part.ContentType,
		})
		sources = append(sources, part.Body)
	}
	owner := inputstream.Own(sources...)
	if snapshotErr != nil {
		if closeErr := owner.Close(); closeErr != nil {
			return nil, fmt.Errorf("%w (closing multipart inputs: %v)", snapshotErr, closeErr)
		}
		return nil, snapshotErr
	}
	defer func() {
		if err := owner.Close(); err != nil {
			log.Printf("closing multipart input streams: %v", err)
		}
	}()

	r, w := io.Pipe()
	// Register this after owner cleanup so the request pipe closes first and
	// unblocks both the HTTP handler and a producer waiting in io.Copy.
	defer r.Close()

	mw := multipart.NewWriter(w)
	header := make(http.Header)
	header.Set("Content-Type", mw.FormDataContentType())
	results := firstresult.New[*Response]()
	go func() {
		resp, err := app.callLocalAPI(timeoutMillis, method, endpoint, header, r)
		if !results.TrySet(resp, err) && resp != nil {
			resp.discard()
		}
	}()

	go func() {
		fail := func(err error) {
			// Publish the causal producer error before CloseWithError can make
			// the request side observe a derivative pipe error.
			results.TrySet(nil, err)
			_ = w.CloseWithError(err)
		}

		for i, part := range uploads {
			contentType := "application/octet-stream"
			if part.contentType != "" {
				contentType = part.contentType
			}
			header := make(textproto.MIMEHeader, 3)
			header.Set("Content-Disposition",
				fmt.Sprintf(`form-data; name="%s"; filename="%s"`,
					escapeQuotes("file"), escapeQuotes(part.filename)))
			header.Set("Content-Type", contentType)
			header.Set("Content-Length", strconv.FormatInt(part.contentLength, 10))
			p, err := mw.CreatePart(header)
			if err != nil {
				fail(fmt.Errorf("CreatePart %d: %w", i, err))
				return
			}
			body := owner.Reader(i)
			if body == nil {
				fail(fmt.Errorf("multipart part %d has a nil body", i))
				return
			}
			if _, err := io.Copy(p, body); err != nil {
				fail(fmt.Errorf("Copy part %d: %w", i, err))
				return
			}
			if err := body.Close(); err != nil {
				fail(fmt.Errorf("Close part %d: %w", i, err))
				return
			}
		}

		if err := mw.Close(); err != nil {
			fail(fmt.Errorf("Close MultipartWriter: %w", err))
			return
		}
		if err := w.Close(); err != nil {
			results.TrySet(nil, fmt.Errorf("Close Writer: %w", err))
		}
	}()

	return results.Wait()
}

func (app *App) NotifyPolicyChanged() {
	app.policyStore.notifyChanged()
}

func (app *App) SetClientLoggingEnabled(enabled bool) {
	if lg := app.logger.Load(); lg != nil {
		lg.SetEnabled(enabled)
	}
}

func (app *App) EditPrefs(prefs ipn.MaskedPrefs) (LocalAPIResponse, error) {
	r, w := io.Pipe()
	defer r.Close()
	go func() {
		enc := json.NewEncoder(w)
		if err := enc.Encode(prefs); err != nil {
			_ = w.CloseWithError(fmt.Errorf("encoding preferences: %w", err))
			return
		}
		_ = w.Close()
	}()
	return app.callLocalAPI(30000, "PATCH", "prefs", nil, r)
}

// callLocalAPI borrows body for the duration of the call. Its caller retains
// ownership and must close it, including on readiness and request failures.
func (app *App) callLocalAPI(timeoutMillis int, method, endpoint string, header http.Header, body io.ReadCloser) (*Response, error) {
	response, _, err := app.callLocalAPIWithExecutionGate(
		timeoutMillis,
		method,
		endpoint,
		header,
		body,
		nil,
	)
	return response, err
}

// callLocalAPIWithExecutionGate borrows body for the duration of the call. A
// non-nil gate wraps the complete ServeHTTP invocation, including response
// body production after the first flush.
func (app *App) callLocalAPIWithExecutionGate(timeoutMillis int, method, endpoint string, header http.Header, body io.ReadCloser, gate localAPIExecutionGate) (*Response, bool, error) {
	return app.callLocalAPIWithContextAndExecutionGate(
		context.Background(),
		timeoutMillis,
		method,
		endpoint,
		header,
		body,
		gate,
	)
}

// callLocalAPIWithContextAndExecutionGate is callLocalAPIWithExecutionGate
// with an additional cancellation owner. No caller mutex may be held while
// this function waits for readiness or invokes ServeHTTP.
func (app *App) callLocalAPIWithContextAndExecutionGate(parent context.Context, timeoutMillis int, method, endpoint string, header http.Header, body io.ReadCloser, gate localAPIExecutionGate) (*Response, bool, error) {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in callLocalAPI %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	if timeoutMillis <= 0 {
		return nil, false, fmt.Errorf("timeout for %s must be positive", endpoint)
	}
	ctx, cancel := context.WithTimeout(
		parent,
		time.Duration(timeoutMillis)*time.Millisecond,
	)
	defer cancel()
	readyCtx, readyCancel := context.WithTimeout(ctx, nativeBackendReadinessTimeout)
	defer readyCancel()
	_, handler, err := app.waitForBackend(readyCtx)
	if err != nil {
		if cause := context.Cause(parent); cause != nil {
			return nil, false, cause
		}
		return nil, false, fmt.Errorf("native backend unavailable for %s: %w", endpoint, err)
	}

	req, err := http.NewRequestWithContext(ctx, method, endpoint, body)
	if err != nil {
		return nil, false, fmt.Errorf("error creating new request for %s: %w", endpoint, err)
	}
	maps.Copy(req.Header, header)
	deadline, _ := ctx.Deadline()
	pipeReader, pipeWriter := net.Pipe()
	pipeReader.SetDeadline(deadline)
	pipeWriter.SetDeadline(deadline)

	resp := &Response{
		headers:          http.Header{},
		status:           http.StatusOK,
		bodyReader:       pipeReader,
		bodyWriter:       pipeWriter,
		startWritingBody: make(chan interface{}),
	}

	var executionRejected chan struct{}
	if gate != nil {
		executionRejected = make(chan struct{})
	}
	go func() {
		defer func() {
			if p := recover(); p != nil {
				log.Printf("panic in CallLocalAPI.ServeHTTP %s: %s", p, debug.Stack())
				panic(p)
			}
		}()

		defer pipeWriter.Close()
		execute := func() {
			handler.ServeHTTP(resp, req)
			resp.Flush()
		}
		if gate == nil {
			execute()
			return
		}
		if !gate(ctx, execute) {
			close(executionRejected)
		}
	}()

	select {
	case <-resp.startWritingBody:
		return resp, true, nil
	case <-executionRejected:
		resp.discard()
		if cause := context.Cause(parent); cause != nil {
			return nil, false, cause
		}
		if ctx.Err() != nil {
			return nil, false, fmt.Errorf("timeout for %s", endpoint)
		}
		return nil, false, nil
	case <-ctx.Done():
		resp.discard()
		if cause := context.Cause(parent); cause != nil {
			return nil, false, cause
		}
		return nil, false, fmt.Errorf("timeout for %s", endpoint)
	}
}

// Response represents the result of processing an localAPI request.
// On completion, the response body can be read out of the bodyWriter.
type Response struct {
	headers              http.Header
	status               int
	bodyWriter           net.Conn
	bodyReader           net.Conn
	startWritingBody     chan interface{}
	startWritingBodyOnce sync.Once
}

func (r *Response) Header() http.Header {
	return r.headers
}

// Write writes the data to the response body which an then be
// read out as a json object.
func (r *Response) Write(data []byte) (int, error) {
	r.Flush()
	if r.status == 0 {
		r.WriteHeader(http.StatusOK)
	}
	return r.bodyWriter.Write(data)
}

func (r *Response) WriteHeader(statusCode int) {
	r.status = statusCode
}

func (r *Response) Body() net.Conn {
	return r.bodyReader
}

func (r *Response) BodyBytes() ([]byte, error) {
	return io.ReadAll(r.bodyReader)
}

func (r *Response) BodyInputStream() InputStream {
	return nil
}

// discard releases both ends of a response that no caller can consume. The
// handler may still be writing when a timeout or a competing multipart result
// wins, so closing only the reader is insufficient.
func (r *Response) discard() {
	_ = r.bodyReader.Close()
	_ = r.bodyWriter.Close()
}

func (r *Response) StatusCode() int {
	return r.status
}

func (r *Response) Flush() {
	r.startWritingBodyOnce.Do(func() {
		close(r.startWritingBody)
	})
}

// Below taken from Go stdlib
var quoteEscaper = strings.NewReplacer("\\", "\\\\", `"`, "\\\"")

func escapeQuotes(s string) string {
	return quoteEscaper.Replace(s)
}
