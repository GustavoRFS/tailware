// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import com.tailscale.ipn.App
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.ui.model.Empty
import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Ipn.Notify
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.NodeID
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

// When set to true, the Notifier will inject fake health warnings for testing purposes
val INJECT_FAKE_HEALTH_WARNINGS = false

data class AuthenticationEvent(
    val sequence: Long,
    val epoch: Long,
    val browseToURL: String? = null,
    val loginFinished: Boolean = false,
    val errMessage: String? = null,
    val state: Ipn.State? = null,
    val hasIdentityMutation: Boolean = false,
    val bootstrap: Boolean = false,
)

internal class LatestNotifierWatchCoordinator {
  private var generation = 0L

  @Synchronized fun replace(): Long = ++generation

  @Synchronized fun isCurrent(candidate: Long): Boolean = candidate == generation

  @Synchronized
  fun runIfCurrent(candidate: Long, action: () -> Unit): Boolean {
    if (candidate != generation) return false
    action()
    return true
  }
}

internal class AuthenticationEpochCoordinator {
  var newestEpoch: Long = 0L
    private set

  var retiredEpoch: Long = 0L
    private set

  var lastEvent: AuthenticationEvent? = null
    private set

  private var adoptedEpoch: Long = 0L
  private val automaticRetirements = mutableSetOf<Long>()

  fun reset() {
    newestEpoch = 0L
    retiredEpoch = 0L
    lastEvent = null
    adoptedEpoch = 0L
    automaticRetirements.clear()
  }

  fun clearEvent() {
    lastEvent = null
  }

  /** Returns whether a Notify may mutate any shared Android notifier state. */
  fun acceptNotification(epoch: Long): Boolean {
    if (epoch == 0L) {
      // During an authentication epoch, current native callbacks are stamped with that epoch. A
      // zero belongs to a callback that entered before the boundary and must not overwrite the
      // replacement watcher's state after a bounded native callback fence expired.
      return newestEpoch <= retiredEpoch
    }
    if (epoch <= retiredEpoch || epoch < newestEpoch) return false
    if (epoch > newestEpoch) newestEpoch = epoch
    return true
  }

  /** Returns true when registration invalidated an older replay event. */
  fun register(epoch: Long): Boolean {
    if (epoch <= 0L ||
        epoch <= retiredEpoch ||
        epoch < newestEpoch ||
        epoch in automaticRetirements) {
      return false
    }
    if (epoch > newestEpoch) newestEpoch = epoch
    adoptedEpoch = epoch
    if (lastEvent?.epoch == epoch) return false
    lastEvent = null
    return true
  }

  fun record(event: AuthenticationEvent) {
    lastEvent = event
  }

  /** Returns true when retiring this epoch cleared its replay event. */
  fun retire(epoch: Long): Boolean {
    automaticRetirements.remove(epoch)
    retiredEpoch = maxOf(retiredEpoch, epoch)
    if (adoptedEpoch == epoch) adoptedEpoch = 0L
    if (lastEvent?.epoch != epoch) return false
    lastEvent = null
    return true
  }

  fun isAdopted(epoch: Long): Boolean = adoptedEpoch == epoch

  fun hasNewerEpoch(epoch: Long): Boolean = newestEpoch > epoch

  /** Claims an epoch that has no existing Android owner. */
  fun claimUnadopted(epoch: Long): Boolean {
    if (isAdopted(epoch)) return false
    register(epoch)
    return isAdopted(epoch)
  }

  fun beginAutomaticRetirement(epoch: Long): Boolean {
    if (epoch <= retiredEpoch || epoch != newestEpoch || isAdopted(epoch)) return false
    return automaticRetirements.add(epoch)
  }

  /** Returns true when a successful native retirement cleared the replay event. */
  fun completeAutomaticRetirement(epoch: Long, nativeRetired: Boolean): Boolean {
    if (!automaticRetirements.remove(epoch) || !nativeRetired) return false
    return retire(epoch)
  }
}

internal fun shouldAutomaticallyRetireAuthentication(event: AuthenticationEvent): Boolean =
    !event.bootstrap &&
        (event.loginFinished ||
            event.errMessage != null ||
            (event.state != null && event.state > Ipn.State.NeedsMachineAuth))

