// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear.tile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
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
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.wear.TailwareActivationCapability
import com.tailscale.ipn.wear.TailwareLanguage
import com.tailscale.ipn.wear.WearMainActivity
import com.tailscale.ipn.wear.localization.TailwareStrings
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** A glanceable Tailware connection control for the Wear OS Tiles carousel. */
class TailwareTileService : TileService() {
  override fun onTileRequest(
      requestParams: RequestBuilders.TileRequest
  ): ListenableFuture<TileBuilders.Tile> {
    if (requestParams.currentState.lastClickableId == CLICK_ID) {
      handleConnectionTap()
    }
    val snapshot = readSnapshot()
    TailwareTileStateStore.writeDisplayedAction(
        this,
        requestParams.tileId.toString(),
        snapshot.displayedAction,
    )
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
      ImmediateFuture(
          ResourceBuilders.Resources.Builder()
              .setVersion(RESOURCES_VERSION)
              .addIdToImageMapping(
                  POWER_ICON_RESOURCE_ID,
                  ResourceBuilders.ImageResource.Builder()
                      .setAndroidResourceByResId(
                          ResourceBuilders.AndroidImageResourceByResId.Builder()
                              .setResourceId(R.drawable.power)
                              .build())
                      .build(),
              )
              .build())

  private fun handleConnectionTap() {
    // A Tile process can be recreated from stored UI state, which is never proof of a current
    // foreground gesture. Every Tile tap therefore follows the passive Activity path. Connect and
    // Disconnect remain visually and causally separate buttons inside the activated Activity.
    openTailware()
  }

  private fun openTailware() {
    runCatching {
          PendingIntent.getActivity(
                  this,
                  OPEN_APP_REQUEST_CODE,
                  Intent(this, WearMainActivity::class.java),
                  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
              )
              .send()
        }
        .onFailure { error ->
          Log.w("TailwareTile", "Unable to open Tailware from the Tile", error)
          TailwareTileUpdates.request(this)
        }
  }

  private fun readSnapshot(): TileSnapshot {
    // Never initialize the Go/VPN backend merely because Wear OS refreshes the Tile.
    val cached = TailwareTileStateStore.read(this)
    val desiredVpnEnabled = cached.vpnActive && TailwareActivationCapability.hasVpnCapability()
    val tunnelStatus =
        resolveTileTunnelStatus(
            ipnState = cached.ipnState,
            vpnActive = cached.vpnActive,
            desiredVpnEnabled = desiredVpnEnabled,
            vpnAuthorized = cached.vpnAuthorized,
            ableToStartVpn = cached.ableToStartVpn,
        )
    val preferences = getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val language = TailwareStrings.selectedLanguage(this).languageTag
    val setupMessage =
        if (tunnelStatus == TileTunnelStatus.Connected) null
        else
            when {
              cached.ipnState == Ipn.State.NeedsLogin ->
                  localized(language, R.string.wear_sign_in_to_set_up)
              cached.ipnState == Ipn.State.NeedsMachineAuth ->
                  localized(language, R.string.wear_approve_this_device)
              !cached.ableToStartVpn -> localized(language, R.string.wear_finish_account_setup)
              !cached.vpnAuthorized -> localized(language, R.string.wear_vpn_setup_required)
              else -> null
            }
    val visualStatus =
        resolveTileVisualStatus(
            tunnelStatus = tunnelStatus,
            underlyingOnline = cached.underlyingOnline,
            setupRequired = setupMessage != null,
            problemMessage = cached.problemMessage,
            problemIsError = cached.problemIsError,
        )
    val displayedAction = TileToggleDecision.OpenApp

    return TileSnapshot(
        tunnelStatus = tunnelStatus,
        visualStatus = visualStatus,
        displayedAction = displayedAction,
        selfIp = cached.selfIp,
        activeExitNodeName = cached.activeExitNodeName,
        tailscaleDnsEnabled = cached.tailscaleDnsEnabled,
        magicDnsName = cached.magicDnsName,
        allowLanAccess = cached.allowLanAccess,
        underlyingOnline = cached.underlyingOnline,
        setupMessage = setupMessage,
        problemMessage = cached.problemMessage,
        showIp = preferences.getBoolean(KEY_TILE_SHOW_IP, true),
        showExitNode = preferences.getBoolean(KEY_TILE_SHOW_EXIT_NODE, true),
        showMagicDns = preferences.getBoolean(KEY_TILE_SHOW_MAGIC_DNS, true),
        language = language,
    )
  }

  @Suppress("DEPRECATION")
  private fun tileLayout(
      requestParams: RequestBuilders.TileRequest,
      snapshot: TileSnapshot,
  ): LayoutElementBuilders.LayoutElement {
    val deviceParameters = requestParams.deviceParameters ?: fallbackDeviceParameters()
    val layoutProfile =
        tileLayoutProfile(
            screenWidthDp = deviceParameters.screenWidthDp,
            screenHeightDp = deviceParameters.screenHeightDp,
        )
    val statusColor =
        when (snapshot.visualStatus) {
          TileVisualStatus.Connected -> COLOR_CONNECTED
          TileVisualStatus.Connecting -> COLOR_CONNECTING
          TileVisualStatus.Off -> COLOR_DISCONNECTED
          TileVisualStatus.Warning -> COLOR_WARNING
          TileVisualStatus.Error -> COLOR_ERROR
        }
    val buttonContent = resolveTileButtonContent(snapshot.visualStatus)
    val statusLabel =
        when (resolveTileHumanStatus(snapshot.visualStatus)) {
          TileHumanStatus.Connected -> localized(snapshot.language, R.string.wear_connected)
          TileHumanStatus.Connecting ->
              localized(snapshot.language, R.string.wear_connecting_c1f3b71f)
          TileHumanStatus.Disconnected -> localized(snapshot.language, R.string.wear_disconnected)
          TileHumanStatus.Attention -> localized(snapshot.language, R.string.wear_attention)
          TileHumanStatus.Error -> localized(snapshot.language, R.string.wear_error)
        }
    val selectedLanguage = TailwareLanguage.fromStoredValue(snapshot.language)
    val networkRows =
        buildTileNetworkRows(
            selfIp = snapshot.selfIp,
            tailscaleDnsEnabled = snapshot.tailscaleDnsEnabled,
            magicDnsName = snapshot.magicDnsName,
            showIp = snapshot.showIp,
            showMagicDns = snapshot.showMagicDns,
            ipLabel = localized(snapshot.language, R.string.wear_label_ip),
            dnsLabel = localized(snapshot.language, R.string.wear_label_dns),
            onLabel = localized(snapshot.language, R.string.wear_on),
            offLabel = localized(snapshot.language, R.string.wear_off),
            valueFormatter = { TailwareStrings.ltr(selectedLanguage, it) },
        )
    val priorityText =
        snapshot.problemMessage?.let(::shortTileProblem)
            ?: snapshot.setupMessage
            ?: if (snapshot.underlyingOnline == false) {
              localized(snapshot.language, R.string.wear_no_internet)
            } else {
              null
            }
    val exitRows =
        buildTileExitRows(
            activeExitNodeName = snapshot.activeExitNodeName,
            allowLanAccess = snapshot.allowLanAccess,
            showExitNode = snapshot.showExitNode,
            exitLabel = localized(snapshot.language, R.string.wear_exit),
            offLabel = localized(snapshot.language, R.string.wear_off_e3de5ab0),
            lanLabel = localized(snapshot.language, R.string.wear_label_lan),
            onLabel = localized(snapshot.language, R.string.wear_on_e0049a66),
            offStateLabel = localized(snapshot.language, R.string.wear_off_e3de5ab0),
            valueFormatter = { TailwareStrings.isolate(selectedLanguage, it) },
        )
    val detailRows =
        prioritizeTileDetailRows(
            priorityText = priorityText,
            networkRows = networkRows,
            exitRows = exitRows,
            readyText = localized(snapshot.language, R.string.wear_ready),
            maximumNormalRows = layoutProfile.maximumNormalRows,
        )
    val opensApp = snapshot.displayedAction == TileToggleDecision.OpenApp
    val contentDescription =
        buildTileContentDescription(
            statusLabel = statusLabel,
            displayedAction = snapshot.displayedAction,
            connectLabel = localized(snapshot.language, R.string.connect),
            disconnectLabel = localized(snapshot.language, R.string.disconnect),
            openAppLabel = localized(snapshot.language, R.string.wear_open_tailware),
        )
    val action =
        if (opensApp) {
          ActionBuilders.LaunchAction.Builder()
              .setAndroidActivity(
                  ActionBuilders.AndroidActivity.Builder()
                      .setPackageName(packageName)
                      .setClassName(WEAR_MAIN_ACTIVITY)
                      .build())
              .build()
        } else {
          ActionBuilders.LoadAction.Builder().build()
        }
    val click = ModifiersBuilders.Clickable.Builder().setId(CLICK_ID).setOnClick(action).build()

    val statusButton =
        Button.Builder(this, click)
            .setSize(layoutProfile.buttonSizeDp)
            .setButtonColors(ButtonColors(COLOR_INK, statusColor))
            .apply {
              when (buttonContent) {
                TileButtonContent.PowerIcon ->
                    setIconContent(POWER_ICON_RESOURCE_ID, DimensionBuilders.dp(22f))
              }
            }
            .setContentDescription(contentDescription)
            .build()
    val statusRing =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.dp(layoutProfile.ringSizeDp))
            .setHeight(DimensionBuilders.dp(layoutProfile.ringSizeDp))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(COLOR_INK))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(layoutProfile.ringSizeDp / 2f))
                                    .build())
                            .build())
                    .setBorder(
                        ModifiersBuilders.Border.Builder()
                            .setColor(ColorBuilders.argb(statusColor))
                            .setWidth(DimensionBuilders.dp(3f))
                            .build())
                    .build())
            .addContent(statusButton)
            .build()

    val content =
        LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(statusRing)
            .addContent(
                LayoutElementBuilders.Spacer.Builder()
                    .setHeight(DimensionBuilders.dp(layoutProfile.spacerHeightDp))
                    .build())
            .addContent(
                Text.Builder(this, statusLabel)
                    .setTypography(layoutProfile.statusTypography)
                    .setColor(ColorBuilders.argb(statusColor))
                    .build())
            .apply {
              detailRows.forEach { detail ->
                addContent(
                    Text.Builder(this@TailwareTileService, detail)
                        .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                        .setColor(
                            ColorBuilders.argb(
                                when (snapshot.visualStatus) {
                                  TileVisualStatus.Warning -> COLOR_WARNING
                                  TileVisualStatus.Error -> COLOR_ERROR
                                  else -> COLOR_MUTED
                                }))
                        .setMaxLines(1)
                        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                        .build())
              }
            }
            .build()

    return PrimaryLayout.Builder(deviceParameters)
        .setPrimaryLabelTextContent(
            Text.Builder(this, localized(snapshot.language, R.string.wear_brand_tailware_upper))
                .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                .setColor(ColorBuilders.argb(COLOR_BRAND))
                .build())
        .setContent(content)
        .build()
  }

  private data class TileSnapshot(
      val tunnelStatus: TileTunnelStatus,
      val visualStatus: TileVisualStatus,
      val displayedAction: TileToggleDecision,
      val selfIp: String?,
      val activeExitNodeName: String?,
      val tailscaleDnsEnabled: Boolean,
      val magicDnsName: String?,
      val allowLanAccess: Boolean,
      val underlyingOnline: Boolean?,
      val setupMessage: String?,
      val problemMessage: String?,
      val showIp: Boolean,
      val showExitNode: Boolean,
      val showMagicDns: Boolean,
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

  private fun localized(language: String, @StringRes resourceId: Int): String =
      TailwareStrings.text(this, TailwareLanguage.fromStoredValue(language), resourceId)

  companion object {
    private const val RESOURCES_VERSION = "5"
    private const val POWER_ICON_RESOURCE_ID = "tailware_power"
    private const val OPEN_APP_REQUEST_CODE = 91
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
    private const val COLOR_WARNING = 0xFFFFBC66.toInt()
    private const val COLOR_ERROR = 0xFFFF6B72.toInt()
  }
}

