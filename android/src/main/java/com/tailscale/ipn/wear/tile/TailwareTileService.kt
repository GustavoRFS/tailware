// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear.tile

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.ColorBuilders
import androidx.wear.tiles.DeviceParametersBuilders
import androidx.wear.tiles.DimensionBuilders
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import androidx.wear.tiles.material.Button
import androidx.wear.tiles.material.ButtonColors
import androidx.wear.tiles.material.Text
import androidx.wear.tiles.material.Typography
import androidx.wear.tiles.material.layouts.PrimaryLayout
import com.google.common.util.concurrent.ListenableFuture
import com.tailscale.ipn.ui.model.Ipn
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** A glanceable Tailware connection control for the Wear OS Tiles carousel. */
class TailwareTileService : TileService() {
  override fun onTileRequest(
      requestParams: RequestBuilders.TileRequest
  ): ListenableFuture<TileBuilders.Tile> {
    val snapshot = readSnapshot()
    val root = tileLayout(requestParams, snapshot)
    val timeline =
        TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(root).build())
                    .build())
            .build()

    return ImmediateFuture(
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_INTERVAL_MILLIS)
            .setTimeline(timeline)
            .build())
  }

  override fun onResourcesRequest(
      requestParams: RequestBuilders.ResourcesRequest
  ): ListenableFuture<ResourceBuilders.Resources> =
      ImmediateFuture(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

  private fun readSnapshot(): TileSnapshot {
    // Never initialize the Go/VPN backend merely because Wear OS refreshes the Tile.
    val cached = TailwareTileStateStore.read(this)
    val tunnelStatus =
        when {
          cached.ipnState == Ipn.State.Running && cached.vpnActive -> TileTunnelStatus.Connected
          cached.ipnState == Ipn.State.Starting -> TileTunnelStatus.Connecting
          else -> TileTunnelStatus.Disconnected
        }
    val preferences = getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val language = preferences.getString(KEY_LANGUAGE, LANGUAGE_ENGLISH) ?: LANGUAGE_ENGLISH
    val setupMessage =
        if (tunnelStatus == TileTunnelStatus.Connected) null
        else
            when {
              cached.ipnState == Ipn.State.NeedsLogin ->
                  localized(
                      language, "Sign in to set up", "Meld je aan", "Connectez-vous", "Anmelden")
              cached.ipnState == Ipn.State.NeedsMachineAuth ->
                  localized(
                      language,
                      "Approve this device",
                      "Keur dit apparaat goed",
                      "Autorisez cet appareil",
                      "Gerät freigeben",
                  )
              !cached.ableToStartVpn ->
                  localized(
                      language,
                      "Finish account setup",
                      "Voltooi de accountinstelling",
                      "Terminez la configuration",
                      "Kontoeinrichtung abschließen",
                  )
              !cached.vpnAuthorized ->
                  localized(
                      language,
                      "VPN setup required",
                      "VPN-instelling nodig",
                      "Configuration VPN requise",
                      "VPN-Einrichtung nötig",
                  )
              else -> null
            }
    val quickAction = preferences.getString(KEY_TILE_QUICK_ACTION, QUICK_ACTION_TOGGLE)

    return TileSnapshot(
        tunnelStatus = tunnelStatus,
        selfIp = cached.selfIp,
        activeExitNodeName = cached.activeExitNodeName,
        underlyingOnline = cached.underlyingOnline,
        setupMessage = setupMessage,
        openApp = quickAction == QUICK_ACTION_OPEN_APP,
        language = language,
    )
  }

  @Suppress("DEPRECATION")
  private fun tileLayout(
      requestParams: RequestBuilders.TileRequest,
      snapshot: TileSnapshot,
  ): LayoutElementBuilders.LayoutElement {
    val deviceParameters = requestParams.deviceParameters ?: fallbackDeviceParameters()
    val underlyingUnavailable = snapshot.underlyingOnline == false
    val statusColor =
        when {
          underlyingUnavailable -> COLOR_CONNECTING
          snapshot.tunnelStatus == TileTunnelStatus.Connected -> COLOR_CONNECTED
          snapshot.tunnelStatus == TileTunnelStatus.Connecting -> COLOR_CONNECTING
          else -> COLOR_DISCONNECTED
        }
    val statusLabel =
        when (snapshot.tunnelStatus) {
          TileTunnelStatus.Connected ->
              localized(snapshot.language, "Connected", "Verbonden", "Connecté", "Verbunden")
          TileTunnelStatus.Connecting ->
              localized(snapshot.language, "Connecting", "Verbinden", "Connexion", "Verbindung")
          TileTunnelStatus.Disconnected ->
              localized(
                  snapshot.language,
                  "Disconnected",
                  "Niet verbonden",
                  "Déconnecté",
                  "Getrennt",
              )
        }
    val displayedStatusLabel =
        if (underlyingUnavailable) {
          localized(
              snapshot.language,
              "No internet",
              "Geen internet",
              "Pas d’internet",
              "Kein Internet",
          )
        } else {
          statusLabel
        }
    val buttonLabel =
        when {
          snapshot.setupMessage != null ->
              localized(snapshot.language, "OPEN", "OPEN", "OUVRIR", "ÖFFNEN")
          snapshot.tunnelStatus == TileTunnelStatus.Connected -> "ON"
          snapshot.tunnelStatus == TileTunnelStatus.Connecting -> "..."
          else -> "OFF"
        }
    val supportingText =
        snapshot.setupMessage
            ?: snapshot.selfIp
            ?: localized(
                snapshot.language,
                "Ready to connect",
                "Klaar om te verbinden",
                "Prêt à connecter",
                "Verbindungsbereit",
            )
    val exitNodeText =
        snapshot.activeExitNodeName?.takeIf { it.isNotBlank() }?.let { name -> "Exit: $name" }
    val opensApp = snapshot.openApp || snapshot.setupMessage != null
    val targetClass =
        if (opensApp) {
          WEAR_MAIN_ACTIVITY
        } else {
          TileActionActivity::class.java.name
        }
    val click =
        ModifiersBuilders.Clickable.Builder()
            .setId(CLICK_ID)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(targetClass)
                            .build())
                    .build())
            .build()

    val statusButton =
        Button.Builder(this, click)
            .setSize(48f)
            .setButtonColors(ButtonColors(statusColor, COLOR_INK))
            .setTextContent(buttonLabel, Typography.TYPOGRAPHY_TITLE3)
            .setContentDescription(
                if (opensApp)
                    localized(
                        snapshot.language,
                        "Open Tailware",
                        "Open Tailware",
                        "Ouvrir Tailware",
                        "Tailware öffnen",
                    )
                else
                    localized(
                        snapshot.language,
                        "Toggle Tailware connection",
                        "Schakel de Tailware-verbinding",
                        "Basculer la connexion Tailware",
                        "Tailware-Verbindung umschalten",
                    ))
            .build()

    val content =
        LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(statusButton)
            .addContent(
                LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(4f)).build())
            .addContent(
                Text.Builder(this, displayedStatusLabel)
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(ColorBuilders.argb(statusColor))
                    .build())
            .addContent(
                Text.Builder(this, supportingText)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(ColorBuilders.argb(COLOR_MUTED))
                    .setMaxLines(1)
                    .build())
            .apply {
              exitNodeText?.let { exitNode ->
                addContent(
                    Text.Builder(this@TailwareTileService, exitNode)
                        .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                        .setColor(ColorBuilders.argb(COLOR_MUTED))
                        .setMaxLines(1)
                        .build())
              }
            }
            .build()

    return PrimaryLayout.Builder(deviceParameters)
        .setPrimaryLabelTextContent(
            Text.Builder(this, "TAILWARE")
                .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                .setColor(ColorBuilders.argb(COLOR_BRAND))
                .build())
        .setContent(content)
        .build()
  }

  private data class TileSnapshot(
      val tunnelStatus: TileTunnelStatus,
      val selfIp: String?,
      val activeExitNodeName: String?,
      val underlyingOnline: Boolean?,
      val setupMessage: String?,
      val openApp: Boolean,
      val language: String,
  )

  private fun fallbackDeviceParameters(): DeviceParametersBuilders.DeviceParameters {
    val metrics = resources.displayMetrics
    val configuration = resources.configuration
    return DeviceParametersBuilders.DeviceParameters.Builder()
        .setScreenWidthDp((metrics.widthPixels / metrics.density).toInt().coerceAtLeast(1))
        .setScreenHeightDp((metrics.heightPixels / metrics.density).toInt().coerceAtLeast(1))
        .setScreenDensity(metrics.density)
        .setDevicePlatform(DeviceParametersBuilders.DEVICE_PLATFORM_WEAR_OS)
        .setScreenShape(
            if (configuration.isScreenRound) DeviceParametersBuilders.SCREEN_SHAPE_ROUND
            else DeviceParametersBuilders.SCREEN_SHAPE_RECT)
        .build()
  }

  private fun localized(
      language: String,
      english: String,
      dutch: String,
      french: String,
      german: String,
  ): String =
      when (language) {
        LANGUAGE_DUTCH -> dutch
        LANGUAGE_FRENCH -> french
        LANGUAGE_GERMAN -> german
        else -> english
      }

  private enum class TileTunnelStatus {
    Connected,
    Connecting,
    Disconnected,
  }

  companion object {
    private const val RESOURCES_VERSION = "2"
    // State changes request immediate updates; this is only a battery-friendly reconciliation.
    private const val FRESHNESS_INTERVAL_MILLIS = 15 * 60_000L
    private const val CLICK_ID = "tailware_connection"
    private const val WEAR_MAIN_ACTIVITY = "com.tailscale.ipn.wear.WearMainActivity"

    private const val COLOR_INK = 0xFF071419.toInt()
    private const val COLOR_MUTED = 0xFF8BA5AC.toInt()
    private const val COLOR_BRAND = 0xFF63F2C2.toInt()
    private const val COLOR_CONNECTED = 0xFF63F2C2.toInt()
    private const val COLOR_CONNECTING = 0xFFFFBC66.toInt()
    private const val COLOR_DISCONNECTED = 0xFF70868D.toInt()
  }
}

