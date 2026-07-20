// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.tailscale.ipn.App
import com.tailscale.ipn.wear.ui.TailwareApp
import com.tailscale.ipn.wear.ui.TailwareTheme
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WearMainActivity : ComponentActivity() {
  private val viewModel: TailwareViewModel by viewModels()
  private val remoteExecutor by lazy { ContextCompat.getMainExecutor(this) }
  private val remoteActivityHelper by lazy { RemoteActivityHelper(this, remoteExecutor) }

  private val notificationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onOptionalNotificationPermissionFinished()
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    // IpnViewModel starts LocalAPI requests from its base-class initializer. Initialize the
    // native application before the lazy Wear view model is first accessed by Compose.
    App.get()
    viewModel.onActivityAttached()
    setContent {
      TailwareTheme {
        TailwareApp(
            viewModel = viewModel,
            onSetOptionalNotifications = ::setOptionalNotifications,
            onOpenLoginOnPhone = ::openLoginOnPhone,
            onSensitiveLoginContentVisible = ::setSensitiveLoginContentVisible,
        )
      }
    }
  }

  override fun onDestroy() {
    // Compose disposes its routes from ComponentActivity.onDestroy(). Mark the recreation first so
    // that a retained ViewModel does not interpret that disposal as an explicit sign-in cancel.
    if (isChangingConfigurations) viewModel.onActivityRecreationStarting()
    super.onDestroy()
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

  private fun openLoginOnPhone(url: String) {
    viewModel.onPhoneLoginOpening(url)
    lifecycleScope.launch {
      val result = runCatching {
        when (remoteActivityHelper.availabilityStatus.first()) {
          RemoteActivityHelper.STATUS_UNAVAILABLE ->
              error("No compatible paired phone is available.")
          RemoteActivityHelper.STATUS_TEMPORARILY_UNAVAILABLE ->
              error("The paired phone is temporarily unreachable. Keep it nearby and unlocked.")
          // Older watches can report UNKNOWN even when the operation succeeds. The official
          // API recommends attempting the launch and handling its result in that case.
          RemoteActivityHelper.STATUS_AVAILABLE,
          RemoteActivityHelper.STATUS_UNKNOWN -> Unit
        }
        val intent =
            Intent(Intent.ACTION_VIEW, url.toUri()).apply { addCategory(Intent.CATEGORY_BROWSABLE) }
        val future = remoteActivityHelper.startRemoteActivity(intent, null)
        withContext(Dispatchers.IO) {
          try {
            future.get(PHONE_OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          } catch (error: ExecutionException) {
            throw (error.cause ?: error)
          } catch (_: TimeoutException) {
            future.cancel(true)
            error("The paired phone did not answer in time.")
          }
        }
      }
      result
          .onSuccess { viewModel.onPhoneLoginOpened(url) }
          .onFailure { viewModel.onPhoneLoginLaunchFailed(url, it) }
    }
  }

  private fun setSensitiveLoginContentVisible(visible: Boolean) {
    if (visible) {
      window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
      window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }

  companion object {
    private const val PHONE_OPEN_TIMEOUT_SECONDS = 15L
  }
}