internal data class TileLayoutProfile(
    val ringSizeDp: Float,
    val buttonSizeDp: Float,
    val spacerHeightDp: Float,
    val buttonTypography: Int,
    val statusTypography: Int,
    val maximumNormalRows: Int,
)

internal fun tileLayoutProfile(screenWidthDp: Int, screenHeightDp: Int): TileLayoutProfile {
  val compact = minOf(screenWidthDp, screenHeightDp) <= 200
  return if (compact) {
    TileLayoutProfile(
        ringSizeDp = 50f,
        buttonSizeDp = 48f,
        spacerHeightDp = 2f,
        buttonTypography = Typography.TYPOGRAPHY_BODY2,
        statusTypography = Typography.TYPOGRAPHY_BODY2,
        maximumNormalRows = 4,
    )
  } else {
    TileLayoutProfile(
        ringSizeDp = 52f,
        buttonSizeDp = 48f,
        spacerHeightDp = 4f,
        buttonTypography = Typography.TYPOGRAPHY_TITLE3,
        statusTypography = Typography.TYPOGRAPHY_TITLE3,
        maximumNormalRows = 4,
    )
  }
}

internal fun buildTileContentDescription(
    statusLabel: String,
    displayedAction: TileToggleDecision,
    connectLabel: String,
    disconnectLabel: String,
    openAppLabel: String,
): String =
    when (displayedAction) {
      TileToggleDecision.Start -> "$statusLabel — $connectLabel"
      TileToggleDecision.Stop -> "$statusLabel — $disconnectLabel"
      TileToggleDecision.OpenApp -> openAppLabel
    }

