// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import com.tailscale.ipn.wear.LoginMethod
import com.tailscale.ipn.wear.LoginPresentation
import com.tailscale.ipn.wear.PhoneLoginStatus
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
import com.tailscale.ipn.wear.WearTunnelStatus
import com.tailscale.ipn.wear.WearUiState
import com.tailscale.ipn.wear.tile.TailwareTileStateStore
import com.tailscale.ipn.wear.tile.TailwareTileUpdates
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

@Composable
fun TailwareApp(
    viewModel: TailwareViewModel,
    onSetOptionalNotifications: (Boolean) -> Unit,
    onOpenLoginOnPhone: (String) -> Unit,
    onSensitiveLoginContentVisible: (Boolean) -> Unit,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val navController = rememberSwipeDismissableNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  var selectedPeerId by rememberSaveable { mutableStateOf<String?>(null) }
  var accountRootRoute by rememberSaveable { mutableStateOf(Route.Gate) }

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

  val onboardingRoutes = remember {
    setOf(
        Route.Gate,
        Route.Welcome,
        Route.LoginMethods,
        Route.LoginQr,
        Route.AuthKey,
        Route.ControlUrl,
        Route.VpnSetup,
    )
  }

  val currentRoute = backStackEntry?.destination?.route
  LaunchedEffect(currentRoute) {
    onSensitiveLoginContentVisible(currentRoute == Route.LoginQr || currentRoute == Route.AuthKey)
  }
  DisposableEffect(Unit) { onDispose { onSensitiveLoginContentVisible(false) } }
  LaunchedEffect(
      state.accountState,
      state.loginUrl,
      state.error?.origin,
      currentRoute,
  ) {
    val route = currentRoute ?: return@LaunchedEffect
    val destination =
        when {
          state.accountState == WearAccountState.SignedIn && route in onboardingRoutes ->
              Route.Dashboard
          state.loginUrl == null &&
              state.error?.origin == WearErrorOrigin.AuthenticationKey &&
              route == Route.LoginQr -> Route.AuthKey
          state.loginUrl == null &&
              state.error?.origin == WearErrorOrigin.CustomServer &&
              route == Route.LoginQr -> Route.ControlUrl
          state.accountState != WearAccountState.SignedIn &&
              state.loginUrl != null &&
              route != Route.LoginQr -> Route.LoginQr
          state.accountState == WearAccountState.SignedOut &&
              (route == Route.Gate || route !in onboardingRoutes) -> Route.Welcome
          state.accountState == WearAccountState.NeedsMachineAuthorization &&
              route != Route.LoginQr -> Route.LoginQr
          else -> null
        }
    if (destination != null) {
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
    composable(Route.Gate) { GateScreen() }
    composable(Route.Welcome) {
      WelcomeScreen(
          state = state,
          onDismissLogoutNotice = viewModel::clearError,
          onConnectAccount = {
            navController.navigate(
                if (state.vpnAuthorization == VpnAuthorization.AdbRequired) Route.VpnSetup
                else Route.LoginMethods)
          },
      )
    }
    composable(Route.LoginMethods) {
      LoginMethodsScreen(
          state = state,
          onTailscaleLogin = {
            if (viewModel.startInteractiveLogin(LoginPresentation.Phone)) {
              navController.navigate(Route.LoginQr)
            }
          },
          onAuthKey = { navController.navigate(Route.AuthKey) },
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
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.AuthKey) {
      LaunchedEffect(Unit) { viewModel.prepareLoginMethod(LoginMethod.AuthenticationKey) }
      DisposableEffect(Unit) {
        onDispose { viewModel.cancelLoginIfActive(LoginMethod.AuthenticationKey) }
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
          onConnectionClick = {
            when (state.vpnAuthorization) {
              VpnAuthorization.Granted -> viewModel.toggleConnection()
              VpnAuthorization.AdbRequired -> navController.navigate(Route.VpnSetup)
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
      LaunchedEffect(state.vpnAuthorization) {
        while (state.vpnAuthorization == VpnAuthorization.AdbRequired) {
          withContext(Dispatchers.IO) {
            viewModel.refreshVpnAuthorization(com.tailscale.ipn.App.get())
          }
          delay(2_000L)
        }
      }
      LaunchedEffect(state.accountState, state.vpnAuthorization) {
        if (state.accountState == WearAccountState.SignedOut &&
            state.vpnAuthorization == VpnAuthorization.Granted) {
          navController.navigate(Route.LoginMethods) {
            popUpTo(Route.VpnSetup) { inclusive = true }
            launchSingleTop = true
          }
        }
      }
      VpnSetupScreen(
          state = state,
          onRetry = { viewModel.refreshVpnAuthorization(com.tailscale.ipn.App.get()) },
          onContinueWithoutVpn =
              if (state.accountState == WearAccountState.SignedOut) {
                { navController.navigate(Route.LoginMethods) }
              } else {
                null
              },
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.About) { AboutScreen(state) }
  }
}

@Composable
private fun GateScreen() {
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
          contentDescription = "Tailware",
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
  TailwareListScreen(title = "TAILWARE") {
    item {
      Image(
          painter = painterResource(R.drawable.tailware_mark),
          contentDescription = "Tailware",
          modifier =
              Modifier.size(if (LocalConfiguration.current.screenWidthDp < 210) 64.dp else 78.dp),
      )
    }
    item {
      Text(
          text =
              tr(
                  state,
                  "Your private network, on your wrist.",
                  "Je privénetwerk, om je pols.",
                  "Votre réseau privé, au poignet.",
                  "Dein privates Netzwerk am Handgelenk.",
              ),
          color = TailwarePalette.Muted,
          textAlign = TextAlign.Center,
          maxLines = 3,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
      )
    }
    item {
      TailwareChip(
          label =
              tr(
                  state,
                  "Connect account",
                  "Account verbinden",
                  "Connecter un compte",
                  "Konto verbinden",
              ),
          secondary =
              tr(
                  state,
                  "Tailscale or Headscale",
                  "Tailscale of Headscale",
                  "Tailscale ou Headscale",
                  "Tailscale oder Headscale",
              ),
          onClick = onConnectAccount,
          accent = true,
      )
    }
    errorItem(
        state,
        onDismissLogoutNotice,
        operations = setOf(WearAction.Logout),
    )
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
  TailwareListScreen(
      title =
          tr(
              state,
              "Connect account",
              "Account verbinden",
              "Connecter un compte",
              "Konto verbinden",
          )) {
        errorItem(state, onDismissError)
        item {
          TailwareChip(
              label =
                  tr(
                      state,
                      "Sign in with Tailscale",
                      "Aanmelden met Tailscale",
                      "Se connecter avec Tailscale",
                      "Mit Tailscale anmelden",
                  ),
              secondary =
                  tr(
                      state,
                      "Open on your phone, with QR as fallback",
                      "Open op je telefoon, met QR als reserve",
                      "Ouvrir sur le téléphone, QR en secours",
                      "Auf dem Smartphone öffnen, QR als Alternative",
                  ),
              onClick = onTailscaleLogin,
              accent = true,
              enabled = state.busyAction != com.tailscale.ipn.wear.WearAction.Login,
          )
        }
        item {
          TailwareChip(
              label =
                  tr(
                      state,
                      "Tailscale authentication key",
                      "Tailscale-authenticatiesleutel",
                      "Clé d’authentification Tailscale",
                      "Tailscale-Authentifizierungsschlüssel",
                  ),
              secondary =
                  tr(
                      state,
                      "For advanced or unattended setup",
                      "Voor geavanceerde of onbeheerde installatie",
                      "Pour une installation avancée ou autonome",
                      "Für erweiterte oder unbeaufsichtigte Einrichtung",
                  ),
              onClick = onAuthKey,
              enabled = state.busyAction != com.tailscale.ipn.wear.WearAction.Login,
          )
        }
        item {
          TailwareChip(
              label =
                  tr(
                      state,
                      "Headscale / custom server",
                      "Headscale / aangepaste server",
                      "Headscale / serveur personnalisé",
                      "Headscale / benutzerdefinierter Server",
                  ),
              secondary =
                  tr(
                      state,
                      "Enter a control server URL",
                      "Voer een controlserver-URL in",
                      "Saisir l’URL du serveur",
                      "Control-Server-URL eingeben",
                  ),
              onClick = onControlUrl,
              enabled = state.busyAction != com.tailscale.ipn.wear.WearAction.Login,
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
    onDismissError: () -> Unit,
) {
  val qr = rememberQrCode(state.loginUrl, size = 420)
  val small = LocalConfiguration.current.screenWidthDp < 210
  TailwareListScreen(title = tr(state, "Sign in", "Aanmelden", "Connexion", "Anmelden")) {
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
              label =
                  tr(
                      state,
                      "Approval required",
                      "Goedkeuring nodig",
                      "Approbation requise",
                      "Freigabe erforderlich",
                  ),
              value =
                  tr(
                      state,
                      "Approve this watch in the admin console.",
                      "Keur dit horloge goed in de beheerconsole.",
                      "Approuvez cette montre dans la console.",
                      "Diese Uhr in der Admin-Konsole freigeben.",
                  ),
              valueColor = TailwarePalette.Warning,
          )
        }
      }
      qr != null -> {
        item {
          TailwareChip(
              label =
                  when (state.phoneLoginStatus) {
                    PhoneLoginStatus.Opening ->
                        tr(
                            state,
                            "Opening on phone…",
                            "Openen op telefoon…",
                            "Ouverture sur le téléphone…",
                            "Wird auf dem Smartphone geöffnet…",
                        )
                    PhoneLoginStatus.Sent ->
                        tr(
                            state,
                            "Sent to phone",
                            "Naar telefoon gestuurd",
                            "Envoyé au téléphone",
                            "An Smartphone gesendet",
                        )
                    PhoneLoginStatus.Idle,
                    PhoneLoginStatus.Failed ->
                        tr(
                            state,
                            "Continue on phone",
                            "Doorgaan op telefoon",
                            "Continuer sur le téléphone",
                            "Auf dem Smartphone fortfahren",
                        )
                  },
              secondary =
                  when (state.phoneLoginStatus) {
                    PhoneLoginStatus.Opening ->
                        tr(
                            state,
                            "Contacting the paired phone",
                            "Gekoppelde telefoon contacteren",
                            "Connexion au téléphone associé",
                            "Gekoppeltes Smartphone wird kontaktiert",
                        )
                    PhoneLoginStatus.Sent ->
                        tr(
                            state,
                            "Finish sign-in on the paired phone",
                            "Rond aanmelden af op de gekoppelde telefoon",
                            "Terminez la connexion sur le téléphone associé",
                            "Anmeldung auf dem Smartphone abschließen",
                        )
                    PhoneLoginStatus.Failed ->
                        tr(
                            state,
                            "Try again or use the QR code below",
                            "Probeer opnieuw of gebruik de QR-code hieronder",
                            "Réessayez ou utilisez le code QR ci-dessous",
                            "Erneut versuchen oder QR-Code unten verwenden",
                        )
                    PhoneLoginStatus.Idle ->
                        tr(
                            state,
                            "Fastest secure sign-in method",
                            "Snelste veilige aanmeldmethode",
                            "Méthode de connexion sécurisée la plus rapide",
                            "Schnellste sichere Anmeldemethode",
                        )
                  },
              onClick = {
                onResetPhoneError()
                state.loginUrl?.let(onOpenOnPhone)
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
                label =
                    tr(
                        state,
                        "Phone unavailable",
                        "Telefoon niet beschikbaar",
                        "Téléphone indisponible",
                        "Smartphone nicht verfügbar",
                    ),
                value = localizedUserMessage(state, message),
                valueColor = TailwarePalette.Warning,
            )
          }
        }
        item {
          Box(
              modifier =
                  Modifier.size(if (small) 132.dp else 156.dp)
                      .background(Color.White, RoundedCornerShape(12.dp))
                      .padding(8.dp),
              contentAlignment = Alignment.Center,
          ) {
            Image(
                bitmap = qr,
                contentDescription =
                    tr(
                        state,
                        "Tailscale login QR code",
                        "Tailscale-aanmeldcode",
                        "Code QR de connexion Tailscale",
                        "Tailscale-Anmelde-QR-Code",
                    ),
                modifier = Modifier.fillMaxSize(),
            )
          }
        }
        state.loginCode?.let { code ->
          item {
            TailwareInfo(
                label =
                    tr(
                        state,
                        "Login code",
                        "Aanmeldcode",
                        "Code de connexion",
                        "Anmeldecode",
                    ),
                value = code,
                mono = true,
                valueColor = TailwarePalette.Signal,
            )
          }
        }
        item {
          Text(
              text =
                  tr(
                      state,
                      "Scan with your phone, then approve Tailware.",
                      "Scan met je telefoon en keur Tailware goed.",
                      "Scannez avec votre téléphone, puis approuvez Tailware.",
                      "Mit dem Handy scannen und Tailware freigeben.",
                  ),
              color = TailwarePalette.Muted,
              textAlign = TextAlign.Center,
              maxLines = 3,
          )
        }
        if (state.activeLoginMethod == LoginMethod.Tailscale) {
          item {
            TailwareChip(
                label =
                    tr(
                        state,
                        "Generate new code",
                        "Nieuwe code maken",
                        "Générer un nouveau code",
                        "Neuen Code erzeugen",
                    ),
                secondary =
                    tr(
                        state,
                        "The previous link will no longer be shown",
                        "De vorige link wordt niet meer getoond",
                        "L’ancien lien ne sera plus affiché",
                        "Der vorherige Link wird nicht mehr angezeigt",
                    ),
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
              text =
                  tr(
                      state,
                      "Creating a secure login…",
                      "Beveiligde aanmelding maken…",
                      "Création de la connexion sécurisée…",
                      "Sichere Anmeldung wird erstellt…",
                  ),
              color = TailwarePalette.Muted,
              textAlign = TextAlign.Center,
          )
        }
        item {
          TailwareChip(
              label = tr(state, "Try again", "Opnieuw proberen", "Réessayer", "Erneut versuchen"),
              onClick = onRetry,
              enabled = state.busyAction != com.tailscale.ipn.wear.WearAction.Login,
          )
        }
      }
    }
    if (state.accountState != WearAccountState.NeedsMachineAuthorization) {
      item {
        TailwareChip(
            label =
                tr(
                    state,
                    "Cancel sign-in",
                    "Aanmelden annuleren",
                    "Annuler la connexion",
                    "Anmeldung abbrechen",
                ),
            onClick = onCancel,
        )
      }
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
  TailwareListScreen(
      title =
          tr(
              state,
              "Authentication key",
              "Authenticatiesleutel",
              "Clé d’authentification",
              "Authentifizierungsschlüssel",
          )) {
        errorItem(
            state,
            onDismissError,
            origins = setOf(WearErrorOrigin.AuthenticationKey, WearErrorOrigin.Global),
        )
        item {
          WearTextField(
              value = key,
              onValueChange = { key = it },
              label = "tskey-auth-…",
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
              label = tr(state, "Sign in", "Aanmelden", "Connexion", "Anmelden"),
              secondary =
                  tr(
                      state,
                      "The key remains visible while it is checked",
                      "De sleutel blijft zichtbaar tijdens de controle",
                      "La clé reste visible pendant la vérification",
                      "Der Schlüssel bleibt während der Prüfung sichtbar",
                  ),
              onClick = { onLogin(key) },
              enabled =
                  key.isNotBlank() && state.busyAction != com.tailscale.ipn.wear.WearAction.Login,
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
  TailwareListScreen(title = "Headscale") {
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
          label = "https://headscale.example",
          keyboardType = KeyboardType.Uri,
      )
    }
    item {
      TailwareChip(
          label =
              tr(
                  state,
                  "Connect server",
                  "Server verbinden",
                  "Connecter le serveur",
                  "Server verbinden",
              ),
          onClick = { onLogin(url) },
          enabled =
              url.length > "https://".length &&
                  state.busyAction != com.tailscale.ipn.wear.WearAction.Login,
          accent = true,
      )
    }
  }
}

@Composable
private fun DashboardScreen(
    state: WearUiState,
    onConnectionClick: () -> Unit,
    onDevices: () -> Unit,
    onExitNodes: () -> Unit,
    onSettings: () -> Unit,
    onHealth: () -> Unit,
    onVpnSetup: () -> Unit,
    onDismissError: () -> Unit,
) {
  val statusText =
      when (state.tunnelStatus) {
        WearTunnelStatus.Connected -> tr(state, "Connected", "Verbonden", "Connecté", "Verbunden")
        WearTunnelStatus.Connecting ->
            tr(state, "Connecting…", "Verbinden…", "Connexion…", "Verbindung…")
        WearTunnelStatus.Disconnecting ->
            tr(state, "Disconnecting…", "Verbreken…", "Déconnexion…", "Trennen…")
        WearTunnelStatus.Disconnected ->
            tr(state, "Disconnected", "Niet verbonden", "Déconnecté", "Getrennt")
        WearTunnelStatus.Unknown -> tr(state, "Starting…", "Starten…", "Démarrage…", "Startet…")
      }
  val statusColor =
      when (state.tunnelStatus) {
        WearTunnelStatus.Connected -> TailwarePalette.Signal
        WearTunnelStatus.Connecting,
        WearTunnelStatus.Disconnecting -> TailwarePalette.Warning
        WearTunnelStatus.Disconnected -> TailwarePalette.Muted
        WearTunnelStatus.Unknown -> TailwarePalette.Blue
      }

  TailwareListScreen(title = "TAILWARE") {
    item {
      ConnectionOrb(
          status = state.tunnelStatus,
          onClick = onConnectionClick,
          enabled =
              state.accountState == WearAccountState.SignedIn &&
                  state.busyAction != com.tailscale.ipn.wear.WearAction.Login &&
                  state.busyAction != com.tailscale.ipn.wear.WearAction.Logout,
          contentDescription =
              tr(
                  state,
                  "Tailware connection",
                  "Tailware-verbinding",
                  "Connexion Tailware",
                  "Tailware-Verbindung",
              ),
          stateDescription = statusText,
          actionLabel =
              if (state.desiredVpnEnabled)
                  tr(state, "Disconnect", "Verbreken", "Déconnecter", "Trennen")
              else tr(state, "Connect", "Verbinden", "Connecter", "Verbinden"),
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
            secondary =
                tr(
                    state,
                    "Tap for details",
                    "Tik voor details",
                    "Touchez pour les détails",
                    "Für Details tippen",
                ),
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
            label =
                tr(
                    state,
                    "VPN setup needed",
                    "VPN-instelling nodig",
                    "Configuration VPN requise",
                    "VPN-Einrichtung nötig",
                ),
            secondary =
                tr(
                    state,
                    "One-time ADB authorization",
                    "Eenmalige ADB-toestemming",
                    "Autorisation ADB unique",
                    "Einmalige ADB-Freigabe",
                ),
            onClick = onVpnSetup,
        )
      }
    }
    errorItem(state, onDismissError)
    state.selfNode?.let { self ->
      item {
        TailwareInfo(
            label =
                tr(
                    state,
                    "This watch",
                    "Dit horloge",
                    "Cette montre",
                    "Diese Uhr",
                ),
            value = self.displayName,
        )
      }
      (self.ipv4 ?: self.ipv6)?.let { address ->
        item { TailwareInfo(label = "TAILSCALE IP", value = address, mono = true) }
      }
      if (self.dnsName.isNotBlank()) {
        item { TailwareInfo(label = "MAGICDNS", value = self.dnsName, mono = true) }
      }
    }
    item {
      TailwareChip(
          label =
              tr(
                  state,
                  "My devices",
                  "Mijn apparaten",
                  "Mes appareils",
                  "Meine Geräte",
              ),
          secondary =
              tr(
                  state,
                  "${state.peers.size} devices in your tailnet",
                  "${state.peers.size} apparaten in je tailnet",
                  "${state.peers.size} appareils dans votre tailnet",
                  "${state.peers.size} Geräte in deinem Tailnet",
              ),
          onClick = onDevices,
      )
    }
    item {
      TailwareChip(
          label =
              tr(
                  state,
                  "Exit node",
                  "Exit node",
                  "Nœud de sortie",
                  "Exit-Node",
              ),
          secondary = state.activeExitNodeName ?: tr(state, "None", "Geen", "Aucun", "Keiner"),
          onClick = onExitNodes,
      )
    }
    item {
      TailwareChip(
          label =
              tr(
                  state,
                  "Settings",
                  "Instellingen",
                  "Paramètres",
                  "Einstellungen",
              ),
          secondary =
              tr(
                  state,
                  "Language, DNS, account and Tile",
                  "Taal, DNS, account en tegel",
                  "Langue, DNS, compte et Tile",
                  "Sprache, DNS, Konto und Tile",
              ),
          onClick = onSettings,
          iconRes = R.drawable.ic_tailware_settings,
      )
    }
  }
}

@Composable
private fun HealthScreen(state: WearUiState) {
  TailwareListScreen(
      title =
          tr(
              state,
              "Connection health",
              "Verbindingsstatus",
              "État de la connexion",
              "Verbindungsstatus",
          )) {
        if (state.healthWarnings.isEmpty()) {
          item {
            TailwareInfo(
                label = tr(state, "Status", "Status", "État", "Status"),
                value =
                    tr(
                        state,
                        "No problems detected",
                        "Geen problemen gevonden",
                        "Aucun problème détecté",
                        "Keine Probleme erkannt",
                    ),
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
  TailwareListScreen(
      title =
          tr(
              state,
              "My devices",
              "Mijn apparaten",
              "Mes appareils",
              "Meine Geräte",
          )) {
        errorItem(state, onDismissError)
        if (state.peers.isEmpty()) {
          item {
            Text(
                text =
                    tr(
                        state,
                        "No devices available yet.",
                        "Nog geen apparaten beschikbaar.",
                        "Aucun appareil disponible.",
                        "Noch keine Geräte verfügbar.",
                    ),
                color = TailwarePalette.Muted,
                textAlign = TextAlign.Center,
            )
          }
        }
        items(state.peers, key = { it.stableId }) { peer ->
          val effectivelyOnline = peerIsEffectivelyOnline(state, peer)
          val status =
              if (effectivelyOnline) tr(state, "Online", "Online", "En ligne", "Online")
              else tr(state, "Offline", "Offline", "Hors ligne", "Offline")
          TailwareChip(
              label =
                  if (peer.isSelf)
                      "${peer.displayName} · ${tr(state, "this watch", "dit horloge", "cette montre", "diese Uhr")}"
                  else peer.displayName,
              secondary = "$status · ${peer.ipv4 ?: peer.ipv6 ?: "—"}",
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
      title = peer?.displayName ?: tr(state, "Device", "Apparaat", "Appareil", "Gerät")) {
        errorItem(state, onDismissError)
        if (peer == null) {
          item {
            TailwareInfo(
                label =
                    tr(state, "Unavailable", "Niet beschikbaar", "Indisponible", "Nicht verfügbar"),
                value =
                    tr(
                        state,
                        "This device left the tailnet.",
                        "Dit apparaat is niet meer in het tailnet.",
                        "Cet appareil a quitté le tailnet.",
                        "Dieses Gerät ist nicht mehr im Tailnet.",
                    ),
                valueColor = TailwarePalette.Warning,
            )
          }
          return@TailwareListScreen
        }
        item {
          TailwareInfo(
              label = tr(state, "Status", "Status", "État", "Status"),
              value =
                  if (effectivelyOnline) tr(state, "Online", "Online", "En ligne", "Online")
                  else tr(state, "Offline", "Offline", "Hors ligne", "Offline"),
              valueColor = if (effectivelyOnline) TailwarePalette.Signal else TailwarePalette.Muted,
          )
        }
        peer.ipv4?.let { item { TailwareInfo(label = "IPV4", value = it, mono = true) } }
        peer.ipv6?.let { item { TailwareInfo(label = "IPV6", value = it, mono = true) } }
        if (peer.dnsName.isNotBlank()) {
          item { TailwareInfo(label = "MAGICDNS", value = peer.dnsName, mono = true) }
        }
        peer.os?.takeIf { it.isNotBlank() }?.let { item { TailwareInfo(label = "OS", value = it) } }
        if (!peer.isSelf) {
          item {
            TailwareChip(
                label =
                    tr(
                        state,
                        "Ping device",
                        "Apparaat pingen",
                        "Tester l’appareil",
                        "Gerät pingen"),
                secondary =
                    tr(
                        state,
                        "Measure tailnet latency",
                        "Meet de tailnet-latentie",
                        "Mesurer la latence du tailnet",
                        "Tailnet-Latenz messen",
                    ),
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
                      label = "PING",
                      value =
                          String.format(
                              Locale.getDefault(),
                              "%.1f ms",
                              pingResult?.latencyMillis ?: 0.0,
                          ),
                      mono = true,
                      valueColor = TailwarePalette.Signal,
                  )
                }
            PingStatus.Failed ->
                item {
                  TailwareChip(
                      label =
                          tr(
                              state,
                              "Ping failed",
                              "Ping mislukt",
                              "Échec du ping",
                              "Ping fehlgeschlagen"),
                      secondary = pingResult?.error?.let { localizedUserMessage(state, it) },
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
  TailwareListScreen(
      title =
          tr(
              state,
              "Exit node",
              "Exit node",
              "Nœud de sortie",
              "Exit-Node",
          )) {
        errorItem(state, onDismissError)
        item {
          TailwareChip(
              label =
                  if (state.activeExitNodeId == null)
                      tr(state, "No exit node", "Geen exit node", "Aucun nœud", "Keine Exit-Node")
                  else
                      tr(
                          state,
                          "Disable active exit node",
                          "Actieve exit node uitschakelen",
                          "Désactiver le nœud actif",
                          "Aktive Exit-Node deaktivieren",
                      ),
              secondary =
                  if (state.activeExitNodeId == null) "✓"
                  else
                      state.activeExitNodeName
                          ?: tr(
                              state,
                              "Use the local internet connection",
                              "Gebruik de lokale internetverbinding",
                              "Utiliser la connexion locale",
                              "Lokale Internetverbindung verwenden"),
              onClick = onClear,
              accent = state.activeExitNodeId == null,
          )
        }
        items(state.exitNodes, key = { it.stableId }) { node ->
          TailwareChip(
              label = node.displayName,
              secondary =
                  buildString {
                    append(
                        if (node.online) tr(state, "Online", "Online", "En ligne", "Online")
                        else tr(state, "Offline", "Offline", "Hors ligne", "Offline"))
                    if (node.active) append(" · ${tr(state, "Active", "Actief", "Actif", "Aktiv")}")
                    else if (node.selected)
                        append(
                            " · ${tr(state, "Last used", "Laatst gebruikt", "Dernier utilisé", "Zuletzt verwendet")}")
                  },
              onClick = { onSelect(node.stableId) },
              enabled = node.online,
              accent = node.active,
          )
        }
        if (state.activeExitNodeId != null) {
          item {
            TailwareToggle(
                label =
                    tr(
                        state,
                        "Allow LAN access",
                        "LAN-toegang toestaan",
                        "Autoriser l’accès LAN",
                        "LAN-Zugriff erlauben",
                    ),
                secondary =
                    tr(
                        state,
                        "Keep access to the watch’s local network",
                        "Behoud toegang tot het lokale netwerk",
                        "Conserver l’accès au réseau local",
                        "Lokalen Netzwerkzugriff behalten",
                    ),
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
  TailwareListScreen(
      title =
          tr(
              state,
              "Settings",
              "Instellingen",
              "Paramètres",
              "Einstellungen",
          )) {
        errorItem(
            state,
            onDismissError,
            excludedOperations = setOf(WearAction.Logout),
        )
        item {
          TailwareChip(
              label = tr(state, "Language", "Taal", "Langue", "Sprache"),
              secondary = languageName(state.language),
              onClick = onLanguage,
              iconRes = R.drawable.globe,
          )
        }
        item {
          TailwareToggle(
              label = "Tailscale DNS",
              secondary = "MagicDNS",
              checked =
                  state.accountState == WearAccountState.SignedIn && state.tailscaleDnsEnabled,
              onCheckedChange = { onToggleDns() },
              enabled =
                  state.accountState == WearAccountState.SignedIn &&
                      state.tailscaleSettingsReady &&
                      state.busyAction == null,
          )
        }
        item {
          TailwareToggle(
              label =
                  tr(
                      state,
                      "Disconnected notification",
                      "Melding bij verbroken verbinding",
                      "Notification de déconnexion",
                      "Hinweis bei Trennung",
                  ),
              secondary =
                  if (state.optionalNotificationsBlockedBySystem)
                      tr(
                          state,
                          "Blocked in Android settings",
                          "Geblokkeerd in Android-instellingen",
                          "Bloqué dans les paramètres Android",
                          "In Android-Einstellungen blockiert",
                      )
                  else
                      tr(
                          state,
                          "Optional; Android still shows the active VPN notification",
                          "Optioneel; Android toont de actieve VPN-melding altijd",
                          "Facultatif ; Android garde la notification VPN active",
                          "Optional; Android zeigt die aktive VPN-Meldung weiterhin",
                      ),
              checked = state.optionalNotificationsEnabled,
              onCheckedChange = onSetNotifications,
          )
        }
        item {
          TailwareChip(
              label =
                  tr(state, "Tailware Tile", "Tailware-tegel", "Tile Tailware", "Tailware-Tile"),
              secondary =
                  tr(
                      state,
                      "Status and quick action",
                      "Status en snelle actie",
                      "État et action rapide",
                      "Status und Schnellaktion",
                  ),
              onClick = onTile,
          )
        }
        state.profileDisplayName?.let { account ->
          item {
            TailwareInfo(
                label = tr(state, "Account", "Account", "Compte", "Konto"),
                value = account,
            )
          }
        }
        state.selfNode?.let { self ->
          item {
            TailwareInfo(
                label =
                    tr(
                        state,
                        "Device key",
                        "Toestelsleutel",
                        "Clé de l’appareil",
                        "Geräteschlüssel",
                    ),
                value = formatKeyExpiry(state, self.keyExpiry),
                valueColor =
                    if (keyIsExpired(self.keyExpiry)) TailwarePalette.Danger
                    else TailwarePalette.Ice,
            )
          }
        }
        errorItem(
            state,
            onDismissError,
            operations = setOf(WearAction.Logout),
        )
        item {
          TailwareChip(
              label =
                  if (confirmLogout)
                      tr(
                          state,
                          "Confirm sign out",
                          "Afmelden bevestigen",
                          "Confirmer la déconnexion",
                          "Abmelden bestätigen",
                      )
                  else tr(state, "Sign out", "Afmelden", "Se déconnecter", "Abmelden"),
              secondary =
                  if (confirmLogout)
                      if (state.underlyingNetworkStatus !=
                          com.tailscale.ipn.UnderlyingNetworkStatus.Online)
                          tr(
                              state,
                              "Signs out locally; the old admin entry may remain",
                              "Meldt lokaal af; de oude beheerinvoer kan blijven staan",
                              "Déconnexion locale ; l’ancienne entrée admin peut rester",
                              "Meldet lokal ab; der alte Admin-Eintrag kann bestehen bleiben",
                          )
                      else
                          tr(
                              state,
                              "Tap once more to sign out on this watch",
                              "Tik nogmaals om je op dit horloge af te melden",
                              "Touchez encore pour vous déconnecter de cette montre",
                              "Noch einmal tippen, um sich auf dieser Uhr abzumelden",
                          )
                  else state.profileDisplayName,
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
                  state.accountState == WearAccountState.SignedIn &&
                      state.busyAction == null &&
                      (!confirmLogout || logoutReady),
              accent = confirmLogout,
          )
        }
        item {
          TailwareChip(
              label = tr(state, "About Tailware", "Over Tailware", "À propos", "Über Tailware"),
              secondary =
                  tr(
                      state,
                      "Version, source and licenses",
                      "Versie, broncode en licenties",
                      "Version, source et licences",
                      "Version, Quellcode und Lizenzen",
                  ),
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
  TailwareListScreen(
      title = tr(state, "About Tailware", "Over Tailware", "À propos", "Über Tailware")) {
        item {
          Image(
              painter = painterResource(R.drawable.tailware_mark),
              contentDescription = "Tailware",
              modifier = Modifier.size(64.dp),
          )
        }
        item {
          TailwareInfo(
              label = tr(state, "Version", "Versie", "Version", "Version"),
              value = BuildConfig.VERSION_NAME,
              mono = true,
          )
        }
        item {
          TailwareInfo(
              label = tr(state, "Source", "Broncode", "Source", "Quellcode"),
              value = "github.com/vladbezm/tailware",
              mono = true,
          )
        }
        item {
          Text(
              text =
                  tr(
                      state,
                      "Independent open-source client based on Tailscale for Android. Not affiliated with or endorsed by Tailscale Inc.",
                      "Onafhankelijke open-sourceclient gebaseerd op Tailscale voor Android. Niet verbonden met of goedgekeurd door Tailscale Inc.",
                      "Client open source indépendant basé sur Tailscale pour Android. Sans affiliation ni approbation de Tailscale Inc.",
                      "Unabhängiger Open-Source-Client auf Basis von Tailscale für Android. Nicht mit Tailscale Inc. verbunden oder von ihr unterstützt.",
                  ),
              color = TailwarePalette.Muted,
              fontSize = 11.sp,
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
          )
        }
        item {
          Text(
              text =
                  tr(
                      state,
                      "BSD 3-Clause license",
                      "BSD 3-Clause-licentie",
                      "Licence BSD 3-Clause",
                      "BSD-3-Clause-Lizenz"),
              color = TailwarePalette.Signal,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth(),
          )
        }
        item {
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

@Composable
private fun LanguageScreen(state: WearUiState, onSelect: (TailwareLanguage) -> Unit) {
  TailwareListScreen(title = tr(state, "Language", "Taal", "Langue", "Sprache")) {
    items(TailwareLanguage.entries) { language ->
      TailwareChip(
          label = languageName(language),
          secondary = if (state.language == language) "✓" else null,
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
  TailwareListScreen(
      title = tr(state, "Tailware Tile", "Tailware-tegel", "Tile Tailware", "Tailware-Tile")) {
        item {
          Text(
              text =
                  tr(
                      state,
                      "Choose what the compact Tile shows and what a tap does.",
                      "Kies wat de compacte tegel toont en wat een tik doet.",
                      "Choisissez les détails du Tile et l’action au toucher.",
                      "Wähle die Tile-Details und die Aktion beim Tippen.",
                  ),
              color = TailwarePalette.Muted,
              textAlign = TextAlign.Center,
              maxLines = 5,
          )
        }
        item {
          TailwareChip(
              label =
                  (if (state.tileQuickAction == TileQuickAction.ToggleConnection) "✓ " else "") +
                      tr(
                          state,
                          "Tap toggles connection",
                          "Tik schakelt verbinding",
                          "Toucher bascule la connexion",
                          "Tippen schaltet Verbindung",
                      ),
              onClick = { onSelect(TileQuickAction.ToggleConnection) },
              accent = state.tileQuickAction == TileQuickAction.ToggleConnection,
          )
        }
        item {
          TailwareChip(
              label =
                  (if (state.tileQuickAction == TileQuickAction.OpenApp) "✓ " else "") +
                      tr(
                          state,
                          "Tap opens Tailware",
                          "Tik opent Tailware",
                          "Toucher ouvre Tailware",
                          "Tippen öffnet Tailware",
                      ),
              onClick = { onSelect(TileQuickAction.OpenApp) },
              accent = state.tileQuickAction == TileQuickAction.OpenApp,
          )
        }
        item {
          TailwareToggle(
              label = "Tailscale IP",
              checked = state.tileShowIp,
              onCheckedChange = onShowIp,
          )
        }
        item {
          TailwareToggle(
              label =
                  tr(
                      state,
                      "Exit node",
                      "Exit-node",
                      "Nœud de sortie",
                      "Exit-Node",
                  ),
              checked = state.tileShowExitNode,
              onCheckedChange = onShowExitNode,
          )
        }
        item {
          TailwareToggle(
              label = "MagicDNS",
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
    onContinueWithoutVpn: (() -> Unit)?,
    onDismissError: () -> Unit,
) {
  TailwareListScreen(
      title =
          tr(
              state,
              "VPN setup",
              "VPN-instelling",
              "Configuration VPN",
              "VPN-Einrichtung",
          )) {
        errorItem(state, onDismissError)
        when (state.vpnAuthorization) {
          VpnAuthorization.Granted -> {
            item {
              TailwareInfo(
                  label =
                      tr(
                          state,
                          "Authorization",
                          "Toestemming",
                          "Autorisation",
                          "Autorisierung",
                      ),
                  value =
                      tr(
                          state,
                          "Ready",
                          "Gereed",
                          "Prêt",
                          "Bereit",
                      ),
                  valueColor = TailwarePalette.Signal,
              )
            }
          }
          VpnAuthorization.AdbRequired -> {
            item {
              TailwareInfo(
                  label = "WEAR OS",
                  value =
                      tr(
                          state,
                          "Wear OS does not provide a supported VPN confirmation screen. Authorize Tailware once through ADB.",
                          "Wear OS biedt geen ondersteund VPN-bevestigingsscherm. Autoriseer Tailware eenmalig via ADB.",
                          "Wear OS ne fournit pas d'écran de confirmation VPN pris en charge. Autorisez Tailware une fois via ADB.",
                          "Wear OS bietet keinen unterstützten VPN-Bestätigungsdialog. Tailware einmal per ADB autorisieren.",
                      ),
                  valueColor = TailwarePalette.Warning,
              )
            }
          }
        }
        if (state.vpnAuthorization != VpnAuthorization.Granted) {
          item {
            Text(
                text =
                    tr(
                        state,
                        "On your computer, after installing Tailware:",
                        "Op je computer, nadat Tailware is geïnstalleerd:",
                        "Sur votre ordinateur, après l’installation de Tailware :",
                        "Auf dem Computer, nachdem Tailware installiert ist:",
                    ),
                color = TailwarePalette.Muted,
                textAlign = TextAlign.Center,
                maxLines = 4,
            )
          }
          item {
            TailwareInfo(
                label = "WINDOWS",
                value = ".\\tools\\authorize-wear-vpn.ps1",
                mono = true,
                valueColor = TailwarePalette.Signal,
            )
          }
          item {
            TailwareInfo(
                label = "MACOS / LINUX",
                value = "./tools/authorize-wear-vpn.sh",
                mono = true,
                valueColor = TailwarePalette.Signal,
            )
          }
          item {
            TailwareInfo(
                label = "PACKAGE",
                value = "io.github.vladbezm.tailware",
                mono = true,
            )
          }
          item {
            TailwareChip(
                label =
                    tr(
                        state,
                        "Retry authorization",
                        "Toestemming opnieuw controleren",
                        "Revérifier l’autorisation",
                        "Autorisierung erneut prüfen",
                    ),
                secondary =
                    tr(
                        state,
                        "Tap after the ADB command succeeds",
                        "Tik nadat het ADB-commando slaagt",
                        "Touchez après la réussite de la commande ADB",
                        "Nach erfolgreichem ADB-Befehl tippen",
                    ),
                onClick = onRetry,
            )
          }
          onContinueWithoutVpn?.let { continueWithoutVpn ->
            item {
              TailwareChip(
                  label =
                      tr(
                          state,
                          "Sign in without VPN yet",
                          "Aanmelden zonder VPN",
                          "Se connecter sans VPN",
                          "Noch ohne VPN anmelden",
                      ),
                  secondary =
                      tr(
                          state,
                          "ADB authorization is still required before connecting",
                          "ADB-toestemming blijft nodig vóór je kunt verbinden",
                          "L’autorisation ADB reste requise avant la connexion",
                          "Vor dem Verbinden ist weiterhin eine ADB-Freigabe nötig",
                      ),
                  onClick = continueWithoutVpn,
              )
            }
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
              dismissLabel =
                  tr(
                      state,
                      "Tap to dismiss",
                      "Tik om te sluiten",
                      "Toucher pour fermer",
                      "Zum Schließen tippen",
                  ),
              onDismiss = onDismiss,
          )
        }
      }
}

internal fun peerIsEffectivelyOnline(state: WearUiState, peer: WearPeer): Boolean =
    state.tunnelStatus == WearTunnelStatus.Connected && (peer.isSelf || peer.online)

private fun localizedHealthTitle(state: WearUiState, warning: WearHealthWarning): String =
    when (warning.code) {
      "tailware-underlay-offline" ->
          tr(
              state,
              "No internet connection",
              "Geen internetverbinding",
              "Aucune connexion Internet",
              "Keine Internetverbindung",
          )
      "tailware-underlay-limited" ->
          tr(
              state,
              "Internet not verified",
              "Internet niet geverifieerd",
              "Internet non vérifié",
              "Internet nicht bestätigt",
          )
      "tailware-exit-node-offline" ->
          tr(
              state,
              "Exit node unavailable",
              "Exit node niet beschikbaar",
              "Nœud de sortie indisponible",
              "Exit-Node nicht verfügbar",
          )
      else ->
          when (state.language) {
            TailwareLanguage.English -> "Tailscale warning: ${warning.title}"
            TailwareLanguage.Dutch -> "Tailscale-waarschuwing: ${warning.title}"
            TailwareLanguage.French -> "Avertissement Tailscale : ${warning.title}"
            TailwareLanguage.German -> "Tailscale-Warnung: ${warning.title}"
            TailwareLanguage.Russian -> "Предупреждение Tailscale: ${warning.title}"
          }
    }

private fun localizedHealthMessage(state: WearUiState, warning: WearHealthWarning): String =
    when (warning.code) {
      "tailware-underlay-offline" ->
          tr(
              state,
              "The Tailware tunnel is still present, but this watch has no internet connection.",
              "De Tailware-tunnel bestaat nog, maar dit horloge heeft geen internetverbinding.",
              "Le tunnel Tailware est toujours présent, mais cette montre n’a pas de connexion Internet.",
              "Der Tailware-Tunnel besteht noch, aber diese Uhr hat keine Internetverbindung.",
          )
      "tailware-underlay-limited" ->
          tr(
              state,
              "Android sees a network, but has not verified that it can reach the internet.",
              "Android ziet een netwerk, maar heeft internettoegang niet kunnen bevestigen.",
              "Android détecte un réseau, mais n’a pas confirmé l’accès à Internet.",
              "Android erkennt ein Netzwerk, konnte den Internetzugang aber nicht bestätigen.",
          )
      "tailware-exit-node-offline" -> {
        val nodeName =
            warning.message.substringBefore(" is still selected").ifBlank {
              tr(state, "Exit node", "Exit node", "Nœud de sortie", "Exit-Node")
            }
        when (state.language) {
          TailwareLanguage.English ->
              "$nodeName is still selected but is offline or missing. Disable it or choose another exit node."
          TailwareLanguage.Dutch ->
              "$nodeName is nog geselecteerd, maar is offline of ontbreekt. Schakel die uit of kies een andere exit node."
          TailwareLanguage.French ->
              "$nodeName est toujours sélectionné, mais est hors ligne ou absent. Désactivez-le ou choisissez un autre nœud de sortie."
          TailwareLanguage.German ->
              "$nodeName ist noch ausgewählt, aber offline oder nicht verfügbar. Deaktiviere ihn oder wähle einen anderen Exit-Node."
          TailwareLanguage.Russian ->
              "$nodeName всё ещё выбран, но не в сети или недоступен. Отключите его или выберите другой выходной узел."
        }
      }
      else ->
          when (state.language) {
            TailwareLanguage.English -> "Tailscale reported: ${warning.message}"
            TailwareLanguage.Dutch -> "Tailscale meldt: ${warning.message}"
            TailwareLanguage.French -> "Tailscale signale : ${warning.message}"
            TailwareLanguage.German -> "Tailscale meldet: ${warning.message}"
            TailwareLanguage.Russian -> "Tailscale сообщает: ${warning.message}"
          }
    }

private fun localizedUserMessage(state: WearUiState, rawMessage: String): String {
  val normalized = rawMessage.replace(Regex("\\s+"), " ").trim()
  localizedPingError(state, normalized)?.let {
    return it
  }
  return when {
    normalized.startsWith("Could not open the sign-in page on the paired phone:") ->
        tr(
            state,
            "Could not open sign-in on the paired phone. Keep it nearby and unlocked.",
            "Aanmelden kon niet op de gekoppelde telefoon worden geopend. Houd die dichtbij en ontgrendeld.",
            "Impossible d’ouvrir la connexion sur le téléphone associé. Gardez-le proche et déverrouillé.",
            "Die Anmeldung konnte nicht auf dem gekoppelten Smartphone geöffnet werden. Halte es in der Nähe und entsperrt.",
        )
    normalized.startsWith("Tailware could not safely close the previous sign-in attempt:") -> {
      val detail = normalized.substringAfter(':').trim()
      when (state.language) {
        TailwareLanguage.English ->
            "Tailware could not safely close the previous sign-in attempt: $detail"
        TailwareLanguage.Dutch ->
            "Tailware kon de vorige aanmeldpoging niet veilig afsluiten: $detail"
        TailwareLanguage.French ->
            "Tailware n’a pas pu fermer en sécurité la tentative précédente : $detail"
        TailwareLanguage.German ->
            "Tailware konnte den vorherigen Anmeldeversuch nicht sicher schließen: $detail"
        TailwareLanguage.Russian ->
            "Tailware не удалось безопасно завершить предыдущую попытку входа: $detail"
      }
    }
    normalized == "The authentication key could not be checked because the watch is offline." ->
        tr(
            state,
            "The key cannot be checked while the watch is offline.",
            "De sleutel kan niet worden gecontroleerd terwijl het horloge offline is.",
            "La clé ne peut pas être vérifiée tant que la montre est hors ligne.",
            "Der Schlüssel kann nicht geprüft werden, solange die Uhr offline ist.",
        )
    normalized.startsWith("Authentication failed:") ->
        tr(
            state,
            "Authentication failed. Check that the Tailscale key is valid and not expired.",
            "Authenticatie mislukt. Controleer of de Tailscale-sleutel geldig en niet verlopen is.",
            "Échec de l’authentification. Vérifiez que la clé Tailscale est valide et non expirée.",
            "Authentifizierung fehlgeschlagen. Prüfe, ob der Tailscale-Schlüssel gültig und nicht abgelaufen ist.",
        )
    normalized ==
        "The authentication key could not be verified. Check that it is valid and try again." ->
        tr(
            state,
            "The key was not accepted. Check it and try again.",
            "De sleutel werd niet aanvaard. Controleer hem en probeer opnieuw.",
            "La clé n’a pas été acceptée. Vérifiez-la et réessayez.",
            "Der Schlüssel wurde nicht akzeptiert. Prüfe ihn und versuche es erneut.",
        )
    normalized.startsWith("The custom server refused the sign-in request:") ->
        tr(
            state,
            "The custom server refused sign-in. Check its address and account settings.",
            "De aangepaste server weigerde de aanmelding. Controleer het adres en de accountinstellingen.",
            "Le serveur personnalisé a refusé la connexion. Vérifiez son adresse et le compte.",
            "Der benutzerdefinierte Server hat die Anmeldung abgelehnt. Prüfe Adresse und Konto.",
        )
    normalized ==
        "The custom server is taking too long to answer. Check its address and connection." ->
        tr(
            state,
            "The custom server is not responding. Check its address and connection.",
            "De aangepaste server antwoordt niet. Controleer het adres en de verbinding.",
            "Le serveur personnalisé ne répond pas. Vérifiez son adresse et la connexion.",
            "Der benutzerdefinierte Server antwortet nicht. Prüfe Adresse und Verbindung.",
        )
    normalized.startsWith("Tailscale could not start sign-in:") ->
        tr(
            state,
            "Tailscale could not start sign-in. Check the watch connection.",
            "Tailscale kon het aanmelden niet starten. Controleer de verbinding van het horloge.",
            "Tailscale n’a pas pu lancer la connexion. Vérifiez la connexion de la montre.",
            "Tailscale konnte die Anmeldung nicht starten. Prüfe die Verbindung der Uhr.",
        )
    normalized == "Tailscale sign-in is taking longer than expected. Check the watch connection." ->
        tr(
            state,
            "Tailscale sign-in is taking too long. Check the watch connection.",
            "Aanmelden bij Tailscale duurt te lang. Controleer de verbinding van het horloge.",
            "La connexion Tailscale prend trop de temps. Vérifiez la connexion de la montre.",
            "Die Tailscale-Anmeldung dauert zu lange. Prüfe die Verbindung der Uhr.",
        )
    normalized == "The operation is taking longer than expected." ->
        tr(
            state,
            "This is taking too long. Check the connection and try again.",
            "Dit duurt te lang. Controleer de verbinding en probeer opnieuw.",
            "L’opération prend trop de temps. Vérifiez la connexion et réessayez.",
            "Das dauert zu lange. Prüfe die Verbindung und versuche es erneut.",
        )
    normalized ==
        "Tailware could not restore the VPN automatically. Tap the connection control to retry." ->
        tr(
            state,
            "Tailware could not restore the VPN automatically. Tap the connection control to retry.",
            "Tailware kon de VPN niet automatisch herstellen. Tik op de verbindingsknop om opnieuw te proberen.",
            "Tailware n’a pas pu rétablir le VPN automatiquement. Touchez la commande de connexion pour réessayer.",
            "Tailware konnte das VPN nicht automatisch wiederherstellen. Tippe zum Wiederholen auf die Verbindungssteuerung.",
        )
    normalized == "Connect a Tailware account before starting the VPN." ->
        tr(
            state,
            "Connect a Tailware account before starting the VPN.",
            "Verbind eerst een Tailware-account voordat je de VPN start.",
            "Connectez un compte Tailware avant de démarrer le VPN.",
            "Verbinde vor dem VPN-Start ein Tailware-Konto.",
        )
    normalized == "VPN authorization is required before Tailware can connect." ->
        tr(
            state,
            "VPN authorization is required before Tailware can connect.",
            "VPN-toestemming is nodig voordat Tailware kan verbinden.",
            "L’autorisation VPN est requise avant la connexion de Tailware.",
            "Vor der Tailware-Verbindung ist eine VPN-Freigabe nötig.",
        )
    normalized == "Android did not allow Tailware to start the VPN service." ->
        tr(
            state,
            "Android did not allow Tailware to start the VPN service.",
            "Android stond Tailware niet toe de VPN-service te starten.",
            "Android n’a pas autorisé Tailware à démarrer le service VPN.",
            "Android hat Tailware den Start des VPN-Dienstes nicht erlaubt.",
        )
    normalized == "Android did not allow Tailware to stop the VPN service." ->
        tr(
            state,
            "Android did not allow Tailware to stop the VPN service.",
            "Android stond Tailware niet toe de VPN-service te stoppen.",
            "Android n’a pas autorisé Tailware à arrêter le service VPN.",
            "Android hat Tailware das Stoppen des VPN-Dienstes nicht erlaubt.",
        )
    normalized == "Enter an authentication key." ->
        tr(
            state,
            "Enter an authentication key.",
            "Voer een authenticatiesleutel in.",
            "Saisissez une clé d’authentification.",
            "Gib einen Authentifizierungsschlüssel ein.",
        )
    normalized == "Enter a valid HTTPS control server URL." ->
        tr(
            state,
            "Enter a valid HTTPS control server URL.",
            "Voer een geldige HTTPS-URL voor de controlserver in.",
            "Saisissez une URL HTTPS valide pour le serveur de contrôle.",
            "Gib eine gültige HTTPS-URL des Control-Servers ein.",
        )
    normalized == "Account details are still loading. Wait a moment and try signing out again." ->
        tr(
            state,
            "Account details are still loading. Wait a moment and try signing out again.",
            "De accountgegevens worden nog geladen. Wacht even en probeer opnieuw af te melden.",
            "Les informations du compte se chargent encore. Patientez puis réessayez.",
            "Die Kontodaten werden noch geladen. Warte kurz und versuche die Abmeldung erneut.",
        )
    normalized ==
        "Signed out locally while offline. Tailscale could not be notified; the old device entry may remain in the admin console until it is removed or expires." ->
        tr(
            state,
            "Signed out locally while offline. Tailscale was not notified; the old device entry may remain in the admin console.",
            "Lokaal afgemeld terwijl je offline was. Tailscale kreeg geen melding; de oude apparaatvermelding kan in de beheerconsole blijven staan.",
            "Déconnexion locale hors ligne. Tailscale n’a pas été informé ; l’ancien appareil peut rester dans la console d’administration.",
            "Offline lokal abgemeldet. Tailscale wurde nicht informiert; der alte Geräteeintrag kann in der Admin-Konsole bestehen bleiben.",
        )
    normalized == "Sign in before changing Tailscale DNS." ->
        tr(
            state,
            "Sign in before changing Tailscale DNS.",
            "Meld je aan voordat je Tailscale DNS wijzigt.",
            "Connectez-vous avant de modifier le DNS Tailscale.",
            "Melde dich an, bevor du Tailscale DNS änderst.",
        )
    normalized == "Tailscale settings are still loading. Wait a moment and try again." ->
        tr(
            state,
            "Tailscale settings are still loading. Wait a moment and try again.",
            "De Tailscale-instellingen worden nog geladen. Wacht even en probeer opnieuw.",
            "Les paramètres Tailscale se chargent encore. Patientez puis réessayez.",
            "Die Tailscale-Einstellungen werden noch geladen. Warte kurz und versuche es erneut.",
        )
    normalized == "Tailscale preferences are not available yet." ->
        tr(
            state,
            "Tailscale preferences are not available yet.",
            "De Tailscale-voorkeuren zijn nog niet beschikbaar.",
            "Les préférences Tailscale ne sont pas encore disponibles.",
            "Die Tailscale-Einstellungen sind noch nicht verfügbar.",
        )
    normalized == "Select an exit node before enabling LAN access." ->
        tr(
            state,
            "Select an exit node before enabling LAN access.",
            "Selecteer een exit node voordat je LAN-toegang inschakelt.",
            "Sélectionnez un nœud de sortie avant d’activer l’accès LAN.",
            "Wähle einen Exit-Node, bevor du den LAN-Zugriff aktivierst.",
        )
    normalized == "The selected exit node is no longer available." ->
        tr(
            state,
            "The selected exit node is no longer available.",
            "De geselecteerde exit node is niet meer beschikbaar.",
            "Le nœud de sortie sélectionné n’est plus disponible.",
            "Der ausgewählte Exit-Node ist nicht mehr verfügbar.",
        )
    normalized == "The selected exit node is offline." ->
        tr(
            state,
            "The selected exit node is offline.",
            "De geselecteerde exit node is offline.",
            "Le nœud de sortie sélectionné est hors ligne.",
            "Der ausgewählte Exit-Node ist offline.",
        )
    normalized ==
        "Connect Tailware first. Device ping is available only while the VPN tunnel is fully connected." ->
        tr(
            state,
            "Connect Tailware first. Device ping is available only while the VPN tunnel is fully connected.",
            "Verbind Tailware eerst. Pingen werkt alleen wanneer de VPN-tunnel volledig verbonden is.",
            "Connectez d’abord Tailware. Le ping est disponible uniquement quand le tunnel VPN est entièrement connecté.",
            "Verbinde zuerst Tailware. Ping ist nur bei vollständig verbundenem VPN-Tunnel verfügbar.",
        )
    normalized == "The selected device is no longer available." ->
        tr(
            state,
            "The selected device is no longer available.",
            "Het geselecteerde apparaat is niet meer beschikbaar.",
            "L’appareil sélectionné n’est plus disponible.",
            "Das ausgewählte Gerät ist nicht mehr verfügbar.",
        )
    normalized == "This is this watch; a self-ping does not test a network path." ->
        tr(
            state,
            "This is this watch; a self-ping does not test a network path.",
            "Dit is dit horloge; jezelf pingen test geen netwerkpad.",
            "Il s’agit de cette montre ; un auto-ping ne teste aucun trajet réseau.",
            "Das ist diese Uhr; ein Selbst-Ping testet keinen Netzwerkpfad.",
        )
    normalized == "This device has no IPv4 address to ping." ->
        tr(
            state,
            "This device has no IPv4 address to ping.",
            "Dit apparaat heeft geen IPv4-adres om te pingen.",
            "Cet appareil n’a pas d’adresse IPv4 à tester.",
            "Dieses Gerät hat keine IPv4-Adresse für den Ping.",
        )
    normalized ==
        "Sign-in succeeded, but the one-time VPN authorization is still required before Tailware can connect." ->
        tr(
            state,
            "Sign-in succeeded, but the one-time VPN authorization is still required before Tailware can connect.",
            "Aanmelden is gelukt, maar de eenmalige VPN-toestemming is nog nodig voordat Tailware kan verbinden.",
            "Connexion réussie, mais l’autorisation VPN unique reste nécessaire avant que Tailware puisse se connecter.",
            "Die Anmeldung war erfolgreich, aber vor der Tailware-Verbindung ist noch die einmalige VPN-Freigabe nötig.",
        )
    normalized ==
        "Sign-in succeeded, but Android did not allow Tailware to start the VPN service." ->
        tr(
            state,
            "Sign-in succeeded, but Android did not allow Tailware to start the VPN service.",
            "Aanmelden is gelukt, maar Android stond Tailware niet toe de VPN-service te starten.",
            "Connexion réussie, mais Android n’a pas autorisé Tailware à démarrer le service VPN.",
            "Die Anmeldung war erfolgreich, aber Android hat Tailware den Start des VPN-Dienstes nicht erlaubt.",
        )
    normalized ==
        "Internet was lost before sign-out could be verified. The local account was kept." ->
        tr(
            state,
            "Internet was lost before sign-out could be verified. The local account was kept.",
            "De internetverbinding viel weg voordat afmelden kon worden gecontroleerd. Het lokale account bleef behouden.",
            "Internet a été perdu avant la vérification de la déconnexion. Le compte local a été conservé.",
            "Die Internetverbindung ging vor der Abmeldeprüfung verloren. Das lokale Konto wurde behalten.",
        )
    normalized == "Remote sign-out could not be verified. The local account was kept." ->
        tr(
            state,
            "Remote sign-out could not be verified. The local account was kept.",
            "Afmelden op afstand kon niet worden gecontroleerd. Het lokale account bleef behouden.",
            "La déconnexion distante n’a pas pu être vérifiée. Le compte local a été conservé.",
            "Die Remote-Abmeldung konnte nicht geprüft werden. Das lokale Konto wurde behalten.",
        )
    normalized == "Tailware could not verify that the local account was removed." ->
        tr(
            state,
            "Tailware could not verify that the local account was removed.",
            "Tailware kon niet controleren of het lokale account was verwijderd.",
            "Tailware n’a pas pu vérifier la suppression du compte local.",
            "Tailware konnte nicht prüfen, ob das lokale Konto entfernt wurde.",
        )
    else ->
        when (state.language) {
          TailwareLanguage.English -> normalized
          TailwareLanguage.Dutch -> "Technische fout: $normalized"
          TailwareLanguage.French -> "Erreur technique : $normalized"
          TailwareLanguage.German -> "Technischer Fehler: $normalized"
          TailwareLanguage.Russian -> "Техническая ошибка: $normalized"
        }
  }
}

private fun localizedPingError(state: WearUiState, message: String): String? {
  val match = PING_RESULT_PATTERN.matchEntire(message) ?: return null
  val peerName = match.groupValues[1]
  val address = match.groupValues[2]
  val outcome = match.groupValues[3]
  if (outcome.startsWith("timed out.")) {
    return when (state.language) {
      TailwareLanguage.English ->
          "Ping to $peerName ($address) timed out. The device may be asleep, offline or unreachable."
      TailwareLanguage.Dutch ->
          "Ping naar $peerName ($address) duurde te lang. Het apparaat slaapt mogelijk, is offline of onbereikbaar."
      TailwareLanguage.French ->
          "Le ping vers $peerName ($address) a expiré. L’appareil est peut-être en veille, hors ligne ou inaccessible."
      TailwareLanguage.German ->
          "Ping an $peerName ($address) hat das Zeitlimit überschritten. Das Gerät schläft möglicherweise, ist offline oder nicht erreichbar."
      TailwareLanguage.Russian ->
          "Время ping до $peerName ($address) истекло. Устройство может спать, быть не в сети или недоступно."
    }
  }

  val detail =
      outcome.removePrefix("failed").removePrefix(":").trim().takeUnless {
        it.isBlank() || it.startsWith("without a diagnostic from the Tailscale backend")
      }
  return when (state.language) {
    TailwareLanguage.English ->
        detail?.let { "Ping to $peerName ($address) failed: $it" }
            ?: "Ping to $peerName ($address) failed without a diagnostic from Tailscale."
    TailwareLanguage.Dutch ->
        detail?.let { "Ping naar $peerName ($address) mislukt: $it" }
            ?: "Ping naar $peerName ($address) mislukte zonder diagnose van Tailscale."
    TailwareLanguage.French ->
        detail?.let { "Échec du ping vers $peerName ($address) : $it" }
            ?: "Échec du ping vers $peerName ($address), sans diagnostic de Tailscale."
    TailwareLanguage.German ->
        detail?.let { "Ping an $peerName ($address) fehlgeschlagen: $it" }
            ?: "Ping an $peerName ($address) ohne Tailscale-Diagnose fehlgeschlagen."
    TailwareLanguage.Russian ->
        detail?.let { "Ping до $peerName ($address) не выполнен: $it" }
            ?: "Ping до $peerName ($address) не выполнен без диагностики Tailscale."
  }
}

private val PING_RESULT_PATTERN = Regex("^Ping to (.+) \\(([^)]+)\\) (timed out\\..*|failed.*)$")

private fun languageName(language: TailwareLanguage): String =
    when (language) {
      TailwareLanguage.English -> "English"
      TailwareLanguage.Dutch -> "Nederlands"
      TailwareLanguage.French -> "Français"
      TailwareLanguage.German -> "Deutsch"
      TailwareLanguage.Russian -> "Русский"
    }

private fun formatKeyExpiry(state: WearUiState, rawExpiry: String?): String {
  val expiry = parseKeyExpiry(rawExpiry)
  if (expiry == null) {
    return tr(
        state,
        "Does not expire",
        "Verloopt niet",
        "N’expire pas",
        "Läuft nicht ab",
    )
  }
  val locale = Locale.forLanguageTag(state.language.languageTag)
  val date =
      DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
          .withLocale(locale)
          .format(expiry.atZone(ZoneId.systemDefault()))
  return if (expiry.isBefore(Instant.now())) {
    tr(state, "Expired on $date", "Verlopen op $date", "Expirée le $date", "Abgelaufen am $date")
  } else {
    tr(state, "Expires on $date", "Verloopt op $date", "Expire le $date", "Läuft ab am $date")
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
    english: String,
    dutch: String,
    french: String,
    german: String,
): String =
    when (state.language) {
      TailwareLanguage.English -> english
      TailwareLanguage.Dutch -> dutch
      TailwareLanguage.French -> french
      TailwareLanguage.German -> german
      TailwareLanguage.Russian -> russianTranslation(english)
    }

private fun russianTranslation(english: String): String =
    when {
      english.endsWith(" devices in your tailnet") -> {
        val count = english.substringBefore(" devices in your tailnet").toIntOrNull()
        if (count == null) {
          "Устройства в вашей сети"
        } else {
          val plural =
              if (count % 100 in 11..14) {
                "устройств"
              } else {
                when (count % 10) {
                  1 -> "устройство"
                  2,
                  3,
                  4 -> "устройства"
                  else -> "устройств"
                }
              }
          "$count $plural в вашей сети"
        }
      }
      english.startsWith("Expired on ") -> english.replace("Expired on ", "Истёк: ")
      english.startsWith("Expires on ") -> english.replace("Expires on ", "Истекает: ")
      else -> RUSSIAN_TRANSLATIONS[english] ?: "Недоступно"
    }

private val RUSSIAN_TRANSLATIONS =
    mapOf(
        "About Tailware" to "О Tailware",
        "Account" to "Аккаунт",
        "Account details are still loading. Wait a moment and try signing out again." to
            "Данные аккаунта ещё загружаются. Подождите и повторите выход.",
        "ADB authorization is still required before connecting" to
            "Перед подключением всё ещё требуется авторизация ADB",
        "Active" to "Активен",
        "Allow LAN access" to "Разрешить доступ к LAN",
        "Android did not allow Tailware to start the VPN service." to
            "Android не разрешил Tailware запустить службу VPN.",
        "Android did not allow Tailware to stop the VPN service." to
            "Android не разрешил Tailware остановить службу VPN.",
        "Android sees a network, but has not verified that it can reach the internet." to
            "Android видит сеть, но не подтвердил доступ к интернету.",
        "Approval required" to "Требуется подтверждение",
        "Approve this watch in the admin console." to
            "Подтвердите эти часы в консоли администратора.",
        "Authentication failed. Check that the Tailscale key is valid and not expired." to
            "Ошибка аутентификации. Проверьте, что ключ Tailscale действителен и не истёк.",
        "Authentication key" to "Ключ аутентификации",
        "Authorization" to "Авторизация",
        "Blocked in Android settings" to "Заблокировано в настройках Android",
        "BSD 3-Clause license" to "Лицензия BSD 3-Clause",
        "Cancel sign-in" to "Отменить вход",
        "Choose what the compact Tile shows and what a tap does." to
            "Выберите данные компактной плитки и действие по нажатию.",
        "Confirm sign out" to "Подтвердить выход",
        "Connect" to "Подключить",
        "Connect account" to "Подключить аккаунт",
        "Connect a Tailware account before starting the VPN." to
            "Подключите аккаунт Tailware перед запуском VPN.",
        "Connect server" to "Подключить сервер",
        "Connect Tailware first. Device ping is available only while the VPN tunnel is fully connected." to
            "Сначала подключите Tailware. Ping доступен только при полностью подключённом VPN-туннеле.",
        "Connected" to "Подключено",
        "Connecting…" to "Подключение…",
        "Connection health" to "Состояние подключения",
        "Contacting the paired phone" to "Связь с сопряжённым телефоном",
        "Continue on phone" to "Продолжить на телефоне",
        "Could not open sign-in on the paired phone. Keep it nearby and unlocked." to
            "Не удалось открыть вход на сопряжённом телефоне. Держите его рядом и разблокированным.",
        "Creating a secure login…" to "Создание защищённого входа…",
        "Device" to "Устройство",
        "Device key" to "Ключ устройства",
        "Disable active exit node" to "Отключить активный выходной узел",
        "Disconnect" to "Отключить",
        "Disconnected" to "Отключено",
        "Disconnected notification" to "Уведомление об отключении",
        "Disconnecting…" to "Отключение…",
        "Does not expire" to "Не истекает",
        "Enter a control server URL" to "Введите URL управляющего сервера",
        "Enter a valid HTTPS control server URL." to
            "Введите корректный HTTPS-URL управляющего сервера.",
        "Enter an authentication key." to "Введите ключ аутентификации.",
        "Exit node" to "Выходной узел",
        "Exit node unavailable" to "Выходной узел недоступен",
        "Fastest secure sign-in method" to "Самый быстрый безопасный способ входа",
        "Finish sign-in on the paired phone" to "Завершите вход на сопряжённом телефоне",
        "For advanced or unattended setup" to "Для расширенной или автономной настройки",
        "Generate new code" to "Создать новый код",
        "Headscale / custom server" to "Headscale / пользовательский сервер",
        "Independent open-source client based on Tailscale for Android. Not affiliated with or endorsed by Tailscale Inc." to
            "Независимый клиент с открытым исходным кодом на основе Tailscale для Android. Не связан с Tailscale Inc. и не одобрен ею.",
        "Internet not verified" to "Доступ в интернет не подтверждён",
        "Internet was lost before sign-out could be verified. The local account was kept." to
            "Интернет пропал до проверки выхода. Локальный аккаунт сохранён.",
        "Keep access to the watch’s local network" to "Сохранить доступ к локальной сети часов",
        "Language" to "Язык",
        "Language, DNS, account and Tile" to "Язык, DNS, аккаунт и плитка",
        "Last used" to "Использовался последним",
        "Login code" to "Код входа",
        "Measure tailnet latency" to "Измерить задержку в tailnet",
        "My devices" to "Мои устройства",
        "No devices available yet." to "Устройств пока нет.",
        "No exit node" to "Без выходного узла",
        "No internet connection" to "Нет подключения к интернету",
        "No problems detected" to "Проблем не обнаружено",
        "None" to "Нет",
        "Offline" to "Не в сети",
        "On your computer, after installing Tailware:" to "На компьютере после установки Tailware:",
        "One-time ADB authorization" to "Однократная авторизация ADB",
        "Online" to "В сети",
        "Open on your phone, with QR as fallback" to
            "Открыть на телефоне; QR-код — запасной вариант",
        "Opening on phone…" to "Открытие на телефоне…",
        "Optional; Android still shows the active VPN notification" to
            "Необязательно; Android всё равно показывает активное VPN-уведомление",
        "Phone unavailable" to "Телефон недоступен",
        "Ping device" to "Проверить устройство",
        "Ping failed" to "Проверка не удалась",
        "Ready" to "Готово",
        "Remote sign-out could not be verified. The local account was kept." to
            "Удалённый выход не удалось проверить. Локальный аккаунт сохранён.",
        "Retry authorization" to "Проверить авторизацию снова",
        "Scan with your phone, then approve Tailware." to
            "Отсканируйте телефоном и подтвердите Tailware.",
        "Select an exit node before enabling LAN access." to
            "Выберите выходной узел перед включением доступа к LAN.",
        "Sent to phone" to "Отправлено на телефон",
        "Settings" to "Настройки",
        "Sign in" to "Войти",
        "Sign in before changing Tailscale DNS." to "Войдите перед изменением Tailscale DNS.",
        "Sign in without VPN yet" to "Войти пока без VPN",
        "Sign in with Tailscale" to "Войти через Tailscale",
        "Sign out" to "Выйти",
        "Signed out locally while offline. Tailscale was not notified; the old device entry may remain in the admin console." to
            "Локальный выход выполнен без сети. Tailscale не получил уведомление; старая запись устройства может остаться в консоли администратора.",
        "Sign-in succeeded, but Android did not allow Tailware to start the VPN service." to
            "Вход выполнен, но Android не разрешил Tailware запустить службу VPN.",
        "Sign-in succeeded, but the one-time VPN authorization is still required before Tailware can connect." to
            "Вход выполнен, но перед подключением Tailware всё ещё требуется однократная авторизация VPN.",
        "Signs out locally; the old admin entry may remain" to
            "Локальный выход; старая запись в админке может остаться",
        "Source" to "Исходный код",
        "Starting…" to "Запуск…",
        "Status" to "Статус",
        "Status and quick action" to "Статус и быстрое действие",
        "Tailscale could not start sign-in. Check the watch connection." to
            "Tailscale не удалось начать вход. Проверьте подключение часов.",
        "Tailscale authentication key" to "Ключ аутентификации Tailscale",
        "Tailscale login QR code" to "QR-код входа Tailscale",
        "Tailscale or Headscale" to "Tailscale или Headscale",
        "Tailscale preferences are not available yet." to "Параметры Tailscale пока недоступны.",
        "Tailscale settings are still loading. Wait a moment and try again." to
            "Настройки Tailscale ещё загружаются. Подождите и повторите попытку.",
        "Tailscale sign-in is taking too long. Check the watch connection." to
            "Вход в Tailscale занимает слишком много времени. Проверьте подключение часов.",
        "Tailware connection" to "Подключение Tailware",
        "Tailware could not restore the VPN automatically. Tap the connection control to retry." to
            "Tailware не удалось автоматически восстановить VPN. Нажмите кнопку подключения, чтобы повторить.",
        "Tailware could not verify that the local account was removed." to
            "Tailware не удалось подтвердить удаление локального аккаунта.",
        "Tailware Tile" to "Плитка Tailware",
        "Tap after the ADB command succeeds" to "Нажмите после успешного выполнения команды ADB",
        "Tap for details" to "Нажмите для подробностей",
        "Tap once more to sign out on this watch" to "Нажмите ещё раз, чтобы выйти на этих часах",
        "Tap opens Tailware" to "Нажатие открывает Tailware",
        "Tap to dismiss" to "Нажмите, чтобы закрыть",
        "Tap toggles connection" to "Нажатие переключает подключение",
        "The custom server is not responding. Check its address and connection." to
            "Пользовательский сервер не отвечает. Проверьте его адрес и подключение.",
        "The custom server refused sign-in. Check its address and account settings." to
            "Пользовательский сервер отклонил вход. Проверьте адрес и настройки аккаунта.",
        "The key cannot be checked while the watch is offline." to
            "Ключ нельзя проверить, пока часы не в сети.",
        "The key remains visible while it is checked" to "Ключ остаётся видимым во время проверки",
        "The key was not accepted. Check it and try again." to
            "Ключ не принят. Проверьте его и повторите попытку.",
        "The previous link will no longer be shown" to "Предыдущая ссылка больше не будет показана",
        "The selected device is no longer available." to "Выбранное устройство больше недоступно.",
        "The selected exit node is no longer available." to
            "Выбранный выходной узел больше недоступен.",
        "The selected exit node is offline." to "Выбранный выходной узел не в сети.",
        "The Tailware tunnel is still present, but this watch has no internet connection." to
            "Туннель Tailware всё ещё активен, но у этих часов нет подключения к интернету.",
        "This watch" to "Эти часы",
        "This device has no IPv4 address to ping." to
            "У этого устройства нет IPv4-адреса для ping.",
        "This device left the tailnet." to "Это устройство покинуло tailnet.",
        "This is taking too long. Check the connection and try again." to
            "Это занимает слишком много времени. Проверьте подключение и повторите попытку.",
        "This is this watch; a self-ping does not test a network path." to
            "Это те же часы; ping самого устройства не проверяет сетевой маршрут.",
        "Try again or use the QR code below" to "Повторите попытку или используйте QR-код ниже",
        "Try again" to "Повторить",
        "Unavailable" to "Недоступно",
        "Use the local internet connection" to "Использовать локальное интернет-подключение",
        "Version" to "Версия",
        "Version, source and licenses" to "Версия, исходный код и лицензии",
        "VPN authorization is required before Tailware can connect." to
            "Перед подключением Tailware требуется авторизация VPN.",
        "VPN setup" to "Настройка VPN",
        "VPN setup needed" to "Нужна настройка VPN",
        "Wear OS does not provide a supported VPN confirmation screen. Authorize Tailware once through ADB." to
            "Wear OS не предоставляет поддерживаемый экран подтверждения VPN. Один раз авторизуйте Tailware через ADB.",
        "Your private network, on your wrist." to "Ваша частная сеть — на запястье.",
        "this watch" to "эти часы",
    )
