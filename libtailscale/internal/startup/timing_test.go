// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package startup

import "testing"

func TestAggregateReadinessCoversBothAttempts(t *testing.T) {
	if AggregateReadinessTimeout <= BackendAttemptTimeout*BackendAttempts {
		t.Fatalf(
			"aggregate readiness %v must exceed %d attempts at %v",
			AggregateReadinessTimeout,
			BackendAttempts,
			BackendAttemptTimeout,
		)
	}
}