internal data class CachedTileState(
    val ipnState: Ipn.State,
    val vpnActive: Boolean,
    val vpnAuthorized: Boolean,
    val ableToStartVpn: Boolean,
    val selfIp: String?,
    val activeExitNodeName: String? = null,
    val underlyingOnline: Boolean? = null,
    val tailscaleDnsEnabled: Boolean = false,
    val magicDnsName: String? = null,
    val allowLanAccess: Boolean = false,
    val problemMessage: String? = null,
    val problemIsError: Boolean = false,
)

/**
 * Small persisted state used by the Tile.
 *
 * Tile requests can arrive while Tailware is not running. Reading this store must therefore remain
 * independent from [com.tailscale.ipn.App.get] and from the native Tailscale engine.
 */
internal object TailwareTileStateStore {
  fun beginProcessSession(context: Context) {
    clearIdentityAndStatus(context)
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
        tailscaleDnsEnabled = preferences.getBoolean(KEY_CACHED_TAILSCALE_DNS_ENABLED, false),
        magicDnsName = preferences.getString(KEY_CACHED_MAGIC_DNS_NAME, null),
        allowLanAccess = preferences.getBoolean(KEY_CACHED_ALLOW_LAN_ACCESS, false),
        problemMessage = preferences.getString(KEY_CACHED_PROBLEM_MESSAGE, null),
        problemIsError = preferences.getBoolean(KEY_CACHED_PROBLEM_IS_ERROR, false),
    )
  }

  fun clearIdentityAndStatus(context: Context): Boolean {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val keysToRemove = preferences.all.keys.filter(::shouldClearTileCacheKey)
    if (keysToRemove.isEmpty()) return false
    preferences.edit { keysToRemove.forEach { remove(it) } }
    return true
  }

  fun writeSupplemental(
      context: Context,
      magicDnsName: String?,
      allowLanAccess: Boolean,
      problemMessage: String?,
      problemIsError: Boolean,
  ): Boolean {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val normalizedDns = magicDnsName?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() }
    val normalizedProblem =
        problemMessage
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_TILE_PROBLEM_CACHE_LENGTH)
            ?.takeIf { it.isNotBlank() }
    val changed =
        preferences.getString(KEY_CACHED_MAGIC_DNS_NAME, null) != normalizedDns ||
            preferences.getBoolean(KEY_CACHED_ALLOW_LAN_ACCESS, false) != allowLanAccess ||
            preferences.getString(KEY_CACHED_PROBLEM_MESSAGE, null) != normalizedProblem ||
            preferences.getBoolean(KEY_CACHED_PROBLEM_IS_ERROR, false) !=
                (normalizedProblem != null && problemIsError)
    if (!changed) return false
    preferences.edit {
      if (normalizedDns == null) remove(KEY_CACHED_MAGIC_DNS_NAME)
      else putString(KEY_CACHED_MAGIC_DNS_NAME, normalizedDns)
      putBoolean(KEY_CACHED_ALLOW_LAN_ACCESS, allowLanAccess)
      if (normalizedProblem == null) remove(KEY_CACHED_PROBLEM_MESSAGE)
      else putString(KEY_CACHED_PROBLEM_MESSAGE, normalizedProblem)
      putBoolean(KEY_CACHED_PROBLEM_IS_ERROR, normalizedProblem != null && problemIsError)
    }
    return true
  }

  fun readDisplayedAction(context: Context, tileId: String): TileToggleDecision {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val stored = preferences.getString(KEY_DISPLAYED_ACTION_PREFIX + tileId, null)
    return displayedActionFromStored(stored)
  }

  fun writeDisplayedAction(context: Context, tileId: String, action: TileToggleDecision) {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val key = KEY_DISPLAYED_ACTION_PREFIX + tileId
    if (preferences.getString(key, null) == action.name) return
    preferences.edit { putString(key, action.name) }
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
      tailscaleDnsEnabled: Boolean = false,
      magicDnsName: String? = null,
      allowLanAccess: Boolean = false,
  ): Boolean {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val signedOutState = ipnState == Ipn.State.NeedsLogin
    val normalizedIp = selfIp?.takeIf { it.isNotBlank() && !signedOutState }
    val normalizedExitNodeName = activeExitNodeName?.takeIf { it.isNotBlank() && !signedOutState }
    val normalizedDnsName =
        magicDnsName?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() && !signedOutState }
    val normalizedAllowLanAccess =
        !signedOutState && normalizedExitNodeName != null && allowLanAccess
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
            storedUnderlyingOnline != underlyingOnline ||
            preferences.getBoolean(KEY_CACHED_TAILSCALE_DNS_ENABLED, false) !=
                tailscaleDnsEnabled ||
            preferences.getString(KEY_CACHED_MAGIC_DNS_NAME, null) != normalizedDnsName ||
            preferences.getBoolean(KEY_CACHED_ALLOW_LAN_ACCESS, false) !=
                normalizedAllowLanAccess ||
            (signedOutState && preferences.all.keys.any { it in SUPPLEMENTAL_STATE_KEYS })
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
      putBoolean(KEY_CACHED_TAILSCALE_DNS_ENABLED, tailscaleDnsEnabled)
      if (normalizedDnsName == null) remove(KEY_CACHED_MAGIC_DNS_NAME)
      else putString(KEY_CACHED_MAGIC_DNS_NAME, normalizedDnsName)
      putBoolean(KEY_CACHED_ALLOW_LAN_ACCESS, normalizedAllowLanAccess)
      if (signedOutState) {
        SUPPLEMENTAL_STATE_KEYS.forEach { remove(it) }
      }
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
private const val KEY_TILE_SHOW_IP = "tile_show_ip"
private const val KEY_TILE_SHOW_EXIT_NODE = "tile_show_exit_node"
private const val KEY_TILE_SHOW_MAGIC_DNS = "tile_show_magic_dns"
private const val KEY_CACHED_IPN_STATE = "tile_cached_ipn_state"
private const val KEY_CACHED_VPN_ACTIVE = "tile_cached_vpn_active"
private const val KEY_CACHED_VPN_AUTHORIZED = "tile_cached_vpn_authorized"
private const val KEY_CACHED_ABLE_TO_START = "tile_cached_able_to_start"
private const val KEY_CACHED_SELF_IP = "tile_cached_self_ip"
private const val KEY_CACHED_ACTIVE_EXIT_NODE = "tile_cached_active_exit_node"
private const val KEY_CACHED_UNDERLYING_ONLINE = "tile_cached_underlying_online"
private const val KEY_CACHED_TAILSCALE_DNS_ENABLED = "tile_cached_tailscale_dns_enabled"
private const val KEY_CACHED_MAGIC_DNS_NAME = "tile_cached_magic_dns_name"
private const val KEY_CACHED_ALLOW_LAN_ACCESS = "tile_cached_allow_lan_access"
private const val KEY_CACHED_PROBLEM_MESSAGE = "tile_cached_problem_message"
private const val KEY_CACHED_PROBLEM_IS_ERROR = "tile_cached_problem_is_error"
private const val KEY_DISPLAYED_ACTION_PREFIX = "tile_displayed_action_"
private const val QUICK_ACTION_TOGGLE = "ToggleConnection"
private const val QUICK_ACTION_OPEN_APP = "OpenApp"
private const val MAX_TILE_PROBLEM_CACHE_LENGTH = 120
private const val MAX_TILE_PROBLEM_LENGTH = 36