// Notifier is a wrapper around the IPN Bus notifier.  It provides a way to watch
// for changes in various parts of the Tailscale engine.  You will typically only use
// a single Notifier per instance of your application which lasts for the lifetime of
// the process.
//
// The primary entry point here is watchIPNBus which will start a watcher on the IPN bus
// and return you the session Id.  When you are done with your watcher, you must call
// unwatchIPNBus with the sessionId.
object Notifier {
  private val TAG = Notifier::class.simpleName
  private val decoder = Json { ignoreUnknownKeys = true }

  // General IPN Bus State
  private val _state = MutableStateFlow(Ipn.State.NoState)
  val state: StateFlow<Ipn.State> = _state
  private val _netmap = MutableStateFlow<Netmap.NetworkMap?>(null)
  val netmap: StateFlow<Netmap.NetworkMap?> = _netmap
  val prefs: StateFlow<Ipn.Prefs?> = MutableStateFlow(null)
  val engineStatus: StateFlow<Ipn.EngineStatus?> = MutableStateFlow(null)
  val tailFSShares: StateFlow<Map<String, String>?> = MutableStateFlow(null)
  val browseToURL: StateFlow<String?> = MutableStateFlow(null)
  val loginFinished: StateFlow<String?> = MutableStateFlow(null)
  val errMessage: StateFlow<String?> = MutableStateFlow(null)
  private val _authenticationEvent =
      MutableSharedFlow<AuthenticationEvent?>(
          replay = 1,
          extraBufferCapacity = 32,
          onBufferOverflow = BufferOverflow.DROP_OLDEST,
      )
  val authenticationEvent: SharedFlow<AuthenticationEvent?> = _authenticationEvent
  val version: StateFlow<String?> = MutableStateFlow(null)
  val health: StateFlow<Health.State?> = MutableStateFlow(null)

  // Taildrop-specific State
  val outgoingFiles: StateFlow<List<Ipn.OutgoingFile>?> = MutableStateFlow(null)
  val incomingFiles: StateFlow<List<Ipn.PartialFile>?> = MutableStateFlow(null)
  val filesWaiting: StateFlow<Empty.Message?> = MutableStateFlow(null)

  private val userProfiles = mutableMapOf<String, Tailcfg.UserProfile>()

  private lateinit var app: libtailscale.Application
  private var manager: libtailscale.NotificationManager? = null
  private var notificationScope: CoroutineScope? = null
  private val watchCoordinator = LatestNotifierWatchCoordinator()
  private val authenticationEpochs = AuthenticationEpochCoordinator()
  private var authenticationEventSequence = 0L

  @Synchronized
  @JvmStatic
  fun setApp(newApp: libtailscale.Application) {
    watchCoordinator.replace()
    manager?.stop()
    manager = null
    app = newApp
    authenticationEpochs.reset()
    _authenticationEvent.tryEmit(null)
  }

