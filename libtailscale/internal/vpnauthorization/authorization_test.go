// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package vpnauthorization

import "testing"

type fakeCommandAuthorizer struct {
	generation int64
	authorized bool
	checks     int
}

func (f *fakeCommandAuthorizer) CommandGeneration() int64 { return f.generation }

func (f *fakeCommandAuthorizer) IsCommandAuthorized(generation int64) bool {
	f.checks++
	return f.authorized && generation == f.generation
}

func TestRequiresGenerationAndLiveCapability(t *testing.T) {
	fake := &fakeCommandAuthorizer{generation: 7, authorized: true}
	if !IsCommandAuthorized(fake, 7) {
		t.Fatal("exact live command was rejected")
	}
	if IsCommandAuthorized(fake, 6) {
		t.Fatal("stale generation was authorized")
	}
	fake.authorized = false
	if IsCommandAuthorized(fake, 7) {
		t.Fatal("persisted generation authorized work after process capability revocation")
	}
	if fake.checks != 2 {
		t.Fatalf("capability check count = %d, want 2 (generation mismatch must fail first)", fake.checks)
	}
}
