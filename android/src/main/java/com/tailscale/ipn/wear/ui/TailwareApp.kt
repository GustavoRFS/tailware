// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text as Material3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.R
import com.tailscale.ipn.wear.ConnectionControlAction
import com.tailscale.ipn.wear.LoginMethod
import com.tailscale.ipn.wear.LoginPresentation
import com.tailscale.ipn.wear.PhoneLoginStatus
import com.tailscale.ipn.wear.PingFailureCategory
import com.tailscale.ipn.wear.PingStatus
import com.tailscale.ipn.wear.TailwareLanguage
import com.tailscale.ipn.wear.TailwareViewModel
import com.tailscale.ipn.wear.TileQuickAction
import com.tailscale.ipn.wear.VpnAuthorization
import com.tailscale.ipn.wear.WearAccountState
import com.tailscale.ipn.wear.WearAction
import com.tailscale.ipn.wear.WearErrorOrigin
import com.tailscale.ipn.wear.WearHealthSeverity
import com.tailscale.ipn.wear.WearHealthWarning
import com.tailscale.ipn.wear.WearPeer
import com.tailscale.ipn.wear.WearPingResult
import com.tailscale.ipn.wear.WearTunnelStatus
import com.tailscale.ipn.wear.WearUiState
import com.tailscale.ipn.wear.localization.TailwareStrings
import com.tailscale.ipn.wear.localization.tailwareHealthResources
import com.tailscale.ipn.wear.resolveConnectionControlAction
import com.tailscale.ipn.wear.tile.TailwareTileStateStore
import com.tailscale.ipn.wear.tile.TailwareTileUpdates
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private object Route {
  const val Gate = "gate"
  const val Welcome = "welcome"
  const val LoginMethods = "login_methods"
  const val LoginQr = "login_qr"
  const val AuthKey = "auth_key"
  const val ControlUrl = "control_url"
  const val Dashboard = "dashboard"
  const val Devices = "devices"
  const val Device = "device"
  const val ExitNodes = "exit_nodes"
  const val Health = "health"
  const val Settings = "settings"
  const val Language = "language"
  const val Tile = "tile"
  const val VpnSetup = "vpn_setup"
  const val About = "about"
}

internal fun routeRequiresSecureWindow(route: String?): Boolean =
    route == Route.LoginQr || route == Route.AuthKey

internal fun isAuthKeyAuthenticationContinuation(
    currentRoute: String,
    destination: String,
    accountState: WearAccountState,
): Boolean =
    currentRoute == Route.AuthKey &&
        ((destination == Route.LoginQr &&
            accountState == WearAccountState.NeedsMachineAuthorization) ||
            (destination == Route.Dashboard && accountState == WearAccountState.SignedIn))

/** Small stateful boundary used by Compose and JVM lifecycle tests. */
internal class SensitiveLoginWindowCoordinator(private val setSecure: (Boolean) -> Unit) {
  private var applied: Boolean? = null

  fun moveTo(route: String?) {
    if (route == null) return
    val secure = routeRequiresSecureWindow(route)
    if (applied == secure) return
    applied = secure
    setSecure(secure)
  }

  fun close() {
    if (applied == true) setSecure(false)
    applied = false
  }
}

internal fun loginProfileMutationAllowed(busyAction: WearAction?): Boolean =
    busyAction != WearAction.Login && busyAction != WearAction.Logout