  @Synchronized
  @OptIn(ExperimentalSerializationApi::class)
  fun start(scope: CoroutineScope) {
    TSLog.d(TAG, "Starting Notifier")
    if (!::app.isInitialized) {
      App.get()
    }
    val generation = watchCoordinator.replace()
    notificationScope = scope
    manager?.stop()
    manager = null
    resetPublishedState()
    val watchedApp = app
    scope.launch(Dispatchers.IO) {
      val mask =
          NotifyWatchOpt.Prefs.value or
              NotifyWatchOpt.InitialState.value or
              NotifyWatchOpt.PeerChanges.value or
              NotifyWatchOpt.NoNetmap.value or
              NotifyWatchOpt.InitialStatus.value or
              NotifyWatchOpt.InitialHealthState.value
      val candidate =
          watchedApp.watchNotifications(mask.toLong()) { notification ->
            runCatching {
                  val notify = decoder.decodeFromStream<Notify>(notification.inputStream())
                  synchronized(this@Notifier) {
                    watchCoordinator.runIfCurrent(generation) {
                      if (!authenticationEpochs.acceptNotification(notify.TailwareAuthEpoch)) {
                        return@runIfCurrent
                      }
                      notify.State?.let { state.set(Ipn.State.fromInt(it)) }
                      if (BuildConfig.DEBUG) {
                        notify.InitialStatus?.let {
                          TSLog.d(
                              TAG,
                              "received initial status: peers=${it.Peer?.size ?: 0}, users=${it.User?.size ?: 0}")
                        }
                        val peersChanged = notify.PeersChanged?.size ?: 0
                        val peersRemoved = notify.PeersRemoved?.size ?: 0
                        val users = notify.UserProfiles?.size ?: 0
                        if (notify.SelfChange != null ||
                            peersChanged > 0 ||
                            peersRemoved > 0 ||
                            users > 0) {
                          TSLog.d(
                              TAG,
                              "received bus update: self=${notify.SelfChange != null}, peersChanged=$peersChanged, peersRemoved=$peersRemoved, users=$users")
                        }
                      }
                      val signedOutBoundary =
                          notify.Prefs?.LoggedOut == true ||
                              notify.State == Ipn.State.NeedsLogin.value
                      if (signedOutBoundary) {
                        clearIdentityAndStatus()
                      } else {
                        updateNetworkMap(notify)
                      }
                      if (!signedOutBoundary) {
                        notify.Prefs?.let(prefs::set)
                      }
                      notify.Engine?.let(engineStatus::set)
                      notify.TailFSShares?.let(tailFSShares::set)
                      notify.BrowseToURL?.let(browseToURL::set)
                      notify.LoginFinished?.let { loginFinished.set(it.property) }
                      notify.ErrMessage?.let(errMessage::set)
                      notify.Version?.let(version::set)
                      notify.OutgoingFiles?.let(outgoingFiles::set)
                      notify.FilesWaiting?.let(filesWaiting::set)
                      notify.IncomingFiles?.let(incomingFiles::set)
                      notify.Health?.let {
                        if (INJECT_FAKE_HEALTH_WARNINGS) {
                          injectFakeHealthState()
                        } else {
                          health.set(it)
                        }
                      }
                      publishAuthenticationEvent(notify)
                    }
                  }
                }
                .onFailure { TSLog.e(TAG, "failed to process IPN notification", it) }
          }
      synchronized(this@Notifier) {
        if (watchCoordinator.isCurrent(generation)) {
          manager = candidate
        } else {
          candidate.stop()
        }
      }
    }
  }

  @Synchronized
  fun stop() {
    TSLog.d(TAG, "Stopping Notifier")
    watchCoordinator.replace()
    notificationScope = null
    manager?.let {
      it.stop()
      manager = null
    }
    resetPublishedState()
  }

  // NotifyWatchOpt is a bitmask of options supplied to the notifier to specify which
  // what we want to see on the Notify bus
  private enum class NotifyWatchOpt(val value: Int) {
    EngineUpdates(1),
    InitialState(2),
    Prefs(4),
    NoPrivateKey(16),
    InitialTailFSShares(32),
    InitialOutgoingFiles(64),
    InitialHealthState(128),
    PeerChanges(4096),
    NoNetmap(8192),
    InitialStatus(16384),
  }

  fun setState(newState: Ipn.State) {
    _state.value = newState
  }

  @Synchronized
  fun beginAuthentication(application: libtailscale.Application = app): Long {
    // Hold the same monitor used by notification publication across the native watcher boundary.
    // A callback that outlives the bounded native fence cannot mutate Kotlin state until the new
    // epoch is registered below.
    val epoch = application.beginAuthentication()
    registerAuthenticationEpoch(epoch)
    return epoch
  }

  /**
   * Begins and publishes Android ownership under the notification/epoch monitor as one boundary. A
   * backend or second Android begin therefore cannot overtake the caller between native
   * registration and its owner field becoming visible.
   */
  @Synchronized
  fun beginAuthenticationWithOwner(
      application: libtailscale.Application = app,
      publishOwner: (Long) -> Boolean,
  ): Long? {
    val epoch = application.beginAuthentication()
    registerAuthenticationEpoch(epoch)
    if (epoch == 0L) return 0L
    val accepted =
        try {
          publishOwner(epoch)
        } catch (error: Throwable) {
          if (application.finishAuthentication(epoch)) clearAuthenticationEvent(epoch)
          throw error
        }
    if (accepted) return epoch
    if (application.finishAuthentication(epoch)) clearAuthenticationEvent(epoch)
    return null
  }

  @Synchronized
  fun finishAuthentication(
      epoch: Long,
      application: libtailscale.Application = app,
  ): Boolean {
    val finished = application.finishAuthentication(epoch)
    if (finished) clearAuthenticationEvent(epoch)
    return finished
  }