private val SUPPLEMENTAL_STATE_KEYS =
    setOf(
        KEY_CACHED_MAGIC_DNS_NAME,
        KEY_CACHED_ALLOW_LAN_ACCESS,
        KEY_CACHED_PROBLEM_MESSAGE,
        KEY_CACHED_PROBLEM_IS_ERROR,
    )

private val CACHED_STATE_KEYS =
    setOf(
        KEY_CACHED_IPN_STATE,
        KEY_CACHED_VPN_ACTIVE,
        KEY_CACHED_VPN_AUTHORIZED,
        KEY_CACHED_ABLE_TO_START,
        KEY_CACHED_SELF_IP,
        KEY_CACHED_ACTIVE_EXIT_NODE,
        KEY_CACHED_UNDERLYING_ONLINE,
        KEY_CACHED_TAILSCALE_DNS_ENABLED,
    ) + SUPPLEMENTAL_STATE_KEYS

internal fun shouldClearTileCacheKey(key: String): Boolean = key in CACHED_STATE_KEYS

internal fun displayedActionFromStored(stored: String?): TileToggleDecision =
    TileToggleDecision.OpenApp

internal enum class TileTunnelStatus {
  Connected,
  Connecting,
  Disconnected,
}

internal enum class TileVisualStatus {
  Connected,
  Connecting,
  Off,
  Warning,
  Error,
}

