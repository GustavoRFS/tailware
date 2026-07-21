// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.wear.localization.TailwareStrings
import com.tailscale.ipn.wear.ui.TailwareApp
import com.tailscale.ipn.wear.ui.TailwareChip
import com.tailscale.ipn.wear.ui.TailwareInfo
import com.tailscale.ipn.wear.ui.TailwareListScreen
import com.tailscale.ipn.wear.ui.TailwareTheme
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WearMainActivity : ComponentActivity() {
  // Null is the passive launch state. Neither Activity construction nor lifecycle callbacks may
  // obtain App or construct the engine-backed ViewModel.
  private val activatedViewModel = mutableStateOf<TailwareViewModel?>(null)
  private val recreationSource = Any()
  private val remoteExecutor by lazy { ContextCompat.getMainExecutor(this) }
  private val remoteActivityHelper by lazy { RemoteActivityHelper(this, remoteExecutor) }
  private var activePhoneLoginAttempt: PhoneLoginOpenAttempt? = null
  private var sensitiveLoginContentVisible = false

  private val notificationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        activatedViewModel.value?.onOptionalNotificationPermissionFinished()
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    restoreActivatedEngineAfterConfiguration(savedInstanceState != null)
    val passiveLanguage = TailwareStrings.selectedLanguage(this)
    setContent {
      val viewModel = activatedViewModel.value
      if (viewModel == null) {
        TailwareTheme(language = passiveLanguage) {
          PassiveLaunchScreen(
              language = passiveLanguage,
              onActivate = ::activateEngineFromUserGesture,
          )
        }
      } else {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        TailwareTheme(language = state.language) {
          TailwareApp(
              viewModel = viewModel,
              onSetOptionalNotifications = ::setOptionalNotifications,
              onOpenLoginOnPhone = ::openLoginOnPhone,
              onOpenGuideOnPhone = ::openGuideOnPhone,
              onOpenAboutWatchSettings = {
                openSystemSettings(Settings.ACTION_DEVICE_INFO_SETTINGS)
              },
              onOpenDeveloperSettings = {
                openSystemSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
              },
              onSensitiveLoginContentVisible = ::setSensitiveLoginContentVisible,
          )
        }
      }
    }
  }

  /** The only Activity path allowed to cross from passive UI into the engine context. */
  private fun activateEngineFromUserGesture() {
    if (activatedViewModel.value != null) return
    if (TailwareActivationCapability.grantEngineFromUserGesture(this) ==
        TailwareActivationCapability.NO_CAPABILITY) {
      return
    }
    val app = App.get()
    val factory =
        object : ViewModelProvider.Factory {
          @Suppress("UNCHECKED_CAST")
          override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            check(modelClass.isAssignableFrom(TailwareViewModel::class.java))
            return TailwareViewModel(app) as T
          }
        }
    val viewModel = ViewModelProvider(app, factory)[TailwareViewModel::class.java]
    viewModel.onActivityAttached()
    activatedViewModel.value = viewModel
  }

  private fun restoreActivatedEngineAfterConfiguration(hasSavedInstanceState: Boolean) {
    val retained = recreationHandoff.consume(hasSavedInstanceState) ?: return
    sensitiveLoginContentVisible = retained.sensitiveLoginContentVisible
    if (sensitiveLoginContentVisible) {
      window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    retained.viewModel.onActivityAttached()
    activatedViewModel.value = retained.viewModel
  }

  override fun onDestroy() {
    val viewModel = activatedViewModel.value
    if (viewModel != null) activePhoneLoginAttempt?.let(viewModel::onPhoneLoginLaunchCancelled)
    activePhoneLoginAttempt = null
    // Compose disposes its routes from ComponentActivity.onDestroy(). Mark the recreation first so
    // that a retained ViewModel does not interpret that disposal as an explicit sign-in cancel.
    if (isChangingConfigurations && viewModel != null) {
      viewModel.onActivityRecreationStarting()
      recreationHandoff.retain(
          source = recreationSource,
          value =
              RetainedActivityState(
                  viewModel = viewModel,
                  sensitiveLoginContentVisible = sensitiveLoginContentVisible,
              ),
          isChangingConfigurations = true,
      )
    } else {
      recreationHandoff.clear(recreationSource)
    }
    setSensitiveLoginContentVisible(false)
    super.onDestroy()
  }

  override fun onResume() {
    super.onResume()
    // Resuming is passive. In particular, do not call VpnService.prepare or initialize App here.
    activatedViewModel.value?.let { viewModel ->
      viewModel.syncLanguageFromSystem()
      viewModel.syncOptionalNotificationPermission()
    }
  }

  private fun setOptionalNotifications(enabled: Boolean) {
    val viewModel = activatedViewModel.value ?: return
    val runtimePermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val permissionGranted =
        !runtimePermissionRequired ||
            ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
    val decision =
        optionalNotificationToggleDecision(
            enabled = enabled,
            runtimePermissionRequired = runtimePermissionRequired,
            permissionGranted = permissionGranted,
        )
    // Persist the requested state before Android shows its dialog. A denial changes effective
    // delivery, not the switch the user explicitly chose in Tailware.
    viewModel.setOptionalNotificationsEnabled(decision.requested)
    if (decision.launchPermissionDialog) {
      notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
    }
  }

  private fun openLoginOnPhone(url: String) {
    val viewModel = activatedViewModel.value ?: return
    val attempt = viewModel.onPhoneLoginOpening(url) ?: return
    activePhoneLoginAttempt = attempt
    lifecycleScope.launch {
      val result = runCatching { openRemoteUrl(url) }
      if (activePhoneLoginAttempt == attempt) {
        activePhoneLoginAttempt = null
      }
      result
          .onSuccess { viewModel.onPhoneLoginOpened(attempt) }
          .onFailure { viewModel.onPhoneLoginLaunchFailed(attempt, it) }
    }
  }

  private suspend fun openGuideOnPhone(url: String): Boolean =
      runCatching {
            openRemoteUrl(url)
            true
          }
          .getOrDefault(false)

  private suspend fun openRemoteUrl(url: String) {
    val validatedUrl = validateRemoteLoginUri(url)
    when (remoteActivityHelper.availabilityStatus.first()) {
      RemoteActivityHelper.STATUS_UNAVAILABLE -> error("No compatible paired phone is available.")
      RemoteActivityHelper.STATUS_TEMPORARILY_UNAVAILABLE ->
          error("The paired phone is temporarily unreachable. Keep it nearby and unlocked.")
      // Older watches can report UNKNOWN even when the operation succeeds. The official API
      // recommends attempting the launch and handling its result in that case.
      RemoteActivityHelper.STATUS_AVAILABLE,
      RemoteActivityHelper.STATUS_UNKNOWN -> Unit
    }
    val intent =
        Intent(Intent.ACTION_VIEW, validatedUrl.toString().toUri()).apply {
          addCategory(Intent.CATEGORY_BROWSABLE)
        }
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

  private fun openSystemSettings(action: String): Boolean {
    val intent = Intent(action)
    if (intent.resolveActivity(packageManager) == null) return false
    return runCatching {
          startActivity(intent)
          true
        }
        .getOrDefault(false)
  }

  private fun setSensitiveLoginContentVisible(visible: Boolean) {
    sensitiveLoginContentVisible = visible
    if (visible) {
      window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
      window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }

  companion object {
    private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    private const val PHONE_OPEN_TIMEOUT_SECONDS = 15L
    private val recreationHandoff = ConfigurationRecreationHandoff<RetainedActivityState>()
  }

  private data class RetainedActivityState(
      val viewModel: TailwareViewModel,
      val sensitiveLoginContentVisible: Boolean,
  )
}

/**
 * One-shot, process-local handoff for an Activity destroyed by a genuine configuration change.
 * Nothing is persisted, and a cold/process-restored Activity cannot consume an authorization.
 */
internal class ConfigurationRecreationHandoff<T : Any> {
  private data class Pending<T>(val source: Any, val value: T)

  private var pending: Pending<T>? = null

  @Synchronized
  fun retain(source: Any, value: T?, isChangingConfigurations: Boolean): Boolean {
    if (!isChangingConfigurations || value == null) {
      clear(source)
      return false
    }
    pending = Pending(source, value)
    return true
  }

  @Synchronized
  fun consume(hasSavedInstanceState: Boolean): T? {
    if (!hasSavedInstanceState) {
      pending = null
      return null
    }
    return pending?.value.also { pending = null }
  }

  @Synchronized
  fun clear(source: Any) {
    if (pending?.source === source) pending = null
  }
}

@Composable
private fun WearMainActivity.PassiveLaunchScreen(
    language: TailwareLanguage,
    onActivate: () -> Unit,
) {
  TailwareListScreen(
      title = TailwareStrings.text(this, language, R.string.wear_brand_tailware_upper)) {
        item {
          TailwareInfo(
              label =
                  TailwareStrings.text(this@PassiveLaunchScreen, language, R.string.wear_status),
              value =
                  TailwareStrings.text(
                      this@PassiveLaunchScreen, language, R.string.wear_disconnected),
          )
        }
        item {
          TailwareChip(
              label =
                  TailwareStrings.text(
                      this@PassiveLaunchScreen, language, R.string.wear_open_tailware),
              secondary =
                  TailwareStrings.text(
                      this@PassiveLaunchScreen, language, R.string.wear_tap_for_details),
              accent = true,
              onClick = onActivate,
          )
        }
      }
}

/**
 * Accepts only hierarchical HTTPS links with an unambiguous host for handoff to the paired phone.
 *
 * In particular, custom schemes and `intent:` links must never cross the watch-to-phone trust
 * boundary when their source is a custom control server.
 */
internal fun validateRemoteLoginUri(url: String): URI {
  val candidate = url.trim()
  require(candidate.isNotEmpty()) { "Remote link is empty." }
  val uri =
      runCatching { URI(candidate) }
          .getOrElse { error -> throw IllegalArgumentException("Remote link is invalid.", error) }
  require(uri.scheme.equals("https", ignoreCase = true)) { "Remote link must use HTTPS." }
  require(!uri.isOpaque && !uri.host.isNullOrBlank()) { "Remote link must include a valid host." }
  require(uri.userInfo == null) { "Remote link must not contain user information." }
  return uri
}

internal data class OptionalNotificationToggleDecision(
    val requested: Boolean,
    val launchPermissionDialog: Boolean,
)

internal fun optionalNotificationToggleDecision(
    enabled: Boolean,
    runtimePermissionRequired: Boolean,
    permissionGranted: Boolean,
): OptionalNotificationToggleDecision =
    OptionalNotificationToggleDecision(
        requested = enabled,
        launchPermissionDialog = enabled && runtimePermissionRequired && !permissionGranted,
    )
