// Copyright (c) Tailware contributors
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.tailscale.ipn.wear.PingStatus
import com.tailscale.ipn.wear.TailwareLanguage
import com.tailscale.ipn.wear.TailwareViewModel
import com.tailscale.ipn.wear.TileQuickAction
import com.tailscale.ipn.wear.VpnAuthorization
import com.tailscale.ipn.wear.WearAccountState
import com.tailscale.ipn.wear.WearHealthSeverity
import com.tailscale.ipn.wear.WearPeer
import com.tailscale.ipn.wear.WearTunnelStatus
import com.tailscale.ipn.wear.WearUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay

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
    onOpenVpnSettings: () -> Unit,
    onOpenLoginOnPhone: (String) -> Unit,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val navController = rememberSwipeDismissableNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  var selectedPeerId by rememberSaveable { mutableStateOf<String?>(null) }
  var accountRootRoute by rememberSaveable { mutableStateOf(Route.Gate) }

  val onboardingRoutes = remember {
    setOf(
        Route.Gate,
        Route.Welcome,
        Route.LoginMethods,
        Route.LoginQr,
        Route.AuthKey,
        Route.ControlUrl,
    )
  }

  val currentRoute = backStackEntry?.destination?.route
  LaunchedEffect(state.accountState, state.loginUrl, currentRoute) {
    val route = currentRoute ?: return@LaunchedEffect
    val destination =
        when {
          state.accountState == WearAccountState.SignedIn && route in onboardingRoutes ->
              Route.Dashboard
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
      navController.navigate(destination) {
        popUpTo(accountRootRoute) { inclusive = true }
        launchSingleTop = true
      }
      accountRootRoute = destination
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
          onConnectAccount = { navController.navigate(Route.LoginMethods) },
      )
    }
    composable(Route.LoginMethods) {
      LoginMethodsScreen(
          state = state,
          onTailscaleLogin = {
            viewModel.startInteractiveLogin()
            navController.navigate(Route.LoginQr)
          },
          onAuthKey = { navController.navigate(Route.AuthKey) },
          onControlUrl = { navController.navigate(Route.ControlUrl) },
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.LoginQr) {
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
          onRetry = viewModel::startInteractiveLogin,
          onOpenOnPhone = onOpenLoginOnPhone,
          onCancel = {
            explicitCancellationRequested.value = true
            viewModel.cancelInteractiveLogin()
            navController.popBackStack()
          },
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.AuthKey) {
      AuthKeyScreen(
          state = state,
          onLogin = viewModel::startAuthKeyLogin,
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.ControlUrl) {
      ControlUrlScreen(
          state = state,
          onLogin = {
            viewModel.startCustomControlLogin(it)
            navController.navigate(Route.LoginQr)
          },
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.Dashboard) {
      DashboardScreen(
          state = state,
          onConnectionClick = {
            when (state.tunnelStatus) {
              WearTunnelStatus.Connected,
              WearTunnelStatus.Connecting -> viewModel.disconnect()
              WearTunnelStatus.Disconnecting -> Unit
              WearTunnelStatus.Disconnected,
              WearTunnelStatus.Unknown ->
                  when (state.vpnAuthorization) {
                    VpnAuthorization.Granted -> viewModel.connect()
                    VpnAuthorization.AdbRequired -> navController.navigate(Route.VpnSetup)
                  }
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
          onVpnSetup = { navController.navigate(Route.VpnSetup) },
          onAbout = { navController.navigate(Route.About) },
          onOpenVpnSettings = onOpenVpnSettings,
          onLogout = viewModel::logoutAccount,
          onDismissError = viewModel::clearError,
      )
    }
    composable(Route.Language) { LanguageScreen(state = state, onSelect = viewModel::setLanguage) }
    composable(Route.Tile) {
      TileSettingsScreen(state = state, onSelect = viewModel::setTileQuickAction)
    }
    composable(Route.VpnSetup) {
      VpnSetupScreen(
          state = state,
          onRetry = { viewModel.refreshVpnAuthorization(com.tailscale.ipn.App.get()) },
          onOpenVpnSettings = onOpenVpnSettings,
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
private fun WelcomeScreen(state: WearUiState, onConnectAccount: () -> Unit) {
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
              label = "Tailscale",
              secondary =
                  tr(
                      state,
                      "Scan a secure QR code",
                      "Scan een beveiligde QR-code",
                      "Scanner un code QR sécurisé",
                      "Sicheren QR-Code scannen",
                  ),
              onClick = onTailscaleLogin,
              accent = true,
              enabled = state.busyAction != com.tailscale.ipn.wear.WearAction.Login,
          )
        }
        item {
          TailwareChip(
              label = "Headscale / custom server",
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
        item {
          TailwareChip(
              label =
                  tr(
                      state,
                      "Authentication key",
                      "Authenticatiesleutel",
                      "Clé d’authentification",
                      "Authentifizierungsschlüssel",
                  ),
              secondary =
                  tr(
                      state,
                      "Useful for advanced setup",
                      "Voor geavanceerde installatie",
                      "Pour une installation avancée",
                      "Für die erweiterte Einrichtung",
                  ),
              onClick = onAuthKey,
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
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
) {
  val qr = rememberQrCode(state.loginUrl)
  val small = LocalConfiguration.current.screenWidthDp < 210
  TailwareListScreen(title = tr(state, "Sign in", "Aanmelden", "Connexion", "Anmelden")) {
    errorItem(state, onDismissError)
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
          Box(
              modifier =
                  Modifier.size(if (small) 116.dp else 138.dp)
                      .background(Color.White, RoundedCornerShape(12.dp))
                      .padding(7.dp),
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
          TailwareChip(
              label =
                  tr(
                      state,
                      "Continue on phone",
                      "Doorgaan op telefoon",
                      "Continuer sur le téléphone",
                      "Auf dem Smartphone fortfahren",
                  ),
              secondary =
                  if (state.phoneLoginSent)
                      tr(
                          state,
                          "Sign-in page sent to the paired phone",
                          "Aanmeldpagina naar de gekoppelde telefoon gestuurd",
                          "Page de connexion envoyée au téléphone associé",
                          "Anmeldeseite an das gekoppelte Smartphone gesendet",
                      )
                  else
                      tr(
                          state,
                          "Open this secure sign-in link remotely",
                          "Open deze beveiligde link op afstand",
                          "Ouvrir ce lien sécurisé à distance",
                          "Sicheren Link aus der Ferne öffnen",
                      ),
              onClick = { state.loginUrl?.let(onOpenOnPhone) },
              accent = true,
          )
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
                      "Der vorige Link wird nicht mehr angezeigt",
                  ),
              onClick = onRetry,
          )
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
    onLogin: (String) -> Unit,
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
        errorItem(state, onDismissError)
        item {
          WearTextField(
              value = key,
              onValueChange = { key = it },
              label = "tskey-auth-…",
              keyboardType = KeyboardType.Password,
              password = true,
          )
        }
        item {
          TailwareChip(
              label = tr(state, "Sign in", "Aanmelden", "Connexion", "Anmelden"),
              secondary =
                  tr(
                      state,
                      "Tailware clears this field after use",
                      "Tailware wist dit veld na gebruik",
                      "Tailware efface ce champ après utilisation",
                      "Tailware löscht dieses Feld nach der Verwendung",
                  ),
              onClick = {
                val submittedKey = key
                key = ""
                onLogin(submittedKey)
              },
              enabled = key.isNotBlank(),
              accent = true,
          )
        }
      }
}

@Composable
private fun ControlUrlScreen(
    state: WearUiState,
    onLogin: (String) -> Unit,
    onDismissError: () -> Unit,
) {
  var url by rememberSaveable { mutableStateOf("https://") }
  TailwareListScreen(title = "Headscale") {
    errorItem(state, onDismissError)
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
          enabled = url.length > "https://".length,
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
          enabled = state.busyAction == null,
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
              if (state.tunnelStatus == WearTunnelStatus.Connected ||
                  state.tunnelStatus == WearTunnelStatus.Connecting)
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
            label = warning.title,
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
            secondary = "One-time ADB authorization",
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
                label = warning.title,
                value = warning.message,
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
          val status =
              if (peer.online) tr(state, "Online", "Online", "En ligne", "Online")
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
                  if (peer.online) tr(state, "Online", "Online", "En ligne", "Online")
                  else tr(state, "Offline", "Offline", "Hors ligne", "Offline"),
              valueColor = if (peer.online) TailwarePalette.Signal else TailwarePalette.Muted,
          )
        }
        peer.ipv4?.let { item { TailwareInfo(label = "IPV4", value = it, mono = true) } }
        peer.ipv6?.let { item { TailwareInfo(label = "IPV6", value = it, mono = true) } }
        if (peer.dnsName.isNotBlank()) {
          item { TailwareInfo(label = "MAGICDNS", value = peer.dnsName, mono = true) }
        }
        peer.os?.takeIf { it.isNotBlank() }?.let { item { TailwareInfo(label = "OS", value = it) } }
        item {
          TailwareChip(
              label =
                  tr(state, "Ping device", "Apparaat pingen", "Tester l’appareil", "Gerät pingen"),
              secondary =
                  tr(
                      state,
                      "Measure tailnet latency",
                      "Meet de tailnet-latentie",
                      "Mesurer la latence du tailnet",
                      "Tailnet-Latenz messen",
                  ),
              onClick = onPing,
              enabled = pingResult?.status != PingStatus.Running,
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
                    secondary = pingResult?.error,
                    onClick = onClearPing,
                )
              }
          PingStatus.Idle -> Unit
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
    onVpnSetup: () -> Unit,
    onAbout: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onLogout: () -> Unit,
    onDismissError: () -> Unit,
) {
  var confirmLogout by remember { mutableStateOf(false) }
  LaunchedEffect(confirmLogout) {
    if (confirmLogout) {
      delay(10_000)
      confirmLogout = false
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
        errorItem(state, onDismissError)
        item {
          TailwareChip(
              label = tr(state, "Language", "Taal", "Langue", "Sprache"),
              secondary = languageName(state.language),
              onClick = onLanguage,
              iconRes = R.drawable.globe,
          )
        }
        state.profileDisplayName?.let {
          item {
            TailwareInfo(
                label = tr(state, "Account", "Account", "Compte", "Konto"),
                value = it,
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
        item {
          TailwareToggle(
              label = "Tailscale DNS",
              secondary = "MagicDNS",
              checked =
                  state.accountState == WearAccountState.SignedIn && state.tailscaleDnsEnabled,
              onCheckedChange = { onToggleDns() },
              enabled = state.accountState == WearAccountState.SignedIn && state.busyAction == null,
          )
        }
        item {
          TailwareToggle(
              label =
                  tr(
                      state,
                      "Optional notifications",
                      "Optionele meldingen",
                      "Notifications facultatives",
                      "Optionale Benachrichtigungen",
                  ),
              secondary =
                  tr(
                      state,
                      "Android still shows the required VPN status",
                      "Android toont nog steeds de verplichte VPN-status",
                      "Android affiche toujours l’état VPN requis",
                      "Android zeigt weiterhin den nötigen VPN-Status",
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
        item {
          TailwareChip(
              label =
                  tr(
                      state,
                      "VPN authorization",
                      "VPN-toestemming",
                      "Autorisation VPN",
                      "VPN-Autorisierung",
                  ),
              secondary =
                  when (state.vpnAuthorization) {
                    VpnAuthorization.Granted ->
                        tr(state, "Authorized", "Toegestaan", "Autorisé", "Autorisiert")
                    VpnAuthorization.AdbRequired -> "ADB setup"
                  },
              onClick = onVpnSetup,
          )
        }
        item {
          TailwareChip(
              label = "Always-on VPN",
              secondary =
                  tr(
                      state,
                      "Open Android VPN settings",
                      "Open Android VPN-instellingen",
                      "Ouvrir les paramètres VPN Android",
                      "Android-VPN-Einstellungen öffnen",
                  ),
              onClick = onOpenVpnSettings,
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
                              "Reconnect to the internet before the final tap",
                              "Herstel internet vóór de laatste tik",
                              "Rétablissez internet avant la dernière pression",
                              "Vor dem letzten Tippen Internet wiederherstellen",
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
              onClick = { if (confirmLogout) onLogout() else confirmLogout = true },
              enabled = state.accountState == WearAccountState.SignedIn && state.busyAction == null,
              accent = confirmLogout,
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
private fun TileSettingsScreen(state: WearUiState, onSelect: (TileQuickAction) -> Unit) {
  TailwareListScreen(
      title = tr(state, "Tailware Tile", "Tailware-tegel", "Tile Tailware", "Tailware-Tile")) {
        item {
          Text(
              text =
                  tr(
                      state,
                      "Add the Tile from your watch’s Tile editor. It shows connection status and your Tailware IP.",
                      "Voeg de tegel toe via de tegel-editor van je horloge. Hij toont de verbinding en je Tailware-IP.",
                      "Ajoutez le Tile depuis l’éditeur de la montre. Il affiche la connexion et votre IP Tailware.",
                      "Füge das Tile im Tile-Editor der Uhr hinzu. Es zeigt Verbindung und Tailware-IP.",
                  ),
              color = TailwarePalette.Muted,
              textAlign = TextAlign.Center,
              maxLines = 5,
          )
        }
        item {
          TailwareChip(
              label =
                  tr(
                      state,
                      "Tap toggles connection",
                      "Tik schakelt verbinding",
                      "Toucher bascule la connexion",
                      "Tippen schaltet Verbindung",
                  ),
              secondary =
                  if (state.tileQuickAction == TileQuickAction.ToggleConnection) "✓" else null,
              onClick = { onSelect(TileQuickAction.ToggleConnection) },
              accent = state.tileQuickAction == TileQuickAction.ToggleConnection,
          )
        }
        item {
          TailwareChip(
              label =
                  tr(
                      state,
                      "Tap opens Tailware",
                      "Tik opent Tailware",
                      "Toucher ouvre Tailware",
                      "Tippen öffnet Tailware",
                  ),
              secondary = if (state.tileQuickAction == TileQuickAction.OpenApp) "✓" else null,
              onClick = { onSelect(TileQuickAction.OpenApp) },
              accent = state.tileQuickAction == TileQuickAction.OpenApp,
          )
        }
      }
}

@Composable
private fun VpnSetupScreen(
    state: WearUiState,
    onRetry: () -> Unit,
    onOpenVpnSettings: () -> Unit,
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
                label = "POWERSHELL",
                value = ".\\tools\\authorize-wear-vpn.ps1",
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
        }
        item {
          TailwareChip(
              label = "Always-on VPN",
              secondary =
                  tr(
                      state,
                      "Open Android VPN settings",
                      "Open Android VPN-instellingen",
                      "Ouvrir les paramètres VPN Android",
                      "Android-VPN-Einstellungen öffnen",
                  ),
              onClick = onOpenVpnSettings,
          )
        }
      }
}

@Composable
private fun WearTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    password: Boolean = false,
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
              fontFamily = if (password) FontFamily.Monospace else FontFamily.Default,
          ),
      keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
      visualTransformation =
          if (password) PasswordVisualTransformation()
          else androidx.compose.ui.text.input.VisualTransformation.None,
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
) {
  state.error?.let { error ->
    item {
      TailwareErrorChip(
          message = error.message,
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

private fun languageName(language: TailwareLanguage): String =
    when (language) {
      TailwareLanguage.English -> "English"
      TailwareLanguage.Dutch -> "Nederlands"
      TailwareLanguage.French -> "Français"
      TailwareLanguage.German -> "Deutsch"
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
    }