internal enum class TileHumanStatus {
  Connected,
  Connecting,
  Disconnected,
  Attention,
  Error,
}

internal enum class TileButtonContent {
  PowerIcon,
}

/** Every state uses the same language-neutral glyph; color and adjacent text communicate status. */
internal fun resolveTileButtonContent(visualStatus: TileVisualStatus): TileButtonContent =
    when (visualStatus) {
      TileVisualStatus.Connected,
      TileVisualStatus.Connecting,
      TileVisualStatus.Off,
      TileVisualStatus.Warning,
      TileVisualStatus.Error -> TileButtonContent.PowerIcon
    }

internal fun resolveTileHumanStatus(visualStatus: TileVisualStatus): TileHumanStatus =
    when (visualStatus) {
      TileVisualStatus.Connected -> TileHumanStatus.Connected
      TileVisualStatus.Connecting -> TileHumanStatus.Connecting
      TileVisualStatus.Off -> TileHumanStatus.Disconnected
      TileVisualStatus.Warning -> TileHumanStatus.Attention
      TileVisualStatus.Error -> TileHumanStatus.Error
    }

internal fun resolveTileVisualStatus(
    tunnelStatus: TileTunnelStatus,
    underlyingOnline: Boolean?,
    setupRequired: Boolean,
    problemMessage: String?,
    problemIsError: Boolean,
): TileVisualStatus =
    when {
      !problemMessage.isNullOrBlank() && problemIsError -> TileVisualStatus.Error
      !problemMessage.isNullOrBlank() || underlyingOnline == false || setupRequired ->
          TileVisualStatus.Warning
      tunnelStatus == TileTunnelStatus.Connected -> TileVisualStatus.Connected
      tunnelStatus == TileTunnelStatus.Connecting -> TileVisualStatus.Connecting
      else -> TileVisualStatus.Off
    }