  @Synchronized
  fun cancelAuthentication(
      epoch: Long,
      application: libtailscale.Application = app,
  ): Boolean {
    val cancelled = application.cancelInteractiveLogin(epoch)
    if (cancelled) clearAuthenticationEvent(epoch)
    return cancelled
  }

  /**
   * Claims a native epoch that LocalBackend auto-created for backend-initiated reauthentication.
   */
  @Synchronized
  fun adoptAuthenticationEpoch(epoch: Long): Boolean {
    return authenticationEpochs.claimUnadopted(epoch)
  }

  @Synchronized
  fun runIfNoNewerAuthenticationEpoch(epoch: Long, action: () -> Unit): Boolean {
    if (authenticationEpochs.hasNewerEpoch(epoch)) return false
    action()
    return true
  }

  @Synchronized
  fun hasNewerAuthenticationEpoch(epoch: Long): Boolean = authenticationEpochs.hasNewerEpoch(epoch)

  /** Latest epoch already observed by Android; zero is the valid pre-authentication baseline. */
  @Synchronized fun authenticationMutationSequence(): Long = authenticationEpochs.newestEpoch

  @Synchronized
  private fun clearIdentityAndStatus() {
    userProfiles.clear()
    _netmap.set(null)
    prefs.set(null)
    engineStatus.set(null)
    tailFSShares.set(null)
    health.set(null)
    browseToURL.set(null)
    loginFinished.set(null)
  }

  @Synchronized
  private fun resetPublishedState() {
    clearIdentityAndStatus()
    _state.set(Ipn.State.NoState)
    prefs.set(null)
    browseToURL.set(null)
    loginFinished.set(null)
    errMessage.set(null)
    version.set(null)
    outgoingFiles.set(null)
    incomingFiles.set(null)
    filesWaiting.set(null)
    authenticationEpochs.clearEvent()
    _authenticationEvent.tryEmit(null)
  }

  @Synchronized
  private fun publishAuthenticationEvent(notify: Notify) {
    val epoch = notify.TailwareAuthEpoch
    if (epoch == 0L) return
    val state = notify.State?.let(Ipn.State::fromInt)
    val hasIdentityMutation =
        notify.Prefs != null ||
            notify.SelfChange != null ||
            notify.InitialStatus != null ||
            !notify.UserProfiles.isNullOrEmpty()
    if (notify.BrowseToURL == null &&
        notify.LoginFinished == null &&
        notify.ErrMessage == null &&
        state == null &&
        !hasIdentityMutation) {
      return
    }
    val event =
        AuthenticationEvent(
            sequence = ++authenticationEventSequence,
            epoch = epoch,
            browseToURL = notify.BrowseToURL,
            loginFinished = notify.LoginFinished != null,
            errMessage = notify.ErrMessage,
            state = state,
            hasIdentityMutation = hasIdentityMutation,
            bootstrap = notify.TailwareAuthBootstrap,
        )
    authenticationEpochs.record(event)
    _authenticationEvent.tryEmit(event)
    val retirementScope = notificationScope
    if (retirementScope != null &&
        shouldAutomaticallyRetireAuthentication(event) &&
        authenticationEpochs.beginAutomaticRetirement(epoch)) {
      // Standard Android screens do not maintain TailwareViewModel's richer reauthentication
      // owner. Retire a backend-auto-created epoch after its terminal event so it cannot remain
      // active until an unrelated later Begin disconnects control. Registration is blocked while
      // the native boundary is pending, but callbacks remain accepted until native confirms that
      // it retired the owner.
      val nativeApp = app
      retirementScope.launch(Dispatchers.IO) {
        val nativeRetired =
            if (event.errMessage != null) {
              nativeApp.cancelInteractiveLogin(epoch)
            } else {
              nativeApp.finishAuthentication(epoch)
            }
        completeAutomaticAuthenticationRetirement(epoch, nativeRetired)
      }
    }
  }

  @Synchronized
  private fun completeAutomaticAuthenticationRetirement(epoch: Long, nativeRetired: Boolean) {
    val cleared = authenticationEpochs.completeAutomaticRetirement(epoch, nativeRetired)
    if (nativeRetired) {
      if (cleared) _authenticationEvent.tryEmit(null)
      return
    }
    // Native still owns this epoch. Replay only the still-current event so a delayed Android owner
    // gets another chance to adopt it; a newer epoch must never receive the stale replay.
    authenticationEpochs.lastEvent
        ?.takeIf { it.epoch == epoch && authenticationEpochs.newestEpoch == epoch }
        ?.let(_authenticationEvent::tryEmit)
  }

