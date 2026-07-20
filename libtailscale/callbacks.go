// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"sync"

	"tailscale.com/net/netmon"
)

type vpnServiceCommand struct {
	service    IPNService
	generation int64
}

type vpnServiceCommandKind uint8

const (
	vpnServiceCommandNone vpnServiceCommandKind = iota
	vpnServiceCommandStart
	vpnServiceCommandDisconnect
)

var (
	// onVPNRequested receives IPNService references together with the exact
	// desired-state generation that requested the VPN connection.
	onVPNRequested = make(chan vpnServiceCommand)
	// onDisconnect receives IPNService references together with the exact
	// desired-state generation that requested the disconnect.
	onDisconnect = make(chan vpnServiceCommand)

	// onGoogleToken receives google ID tokens.
	onGoogleToken = make(chan string)

	// onDNSConfigChanged is notified when the network changes and the DNS config needs to be updated. It receives the updated interface name.
	onDNSConfigChanged = make(chan string, 1)

	// onLog receives Android logs to be sent to the logger
	onLog = make(chan string, 10)

	// onShareFileHelper receives ShareFileHelper references when the app is initialized so that files can be received via Storage Access Framework
	onShareFileHelper = make(chan ShareFileHelper, 1)
)

// ifname is the interface name retrieved from LinkProperties on network change. An empty string is used if there is no network available.
func OnDNSConfigChanged(ifname string) {
	select {
	case onDNSConfigChanged <- ifname:
	default:
	}
}

// OnGatewayChanged is called when LinkProperties provides an updated default gateway IP.
func OnGatewayChanged(gatewayIP string) {
	netmon.UpdateLastKnownDefaultGateway(gatewayIP)
}

var android struct {
	// mu protects all fields of this structure. However, once a
	// non-nil jvm is returned from javaVM, all the other fields may
	// be accessed unlocked.
	mu sync.Mutex

	// appCtx is the global Android App context.
	appCtx AppContext
}
