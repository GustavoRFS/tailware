// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package vpnstatus

import "testing"

type statusUpdate struct {
	status     bool
	generation int64
}

type fakeReporter struct {
	desiredGeneration int64
	updates           []statusUpdate
}

func (f *fakeReporter) CommandGeneration() int64 {
	return f.desiredGeneration
}

func (f *fakeReporter) UpdateVpnStatus(status bool, commandGeneration int64) {
	f.updates = append(f.updates, statusUpdate{status: status, generation: commandGeneration})
}

func TestPublishClosedTunnelOnlyForCurrentGeneration(t *testing.T) {
	reporter := &fakeReporter{desiredGeneration: 8}

	if !PublishIfCurrent(reporter, 8, false) {
		t.Fatal("current generation was not published")
	}
	if got, want := reporter.updates, []statusUpdate{{status: false, generation: 8}}; !equalUpdates(got, want) {
		t.Fatalf("updates = %#v, want %#v", got, want)
	}

	reporter.desiredGeneration = 9
	if PublishIfCurrent(reporter, 8, false) {
		t.Fatal("stale generation was published")
	}
	if got, want := reporter.updates, []statusUpdate{{status: false, generation: 8}}; !equalUpdates(got, want) {
		t.Fatalf("stale publication changed updates to %#v, want %#v", got, want)
	}
}

func equalUpdates(left, right []statusUpdate) bool {
	if len(left) != len(right) {
		return false
	}
	for index := range left {
		if left[index] != right[index] {
			return false
		}
	}
	return true
}