  @Synchronized
  private fun clearAuthenticationEvent(epoch: Long) {
    if (authenticationEpochs.retire(epoch)) {
      _authenticationEvent.tryEmit(null)
    }
  }

  @Synchronized
  private fun registerAuthenticationEpoch(epoch: Long) {
    if (authenticationEpochs.register(epoch)) _authenticationEvent.tryEmit(null)
  }

  @Synchronized
  private fun updateNetworkMap(notify: Notify) {
    val initial = notify.InitialStatus
    if (initial != null) {
      userProfiles.clear()
      initial.User?.let { userProfiles.putAll(it) }
    }

    val cur = _netmap.value
    val self = notify.SelfChange
    var next = initial?.toNetworkMapOrNull(userProfiles.toMap()) ?: cur
    if (next == null && self != null) {
      // A fresh/signed-out watcher can receive an empty InitialStatus, followed
      // later by SelfChange and peer deltas after the first successful login.
      // Bootstrap from that self node instead of dropping the delta forever.
      if (initial == null) {
        userProfiles.clear()
      }
      next = bootstrapNetworkMapFromSelfChange(next, self)
    }

    if (initial != null && next == null) {
      _netmap.set(null)
      if (BuildConfig.DEBUG) {
        TSLog.d(TAG, "received empty initial status; cleared network map")
      }
      return
    }

    if (next == null) {
      return
    }

    if (initial == null && self != null && !next.isSameSelf(self)) {
      // A self node change is a profile boundary. It resets the implicit bus
      // state, so the publisher must resend any UserProfiles referenced by the
      // new profile. Clear before applying this Notify's UserProfiles below so
      // profiles included in the same update are retained.
      userProfiles.clear()
      next = self.toEmptyNetworkMap()
      if (BuildConfig.DEBUG) {
        TSLog.d(
            TAG,
            "received self change for a different node; cleared peers and users before applying deltas")
      }
    }

    notify.UserProfiles?.let { userProfiles.putAll(it) }

    next = next.copy(UserProfiles = userProfiles.toMap())
    if (self != null) {
      next =
          next.copy(
              SelfNode = self.withDisplayNames(next.magicDNSSuffix()),
              AllCaps = self.capabilities())
    }
    val removed = notify.PeersRemoved
    if (!removed.isNullOrEmpty()) {
      val removedIDs = removed.toSet()
      next = next.copy(Peers = next.Peers?.filterNot { removedIDs.contains(it.ID) })
    }
    val changed = notify.PeersChanged
    if (!changed.isNullOrEmpty()) {
      val suffix = next.magicDNSSuffix()
      next = next.copy(Peers = mergePeers(next.Peers, changed.map { it.withDisplayNames(suffix) }))
    }
    if (next != cur) {
      _netmap.set(next)
    }
    if (initial != null) {
      if (BuildConfig.DEBUG) {
        TSLog.d(
            TAG,
            "updated network map from initial status: peers=${next.Peers?.size ?: 0}, users=${next.UserProfiles.size}, selfUser=${next.SelfNode.User}")
      }
    }
  }

  internal fun bootstrapNetworkMapFromSelfChange(
      current: Netmap.NetworkMap?,
      selfChange: Tailcfg.Node?,
  ): Netmap.NetworkMap? = current ?: selfChange?.toEmptyNetworkMap()

  private fun mergePeers(
      peers: List<Tailcfg.Node>?,
      changed: List<Tailcfg.Node>
  ): List<Tailcfg.Node> {
    val byID = linkedMapOf<NodeID, Tailcfg.Node>()
    peers.orEmpty().forEach { byID[it.ID] = it }
    changed.forEach { byID[it.ID] = it }
    return byID.values.toList()
  }

  private fun IpnState.Status.toNetworkMapOrNull(
      profiles: Map<String, Tailcfg.UserProfile>
  ): Netmap.NetworkMap? {
    val self = Self?.toNode(this) ?: return null
    return Netmap.NetworkMap(
        SelfNode = self,
        Peers = Peer?.values?.map { it.toNode(this) }.orEmpty(),
        Domain = CurrentTailnet?.Name.orEmpty(),
        UserProfiles = profiles,
        TKAEnabled = false,
        DNS = null,
        AllCaps = self.capabilities())
  }

