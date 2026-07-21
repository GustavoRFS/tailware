// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package vpnauthorization provides the final native boundary check for an
// Android VPN command. It deliberately requires both current desired state and
// the non-persisted process capability reported by Android.
package vpnauthorization

type CommandAuthorizer interface {
	CommandGeneration() int64
	IsCommandAuthorized(commandGeneration int64) bool
}

func IsCommandAuthorized(service CommandAuthorizer, commandGeneration int64) bool {
	return service != nil &&
		service.CommandGeneration() == commandGeneration &&
		service.IsCommandAuthorized(commandGeneration)
}
