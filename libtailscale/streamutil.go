// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"io"

	"github.com/tailscale/tailscale-android/libtailscale/internal/inputstream"
)

// adaptInputStream wraps an [InputStream] into an [io.ReadCloser].
// The returned reader owns in and propagates its terminal read and close
// errors. In particular, Close reaches the Java source and can unblock a
// concurrent gomobile Read.
func adaptInputStream(in InputStream) io.ReadCloser {
	return inputstream.New(in)
}