internal data class CachedTileState(
    val ipnState: Ipn.State,
    val vpnActive: Boolean,
    val vpnAuthorized: Boolean,
    val ableToStartVpn: Boolean,
    val selfIp: String?,
    val activeExitNodeName: String? = null,
    val underlyingOnline: Boolean? = null,
)

/**
 * Small persisted state used by the Tile.
 *
 * Tile requests can arrive while Tailware is not running. Reading this store must therefore remain
 * independent from [com.tailscale.ipn.App.get] and from the native Tailscale engine.
 */
internal object TailwareTileStateStore {
  fun beginProcessSession(context: Context) {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val cachedState = preferences.getString(KEY_CACHED_IPN_STATE, null)
    val runningState =
        cachedState == Ipn.State.Running.name ||
            cachedState == Ipn.State.Starting.name ||
            cachedState == Ipn.State.Stopping.name
    if (!preferences.getBoolean(KEY_CACHED_VPN_ACTIVE, false) && !runningState) return
    preferences.edit {
      putBoolean(KEY_CACHED_VPN_ACTIVE, false)
      if (runningState) putString(KEY_CACHED_IPN_STATE, Ipn.State.Stopped.name)
    }
  }

  fun read(context: Context): CachedTileState {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val stateName = preferences.getString(KEY_CACHED_IPN_STATE, Ipn.State.NeedsLogin.name)
    val ipnState = Ipn.State.values().firstOrNull { it.name == stateName } ?: Ipn.State.NeedsLogin
    val underlyingOnline =
        if (preferences.contains(KEY_CACHED_UNDERLYING_ONLINE)) {
          preferences.getBoolean(KEY_CACHED_UNDERLYING_ONLINE, false)
        } else {
          null
        }
    return CachedTileState(
        ipnState = ipnState,
        vpnActive = preferences.getBoolean(KEY_CACHED_VPN_ACTIVE, false),
        vpnAuthorized = preferences.getBoolean(KEY_CACHED_VPN_AUTHORIZED, false),
        ableToStartVpn = preferences.getBoolean(KEY_CACHED_ABLE_TO_START, false),
        selfIp = preferences.getString(KEY_CACHED_SELF_IP, null),
        activeExitNodeName = preferences.getString(KEY_CACHED_ACTIVE_EXIT_NODE, null),
        underlyingOnline = underlyingOnline,
    )
  }

