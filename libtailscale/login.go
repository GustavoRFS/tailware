// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

// CancelInteractiveLogin disconnects the active control client. LocalBackend
// clears its pending AuthURL as part of that operation, while leaving profiles,
// node keys, and preferences untouched.
func (app *App) CancelInteractiveLogin() {
	app.ready.Wait()
	app.backend.DisconnectControl()
}