  private fun Tailcfg.Node.capabilities() = CapMap?.keys?.toList() ?: Capabilities.orEmpty()

  private fun Tailcfg.Node.toEmptyNetworkMap(): Netmap.NetworkMap {
    val suffix = magicDNSSuffix()
    val self = withDisplayNames(suffix)
    return Netmap.NetworkMap(
        SelfNode = self,
        Peers = emptyList(),
        Domain = suffix,
        UserProfiles = emptyMap(),
        TKAEnabled = false,
        DNS = null,
        AllCaps = self.capabilities())
  }

  private fun IpnState.PeerStatus.toNode(status: IpnState.Status): Tailcfg.Node {
    val computedName = displayName(status)
    val computedNameWithHost =
        if (HostName.isNotEmpty() && !computedName.equals(HostName, ignoreCase = true)) {
          "$computedName ($HostName)"
        } else {
          computedName
        }
    val allowedIPs =
        AllowedIPs
            ?: buildList {
              TailscaleIPs?.let { addAll(it) }
              PrimaryRoutes?.let { addAll(it) }
              if (ExitNodeOption) {
                add("0.0.0.0/0")
                add("::/0")
              }
            }
    return Tailcfg.Node(
        ID = NodeID,
        StableID = ID,
        Name = DNSName.ifEmpty { HostName },
        User = UserID,
        Sharer = AltSharerUserID.takeIf { it != 0L },
        Key = PublicKey,
        KeyExpiry = KeyExpiry,
        Addresses = TailscaleIPs,
        AllowedIPs = allowedIPs,
        Hostinfo =
            Tailcfg.Hostinfo(
                OS = OS, Hostname = HostName, ShareeNode = ShareeNode, Location = Location),
        LastSeen = LastSeen,
        Online = Online,
        Capabilities = Capabilities,
        CapMap = CapMap,
        ComputedName = computedName,
        ComputedNameWithHost = computedNameWithHost)
  }

  private fun IpnState.PeerStatus.displayName(status: IpnState.Status): String {
    val computed = computedName(status)
    return computed.ifEmpty { HostName.ifEmpty { PublicKey } }
  }

  // isSameSelf reports whether self can be applied as a mutation to this
  // synthetic NetworkMap. This is intentionally a boundary check, not a full
  // node equality check: if any stable self identity field differs, a WatchIPNBus
  // delta stream has crossed to a different profile/tailnet and the peer set must
  // be rebuilt from that stream instead of merged with stale peers.
  private fun Netmap.NetworkMap.isSameSelf(self: Tailcfg.Node): Boolean {
    if (SelfNode.ID != 0L && self.ID != 0L && SelfNode.ID != self.ID) {
      return false
    }
    if (SelfNode.StableID.isNotEmpty() &&
        self.StableID.isNotEmpty() &&
        SelfNode.StableID != self.StableID) {
      return false
    }
    if (SelfNode.User != 0L && self.User != 0L && SelfNode.User != self.User) {
      return false
    }
    return true
  }

  private fun Netmap.NetworkMap.magicDNSSuffix(): String = SelfNode.magicDNSSuffix()

  private fun Tailcfg.Node.magicDNSSuffix(): String {
    val name = Name.trim('.')
    val suffix = name.substringAfter('.', missingDelimiterValue = name)
    return suffix.trim('.')
  }

  private fun Tailcfg.Node.withDisplayNames(magicDNSSuffix: String): Tailcfg.Node {
    if (!ComputedName.isNullOrEmpty() && !ComputedNameWithHost.isNullOrEmpty()) {
      return this
    }
    val name = Name.trim('.').removeSuffix(".$magicDNSSuffix")
    var hostIfDifferent = Hostinfo.Hostname.orEmpty()
    if (name.equals(hostIfDifferent, ignoreCase = true)) {
      hostIfDifferent = ""
    }
    val computedName =
        when {
          name.isNotEmpty() -> name
          hostIfDifferent.isNotEmpty() -> {
            val host = hostIfDifferent
            hostIfDifferent = ""
            host
          }
          else -> Key
        }
    val computedNameWithHost =
        if (hostIfDifferent.isNotEmpty()) "$computedName ($hostIfDifferent)" else computedName
    return copy(ComputedName = computedName, ComputedNameWithHost = computedNameWithHost)
  }
}