  fun write(
      context: Context,
      ipnState: Ipn.State,
      vpnActive: Boolean,
      vpnAuthorized: Boolean,
      ableToStartVpn: Boolean,
      selfIp: String?,
      activeExitNodeName: String? = null,
      underlyingOnline: Boolean? = null,
  ): Boolean {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val normalizedIp = selfIp?.takeIf { it.isNotBlank() }
    val normalizedExitNodeName = activeExitNodeName?.takeIf { it.isNotBlank() }
    val storedUnderlyingOnline =
        if (preferences.contains(KEY_CACHED_UNDERLYING_ONLINE)) {
          preferences.getBoolean(KEY_CACHED_UNDERLYING_ONLINE, false)
        } else {
          null
        }
    val changed =
        preferences.getString(KEY_CACHED_IPN_STATE, null) != ipnState.name ||
            preferences.getBoolean(KEY_CACHED_VPN_ACTIVE, false) != vpnActive ||
            preferences.getBoolean(KEY_CACHED_VPN_AUTHORIZED, false) != vpnAuthorized ||
            preferences.getBoolean(KEY_CACHED_ABLE_TO_START, false) != ableToStartVpn ||
            preferences.getString(KEY_CACHED_SELF_IP, null) != normalizedIp ||
            preferences.getString(KEY_CACHED_ACTIVE_EXIT_NODE, null) != normalizedExitNodeName ||
            storedUnderlyingOnline != underlyingOnline
    if (!changed) return false

    preferences.edit {
      putString(KEY_CACHED_IPN_STATE, ipnState.name)
      putBoolean(KEY_CACHED_VPN_ACTIVE, vpnActive)
      putBoolean(KEY_CACHED_VPN_AUTHORIZED, vpnAuthorized)
      putBoolean(KEY_CACHED_ABLE_TO_START, ableToStartVpn)
      if (normalizedIp == null) remove(KEY_CACHED_SELF_IP)
      else putString(KEY_CACHED_SELF_IP, normalizedIp)
      if (normalizedExitNodeName == null) remove(KEY_CACHED_ACTIVE_EXIT_NODE)
      else putString(KEY_CACHED_ACTIVE_EXIT_NODE, normalizedExitNodeName)
      if (underlyingOnline == null) remove(KEY_CACHED_UNDERLYING_ONLINE)
      else putBoolean(KEY_CACHED_UNDERLYING_ONLINE, underlyingOnline)
    }
    return true
  }
}

