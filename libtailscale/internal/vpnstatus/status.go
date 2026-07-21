// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package vpnstatus safely publishes native tunnel state for one desired-state generation.
package vpnstatus

// Reporter is the narrow portion of Android's VPN service needed for tunnel-status publication.
type Reporter interface {
	CommandGeneration() int64
	UpdateVpnStatus(status bool, commandGeneration int64)
}

// PublishIfCurrent reports status only while commandGeneration still owns Android's desired state.
func PublishIfCurrent(reporter Reporter, commandGeneration int64, status bool) bool {
	if reporter.CommandGeneration() != commandGeneration {
		return false
	}
	reporter.UpdateVpnStatus(status, commandGeneration)
	return true
}
