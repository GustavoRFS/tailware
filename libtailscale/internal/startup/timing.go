// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package startup contains the cross-layer native startup timing contract.
package startup

import "time"

const (
	BackendAttemptTimeout     = 25 * time.Second
	BackendAttempts           = 2
	AggregateReadinessTimeout = 60 * time.Second
)
