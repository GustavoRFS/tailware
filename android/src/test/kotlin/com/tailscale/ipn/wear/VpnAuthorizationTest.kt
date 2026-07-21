// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import com.tailscale.ipn.wear.ui.VpnSetupContinuation
import com.tailscale.ipn.wear.ui.shouldForceVpnSetup
import com.tailscale.ipn.wear.ui.shouldRedirectToLoginQr
import com.tailscale.ipn.wear.ui.vpnSetupContinuation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnAuthorizationTest {
  @Test
  fun preparedVpnIsGranted() {
    assertEquals(VpnAuthorization.Granted, vpnAuthorizationFromPrepared(vpnPrepared = true))
  }

  @Test
  fun unpreparedVpnRequiresAdb() {
    assertEquals(VpnAuthorization.AdbRequired, vpnAuthorizationFromPrepared(vpnPrepared = false))
  }

  @Test
  fun initialWearStateRequiresAdb() {
    assertEquals(VpnAuthorization.AdbRequired, WearUiState().vpnAuthorization)
  }

  @Test
  fun missingAuthorizationForcesSetupBeforeWelcomeOrLogin() {
    listOf("gate", "welcome", "login_methods", "login_qr", "auth_key", "control_url").forEach {
        route ->
      assertTrue(
          "Missing VPN authorization must intercept $route",
          shouldForceVpnSetup(VpnAuthorization.AdbRequired, route),
      )
    }
  }

  @Test
  fun setupDoesNotRedirectItselfAndGrantedAuthorizationNeverForcesSetup() {
    assertTrue(!shouldForceVpnSetup(VpnAuthorization.AdbRequired, "vpn_setup"))
    assertTrue(!shouldForceVpnSetup(VpnAuthorization.Granted, "welcome"))
    assertTrue(!shouldForceVpnSetup(VpnAuthorization.AdbRequired, "dashboard"))
  }

  @Test
  fun missingAuthorizationAlwaysKeepsAnExistingLoginUrlBehindTheSetupGate() {
    listOf("vpn_setup", "welcome", "login_methods", "login_qr").forEach { route ->
      assertFalse(
          "A pending login URL must not escape the missing-grant gate from $route",
          shouldRedirectToLoginQr(
              accountState = WearAccountState.Authenticating,
              authorization = VpnAuthorization.AdbRequired,
              hasLoginUrl = true,
              currentRoute = route,
          ),
      )
    }
  }

  @Test
  fun machineApprovalAllowsSettingsAndLogoutPathEvenWhileLoginUrlRemainsPresent() {
    listOf("settings", "language", "tile", "vpn_setup", "about").forEach { route ->
      assertFalse(
          "Machine approval must not displace the allowed $route escape",
          shouldRedirectToLoginQr(
              accountState = WearAccountState.NeedsMachineAuthorization,
              authorization = VpnAuthorization.Granted,
              hasLoginUrl = true,
              currentRoute = route,
          ),
      )
    }
    assertTrue(
        shouldRedirectToLoginQr(
            accountState = WearAccountState.NeedsMachineAuthorization,
            authorization = VpnAuthorization.Granted,
            hasLoginUrl = true,
            currentRoute = "dashboard",
        ))
  }

  @Test
  fun grantedInteractiveLoginStillRoutesToQrButSignedInAccountNeverDoes() {
    assertTrue(
        shouldRedirectToLoginQr(
            accountState = WearAccountState.Authenticating,
            authorization = VpnAuthorization.Granted,
            hasLoginUrl = true,
            currentRoute = "auth_key",
        ))
    assertFalse(
        shouldRedirectToLoginQr(
            accountState = WearAccountState.SignedIn,
            authorization = VpnAuthorization.Granted,
            hasLoginUrl = true,
            currentRoute = "dashboard",
        ))
  }

  @Test
  fun grantedSetupContinuesToLoginOnlyForSignedOutAccount() {
    assertEquals(
        VpnSetupContinuation.LoginMethods,
        vpnSetupContinuation(WearAccountState.SignedOut, VpnAuthorization.Granted),
    )
  }

  @Test
  fun grantedSetupReturnsSignedInAccountToDashboard() {
    assertEquals(
        VpnSetupContinuation.Dashboard,
        vpnSetupContinuation(WearAccountState.SignedIn, VpnAuthorization.Granted),
    )
  }

  @Test
  fun grantedSetupContinuesMachineApprovalToQrEvenWithoutAnAuthUrl() {
    assertEquals(
        VpnSetupContinuation.LoginQr,
        vpnSetupContinuation(
            WearAccountState.NeedsMachineAuthorization,
            VpnAuthorization.Granted,
        ),
    )
    assertTrue(
        shouldRedirectToLoginQr(
            accountState = WearAccountState.NeedsMachineAuthorization,
            authorization = VpnAuthorization.Granted,
            hasLoginUrl = false,
            currentRoute = "dashboard",
        ))
  }

  @Test
  fun setupDoesNotNavigateWhileAuthorizationOrAccountIsUnresolved() {
    assertNull(
        vpnSetupContinuation(WearAccountState.SignedOut, VpnAuthorization.AdbRequired),
    )
    assertNull(
        vpnSetupContinuation(WearAccountState.Loading, VpnAuthorization.Granted),
    )
    assertNull(
        vpnSetupContinuation(WearAccountState.Authenticating, VpnAuthorization.Granted),
    )
  }

  @Test
  fun setupContinuationRoutingMatrixIsExhaustive() {
    val expected =
        mapOf(
            (WearAccountState.Loading to VpnAuthorization.Granted) to null,
            (WearAccountState.Loading to VpnAuthorization.AdbRequired) to null,
            (WearAccountState.SignedOut to VpnAuthorization.Granted) to
                VpnSetupContinuation.LoginMethods,
            (WearAccountState.SignedOut to VpnAuthorization.AdbRequired) to null,
            (WearAccountState.Authenticating to VpnAuthorization.Granted) to null,
            (WearAccountState.Authenticating to VpnAuthorization.AdbRequired) to null,
            (WearAccountState.NeedsMachineAuthorization to VpnAuthorization.Granted) to
                VpnSetupContinuation.LoginQr,
            (WearAccountState.NeedsMachineAuthorization to VpnAuthorization.AdbRequired) to null,
            (WearAccountState.SignedIn to VpnAuthorization.Granted) to
                VpnSetupContinuation.Dashboard,
            (WearAccountState.SignedIn to VpnAuthorization.AdbRequired) to null,
        )

    assertEquals(
        WearAccountState.entries.size * VpnAuthorization.entries.size,
        expected.size,
    )
    WearAccountState.entries.forEach { accountState ->
      VpnAuthorization.entries.forEach { authorization ->
        val state = accountState to authorization
        assertTrue("Missing routing expectation for $state", expected.containsKey(state))
        assertEquals(
            "Unexpected setup continuation for $state",
            expected[state],
            vpnSetupContinuation(accountState, authorization),
        )
      }
    }
  }
}