internal fun displayedTileAction(
    alwaysOpenApp: Boolean,
    visualStatus: TileVisualStatus,
    tunnelStatus: TileTunnelStatus,
): TileToggleDecision = TileToggleDecision.OpenApp

/**
 * The persisted desired state deliberately wins during transitions, so a Tile refresh immediately
 * reflects the user's latest tap instead of waiting for a TUN callback.
 */
internal fun resolveTileTunnelStatus(
    ipnState: Ipn.State,
    vpnActive: Boolean,
    desiredVpnEnabled: Boolean,
    vpnAuthorized: Boolean,
    ableToStartVpn: Boolean,
): TileTunnelStatus =
    when {
      !desiredVpnEnabled -> TileTunnelStatus.Disconnected
      ipnState == Ipn.State.Running && vpnActive -> TileTunnelStatus.Connected
      vpnAuthorized && ableToStartVpn -> TileTunnelStatus.Connecting
      else -> TileTunnelStatus.Disconnected
    }

internal data class TileNetworkRows(val ip: String?, val magicDns: String?)

internal data class TileExitRows(val exitNode: String?, val lan: String?)

internal fun prioritizeTileDetailRows(
    priorityText: String?,
    networkRows: TileNetworkRows,
    exitRows: TileExitRows,
    readyText: String,
    maximumNormalRows: Int = 3,
): List<String> {
  priorityText
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return listOf(it)
      }
  val maximumRows = maximumNormalRows.coerceAtLeast(1)
  val network = listOfNotNull(networkRows.ip, networkRows.magicDns)
  val exit = listOfNotNull(exitRows.exitNode, exitRows.lan)
  val allRows = network + exit
  if (allRows.isEmpty()) return listOf(readyText)
  if (allRows.size <= maximumRows) return allRows

  // LAN is meaningful only while an exit node is active. Reserve the exit and LAN rows as a pair
  // before filling the remaining budget with network identity, so a long exit-node name can never
  // hide the LAN state through text ellipsizing or row truncation.
  if (exitRows.lan != null) {
    if (maximumRows == 1) return listOfNotNull(exitRows.exitNode ?: exitRows.lan)
    val reservedExitRows = listOfNotNull(exitRows.exitNode, exitRows.lan)
    return network.take((maximumRows - reservedExitRows.size).coerceAtLeast(0)) +
        reservedExitRows.take(maximumRows)
  }
  return allRows.take(maximumRows)
}

