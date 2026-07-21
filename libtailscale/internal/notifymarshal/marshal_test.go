// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package notifymarshal

import (
	"encoding/json"
	"testing"

	"tailscale.com/ipn"
)

func TestMarshalCarriesAuthenticationEpoch(t *testing.T) {
	url := "https://login.tailscale.com/a/example"
	got, err := Marshal(&ipn.Notify{BrowseToURL: &url}, 42, true)
	if err != nil {
		t.Fatal(err)
	}
	var decoded struct {
		BrowseToURL           *string
		TailwareAuthEpoch     int64
		TailwareAuthBootstrap bool
	}
	if err := json.Unmarshal(got, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.BrowseToURL == nil || *decoded.BrowseToURL != url {
		t.Fatalf("BrowseToURL = %v, want %q", decoded.BrowseToURL, url)
	}
	if decoded.TailwareAuthEpoch != 42 {
		t.Fatalf("TailwareAuthEpoch = %d, want 42", decoded.TailwareAuthEpoch)
	}
	if !decoded.TailwareAuthBootstrap {
		t.Fatal("TailwareAuthBootstrap = false, want true")
	}
}
