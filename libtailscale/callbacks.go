// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"log"
	"runtime/debug"
	"sync"

	"github.com/tailscale/tailscale-android/libtailscale/internal/commandmailbox"
	"tailscale.com/net/netmon"
)

type vpnServiceCommand struct {
	service    IPNService
	generation int64
	kind       vpnServiceCommandKind
}

type vpnServiceCommandKind uint8

const (
	vpnServiceCommandNone vpnServiceCommandKind = iota
	vpnServiceCommandStart
	vpnServiceCommandDisconnect
)

var (
	// vpnCommands is deliberately producer-non-blocking. Android invokes
	// RequestVPN on its main service thread, including before runBackend has
	// finished constructing its consumer. Disconnect installs its teardown
	// barrier synchronously and closes asynchronously instead of using this
	// mailbox. The bounded mailbox drops only the oldest START under extreme
	// pressure, preserving the newest desired generation Android will accept.
	vpnCommands = commandmailbox.New[vpnServiceCommand](32)

	// onGoogleToken receives google ID tokens.
	onGoogleToken = make(chan string)

	// onDNSConfigChanged is notified when the network changes and the DNS config needs to be updated. It receives the updated interface name.
	onDNSConfigChanged = make(chan string, 1)

	// onLog receives Android logs to be sent to the logger
	onLog = make(chan string, 10)

	// onShareFileHelper receives ShareFileHelper references when the app is initialized so that files can be received via Storage Access Framework
	onShareFileHelper = make(chan ShareFileHelper, 1)
)

func beginVPNCommandAttempt() {
	vpnCommands.Begin()
}

func submitVPNCommand(command vpnServiceCommand) {
	if err := vpnCommands.Submit(command); err != nil {
		reportVPNCommandStartupFailure(command, err)
	}
}

func failPendingVPNCommands(err error) {
	commands, won := vpnCommands.Fail(err)
	if !won {
		return
	}
	for _, command := range commands {
		reportVPNCommandStartupFailure(command, err)
	}
}

func reportVPNCommandStartupFailure(command vpnServiceCommand, err error) {
	if command.kind != vpnServiceCommandStart {
		return
	}
	// Never re-enter Android while RequestVPN is still on the main thread.
	// HandleVpnServiceError atomically accepts only this exact desired
	// generation, so older queued starts cannot roll back a newer ON/OFF/ON.
	go func() {
		defer func() {
			if recovered := recover(); recovered != nil {
				log.Printf(
					"panic reporting native startup failure for generation=%d: %v\n%s",
					command.generation,
					recovered,
					debug.Stack(),
				)
			}
		}()
		if !command.service.HandleVpnServiceError(command.generation) {
			log.Printf(
				"native startup failure was not claimed by service=%q generation=%d: %v",
				command.service.ID(),
				command.generation,
				err,
			)
			return
		}
		log.Printf(
			"native startup failure reported to service=%q generation=%d: %v",
			command.service.ID(),
			command.generation,
			err,
		)
	}()
}

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