internal fun buildTileNetworkRows(
    selfIp: String?,
    tailscaleDnsEnabled: Boolean,
    magicDnsName: String?,
    showIp: Boolean,
    showMagicDns: Boolean,
    ipLabel: String = "IP",
    dnsLabel: String = "DNS",
    onLabel: String = "ON",
    offLabel: String = "OFF",
    valueFormatter: (String) -> String = { it },
): TileNetworkRows =
    TileNetworkRows(
        ip =
            buildTileIpRow(
                selfIp = selfIp,
                showIp = showIp,
                ipLabel = ipLabel,
                valueFormatter = valueFormatter,
            ),
        magicDns =
            buildTileMagicDnsRow(
                tailscaleDnsEnabled = tailscaleDnsEnabled,
                magicDnsName = magicDnsName,
                showMagicDns = showMagicDns,
                dnsLabel = dnsLabel,
                onLabel = onLabel,
                offLabel = offLabel,
                valueFormatter = valueFormatter,
            ),
    )

internal fun buildTileIpRow(
    selfIp: String?,
    showIp: Boolean,
    ipLabel: String = "IP",
    valueFormatter: (String) -> String = { it },
): String? =
    if (showIp) {
      selfIp
          ?.takeIf { it.isNotBlank() }
          ?.let(::compactTileIpRaw)
          ?.let(valueFormatter)
          ?.let { "$ipLabel $it" }
    } else {
      null
    }

