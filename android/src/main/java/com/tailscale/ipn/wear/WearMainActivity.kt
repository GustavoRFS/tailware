// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.tailscale.ipn.wear.ui.TailwareApp
import com.tailscale.ipn.wear.ui.TailwareTheme

class WearMainActivity : ComponentActivity() {
  private val viewModel: TailwareViewModel by viewModels()

  private val vpnConsentLauncher =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onVpnConsentResult(result.resultCode == Activity.RESULT_OK, this)
      }

  private val notificationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setOptionalNotificationsEnabled(granted)
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    setContent {
      TailwareTheme {
        TailwareApp(
            viewModel = viewModel,
            onRequestVpnConsent = ::requestVpnConsent,
            onSetOptionalNotifications = ::setOptionalNotifications,
            onOpenVpnSettings = ::openVpnSettings,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshVpnAuthorization(this)
    viewModel.syncOptionalNotificationPermission()
  }

  private fun requestVpnConsent() {
    val intent = viewModel.vpnConsentIntent(this)
    if (intent == null) {
      viewModel.refreshVpnAuthorization(this)
      if (viewModel.uiState.value.vpnAuthorization == VpnAuthorization.Granted) {
        viewModel.connect()
      } else {
        viewModel.onVpnConsentLaunchFailed()
      }
      return
    }

    try {
      vpnConsentLauncher.launch(intent)
    } catch (error: ActivityNotFoundException) {
      viewModel.onVpnConsentLaunchFailed(error.message)
    } catch (error: SecurityException) {
      viewModel.onVpnConsentLaunchFailed(error.message)
    }
  }

  private fun setOptionalNotifications(enabled: Boolean) {
    if (!enabled) {
      viewModel.setOptionalNotificationsEnabled(false)
      return
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) {
      viewModel.setOptionalNotificationsEnabled(true)
    } else {
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  private fun openVpnSettings() {
    val opened =
        sequenceOf(
                Intent(Settings.ACTION_VPN_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )
            .any { intent ->
              intent.resolveActivity(packageManager) != null &&
                  runCatching { startActivity(intent) }.isSuccess
            }
    if (!opened) {
      viewModel.onVpnSettingsLaunchFailed()
    }
  }
}
