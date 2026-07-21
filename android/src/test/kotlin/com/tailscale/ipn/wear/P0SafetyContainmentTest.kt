// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.content.Context
import android.content.SharedPreferences
import android.net.VpnService
import com.tailscale.ipn.IPNService
import com.tailscale.ipn.RejectedServiceStartDecision
import com.tailscale.ipn.ServiceEntryAuthorization
import com.tailscale.ipn.VPNServiceBuilder
import com.tailscale.ipn.rejectedServiceStartDecision
import com.tailscale.ipn.serviceEntryAuthorization
import com.tailscale.ipn.ui.localapi.LocalApiRequestSupersededException
import com.tailscale.ipn.ui.localapi.executeAuthorizedLocalApiOperation
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class P0SafetyContainmentTest {
  @Before
  fun resetProcessAuthority() {
    TailwareActivationCapability.resetForTests()
    TailwareVpnStateStore.resetP0ContainmentForTests()
  }

  @After
  fun clearProcessAuthority() {
    TailwareActivationCapability.resetForTests()
    TailwareVpnStateStore.resetP0ContainmentForTests()
  }

  @Test
  fun private5DesiredOnIsSynchronouslyMigratedToOff() {
    assertEquals(
        P0ContainmentMigrationPlan(
            safeState = DesiredVpnState(enabled = false, generation = 42L),
            writeRequired = true,
        ),
        p0ContainmentMigrationPlan(
            currentState = DesiredVpnState(enabled = true, generation = 41L),
            recordedVersion = 0,
        ),
    )
    assertEquals(
        P0ContainmentMigrationPlan(
            safeState = DesiredVpnState(enabled = false, generation = 42L),
            writeRequired = true,
        ),
        p0ContainmentMigrationPlan(
            currentState = DesiredVpnState(enabled = false, generation = 41L),
            recordedVersion = 0,
        ),
    )
  }

  @Test
  fun currentMigrationIsIdempotentButAnyRecreatedOnIsCleared() {
    assertEquals(
        P0ContainmentMigrationPlan(
            safeState = DesiredVpnState(enabled = false, generation = 9L),
            writeRequired = false,
        ),
        p0ContainmentMigrationPlan(
            currentState = DesiredVpnState(enabled = false, generation = 9L),
            recordedVersion = TailwareVpnStateStore.P0_CONTAINMENT_MIGRATION_VERSION,
        ),
    )
    assertEquals(
        DesiredVpnState(enabled = false, generation = 10L),
        p0ContainmentMigrationPlan(
                currentState = DesiredVpnState(enabled = true, generation = 9L),
                recordedVersion = TailwareVpnStateStore.P0_CONTAINMENT_MIGRATION_VERSION,
            )
            .safeState,
    )
  }

  @Test
  fun failedContainmentCommitIssuesNoEngineCapability() {
    val fixture =
        preferencesFixture(
            desiredEnabled = true,
            desiredGeneration = 8L,
            migrationVersion = 0,
            commitResult = false,
        )

    assertEquals(
        TailwareActivationCapability.NO_CAPABILITY,
        TailwareActivationCapability.grantEngineFromUserGesture(fixture.context),
    )
    assertFalse(TailwareActivationCapability.hasEngineCapability())
    verify(fixture.editor).commit()
  }

  @Test
  fun engineLoginAndVpnAuthoritiesAreSeparateAndProcessLocal() {
    val fixture = preferencesFixture()
    val engine = TailwareActivationCapability.grantEngineFromUserGesture(fixture.context)
    assertTrue(engine != TailwareActivationCapability.NO_CAPABILITY)
    assertTrue(TailwareActivationCapability.hasEngineCapability())
    assertFalse(TailwareActivationCapability.hasLoginCapability())
    assertFalse(TailwareActivationCapability.hasVpnCapability())

    val login = TailwareActivationCapability.grantLoginFromUserGesture(fixture.context)
    assertTrue(TailwareActivationCapability.acceptsLoginCapability(login))
    assertFalse(TailwareActivationCapability.hasVpnCapability())
    assertTrue(TailwareActivationCapability.bindLoginRequest(100L, login))
    assertTrue(TailwareActivationCapability.acceptsLoginRequest(100L))
    assertFalse(TailwareActivationCapability.bindLoginRequest(101L, login + 1L))

    val vpn = TailwareActivationCapability.grantVpnFromUserGesture(fixture.context)
    assertTrue(TailwareActivationCapability.acceptsVpnCapability(vpn))
    assertTrue(TailwareActivationCapability.bindVpnCommand(12L, vpn))
    assertTrue(TailwareActivationCapability.acceptsVpnCommand(12L))
    assertFalse(TailwareActivationCapability.bindVpnCommand(13L, vpn + 1L))

    TailwareActivationCapability.resetForTests()
    assertFalse(TailwareActivationCapability.hasEngineCapability())
    assertFalse(TailwareActivationCapability.acceptsLoginRequest(100L))
    assertFalse(TailwareActivationCapability.acceptsVpnCommand(12L))
  }

  @Test
  fun staleStopCannotRevokeANewerConnectOrGeneration() {
    val fixture = preferencesFixture()
    val first = TailwareActivationCapability.grantVpnFromUserGesture(fixture.context)
    assertTrue(TailwareActivationCapability.bindVpnCommand(20L, first))
    TailwareActivationCapability.revokeVpnCapability(first)

    val second = TailwareActivationCapability.grantVpnFromUserGesture(fixture.context)
    assertTrue(TailwareActivationCapability.bindVpnCommand(22L, second))
    TailwareActivationCapability.revokeVpnCapability(first)

    assertTrue(TailwareActivationCapability.acceptsVpnCapability(second))
    assertFalse(TailwareActivationCapability.acceptsVpnCommand(20L))
    assertTrue(TailwareActivationCapability.acceptsVpnCommand(22L))
  }

  @Test
  fun serviceFrontDoorRejectsColdStickyForgedAndWrongCapabilityStarts() {
    assertEquals(
        ServiceEntryAuthorization.RejectPassive,
        serviceEntryAuthorization(
            action = null,
            hasEngineCapability = false,
            hasBoundLoginRequest = false,
            hasBoundVpnCommand = false,
        ),
    )
    assertEquals(
        ServiceEntryAuthorization.RejectPassive,
        serviceEntryAuthorization(
            action = "android.net.VpnService",
            hasEngineCapability = false,
            hasBoundLoginRequest = false,
            hasBoundVpnCommand = false,
        ),
    )
    assertEquals(
        ServiceEntryAuthorization.RejectPassive,
        serviceEntryAuthorization(
            action = IPNService.ACTION_START_VPN,
            hasEngineCapability = true,
            hasBoundLoginRequest = false,
            hasBoundVpnCommand = false,
        ),
    )
    assertEquals(
        ServiceEntryAuthorization.InteractiveLogin,
        serviceEntryAuthorization(
            action = IPNService.ACTION_START_FOREGROUND_ONLY,
            hasEngineCapability = true,
            hasBoundLoginRequest = true,
            hasBoundVpnCommand = false,
        ),
    )
    assertEquals(
        ServiceEntryAuthorization.ExplicitVpn,
        serviceEntryAuthorization(
            action = IPNService.ACTION_START_VPN,
            hasEngineCapability = true,
            hasBoundLoginRequest = false,
            hasBoundVpnCommand = true,
        ),
    )
  }

  @Test
  fun rejectedOldEnvelopePreservesOnlyAnExactNewOwner() {
    val current = DesiredVpnState(enabled = true, generation = 22L)
    assertEquals(
        RejectedServiceStartDecision.PreserveAuthorizedOwner,
        rejectedServiceStartDecision(
            currentState = current,
            hasBoundCurrentVpnCommand = true,
            hasAuthorizedLoginOwner = false,
        ),
    )
    assertEquals(
        RejectedServiceStartDecision.StopPassive,
        rejectedServiceStartDecision(
            currentState = current,
            hasBoundCurrentVpnCommand = false,
            hasAuthorizedLoginOwner = false,
        ),
    )
  }

  @Test
  fun productionActivationGatesRunNoFakeNativeOrNetworkProbeBeforeActivation() {
    var nativeStarts = 0
    var networkMonitors = 0
    var protects = 0
    var binds = 0

    val engineResult =
        TailwareActivationCapability.withEngineAuthorization {
          nativeStarts++
          networkMonitors++
        }
    val vpnResult =
        TailwareActivationCapability.withVpnCommandAuthorization(5L) {
          protects++
          binds++
        }

    assertEquals(null, engineResult)
    assertEquals(null, vpnResult)
    assertEquals(0, nativeStarts)
    assertEquals(0, networkMonitors)
    assertEquals(0, protects)
    assertEquals(0, binds)
  }

  @Test
  fun unauthorizedProductionBuilderRunsNoFakeTunRouteOrDnsMutation() {
    val platformBuilder = mock<VpnService.Builder>()
    val builder =
        VPNServiceBuilder(
            builder = platformBuilder,
            commandGeneration = 9L,
            isAuthorized = { false },
        )

    listOf<() -> Unit>(
            { builder.addAddress("100.64.0.1", 32) },
            { builder.addDNSServer("100.100.100.100") },
            { builder.addRoute("0.0.0.0", 0) },
            { builder.excludeRoute("192.168.0.0", 16) },
            { builder.addSearchDomain("example.test") },
            { builder.setMTU(1280) },
            { builder.establish() },
        )
        .forEach(::assertSecurityRejected)

    verifyNoInteractions(platformBuilder)
  }

  @Test
  fun queuedLocalApiMutationRechecksOwnershipInsideExecutionGate() {
    var currentGeneration = 4L
    var nativeCalls = 0
    val rejected = runCatching {
      executeAuthorizedLocalApiOperation(
          authorization = { currentGeneration == 4L },
          executionGate = { operation ->
            // Deterministically model a newer Stop/Start winning after enqueue but before the
            // LocalAPI worker reaches its final execution point.
            currentGeneration = 5L
            operation()
          },
      ) {
        nativeCalls++
      }
    }
    assertTrue(rejected.exceptionOrNull() is LocalApiRequestSupersededException)
    assertEquals(0, nativeCalls)

    val accepted =
        executeAuthorizedLocalApiOperation(
            authorization = { currentGeneration == 5L },
            executionGate = { it() },
        ) {
          nativeCalls++
          17
        }
    assertEquals(17, accepted)
    assertEquals(1, nativeCalls)
  }

  @Test
  fun rejectedServiceDecisionRunsNoSensitiveProbe() {
    var sensitiveOperations = 0
    val decision =
        serviceEntryAuthorization(
            action = null,
            hasEngineCapability = false,
            hasBoundLoginRequest = false,
            hasBoundVpnCommand = false,
        )
    if (decision != ServiceEntryAuthorization.RejectPassive) {
      sensitiveOperations++
    }
    assertEquals(0, sensitiveOperations)
  }

  @Test
  fun staticColdEntryPointsRemainPassiveAndNonSticky() {
    val activity = source("java/com/tailscale/ipn/wear/WearMainActivity.kt")
    val constructor = activity.substringBefore("override fun onCreate")
    val executableConstructor = stripLineComments(constructor)
    listOf("App.get()", "TailwareViewModel(", "ViewModelProvider(").forEach { forbidden ->
      assertFalse(
          "Wear Activity construction contains $forbidden",
          executableConstructor.contains(forbidden),
      )
    }
    val onCreate =
        sourceSegment(
            activity,
            "override fun onCreate",
            "/** The only Activity path allowed to cross",
        )
    val onResume =
        sourceSegment(
            activity,
            "override fun onResume",
            "private fun setOptionalNotifications",
        )
    listOf(onCreate, onResume).forEach { passiveLifecycle ->
      val executableText = stripLineComments(passiveLifecycle)
      assertFalse(executableText.contains("App.get()"))
      assertFalse(Regex("VpnService\\.prepare\\s*\\(").containsMatchIn(executableText))
      assertFalse(executableText.contains("grantEngineFromUserGesture"))
      assertFalse(executableText.contains("grantLoginFromUserGesture"))
      assertFalse(executableText.contains("grantVpnFromUserGesture"))
    }

    val activation =
        sourceSegment(
            activity,
            "private fun activateEngineFromUserGesture",
            "override fun onDestroy",
        )
    assertTrue(activation.indexOf("grantEngineFromUserGesture") < activation.indexOf("App.get()"))
    assertTrue(activation.indexOf("App.get()") < activation.indexOf("TailwareViewModel(app)"))

    val boot = source("java/com/tailscale/ipn/wear/TailwareBootReceiver.kt")
    listOf(
            "App.get()",
            "VpnService.prepare",
            "startService(",
            "WorkManager.getInstance",
            "androidx.work",
        )
        .forEach { forbidden ->
          assertFalse("Boot path contains $forbidden", boot.contains(forbidden))
        }

    val tile = source("java/com/tailscale/ipn/wear/tile/TailwareTileService.kt")
    listOf("VpnService.prepare", ".startVPN(", ".stopVPN(", "App.get()").forEach { forbidden ->
      assertFalse("Tile path contains $forbidden", tile.contains(forbidden))
    }

    val service = source("java/com/tailscale/ipn/IPNService.kt")
    assertFalse(service.contains("START_STICKY"))
    val onStartCommand =
        sourceSegment(
            service,
            "override fun onStartCommand",
            "private fun startFromPersistedState",
        )
    assertTrue(
        onStartCommand.indexOf("serviceEntryAuthorization(") <
            onStartCommand.indexOf("latestStartId ="))
    assertTrue(
        onStartCommand.indexOf("ServiceEntryAuthorization.RejectPassive") <
            onStartCommand.indexOf("latestStartId ="))

    val receiver = source("java/com/tailscale/ipn/IPNReceiver.java")
    assertFalse(receiver.contains("VpnService.prepare"))
    assertFalse(receiver.contains("enqueueUniqueWork"))
    assertFalse(receiver.contains("WorkManager.getInstance"))

    val app = source("java/com/tailscale/ipn/App.kt")
    val appGet = sourceSegment(app, "fun get(): App", "val dns = DnsConfig()")
    assertTrue(appGet.contains("withEngineAuthorization"))
    val goVpnSources =
        listOf("backend.go", "vpn_lifecycle.go").joinToString("\n") { source ->
          File(mainRoot(), "../../../libtailscale/$source").canonicalFile.readText()
        }
    assertFalse(Regex("\\.DebugRebind\\s*\\(").containsMatchIn(stripLineComments(goVpnSources)))
    assertTrue(goVpnSources.contains("vpnauthorization.IsCommandAuthorized(s, generation)"))
    assertTrue(goVpnSources.contains("s.BindSocketToNetwork"))
  }

  @Test
  fun loginSuccessAndBackgroundWorkersCannotStartVpnOrNativeCode() {
    val wearViewModel = source("java/com/tailscale/ipn/wear/viewmodel/TailwareViewModel.kt")
    val loginSuccess =
        wearViewModel
            .substringAfter("private fun completeSuccessfulLoginWithoutAuthenticationOwner")
            .substringBefore("private fun stopLoginForegroundProtection")
    assertFalse(loginSuccess.contains("startVPN"))
    assertFalse(loginSuccess.contains("requestVPN"))
    assertFalse(loginSuccess.contains("grantVpnFromUserGesture"))
    assertFalse(wearViewModel.contains("tryAutoConnectAfterLogin"))
    assertFalse(wearViewModel.contains("maybeRestoreDesiredVpn"))

    val useExitWorker = source("java/com/tailscale/ipn/UseExitNodeWorker.kt")
    val integrationWorker = source("java/com/tailscale/ipn/IntegrationLoginWorker.kt")
    listOf("App.get()", "UninitializedApp.get()", "Client(", "Libtailscale").forEach { forbidden ->
      assertFalse("Exit-node Worker contains $forbidden", useExitWorker.contains(forbidden))
      assertFalse(
          "Integration Worker contains $forbidden",
          integrationWorker.contains(forbidden),
      )
    }
  }

  @Test
  fun noSamsungImsSettingsWriteOrRecoverySurfaceWasIntroduced() {
    val productionFiles =
        (mainRoot().walkTopDown().filter {
              it.isFile && it.extension in setOf("kt", "java", "xml")
            } +
                File(mainRoot(), "../../../libtailscale").canonicalFile.walkTopDown().filter {
                  it.isFile && it.extension == "go"
                })
            .toList()
    val productionText = productionFiles.joinToString("\n") { it.readText() }
    listOf(
            "com.sec.ims",
            "content://com.sec.ims",
            "Settings.Global.put",
            "Settings.Secure.put",
            "PowerManager.reboot",
            "RecoverySystem.reboot",
            "android.permission.REBOOT",
            "android.permission.WRITE_SECURE_SETTINGS",
        )
        .forEach { forbidden ->
          assertFalse(
              "Production code contains forbidden surface $forbidden",
              productionText.contains(forbidden))
        }
  }

  @Test
  fun sourceManifestDisablesWorkManagerBeforeApplicationOnCreate() {
    val manifest = File(mainRoot(), "AndroidManifest.xml").readText()
    assertTrue(manifest.contains("android:name=\".wear.WearMainActivity\""))
    assertFalse(manifest.contains("android:name=\".MainActivity\""))
    assertFalse(manifest.contains("android:name=\".ShareActivity\""))
    assertTrue(manifest.contains("androidx.work.WorkManagerInitializer"))
    assertTrue(
        Regex("androidx\\.work\\.WorkManagerInitializer[\\s\\S]{0,160}tools:node=\"remove\"")
            .containsMatchIn(manifest))
    assertTrue(
        Regex(
                "androidx\\.work\\.impl\\.background\\.systemjob\\.SystemJobService[\\s\\S]{0,120}tools:node=\"remove\"")
            .containsMatchIn(manifest))
  }

  private data class PreferencesFixture(
      val context: Context,
      val editor: SharedPreferences.Editor,
  )

  private fun preferencesFixture(
      desiredEnabled: Boolean = false,
      desiredGeneration: Long = 0L,
      migrationVersion: Int = TailwareVpnStateStore.P0_CONTAINMENT_MIGRATION_VERSION,
      commitResult: Boolean = true,
  ): PreferencesFixture {
    val context = mock<Context>()
    val preferences = mock<SharedPreferences>()
    val editor = mock<SharedPreferences.Editor>()
    whenever(context.applicationContext).thenReturn(context)
    whenever(context.getSharedPreferences(any(), eq(Context.MODE_PRIVATE))).thenReturn(preferences)
    whenever(preferences.getBoolean("desired_vpn_enabled", false)).thenReturn(desiredEnabled)
    whenever(preferences.getLong("desired_vpn_generation", 0L)).thenReturn(desiredGeneration)
    whenever(preferences.getLong("desired_vpn_write_nonce", 0L)).thenReturn(0L)
    whenever(preferences.getInt("p0_containment_migration_version", 0)).thenReturn(migrationVersion)
    whenever(preferences.edit()).thenReturn(editor)
    whenever(editor.putBoolean(any(), any())).thenReturn(editor)
    whenever(editor.putLong(any(), any())).thenReturn(editor)
    whenever(editor.putInt(any(), any())).thenReturn(editor)
    whenever(editor.commit()).thenReturn(commitResult)
    if (migrationVersion >= TailwareVpnStateStore.P0_CONTAINMENT_MIGRATION_VERSION &&
        !desiredEnabled) {
      verify(editor, never()).commit()
    }
    return PreferencesFixture(context, editor)
  }

  private fun mainRoot(): File {
    val direct = File("src/main")
    return if (direct.isDirectory) direct else File("android/src/main")
  }

  private fun source(relativePath: String): String = File(mainRoot(), relativePath).readText()

  private fun sourceSegment(source: String, start: String, end: String): String {
    val startIndex = source.indexOf(start)
    val endIndex = source.indexOf(end, startIndex + start.length)
    check(startIndex >= 0 && endIndex > startIndex) {
      "Unable to find source segment $start .. $end"
    }
    return source.substring(startIndex, endIndex)
  }

  private fun stripLineComments(source: String): String =
      source.lineSequence().joinToString("\n") { it.substringBefore("//") }

  private fun assertSecurityRejected(operation: () -> Unit) {
    val failure = runCatching(operation).exceptionOrNull()
    assertTrue(
        "Expected a fail-closed authorization exception, got $failure",
        failure is SecurityException)
  }
}