internal fun buildTileMagicDnsRow(
    tailscaleDnsEnabled: Boolean,
    magicDnsName: String?,
    showMagicDns: Boolean,
    dnsLabel: String = "DNS",
    onLabel: String = "ON",
    offLabel: String = "OFF",
    valueFormatter: (String) -> String = { it },
): String? {
  if (!showMagicDns) return null
  val value =
      if (tailscaleDnsEnabled) {
        magicDnsName
            ?.takeIf { it.isNotBlank() }
            ?.let(::compactTileDnsRaw)
            ?.takeIf { it.isNotBlank() }
            ?.let(valueFormatter) ?: onLabel
      } else {
        offLabel
      }
  return "$dnsLabel $value"
}

internal fun buildTileExitRows(
    activeExitNodeName: String?,
    allowLanAccess: Boolean,
    showExitNode: Boolean,
    exitLabel: String = "Exit",
    offLabel: String,
    lanLabel: String,
    onLabel: String,
    offStateLabel: String,
    valueFormatter: (String) -> String = { it },
): TileExitRows {
  if (!showExitNode) return TileExitRows(exitNode = null, lan = null)
  val exitNode =
      activeExitNodeName?.takeIf { it.isNotBlank() }?.let(::compactTileExitRaw)?.let(valueFormatter)
          ?: return TileExitRows(exitNode = "$exitLabel: $offLabel", lan = null)
  return TileExitRows(
      exitNode = "$exitLabel: $exitNode",
      lan = "$lanLabel ${if (allowLanAccess) onLabel else offStateLabel}",
  )
}

internal fun compactTileIpRaw(value: String): String = compactTileValue(value, 20)

internal fun compactTileDnsRaw(value: String): String =
    compactTileValue(value.trim().trimEnd('.').substringBefore('.'), 14)

internal fun compactTileExitRaw(value: String): String = compactTileValue(value, 14)

internal fun compactTileValue(value: String, maxLength: Int): String {
  require(maxLength >= 2) { "Tile values need room for content and an ellipsis." }
  val normalized = value.replace(Regex("\\s+"), " ").trim()
  if (normalized.length <= maxLength) return normalized
  return normalized.take(maxLength - 1).trimEnd() + "…"
}

internal fun shortTileProblem(message: String): String {
  val normalized = message.replace(Regex("\\s+"), " ").trim()
  if (normalized.length <= MAX_TILE_PROBLEM_LENGTH) return normalized
  return normalized.take(MAX_TILE_PROBLEM_LENGTH - 1).trimEnd() + "…"
}

internal enum class TileToggleDecision {
  Start,
  Stop,
  OpenApp,
}

/**
 * Resolves a Tile tap from the user's persisted intent, not from a lagging TUN callback.
 *
 * An explicit ON choice can always be switched OFF, including during a transition. A new start is
 * accepted only after account and VPN setup have completed.
 */
internal fun tileToggleDecision(
    desiredVpnEnabled: Boolean,
    vpnAuthorized: Boolean,
    ableToStartVpn: Boolean,
): TileToggleDecision = TileToggleDecision.OpenApp
