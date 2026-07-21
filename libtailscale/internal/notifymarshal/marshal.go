// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package notifymarshal encodes native notifications plus Tailware's
// authentication ownership metadata without depending on the Android bridge.
package notifymarshal

import (
	"encoding/json"

	"tailscale.com/ipn"
)

func Marshal(notify *ipn.Notify, authenticationEpoch int64, authenticationBootstrap bool) ([]byte, error) {
	encoded, err := json.Marshal(notify)
	if err != nil {
		return nil, err
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &fields); err != nil {
		return nil, err
	}
	if fields == nil {
		fields = map[string]json.RawMessage{}
	}
	encodedEpoch, err := json.Marshal(authenticationEpoch)
	if err != nil {
		return nil, err
	}
	fields["TailwareAuthEpoch"] = encodedEpoch
	encodedBootstrap, err := json.Marshal(authenticationBootstrap)
	if err != nil {
		return nil, err
	}
	fields["TailwareAuthBootstrap"] = encodedBootstrap
	return json.Marshal(fields)
}