/** Requests that every active instance of the Tailware Tile fetch fresh state. */
object TailwareTileUpdates {
  fun request(context: Context) {
    try {
      TileService.getUpdater(context.applicationContext)
          .requestUpdate(TailwareTileService::class.java)
    } catch (exception: SecurityException) {
      // A Tile refresh is best-effort. OEM Wear builds must never be able to take down the VPN app
      // when their System UI update endpoint is unavailable or protected.
      Log.w("TailwareTileUpdates", "Unable to request a Tile refresh", exception)
    }
  }
}

/** Minimal already-completed future; avoids pulling the full Guava artifact into the watch APK. */
private class ImmediateFuture<T>(private val value: T) : ListenableFuture<T> {
  override fun addListener(listener: Runnable, executor: Executor) {
    executor.execute(listener)
  }

  override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

  override fun isCancelled(): Boolean = false

  override fun isDone(): Boolean = true

  override fun get(): T = value

  override fun get(timeout: Long, unit: TimeUnit): T = value
}

private const val PREFERENCES_FILE = "tailware_wear_preferences"
private const val KEY_TILE_QUICK_ACTION = "tile_quick_action"
private const val KEY_LANGUAGE = "language"
private const val KEY_CACHED_IPN_STATE = "tile_cached_ipn_state"
private const val KEY_CACHED_VPN_ACTIVE = "tile_cached_vpn_active"
private const val KEY_CACHED_VPN_AUTHORIZED = "tile_cached_vpn_authorized"
private const val KEY_CACHED_ABLE_TO_START = "tile_cached_able_to_start"
private const val KEY_CACHED_SELF_IP = "tile_cached_self_ip"
private const val KEY_CACHED_ACTIVE_EXIT_NODE = "tile_cached_active_exit_node"
private const val KEY_CACHED_UNDERLYING_ONLINE = "tile_cached_underlying_online"
private const val LANGUAGE_ENGLISH = "English"
private const val LANGUAGE_DUTCH = "Dutch"
private const val LANGUAGE_FRENCH = "French"
private const val LANGUAGE_GERMAN = "German"
private const val QUICK_ACTION_TOGGLE = "ToggleConnection"
private const val QUICK_ACTION_OPEN_APP = "OpenApp"
