// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.Manifest
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
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.tailscale.ipn.App
import com.tailscale.ipn.wear.ui.TailwareApp
import com.tailscale.ipn.wear.ui.TailwareTheme

class WearMainActivity : ComponentActivity() {
  private val viewModel: TailwareViewModel by viewModels()
  private val remoteExecutor by lazy { ContextCompat.getMainExecutor(this) }
  private val remoteActivityHelper by lazy { RemoteActivityHelper(this, remoteExecutor) }

  private val notificationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setOptionalNotificationsEnabled(granted)
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    // IpnViewModel starts LocalAPI requests from its base-class initializer. Initialize the
    // native application before the lazy Wear view model is first accessed by Compose.
    App.get()
    setContent {
      TailwareTheme {
        TailwareApp(
            viewModel = viewModel,
            onSetOptionalNotifications = ::setOptionalNotifications,
            onOpenVpnSettings = ::openVpnSettings,
            onOpenLoginOnPhone = ::openLoginOnPhone,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshVpnAuthorization(this)
    viewModel.syncOptionalNotificationPermission()
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

  private fun openLoginOnPhone(url: String) {
    val future =
        runCatching {
              val intent =
                  Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                  }
              remoteActivityHelper.startRemoteActivity(intent, null)
            }
            .getOrElse {
              viewModel.onPhoneLoginLaunchFailed(url, it)
              return
            }
    runCatching {
          future.addListener(
              {
                runCatching { future.get() }
                    .onSuccess { viewModel.onPhoneLoginOpened(url) }
                    .onFailure { viewModel.onPhoneLoginLaunchFailed(url, it) }
              },
              remoteExecutor,
          )
        }
        .onFailure { viewModel.onPhoneLoginLaunchFailed(url, it) }
  }
}