@Composable
fun TailwareApp(
    viewModel: TailwareViewModel,
    onSetOptionalNotifications: (Boolean) -> Unit,
    onOpenLoginOnPhone: (String) -> Unit,
    onOpenGuideOnPhone: suspend (String) -> Boolean,
    onOpenAboutWatchSettings: () -> Boolean,
    onOpenDeveloperSettings: () -> Boolean,
    onSensitiveLoginContentVisible: (Boolean) -> Unit,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val navController = rememberSwipeDismissableNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  var selectedPeerId by rememberSaveable { mutableStateOf<String?>(null) }
  var accountRootRoute by rememberSaveable { mutableStateOf(Route.Gate) }
  val authKeyRouteContinuation = remember { mutableStateOf(false) }

  LaunchedEffect(
      state.accountState,
      state.selfNode?.dnsName,
      state.activeExitNodeId,
      state.activeExitNodeName,
      state.allowLanAccess,
      state.error?.message,
      state.healthWarnings,
      state.language,
  ) {
    val changed =
        if (state.accountState == WearAccountState.SignedOut) {
          TailwareTileStateStore.clearIdentityAndStatus(context)
        } else {
          val warning = state.healthWarnings.firstOrNull()
          TailwareTileStateStore.writeSupplemental(
              context = context,
              magicDnsName = state.selfNode?.dnsName,
              allowLanAccess = state.activeExitNodeId != null && state.allowLanAccess,
              problemMessage =
                  state.error?.message?.let { localizedUserMessage(state, it) }
                      ?: warning?.let { localizedHealthMessage(state, it) },
              problemIsError = state.error != null || warning?.severity == WearHealthSeverity.High,
          )
        }
    if (changed) TailwareTileUpdates.request(context)
  }
  LaunchedEffect(state.language) { TailwareTileUpdates.request(context) }

  val currentRoute = backStackEntry?.destination?.route
  val latestSensitiveWindowCallback = rememberUpdatedState(onSensitiveLoginContentVisible)
  val sensitiveWindowCoordinator = remember {
    SensitiveLoginWindowCoordinator { secure -> latestSensitiveWindowCallback.value(secure) }
  }
  DisposableEffect(currentRoute) {
    sensitiveWindowCoordinator.moveTo(currentRoute)
    onDispose {}
  }
  DisposableEffect(sensitiveWindowCoordinator) { onDispose { sensitiveWindowCoordinator.close() } }
  LaunchedEffect(
      state.accountState,
      state.vpnAuthorization,
      state.loginUrl,
      state.error?.origin,
      currentRoute,
  ) {
    val route = currentRoute ?: return@LaunchedEffect
    val destination =
        when {
          shouldForceVpnSetup(state.vpnAuthorization, route) -> Route.VpnSetup
          state.accountState == WearAccountState.SignedIn &&
              state.vpnAuthorization == VpnAuthorization.Granted &&
              route in ONBOARDING_ROUTES -> Route.Dashboard
          state.loginUrl == null &&
              state.error?.origin == WearErrorOrigin.AuthenticationKey &&
              route == Route.LoginQr -> Route.AuthKey
          state.loginUrl == null &&
              state.error?.origin == WearErrorOrigin.CustomServer &&
              route == Route.LoginQr -> Route.ControlUrl
          shouldRedirectToLoginQr(
              accountState = state.accountState,
              authorization = state.vpnAuthorization,
              hasLoginUrl = state.loginUrl != null,
              currentRoute = route,
          ) -> Route.LoginQr
          state.accountState == WearAccountState.SignedOut &&
              (route == Route.Gate || route !in ONBOARDING_ROUTES) -> Route.Welcome
          state.accountState == WearAccountState.NeedsMachineAuthorization &&
              route !in MACHINE_AUTH_ALLOWED_ROUTES -> Route.LoginQr
          else -> null
        }
    if (destination != null) {
      if (route == Route.AuthKey) {
        authKeyRouteContinuation.value =
            isAuthKeyAuthenticationContinuation(route, destination, state.accountState)
      }
      if (routeRequiresSecureWindow(destination)) {
        // Apply screenshot protection before navigation commits the sensitive destination.
        sensitiveWindowCoordinator.moveTo(destination)
      }
      when (destination) {
        Route.LoginQr -> navController.navigate(destination) { launchSingleTop = true }
        Route.AuthKey,
        Route.ControlUrl -> {
          if (!navController.popBackStack(destination, inclusive = false)) {
            navController.navigate(destination) { launchSingleTop = true }
          }
        }
        else -> {
          navController.navigate(destination) {
            popUpTo(accountRootRoute) { inclusive = true }
            launchSingleTop = true
          }
          accountRootRoute = destination
        }
      }
    }
  }

  SwipeDismissableNavHost(
      navController = navController,
      startDestination = Route.Gate,
      modifier = Modifier.fillMaxSize().background(TailwarePalette.Ink),
  ) {
    composable(Route.Gate) { GateScreen(state) }
    composable(Route.Welcome) {
      WelcomeScreen(
          state = state,
          onDismissLogoutNotice = viewModel::clearError,
          onConnectAccount = {
            if (loginProfileMutationAllowed(state.busyAction)) {
              navController.navigate(
                  if (state.vpnAuthorization == VpnAuthorization.AdbRequired) Route.VpnSetup
                  else Route.LoginMethods)
            }
          },
      )
    }
    composable(Route.LoginMethods) {
      LoginMethodsScreen(
          state = state,
          onTailscaleLogin = {
            if (viewModel.startInteractiveLogin(LoginPresentation.Phone)) {
              sensitiveWindowCoordinator.moveTo(Route.LoginQr)
              navController.navigate(Route.LoginQr)
            }
          },
          onAuthKey = {
            sensitiveWindowCoordinator.moveTo(Route.AuthKey)
            navController.navigate(Route.AuthKey)
          },
          onControlUrl = { navController.navigate(Route.ControlUrl) },
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.LoginQr) {
      LaunchedEffect(state.loginPresentation, state.loginUrl, state.phoneLoginStatus) {
        val url = state.loginUrl
        if (state.loginPresentation == LoginPresentation.Phone &&
            url != null &&
            state.phoneLoginStatus == PhoneLoginStatus.Idle) {
          onOpenLoginOnPhone(url)
        }
      }
      val latestAccountState by rememberUpdatedState(state.accountState)
      val explicitCancellationRequested = remember { mutableStateOf(false) }
      DisposableEffect(Unit) {
        onDispose {
          if (!explicitCancellationRequested.value &&
              latestAccountState != WearAccountState.SignedIn &&
              latestAccountState != WearAccountState.NeedsMachineAuthorization) {
            viewModel.cancelInteractiveLogin()
          }
        }
      }
      LoginQrScreen(
          state = state,
          onRetry = { viewModel.regenerateInteractiveLogin(state.loginPresentation) },
          onOpenOnPhone = onOpenLoginOnPhone,
          onResetPhoneError = viewModel::clearPhoneLoginError,
          onCancel = {
            explicitCancellationRequested.value = true
            viewModel.cancelInteractiveLogin()
            navController.popBackStack()
          },
          onSettings = { navController.navigate(Route.Settings) },
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.AuthKey) {
      LaunchedEffect(Unit) { viewModel.prepareLoginMethod(LoginMethod.AuthenticationKey) }
      DisposableEffect(Unit) {
        onDispose {
          val preserveAuthenticatedContinuation = authKeyRouteContinuation.value
          authKeyRouteContinuation.value = false
          viewModel.cancelLoginIfActive(
              LoginMethod.AuthenticationKey,
              preserveAuthenticatedContinuation = preserveAuthenticatedContinuation,
          )
        }
      }
      AuthKeyScreen(
          state = state,
          onLogin = viewModel::startAuthKeyLogin,
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.ControlUrl) {
      val latestLoginUrl by rememberUpdatedState(state.loginUrl)
      LaunchedEffect(Unit) { viewModel.prepareLoginMethod(LoginMethod.CustomServer) }
      DisposableEffect(Unit) {
        onDispose {
          if (latestLoginUrl == null) {
            viewModel.cancelLoginIfActive(LoginMethod.CustomServer)
          }
        }
      }
      ControlUrlScreen(
          state = state,
          onLogin = viewModel::startCustomControlLogin,
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.Dashboard) {
      DashboardScreen(
          state = state,
          onConnectionClick = { action ->
            when (action) {
              ConnectionControlAction.Connect,
              ConnectionControlAction.Disconnect -> viewModel.performConnectionControl(action)
              ConnectionControlAction.OpenVpnSetup -> navController.navigate(Route.VpnSetup)
            }
          },
          onDevices = { navController.navigate(Route.Devices) },
          onExitNodes = { navController.navigate(Route.ExitNodes) },
          onSettings = { navController.navigate(Route.Settings) },
          onHealth = { navController.navigate(Route.Health) },
          onVpnSetup = { navController.navigate(Route.VpnSetup) },
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.Devices) {
      DevicesScreen(
          state = state,
          onPeer = {
            selectedPeerId = it
            navController.navigate(Route.Device)
          },
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.Device) {
      val peer = state.peers.firstOrNull { it.stableId == selectedPeerId }
      DeviceScreen(
          state = state,
          peer = peer,
          onPing = { peer?.stableId?.let(viewModel::pingPeer) },
          onClearPing = viewModel::clearPingResult,
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.ExitNodes) {
      ExitNodesScreen(
          state = state,
          onSelect = viewModel::selectExitNode,
          onClear = viewModel::clearExitNode,
          onToggleLan = { viewModel.toggleAllowLanAccess() },
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.Health) { HealthScreen(state = state) }
    composable(Route.Settings) {
      SettingsScreen(
          state = state,
          onToggleDns = { viewModel.toggleTailscaleDns() },
          onSetNotifications = onSetOptionalNotifications,
          onLanguage = { navController.navigate(Route.Language) },
          onTile = { navController.navigate(Route.Tile) },
          onAbout = { navController.navigate(Route.About) },
          onLogout = viewModel::logoutAccount,
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.Language) { LanguageScreen(state = state, onSelect = viewModel::setLanguage) }
    composable(Route.Tile) {
      TileSettingsScreen(
          state = state,
          onSelect = viewModel::setTileQuickAction,
          onShowIp = viewModel::setTileShowIp,
          onShowExitNode = viewModel::setTileShowExitNode,
          onShowMagicDns = viewModel::setTileShowMagicDns,
      )
    }
    composable(Route.VpnSetup) {
      LaunchedEffect(state.accountState, state.vpnAuthorization) {
        vpnSetupContinuation(state.accountState, state.vpnAuthorization)?.let { continuation ->
          val destination =
              when (continuation) {
                VpnSetupContinuation.LoginMethods -> Route.LoginMethods
                VpnSetupContinuation.LoginQr -> Route.LoginQr
                VpnSetupContinuation.Dashboard -> Route.Dashboard
              }
          navController.navigate(destination) {
            popUpTo(Route.VpnSetup) { inclusive = true }
            launchSingleTop = true
          }
          accountRootRoute = destination
        }
      }
      VpnSetupScreen(
          state = state,
          onRetry = { viewModel.checkVpnAuthorizationFromUserGesture(context) },
          onOpenGuideOnPhone = { onOpenGuideOnPhone(VPN_SETUP_GUIDE_URL) },
          onOpenAboutWatchSettings = onOpenAboutWatchSettings,
          onOpenDeveloperSettings = onOpenDeveloperSettings,
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.About) { AboutScreen(state) }
  }
}

@Composable
private fun GateScreen(state: WearUiState) {
  Box(
      modifier = Modifier.fillMaxSize().background(TailwarePalette.Ink),
      contentAlignment = Alignment.Center,
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Image(
          painter = painterResource(R.drawable.tailware_mark),
          contentDescription = tr(state, R.string.wear_brand_tailware),
          modifier = Modifier.size(72.dp),
      )
      CircularProgressIndicator(
          modifier = Modifier.size(28.dp),
          indicatorColor = TailwarePalette.Signal,
      )
    }
  }
}

@Composable
private fun WelcomeScreen(
    state: WearUiState,
    onDismissLogoutNotice: () -> Unit,
    onConnectAccount: () -> Unit,
) {
  TailwareListScreen(title = tr(state, R.string.wear_brand_tailware_upper)) {
    item {
      Image(
          painter = painterResource(R.drawable.tailware_mark),
          contentDescription = tr(state, R.string.wear_brand_tailware),
          modifier =
              Modifier.size(if (LocalConfiguration.current.screenWidthDp < 210) 64.dp else 78.dp),
      )
    }
    item {
      Text(
          text = tr(state, R.string.wear_your_private_network_on_your_wrist),
          color = TailwarePalette.Muted,
          textAlign = TextAlign.Center,
          maxLines = 3,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
      )
    }
    item {
      TailwareChip(
          label = tr(state, R.string.wear_connect_account),
          secondary = tr(state, R.string.wear_tailscale_or_headscale),
          onClick = onConnectAccount,
          accent = true,
          enabled = loginProfileMutationAllowed(state.busyAction),
      )
    }
    errorItem(state, onDismissLogoutNotice, operations = setOf(WearAction.Logout))
  }
}

@Composable
private fun LoginMethodsScreen(
    state: WearUiState,
    onTailscaleLogin: () -> Unit,
    onAuthKey: () -> Unit,
    onControlUrl: () -> Unit,
    onDismissError: () -> Unit,
) {
  TailwareListScreen(title = tr(state, R.string.wear_connect_account)) {
    errorItem(state, onDismissError)
    item {
      TailwareChip(
          label = tr(state, R.string.wear_sign_in_with_tailscale),
          secondary = tr(state, R.string.wear_open_on_your_phone_with_qr_as_fallback),
          onClick = onTailscaleLogin,
          accent = true,
          enabled = loginProfileMutationAllowed(state.busyAction),
      )
    }
    item {
      TailwareChip(
          label = tr(state, R.string.wear_tailscale_authentication_key),
          secondary = tr(state, R.string.wear_for_advanced_or_unattended_setup),
          onClick = onAuthKey,
          enabled = loginProfileMutationAllowed(state.busyAction),
      )
    }
    item {
      TailwareChip(
          label = tr(state, R.string.wear_headscale_custom_server),
          secondary = tr(state, R.string.wear_enter_a_control_server_url),
          onClick = onControlUrl,
          enabled = loginProfileMutationAllowed(state.busyAction),
      )
    }
  }
}

@Composable
private fun LoginQrScreen(
    state: WearUiState,
    onRetry: () -> Unit,
    onOpenOnPhone: (String) -> Unit,
    onResetPhoneError: () -> Unit,
    onCancel: () -> Unit,
    onSettings: () -> Unit,
    onDismissError: () -> Unit,
) {
  var qrRetryKey by rememberSaveable { mutableIntStateOf(0) }
  val qrState = rememberQrCode(state.loginUrl, size = 420, retryKey = qrRetryKey)
  val small = LocalConfiguration.current.screenWidthDp < 210
  TailwareListScreen(title = tr(state, R.string.wear_sign_in)) {
    errorItem(
        state,
        onDismissError,
        origins =
            setOf(
                WearErrorOrigin.InteractiveLogin,
                WearErrorOrigin.CustomServer,
                WearErrorOrigin.Global,
            ),
    )
    when {
      state.accountState == WearAccountState.NeedsMachineAuthorization -> {
        item {
          TailwareInfo(
              label = tr(state, R.string.wear_approval_required),
              value = tr(state, R.string.wear_approve_this_watch_in_the_admin_console),
              valueColor = TailwarePalette.Warning,
          )
        }
      }
      state.loginUrl != null -> {
        item {
          TailwareChip(
              label =
                  when (state.phoneLoginStatus) {
                    PhoneLoginStatus.Opening -> tr(state, R.string.wear_opening_on_phone)
                    PhoneLoginStatus.Sent -> tr(state, R.string.wear_sent_to_phone)
                    PhoneLoginStatus.Idle,
                    PhoneLoginStatus.Failed -> tr(state, R.string.wear_continue_on_phone)
                  },
              secondary =
                  when (state.phoneLoginStatus) {
                    PhoneLoginStatus.Opening -> tr(state, R.string.wear_contacting_the_paired_phone)
                    PhoneLoginStatus.Sent ->
                        tr(state, R.string.wear_finish_sign_in_on_the_paired_phone)
                    PhoneLoginStatus.Failed ->
                        if (qrState is QrCodeState.Ready)
                            tr(state, R.string.wear_try_again_or_use_the_qr_code_below)
                        else tr(state, R.string.wear_try_again)
                    PhoneLoginStatus.Idle -> tr(state, R.string.wear_fastest_secure_sign_in_method)
                  },
              onClick = {
                onResetPhoneError()
                onOpenOnPhone(state.loginUrl)
              },
              enabled = state.phoneLoginStatus != PhoneLoginStatus.Opening,
              accent = true,
          )
        }
        if (state.phoneLoginStatus == PhoneLoginStatus.Opening) {
          item {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                indicatorColor = TailwarePalette.Signal,
            )
          }
        }
        state.phoneLoginError?.let { message ->
          item {
            TailwareInfo(
                label = tr(state, R.string.wear_phone_unavailable),
                value = localizedUserMessage(state, message),
                valueColor = TailwarePalette.Warning,
                announceChanges = true,
            )
          }
        }
        when (val currentQrState = qrState) {
          is QrCodeState.Ready -> {
            item {
              Box(
                  modifier =
                      Modifier.size(if (small) 132.dp else 156.dp)
                          .background(Color.White, RoundedCornerShape(12.dp))
                          .padding(8.dp),
                  contentAlignment = Alignment.Center,
              ) {
                Image(
                    bitmap = currentQrState.bitmap,
                    contentDescription = tr(state, R.string.wear_tailscale_login_qr_code),
                    modifier = Modifier.fillMaxSize(),
                )
              }
            }
          }
          QrCodeState.Loading -> {
            item {
              CircularProgressIndicator(
                  modifier = Modifier.size(24.dp),
                  indicatorColor = TailwarePalette.Signal,
              )
            }
          }
          QrCodeState.Error -> {
            item {
              TailwareInfo(
                  label = tr(state, R.string.wear_tailscale_login_qr_code),
                  value = tr(state, R.string.wear_unexpected_error_try_again),
                  valueColor = TailwarePalette.Warning,
                  announceChanges = true,
              )
            }
            item {
              TailwareChip(
                  label = tr(state, R.string.wear_try_again),
                  onClick = { qrRetryKey += 1 },
              )
            }
          }
          QrCodeState.Empty -> Unit
        }
        state.loginCode?.let { code ->
          item {
            TailwareInfo(
                label = tr(state, R.string.wear_login_code),
                value = TailwareStrings.ltr(state.language, code),
                mono = true,
                valueColor = TailwarePalette.Signal,
            )
          }
        }
        if (qrState is QrCodeState.Ready) {
          item {
            Text(
                text = tr(state, R.string.wear_scan_with_your_phone_then_approve_tailware),
                color = TailwarePalette.Muted,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
          }
        }
        if (state.activeLoginMethod == LoginMethod.Tailscale) {
          item {
            TailwareChip(
                label = tr(state, R.string.wear_generate_new_code),
                secondary = tr(state, R.string.wear_the_previous_link_will_no_longer_be_shown),
                onClick = onRetry,
            )
          }
        }
      }
      else -> {
        item {
          CircularProgressIndicator(
              modifier = Modifier.size(34.dp),
              indicatorColor = TailwarePalette.Signal,
          )
        }
        item {
          Text(
              text = tr(state, R.string.wear_creating_a_secure_login),
              color = TailwarePalette.Muted,
              textAlign = TextAlign.Center,
          )
        }
        item {
          TailwareChip(
              label = tr(state, R.string.wear_try_again),
              onClick = onRetry,
              enabled = state.busyAction != com.tailscale.ipn.wear.WearAction.Login,
          )
        }
      }
    }
    item { TailwareChip(label = tr(state, R.string.wear_cancel_sign_in), onClick = onCancel) }
    if (state.accountState == WearAccountState.NeedsMachineAuthorization) {
      item { TailwareChip(label = tr(state, R.string.wear_settings), onClick = onSettings) }
    }
  }
}

@Composable
private fun AuthKeyScreen(
    state: WearUiState,
    onLogin: (String) -> Boolean,
    onDismissError: () -> Unit,
) {
  // Authentication keys are secrets: never place them in a saveable state Bundle.
  var key by remember { mutableStateOf("") }
  TailwareListScreen(title = tr(state, R.string.wear_authentication_key)) {
    errorItem(
        state,
        onDismissError,
        origins = setOf(WearErrorOrigin.AuthenticationKey, WearErrorOrigin.Global),
    )
    item {
      WearTextField(
          value = key,
          onValueChange = { key = it },
          label = tr(state, R.string.wear_auth_key_example),
          keyboardType = KeyboardType.Ascii,
          mono = true,
      )
    }
    if (state.busyAction == com.tailscale.ipn.wear.WearAction.Login) {
      item {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            indicatorColor = TailwarePalette.Signal,
        )
      }
    }
    item {
      TailwareChip(
          label = tr(state, R.string.wear_sign_in),
          secondary = tr(state, R.string.wear_the_key_remains_visible_while_it_is_checked),
          onClick = { onLogin(key) },
          enabled = key.isNotBlank() && loginProfileMutationAllowed(state.busyAction),
          accent = true,
      )
    }
  }
}

@Composable
private fun ControlUrlScreen(
    state: WearUiState,
    onLogin: (String) -> Boolean,
    onDismissError: () -> Unit,
) {
  var url by rememberSaveable { mutableStateOf("https://") }
  TailwareListScreen(title = tr(state, R.string.wear_label_headscale)) {
    errorItem(
        state,
        onDismissError,
        origins = setOf(WearErrorOrigin.CustomServer, WearErrorOrigin.Global),
    )
    if (state.busyAction == com.tailscale.ipn.wear.WearAction.Login) {
      item {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            indicatorColor = TailwarePalette.Signal,
        )
      }
    }
    item {
      WearTextField(
          value = url,
          onValueChange = { url = it },
          label = tr(state, R.string.wear_control_url_example),
          keyboardType = KeyboardType.Uri,
      )
    }
    item {
      TailwareChip(
          label = tr(state, R.string.wear_connect_server),
          onClick = { onLogin(url) },
          enabled = url.length > "https://".length && loginProfileMutationAllowed(state.busyAction),
          accent = true,
      )
    }
  }
}

@Composable
private fun DashboardScreen(
    state: WearUiState,
    onConnectionClick: (ConnectionControlAction) -> Unit,
    onDevices: () -> Unit,
    onExitNodes: () -> Unit,
    onSettings: () -> Unit,
    onHealth: () -> Unit,
    onVpnSetup: () -> Unit,
    onDismissError: () -> Unit,
) {
  val statusText =
      when (state.tunnelStatus) {
        WearTunnelStatus.Connected -> tr(state, R.string.wear_connected)
        WearTunnelStatus.Connecting -> tr(state, R.string.wear_connecting)
        WearTunnelStatus.Disconnecting -> tr(state, R.string.wear_disconnecting)
        WearTunnelStatus.Disconnected -> tr(state, R.string.wear_disconnected)
        WearTunnelStatus.Unknown -> tr(state, R.string.wear_starting)
      }
  val statusColor =
      when (state.tunnelStatus) {
        WearTunnelStatus.Connected -> TailwarePalette.Signal
        WearTunnelStatus.Connecting,
        WearTunnelStatus.Disconnecting -> TailwarePalette.Warning
        WearTunnelStatus.Disconnected -> TailwarePalette.Muted
        WearTunnelStatus.Unknown -> TailwarePalette.Blue
      }
  val connectionAction =
      resolveConnectionControlAction(
          vpnAuthorization = state.vpnAuthorization,
          desiredVpnEnabled = state.desiredVpnEnabled,
          errorOperation = state.error?.operation,
      )
  val connectionActionLabel =
      when (connectionAction) {
        ConnectionControlAction.Connect -> tr(state, R.string.wear_connect)
        ConnectionControlAction.Disconnect -> tr(state, R.string.wear_disconnect)
        ConnectionControlAction.OpenVpnSetup -> tr(state, R.string.wear_vpn_setup)
      }

  TailwareListScreen(title = tr(state, R.string.wear_brand_tailware_upper)) {
    item {
      ConnectionOrb(
          status = state.tunnelStatus,
          onClick = { onConnectionClick(connectionAction) },
          enabled =
              state.accountState == WearAccountState.SignedIn &&
                  state.busyAction != com.tailscale.ipn.wear.WearAction.Login &&
                  state.busyAction != com.tailscale.ipn.wear.WearAction.Logout,
          contentDescription =
              connectionControlContentDescription(
                  controlLabel = tr(state, R.string.wear_tailware_connection),
                  statusLabel = statusText,
                  actionLabel = connectionActionLabel,
              ),
          stateDescription = statusText,
          actionLabel = connectionActionLabel,
      )
    }
    item {
      Text(
          text = statusText,
          color = statusColor,
          fontWeight = FontWeight.SemiBold,
          fontSize = 15.sp,
          textAlign = TextAlign.Center,
      )
    }
    state.healthWarnings.firstOrNull()?.let { warning ->
      item {
        TailwareChip(
            label = localizedHealthTitle(state, warning),
            secondary = tr(state, R.string.wear_tap_for_details),
            onClick = onHealth,
            iconRes = R.drawable.warning_rounded,
            warning = warning.severity != WearHealthSeverity.High,
            danger = warning.severity == WearHealthSeverity.High,
        )
      }
    }
    if (state.vpnAuthorization != VpnAuthorization.Granted) {
      item {
        TailwareChip(
            label = tr(state, R.string.wear_vpn_setup_needed),
            secondary = tr(state, R.string.wear_one_time_adb_authorization),
            onClick = onVpnSetup,
        )
      }
    }
    errorItem(state, onDismissError)
    state.selfNode?.let { self ->
      item {
        TailwareInfo(
            label = tr(state, R.string.wear_this_watch),
            value = TailwareStrings.isolate(state.language, self.displayName),
        )
      }
      (self.ipv4 ?: self.ipv6)?.let { address ->
        item {
          TailwareInfo(
              label = tr(state, R.string.wear_label_tailscale_ip),
              value = TailwareStrings.ltr(state.language, address),
              mono = true,
          )
        }
      }
      if (self.dnsName.isNotBlank()) {
        item {
          TailwareInfo(
              label = tr(state, R.string.wear_label_magicdns),
              value = TailwareStrings.ltr(state.language, self.dnsName),
              mono = true,
          )
        }
      }
    }
    item {
      TailwareChip(
          label = tr(state, R.string.wear_my_devices),
          secondary =
              trQuantity(
                  state, R.plurals.wear_devices_in_tailnet, state.peers.size, state.peers.size),
          onClick = onDevices,
      )
    }
    item {
      TailwareChip(
          label = tr(state, R.string.wear_exit_node),
          secondary =
              state.activeExitNodeName?.let { TailwareStrings.isolate(state.language, it) }
                  ?: tr(state, R.string.wear_none),
          onClick = onExitNodes,
      )
    }
    item {
      TailwareChip(
          label = tr(state, R.string.wear_settings),
          secondary = tr(state, R.string.wear_language_dns_account_and_tile),
          onClick = onSettings,
          iconRes = R.drawable.ic_tailware_settings,
      )
    }
  }
}

@Composable
private fun HealthScreen(state: WearUiState) {
  TailwareListScreen(title = tr(state, R.string.wear_connection_health)) {
    if (state.healthWarnings.isEmpty()) {
      item {
        TailwareInfo(
            label = tr(state, R.string.wear_status),
            value = tr(state, R.string.wear_no_problems_detected),
            valueColor = TailwarePalette.Signal,
        )
      }
    } else {
      items(state.healthWarnings, key = { it.code }) { warning ->
        TailwareInfo(
            label = localizedHealthTitle(state, warning),
            value = localizedHealthMessage(state, warning),
            valueColor =
                if (warning.severity == WearHealthSeverity.High) TailwarePalette.Danger
                else TailwarePalette.Warning,
        )
      }
    }
  }
}

@Composable
private fun DevicesScreen(
    state: WearUiState,
    onPeer: (String) -> Unit,
    onDismissError: () -> Unit,
) {
  TailwareListScreen(title = tr(state, R.string.wear_my_devices)) {
    errorItem(state, onDismissError)
    if (state.peers.isEmpty()) {
      item {
        Text(
            text = tr(state, R.string.wear_no_devices_available_yet),
            color = TailwarePalette.Muted,
            textAlign = TextAlign.Center,
        )
      }
    }
    items(state.peers, key = { it.stableId }) { peer ->
      val effectivelyOnline = peerIsEffectivelyOnline(state, peer)
      val status =
          if (effectivelyOnline) tr(state, R.string.wear_online)
          else tr(state, R.string.wear_offline)
      val address =
          peer.ipv4?.let { TailwareStrings.ltr(state.language, it) }
              ?: peer.ipv6?.let { TailwareStrings.ltr(state.language, it) }
              ?: "—"
      TailwareChip(
          label = TailwareStrings.isolate(state.language, peer.displayName),
          secondary =
              buildList {
                    if (peer.isSelf) add(tr(state, R.string.wear_this_watch_ad1be56b))
                    add(status)
                    add(address)
                  }
                  .joinToString(" · "),
          onClick = { onPeer(peer.stableId) },
          accent = peer.isSelf,
      )
    }
  }
}

@Composable
private fun DeviceScreen(
    state: WearUiState,
    peer: WearPeer?,
    onPing: () -> Unit,
    onClearPing: () -> Unit,
    onDismissError: () -> Unit,
) {
  val pingResult = state.pingResult.takeIf { it.peerId == peer?.stableId }
  val effectivelyOnline = peer?.let { peerIsEffectivelyOnline(state, it) } == true
  TailwareListScreen(
      title =
          peer?.displayName?.let { TailwareStrings.isolate(state.language, it) }
              ?: tr(state, R.string.wear_device)) {
        errorItem(state, onDismissError)
        if (peer == null) {
          item {
            TailwareInfo(
                label = tr(state, R.string.wear_unavailable),
                value = tr(state, R.string.wear_this_device_left_the_tailnet),
                valueColor = TailwarePalette.Warning,
            )
          }
          return@TailwareListScreen
        }
        item {
          TailwareInfo(
              label = tr(state, R.string.wear_status),
              value =
                  if (effectivelyOnline) tr(state, R.string.wear_online)
                  else tr(state, R.string.wear_offline),
              valueColor = if (effectivelyOnline) TailwarePalette.Signal else TailwarePalette.Muted,
          )
        }
        peer.ipv4?.let {
          item {
            TailwareInfo(
                label = tr(state, R.string.wear_label_ipv4),
                value = TailwareStrings.ltr(state.language, it),
                mono = true,
            )
          }
        }
        peer.ipv6?.let {
          item {
            TailwareInfo(
                label = tr(state, R.string.wear_label_ipv6),
                value = TailwareStrings.ltr(state.language, it),
                mono = true,
            )
          }
        }
        if (peer.dnsName.isNotBlank()) {
          item {
            TailwareInfo(
                label = tr(state, R.string.wear_label_magicdns),
                value = TailwareStrings.ltr(state.language, peer.dnsName),
                mono = true,
            )
          }
        }
        peer.os
            ?.takeIf { it.isNotBlank() }
            ?.let {
              item {
                TailwareInfo(
                    label = tr(state, R.string.wear_label_os),
                    value = TailwareStrings.isolate(state.language, it),
                )
              }
            }
        if (!peer.isSelf) {
          item {
            TailwareChip(
                label = tr(state, R.string.wear_ping_device),
                secondary = tr(state, R.string.wear_measure_tailnet_latency),
                onClick = onPing,
                enabled =
                    state.tunnelStatus == WearTunnelStatus.Connected &&
                        pingResult?.status != PingStatus.Running,
                accent = true,
            )
          }
          when (pingResult?.status ?: PingStatus.Idle) {
            PingStatus.Running ->
                item {
                  CircularProgressIndicator(
                      modifier = Modifier.size(30.dp),
                      indicatorColor = TailwarePalette.Signal,
                  )
                }
            PingStatus.Success ->
                item {
                  TailwareInfo(
                      label = tr(state, R.string.wear_label_ping),
                      value =
                          TailwareStrings.ltr(
                              state.language,
                              tr(
                                  state,
                                  R.string.wear_ping_latency_ms,
                                  pingResult?.latencyMillis ?: 0.0),
                          ),
                      mono = true,
                      valueColor = TailwarePalette.Signal,
                  )
                }
            PingStatus.Failed ->
                item {
                  TailwareChip(
                      label = tr(state, R.string.wear_ping_failed),
                      secondary = pingResult?.let { localizedPingFailure(state, it) },
                      onClick = onClearPing,
                  )
                }
            PingStatus.Idle -> Unit
          }
        }
      }
}

@Composable
private fun ExitNodesScreen(
    state: WearUiState,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onToggleLan: () -> Unit,
    onDismissError: () -> Unit,
) {
  TailwareListScreen(title = tr(state, R.string.wear_exit_node)) {
    errorItem(state, onDismissError)
    item {
      TailwareChip(
          label =
              if (state.activeExitNodeId == null) tr(state, R.string.wear_no_exit_node)
              else tr(state, R.string.wear_disable_active_exit_node),
          secondary =
              if (state.activeExitNodeId == null) tr(state, R.string.wear_active)
              else
                  state.activeExitNodeName?.let { TailwareStrings.isolate(state.language, it) }
                      ?: tr(state, R.string.wear_use_the_local_internet_connection),
          onClick = onClear,
          accent = state.activeExitNodeId == null,
      )
    }
    items(state.exitNodes, key = { it.stableId }) { node ->
      TailwareChip(
          label = TailwareStrings.isolate(state.language, node.displayName),
          secondary =
              buildString {
                append(
                    if (node.online) tr(state, R.string.wear_online)
                    else tr(state, R.string.wear_offline))
                if (node.active) append(" · ${tr(state, R.string.wear_active)}")
                else if (node.selected) append(" · ${tr(state, R.string.wear_last_used)}")
              },
          onClick = { onSelect(node.stableId) },
          enabled = node.online,
          accent = node.active,
      )
    }
    if (state.activeExitNodeId != null) {
      item {
        TailwareToggle(
            label = tr(state, R.string.wear_allow_lan_access),
            secondary = tr(state, R.string.wear_keep_access_to_the_watch_s_local_network),
            checked = state.allowLanAccess,
            onCheckedChange = { onToggleLan() },
        )
      }
    }
  }
}

@Composable
private fun SettingsScreen(
    state: WearUiState,
    onToggleDns: () -> Unit,
    onSetNotifications: (Boolean) -> Unit,
    onLanguage: () -> Unit,
    onTile: () -> Unit,
    onAbout: () -> Unit,
    onLogout: () -> Unit,
    onDismissError: () -> Unit,
) {
  var confirmLogout by remember { mutableStateOf(false) }
  var logoutReady by remember { mutableStateOf(false) }
  LaunchedEffect(confirmLogout) {
    if (confirmLogout) {
      logoutReady = false
      delay(700)
      if (!confirmLogout) return@LaunchedEffect
      logoutReady = true
      delay(9_300)
      confirmLogout = false
      logoutReady = false
    } else {
      logoutReady = false
    }
  }
  val logoutMode = logoutUiMode(confirmLogout = confirmLogout, busyAction = state.busyAction)
  TailwareListScreen(title = tr(state, R.string.wear_settings)) {
    errorItem(state, onDismissError, excludedOperations = setOf(WearAction.Logout))
    item {
      TailwareChip(
          label = tr(state, R.string.wear_language),
          secondary = languageName(state.language),
          onClick = onLanguage,
          iconRes = R.drawable.globe,
      )
    }
    item {
      TailwareToggle(
          label = tr(state, R.string.wear_label_tailscale_dns),
          secondary = tr(state, R.string.wear_label_magicdns),
          checked = state.accountState == WearAccountState.SignedIn && state.tailscaleDnsEnabled,
          onCheckedChange = { onToggleDns() },
          enabled =
              state.accountState == WearAccountState.SignedIn &&
                  state.tailscaleSettingsReady &&
                  state.busyAction == null,
      )
    }
    item {
      TailwareToggle(
          label = tr(state, R.string.wear_disconnected_notification),
          secondary =
              if (state.optionalNotificationsBlockedBySystem)
                  tr(state, R.string.wear_blocked_in_android_settings)
              else
                  tr(state, R.string.wear_optional_android_still_shows_the_active_vpn_notification),
          checked = state.optionalNotificationsRequested,
          onCheckedChange = onSetNotifications,
      )
    }
    item {
      TailwareChip(
          label = tr(state, R.string.wear_tailware_tile),
          secondary = tr(state, R.string.wear_status_and_quick_action),
          onClick = onTile,
      )
    }
    state.selfNode?.let { self ->
      item {
        TailwareInfo(
            label = tr(state, R.string.wear_device_key),
            value = formatKeyExpiry(state, self.keyExpiry),
            valueColor =
                if (keyIsExpired(self.keyExpiry)) TailwarePalette.Danger else TailwarePalette.Ice,
        )
      }
    }
    errorItem(state, onDismissError, operations = setOf(WearAction.Logout))
    item {
      TailwareChip(
          label =
              when (logoutMode) {
                LogoutUiMode.Busy -> "${tr(state, R.string.wear_sign_out)}…"
                LogoutUiMode.Confirm -> tr(state, R.string.wear_confirm_sign_out)
                LogoutUiMode.Idle -> tr(state, R.string.wear_sign_out)
              },
          secondary =
              when (logoutMode) {
                LogoutUiMode.Busy ->
                    state.profileDisplayName?.let { TailwareStrings.isolate(state.language, it) }
                LogoutUiMode.Confirm ->
                    if (state.underlyingNetworkStatus !=
                        com.tailscale.ipn.UnderlyingNetworkStatus.Online)
                        tr(state, R.string.wear_signs_out_locally_the_old_admin_entry_may_remain)
                    else tr(state, R.string.wear_tap_once_more_to_sign_out_on_this_watch)
                LogoutUiMode.Idle ->
                    state.profileDisplayName?.let { TailwareStrings.isolate(state.language, it) }
              },
          onClick = {
            if (confirmLogout && logoutReady) {
              confirmLogout = false
              logoutReady = false
              onLogout()
            } else if (!confirmLogout) {
              confirmLogout = true
            }
          },
          enabled =
              (state.accountState == WearAccountState.SignedIn ||
                  state.accountState == WearAccountState.NeedsMachineAuthorization) &&
                  state.busyAction == null &&
                  (!confirmLogout || logoutReady),
          accent = logoutMode != LogoutUiMode.Idle,
      )
    }
    if (logoutMode == LogoutUiMode.Busy) {
      item {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            indicatorColor = TailwarePalette.Signal,
        )
      }
    }
    item {
      TailwareChip(
          label = tr(state, R.string.wear_about_tailware),
          secondary = tr(state, R.string.wear_version_source_and_licenses),
          onClick = onAbout,
      )
    }
  }
}

@Composable
private fun AboutScreen(state: WearUiState) {
  val context = LocalContext.current
  val licenseText =
      remember(context) {
        context.resources.openRawResource(R.raw.tailscale_bsd_license).bufferedReader().use {
          it.readText()
        }
      }
  TailwareListScreen(title = tr(state, R.string.wear_about_tailware)) {
    item {
      Image(
          painter = painterResource(R.drawable.tailware_mark),
          contentDescription = tr(state, R.string.wear_brand_tailware),
          modifier = Modifier.size(64.dp),
      )
    }
    item {
      TailwareInfo(
          label = tr(state, R.string.wear_version),
          value = TailwareStrings.ltr(state.language, BuildConfig.VERSION_NAME),
          mono = true,
      )
    }
    item {
      TailwareInfo(
          label = tr(state, R.string.wear_source),
          value = TailwareStrings.ltr(state.language, "github.com/vladbezm/tailware"),
          mono = true,
      )
    }
    item {
      Text(
          text =
              tr(
                  state,
                  R.string.wear_independent_open_source_client_based_on_tailscale_for_a_6c2834be),
          color = TailwarePalette.Muted,
          fontSize = 11.sp,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
      )
    }
    item {
      Text(
          text = tr(state, R.string.wear_bsd_3_clause_license),
          color = TailwarePalette.Signal,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
      )
    }
    item {
      CompositionLocalProvider(LocalLayoutDirection provides legalTextLayoutDirection()) {
        Text(
            text = licenseText,
            color = TailwarePalette.Ice,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
      }
    }
  }
}

@Composable
private fun LanguageScreen(state: WearUiState, onSelect: (TailwareLanguage) -> Unit) {
  TailwareListScreen(title = tr(state, R.string.wear_language)) {
    items(TailwareLanguage.entries) { language ->
      TailwareChip(
          label = TailwareStrings.isolate(state.language, languageName(language)),
          secondary = if (state.language == language) tr(state, R.string.wear_active) else null,
          onClick = { onSelect(language) },
          accent = state.language == language,
      )
    }
  }
}

@Composable
private fun TileSettingsScreen(
    state: WearUiState,
    onSelect: (TileQuickAction) -> Unit,
    onShowIp: (Boolean) -> Unit,
    onShowExitNode: (Boolean) -> Unit,
    onShowMagicDns: (Boolean) -> Unit,
) {
  TailwareListScreen(title = tr(state, R.string.wear_tailware_tile)) {
    item {
      Text(
          text = tr(state, R.string.wear_choose_what_the_compact_tile_shows_and_what_a_tap_does),
          color = TailwarePalette.Muted,
          textAlign = TextAlign.Center,
          maxLines = 5,
      )
    }
    item {
      TailwareChip(
          label = tr(state, R.string.wear_tap_toggles_connection),
          secondary =
              if (state.tileQuickAction == TileQuickAction.ToggleConnection)
                  tr(state, R.string.wear_active)
              else null,
          onClick = { onSelect(TileQuickAction.ToggleConnection) },
          accent = state.tileQuickAction == TileQuickAction.ToggleConnection,
      )
    }
    item {
      TailwareChip(
          label = tr(state, R.string.wear_tap_opens_tailware),
          secondary =
              if (state.tileQuickAction == TileQuickAction.OpenApp) tr(state, R.string.wear_active)
              else null,
          onClick = { onSelect(TileQuickAction.OpenApp) },
          accent = state.tileQuickAction == TileQuickAction.OpenApp,
      )
    }
    item {
      TailwareToggle(
          label = tr(state, R.string.wear_label_tailscale_ip),
          checked = state.tileShowIp,
          onCheckedChange = onShowIp,
      )
    }
    item {
      TailwareToggle(
          label = tr(state, R.string.wear_exit_node),
          checked = state.tileShowExitNode,
          onCheckedChange = onShowExitNode,
      )
    }
    item {
      TailwareToggle(
          label = tr(state, R.string.wear_label_magicdns),
          checked = state.tileShowMagicDns,
          onCheckedChange = onShowMagicDns,
      )
    }
  }
}

@Composable
private fun VpnSetupScreen(
    state: WearUiState,
    onRetry: () -> Unit,
    onOpenGuideOnPhone: suspend () -> Boolean,
    onOpenAboutWatchSettings: () -> Boolean,
    onOpenDeveloperSettings: () -> Boolean,
    onDismissError: () -> Unit,
) {
  val small = LocalConfiguration.current.screenWidthDp < 210
  var guideQrRetryKey by rememberSaveable { mutableIntStateOf(0) }
  val guideQrState = rememberQrCode(VPN_SETUP_GUIDE_URL, size = 420, retryKey = guideQrRetryKey)
  val coroutineScope = rememberCoroutineScope()
  var settingsError by remember { mutableStateOf<String?>(null) }
  var guideError by remember { mutableStateOf<String?>(null) }
  var guideOpening by remember { mutableStateOf(false) }
  val openGuide = {
    if (!guideOpening) {
      guideOpening = true
      guideError = null
      coroutineScope.launch {
        guideError =
            if (onOpenGuideOnPhone()) {
              null
            } else {
              tr(state, R.string.wear_phone_unavailable)
            }
        guideOpening = false
      }
    }
    Unit
  }
  val guideActionLabel =
      if (guideOpening) {
        tr(state, R.string.wear_opening_on_phone)
      } else {
        tr(state, R.string.wear_open_guide_on_phone)
      }

  TailwareListScreen(title = tr(state, R.string.wear_vpn_setup)) {
    errorItem(state, onDismissError)
    when (state.vpnAuthorization) {
      VpnAuthorization.Granted -> {
        item {
          TailwareInfo(
              label = tr(state, R.string.wear_authorization),
              value = tr(state, R.string.wear_ready),
              valueColor = TailwarePalette.Signal,
          )
        }
      }
      VpnAuthorization.AdbRequired -> {
        item {
          TailwareInfo(
              label = tr(state, R.string.wear_one_time_setup),
              value =
                  tr(
                      state,
                      R.string.wear_tailware_is_waiting_for_vpn_authorization_from_your_computer),
              valueColor = TailwarePalette.Warning,
          )
        }
        item {
          CircularProgressIndicator(
              modifier = Modifier.size(24.dp),
              indicatorColor = TailwarePalette.Warning,
          )
        }
      }
    }
    if (state.vpnAuthorization != VpnAuthorization.Granted) {
      item {
        TailwareChip(
            label = tr(state, R.string.wear_1_enable_developer_options),
            secondary =
                tr(state, R.string.wear_about_watch_software_information_tap_build_number_7_times),
            secondaryMaxLines = Int.MAX_VALUE,
            onClick = {
              settingsError =
                  if (onOpenAboutWatchSettings()) {
                    null
                  } else {
                    tr(state, R.string.wear_open_about_watch_manually_in_settings)
                  }
            },
        )
      }
      item {
        TailwareChip(
            label = tr(state, R.string.wear_2_enable_adb_and_wireless_debugging),
            secondary = tr(state, R.string.wear_developer_options_adb_debugging_wireless_debugging),
            secondaryMaxLines = Int.MAX_VALUE,
            onClick = {
              settingsError =
                  if (onOpenDeveloperSettings()) {
                    null
                  } else {
                    tr(state, R.string.wear_open_developer_options_manually_in_settings)
                  }
            },
        )
      }
      settingsError?.let { message ->
        item {
          TailwareInfo(
              label = tr(state, R.string.wear_settings),
              value = message,
              valueColor = TailwarePalette.Warning,
              announceChanges = true,
          )
        }
      }
      item {
        Text(
            text =
                tr(
                    state,
                    R.string.wear_3_pair_the_watch_from_a_computer_then_run_the_tailware_helper),
            color = TailwarePalette.Ice,
            textAlign = TextAlign.Center,
            maxLines = 5,
        )
      }
      item {
        TailwareInfo(
            label = tr(state, R.string.wear_label_windows),
            value = TailwareStrings.ltr(state.language, ".\\tools\\authorize-wear-vpn.ps1"),
            mono = true,
            valueColor = TailwarePalette.Signal,
        )
      }
      item {
        TailwareInfo(
            label = tr(state, R.string.wear_label_macos_linux),
            value = TailwareStrings.ltr(state.language, "./tools/authorize-wear-vpn.sh"),
            mono = true,
            valueColor = TailwarePalette.Signal,
        )
      }
      item {
        TailwareInfo(
            label = tr(state, R.string.wear_label_package),
            value = TailwareStrings.ltr(state.language, "io.github.vladbezm.tailware"),
            mono = true,
        )
      }
      when (val currentGuideQrState = guideQrState) {
        is QrCodeState.Ready -> {
          item {
            Box(
                modifier =
                    Modifier.size(if (small) 132.dp else 156.dp)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .clickable(
                            enabled = !guideOpening,
                            onClickLabel = tr(state, R.string.wear_open_guide_on_phone),
                            role = Role.Button,
                            onClick = openGuide,
                        )
                        .semantics(mergeDescendants = true) {
                          contentDescription =
                              tr(state, R.string.wear_qr_code_for_the_tailware_vpn_setup_guide)
                        }
                        .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
              Image(
                  bitmap = currentGuideQrState.bitmap,
                  contentDescription = null,
                  modifier = Modifier.fillMaxSize(),
              )
            }
          }
        }
        QrCodeState.Loading -> {
          item {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                indicatorColor = TailwarePalette.Signal,
            )
          }
        }
        QrCodeState.Error -> {
          item {
            TailwareInfo(
                label = tr(state, R.string.wear_qr_code_for_the_tailware_vpn_setup_guide),
                value = tr(state, R.string.wear_unexpected_error_try_again),
                valueColor = TailwarePalette.Warning,
                announceChanges = true,
            )
          }
          item {
            TailwareChip(
                label = tr(state, R.string.wear_try_again),
                onClick = { guideQrRetryKey += 1 },
            )
          }
        }
        QrCodeState.Empty -> Unit
      }
      if (guideQrState is QrCodeState.Ready) {
        item {
          Text(
              text =
                  tr(
                      state,
                      R.string
                          .wear_scan_the_qr_code_or_tap_it_to_open_the_guide_on_your_pa_3ca40494,
                  ),
              color = TailwarePalette.Muted,
              textAlign = TextAlign.Center,
              maxLines = 5,
          )
        }
      }
      item {
        TailwareInfo(
            label = tr(state, R.string.wear_manual_link),
            value = TailwareStrings.ltr(state.language, VPN_SETUP_GUIDE_URL),
            mono = true,
            valueColor = TailwarePalette.Signal,
        )
      }
      item {
        TailwareChip(
            label = guideActionLabel,
            secondary =
                if (guideQrState is QrCodeState.Ready)
                    tr(state, R.string.wear_the_qr_code_and_manual_link_always_remain_available)
                else null,
            onClick = openGuide,
            enabled = !guideOpening,
        )
      }
      if (guideOpening) {
        item {
          CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              indicatorColor = TailwarePalette.Signal,
          )
        }
      }
      guideError?.let { message ->
        item {
          TailwareInfo(
              label = tr(state, R.string.wear_open_guide_on_phone),
              value = message,
              valueColor = TailwarePalette.Warning,
              announceChanges = true,
          )
        }
      }
      item {
        TailwareChip(
            label = tr(state, R.string.wear_retry_authorization),
            secondary = tr(state, R.string.wear_tap_after_the_adb_command_succeeds),
            onClick = onRetry,
        )
      }
    }
  }
}

@Composable
private fun WearTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    mono: Boolean = false,
) {
  OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      label = { Material3Text(label, maxLines = 1, fontSize = 10.sp) },
      textStyle =
          androidx.compose.ui.text.TextStyle(
              color = TailwarePalette.Ice,
              fontSize = 12.sp,
              fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
              textDirection = wearTechnicalTextDirection(),
          ),
      keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
      visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
      shape = RoundedCornerShape(16.dp),
      colors =
          OutlinedTextFieldDefaults.colors(
              focusedBorderColor = TailwarePalette.Signal,
              unfocusedBorderColor = TailwarePalette.Muted,
              cursorColor = TailwarePalette.Signal,
              focusedLabelColor = TailwarePalette.Signal,
              unfocusedLabelColor = TailwarePalette.Muted,
              focusedContainerColor = TailwarePalette.Deep,
              unfocusedContainerColor = TailwarePalette.Deep,
          ),
  )
}

internal fun wearTechnicalTextDirection(): TextDirection = TextDirection.Ltr

internal fun legalTextLayoutDirection(): LayoutDirection = LayoutDirection.Ltr

internal fun connectionControlContentDescription(
    controlLabel: String,
    statusLabel: String,
    actionLabel: String,
): String = "$controlLabel. $statusLabel. $actionLabel"

internal enum class LogoutUiMode {
  Idle,
  Confirm,
  Busy,
}

internal fun logoutUiMode(confirmLogout: Boolean, busyAction: WearAction?): LogoutUiMode =
    when {
      busyAction == WearAction.Logout -> LogoutUiMode.Busy
      confirmLogout -> LogoutUiMode.Confirm
      else -> LogoutUiMode.Idle
    }

private fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.errorItem(
    state: WearUiState,
    onDismiss: () -> Unit,
    origins: Set<WearErrorOrigin> = setOf(WearErrorOrigin.Global),
    operations: Set<WearAction>? = null,
    excludedOperations: Set<WearAction> = emptySet(),
) {
  state.error
      ?.takeIf {
        val operation = it.operation
        it.origin in origins &&
            (operations == null || (operation != null && operation in operations)) &&
            (operation == null || operation !in excludedOperations)
      }
      ?.let { error ->
        item {
          TailwareErrorChip(
              message = localizedUserMessage(state, error.message),
              dismissLabel = tr(state, R.string.wear_tap_to_dismiss),
              onDismiss = onDismiss,
          )
        }
      }
}

internal fun peerIsEffectivelyOnline(state: WearUiState, peer: WearPeer): Boolean =
    state.tunnelStatus == WearTunnelStatus.Connected && (peer.isSelf || peer.online)

private fun localizedHealthTitle(state: WearUiState, warning: WearHealthWarning): String =
    when (warning.code) {
      "tailware-underlay-offline" -> tr(state, R.string.wear_no_internet_connection)
      "tailware-underlay-limited" -> tr(state, R.string.wear_internet_not_verified)
      "tailware-exit-node-offline" -> tr(state, R.string.wear_exit_node_unavailable)
      else -> tr(state, tailwareHealthResources(warning.code).title)
    }

private fun localizedHealthMessage(state: WearUiState, warning: WearHealthWarning): String =
    when (warning.code) {
      "tailware-underlay-offline" ->
          tr(state, R.string.wear_the_tailware_tunnel_is_still_present_but_this_watch_has_a41df34f)
      "tailware-underlay-limited" ->
          tr(state, R.string.wear_android_sees_a_network_but_has_not_verified_that_it_can_ed00ffc6)
      "tailware-exit-node-offline" -> {
        val nodeName =
            warning.subjectName?.takeIf { it.isNotBlank() }
                ?: run { tr(state, R.string.wear_exit_node) }
        tr(
            state,
            R.string.wear_exit_node_still_selected_offline,
            TailwareStrings.isolate(state.language, nodeName),
        )
      }
      else -> tr(state, tailwareHealthResources(warning.code).message)
    }

private fun localizedUserMessage(state: WearUiState, rawMessage: String): String {
  val normalized = rawMessage.replace(Regex("\\s+"), " ").trim()
  return when {
    normalized.startsWith("Could not open the sign-in page on the paired phone:") ->
        tr(state, R.string.wear_could_not_open_sign_in_on_the_paired_phone_keep_it_near_fc9b5d0d)
    normalized.startsWith("Tailware could not safely close the previous sign-in attempt:") ->
        tr(state, R.string.wear_previous_sign_in_cleanup_failed)
    normalized == "The previous sign-in attempt could not be cleaned up in time. Try again." ->
        tr(state, R.string.wear_previous_sign_in_cleanup_timed_out)
    normalized == "The authentication key could not be checked because the watch is offline." ->
        tr(state, R.string.wear_the_key_cannot_be_checked_while_the_watch_is_offline)
    normalized.startsWith("Authentication failed:") ->
        tr(state, R.string.wear_authentication_failed_check_that_the_tailscale_key_is_v_16d9c6c7)
    normalized ==
        "The authentication key could not be verified. Check that it is valid and try again." ->
        tr(state, R.string.wear_the_key_was_not_accepted_check_it_and_try_again)
    normalized.startsWith("The custom server refused the sign-in request:") ->
        tr(state, R.string.wear_the_custom_server_refused_sign_in_check_its_address_and_fa383226)
    normalized ==
        "The custom server is taking too long to answer. Check its address and connection." ->
        tr(state, R.string.wear_the_custom_server_is_not_responding_check_its_address_a_002009fb)
    normalized.startsWith("Tailscale could not start sign-in:") ->
        tr(state, R.string.wear_tailscale_could_not_start_sign_in_check_the_watch_connection)
    normalized == "Tailscale sign-in is taking longer than expected. Check the watch connection." ->
        tr(state, R.string.wear_tailscale_sign_in_is_taking_too_long_check_the_watch_connection)
    normalized == "The operation is taking longer than expected." ->
        tr(state, R.string.wear_this_is_taking_too_long_check_the_connection_and_try_again)
    normalized ==
        "Tailware could not restore the VPN automatically. Tap the connection control to retry." ->
        tr(state, R.string.wear_tailware_could_not_restore_the_vpn_automatically_tap_th_cdc0dec2)
    normalized == "Connect a Tailware account before starting the VPN." ->
        tr(state, R.string.wear_connect_a_tailware_account_before_starting_the_vpn)
    normalized == "VPN authorization is required before Tailware can connect." ->
        tr(state, R.string.wear_vpn_authorization_is_required_before_tailware_can_connect)
    normalized == "Android did not allow Tailware to start the VPN service." ->
        tr(state, R.string.wear_android_did_not_allow_tailware_to_start_the_vpn_service)
    normalized == "Android did not allow Tailware to stop the VPN service." ->
        tr(state, R.string.wear_android_did_not_allow_tailware_to_stop_the_vpn_service)
    normalized == "Enter an authentication key." ->
        tr(state, R.string.wear_enter_an_authentication_key)
    normalized == "Enter a valid HTTPS control server URL." ->
        tr(state, R.string.wear_enter_a_valid_https_control_server_url)
    normalized == "Account details are still loading. Wait a moment and try signing out again." ->
        tr(state, R.string.wear_account_details_are_still_loading_wait_a_moment_and_try_ec7ee6da)
    normalized ==
        "Signed out locally while offline. Tailscale could not be notified; the old device entry may remain in the admin console until it is removed or expires." ->
        tr(state, R.string.wear_signed_out_locally_while_offline_tailscale_was_not_noti_f12f2056)
    normalized == "Sign in before changing Tailscale DNS." ->
        tr(state, R.string.wear_sign_in_before_changing_tailscale_dns)
    normalized == "Tailscale settings are still loading. Wait a moment and try again." ->
        tr(state, R.string.wear_tailscale_settings_are_still_loading_wait_a_moment_and_try_again)
    normalized == "Tailscale preferences are not available yet." ->
        tr(state, R.string.wear_tailscale_preferences_are_not_available_yet)
    normalized == "Select an exit node before enabling LAN access." ->
        tr(state, R.string.wear_select_an_exit_node_before_enabling_lan_access)
    normalized == "The selected exit node is no longer available." ->
        tr(state, R.string.wear_the_selected_exit_node_is_no_longer_available)
    normalized == "The selected exit node is offline." ->
        tr(state, R.string.wear_the_selected_exit_node_is_offline)
    normalized ==
        "Connect Tailware first. Device ping is available only while the VPN tunnel is fully connected." ->
        tr(state, R.string.wear_connect_tailware_first_device_ping_is_available_only_wh_0f80551b)
    normalized == "The selected device is no longer available." ->
        tr(state, R.string.wear_the_selected_device_is_no_longer_available)
    normalized == "This is this watch; a self-ping does not test a network path." ->
        tr(state, R.string.wear_this_is_this_watch_a_self_ping_does_not_test_a_network_path)
    normalized == "This device has no IPv4 address to ping." ->
        tr(state, R.string.wear_this_device_has_no_ipv4_address_to_ping)
    normalized ==
        "Sign-in succeeded, but the one-time VPN authorization is still required before Tailware can connect." ->
        tr(state, R.string.wear_sign_in_succeeded_but_the_one_time_vpn_authorization_is_3df65858)
    normalized ==
        "Sign-in succeeded, but Android did not allow Tailware to start the VPN service." ->
        tr(state, R.string.wear_sign_in_succeeded_but_android_did_not_allow_tailware_to_a02d8d74)
    normalized ==
        "Internet was lost before sign-out could be verified. The local account was kept." ->
        tr(state, R.string.wear_internet_was_lost_before_sign_out_could_be_verified_the_ddab4b6c)
    normalized == "Remote sign-out could not be verified. The local account was kept." ->
        tr(state, R.string.wear_remote_sign_out_could_not_be_verified_the_local_account_was_kept)
    normalized == "Tailware could not verify that the local account was removed." ->
        tr(state, R.string.wear_tailware_could_not_verify_that_the_local_account_was_removed)
    else -> tr(state, R.string.wear_unexpected_error_try_again)
  }
}

private fun localizedPingFailure(state: WearUiState, result: WearPingResult): String? {
  val peerName = result.peerName ?: return null
  val address = result.address ?: return null
  val safePeerName = TailwareStrings.isolate(state.language, peerName)
  val safeAddress = TailwareStrings.ltr(state.language, address)
  return when (result.failureCategory) {
    PingFailureCategory.TimedOut ->
        tr(state, R.string.wear_ping_timed_out, safePeerName, safeAddress)
    PingFailureCategory.NoDiagnostic ->
        tr(state, R.string.wear_ping_failed_without_diagnostic, safePeerName, safeAddress)
    PingFailureCategory.BackendFailure,
    null ->
        buildString {
          append(tr(state, R.string.wear_ping_failed))
          append(": ")
          append(safePeerName)
          append(" (")
          append(safeAddress)
          append(").")
        }
  }
}

private fun languageName(language: TailwareLanguage): String = language.nativeName

private fun formatKeyExpiry(state: WearUiState, rawExpiry: String?): String {
  val expiry = parseKeyExpiry(rawExpiry)
  if (expiry == null) {
    return tr(state, R.string.wear_does_not_expire)
  }
  val locale = Locale.forLanguageTag(state.language.languageTag)
  val date =
      DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
          .withLocale(locale)
          .format(expiry.atZone(ZoneId.systemDefault()))
  return if (expiry.isBefore(Instant.now())) {
    tr(state, R.string.wear_expired_on, date)
  } else {
    tr(state, R.string.wear_expires_on, date)
  }
}

private fun keyIsExpired(rawExpiry: String?): Boolean =
    parseKeyExpiry(rawExpiry)?.isBefore(Instant.now()) == true

private fun parseKeyExpiry(rawExpiry: String?): Instant? {
  if (rawExpiry.isNullOrBlank() || rawExpiry == "0001-01-01T00:00:00Z") return null
  return runCatching { Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(rawExpiry)) }
      .getOrNull()
}

private fun tr(
    state: WearUiState,
    @StringRes resourceId: Int,
    vararg formatArguments: Any,
): String = TailwareStrings.text(state.language, resourceId, *formatArguments)

private fun trQuantity(
    state: WearUiState,
    @PluralsRes resourceId: Int,
    quantity: Int,
    vararg formatArguments: Any,
): String = TailwareStrings.quantity(state.language, resourceId, quantity, *formatArguments)

private val ONBOARDING_ROUTES =
    setOf(
        Route.Gate,
        Route.Welcome,
        Route.LoginMethods,
        Route.LoginQr,
        Route.AuthKey,
        Route.ControlUrl,
        Route.VpnSetup,
    )

private val MACHINE_AUTH_ALLOWED_ROUTES =
    setOf(Route.LoginQr, Route.Settings, Route.Language, Route.Tile, Route.VpnSetup, Route.About)

internal fun shouldForceVpnSetup(authorization: VpnAuthorization, currentRoute: String): Boolean =
    authorization == VpnAuthorization.AdbRequired &&
        currentRoute in ONBOARDING_ROUTES &&
        currentRoute != Route.VpnSetup

internal fun shouldRedirectToLoginQr(
    accountState: WearAccountState,
    authorization: VpnAuthorization,
    hasLoginUrl: Boolean,
    currentRoute: String,
): Boolean =
    accountState != WearAccountState.SignedIn &&
        authorization == VpnAuthorization.Granted &&
        (hasLoginUrl || accountState == WearAccountState.NeedsMachineAuthorization) &&
        currentRoute != Route.LoginQr &&
        !(accountState == WearAccountState.NeedsMachineAuthorization &&
            currentRoute in MACHINE_AUTH_ALLOWED_ROUTES)

private const val VPN_SETUP_GUIDE_URL =
    "https://github.com/vladbezm/tailware/blob/main/docs/wear-vpn-setup.md"

internal enum class VpnSetupContinuation {
  LoginMethods,
  LoginQr,
  Dashboard,
}

internal fun vpnSetupContinuation(
    accountState: WearAccountState,
    authorization: VpnAuthorization,
): VpnSetupContinuation? {
  if (authorization != VpnAuthorization.Granted) return null
  return when (accountState) {
    WearAccountState.SignedOut -> VpnSetupContinuation.LoginMethods
    WearAccountState.NeedsMachineAuthorization -> VpnSetupContinuation.LoginQr
    WearAccountState.SignedIn -> VpnSetupContinuation.Dashboard
    WearAccountState.Loading,
    WearAccountState.Authenticating -> null
  }
}
