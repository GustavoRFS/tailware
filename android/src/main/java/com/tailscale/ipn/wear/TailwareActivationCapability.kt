// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.content.Context
import java.util.UUID

/**
 * Process-local authority for work that can touch the native engine or Android VPN stack.
 *
 * These tokens are deliberately never persisted or serialized. Service requests carry ordinary
 * command/request IDs whose authority is looked up in this process-local registry. Process death
 * therefore revokes every authority. Persisted account or desired-VPN state is data, not
 * authorization to initialize the engine or recreate a tunnel.
 */
object TailwareActivationCapability {
  const val NO_CAPABILITY: Long = 0L

  @Volatile private var engineCapability = NO_CAPABILITY
  @Volatile private var nativeEngineStarted = false
  @Volatile private var vpnCapability = NO_CAPABILITY
  private val loginCapabilities = mutableSetOf<Long>()
  private val loginRequestCapabilities = mutableMapOf<Long, Long>()
  private val vpnCommandCapabilities = mutableMapOf<Long, Long>()

  /** Grants only native-engine access after a direct tap in the foreground Activity. */
  @JvmStatic
  @Synchronized
  fun grantEngineFromUserGesture(context: Context): Long {
    if (!TailwareVpnStateStore.ensureP0Containment(context.applicationContext)) {
      return NO_CAPABILITY
    }
    if (engineCapability == NO_CAPABILITY) engineCapability = newCapability()
    return engineCapability
  }

  /** Grants one interactive-login session; it does not authorize a VPN service or TUN. */
  @JvmStatic
  @Synchronized
  fun grantLoginFromUserGesture(context: Context): Long {
    if (grantEngineFromUserGesture(context) == NO_CAPABILITY) return NO_CAPABILITY
    return newCapability().also(loginCapabilities::add)
  }

  /** Grants VPN authority only for a direct Connect gesture in the active process. */
  @JvmStatic
  @Synchronized
  fun grantVpnFromUserGesture(context: Context): Long {
    if (grantEngineFromUserGesture(context) == NO_CAPABILITY) return NO_CAPABILITY
    vpnCapability = newCapability()
    return vpnCapability
  }

  @JvmStatic fun hasEngineCapability(): Boolean = engineCapability != NO_CAPABILITY

  /** Executes native-engine entry work only after this process received a foreground tap. */
  @Synchronized
  internal fun <T> withEngineAuthorization(block: () -> T): T? {
    if (!hasEngineCapability()) return null
    return block()
  }

  @JvmStatic @Synchronized fun hasLoginCapability(): Boolean = loginCapabilities.isNotEmpty()

  @JvmStatic fun hasVpnCapability(): Boolean = vpnCapability != NO_CAPABILITY

  internal fun currentEngineCapability(): Long = engineCapability

  internal fun currentVpnCapability(): Long = vpnCapability

  @JvmStatic
  fun acceptsEngineCapability(candidate: Long): Boolean =
      candidate != NO_CAPABILITY && candidate == engineCapability

  @JvmStatic
  @Synchronized
  fun acceptsLoginCapability(candidate: Long): Boolean =
      candidate != NO_CAPABILITY && candidate in loginCapabilities

  @JvmStatic
  fun acceptsVpnCapability(candidate: Long): Boolean =
      candidate != NO_CAPABILITY && candidate == vpnCapability

  @Synchronized
  internal fun bindLoginRequest(requestId: Long, candidate: Long): Boolean {
    if (!acceptsLoginCapability(candidate)) return false
    loginRequestCapabilities[requestId] = candidate
    return true
  }

  @Synchronized
  internal fun acceptsLoginRequest(requestId: Long): Boolean =
      loginRequestCapabilities[requestId]?.let(::acceptsLoginCapability) == true

  /** Atomically validates and consumes the single-delivery service envelope. */
  @Synchronized
  internal fun consumeLoginRequest(requestId: Long): Long? {
    val candidate = loginRequestCapabilities.remove(requestId) ?: return null
    return candidate.takeIf(::acceptsLoginCapability)
  }

  @Synchronized
  internal fun releaseLoginRequest(requestId: Long) {
    loginRequestCapabilities.remove(requestId)
  }

  @Synchronized
  internal fun bindVpnCommand(generation: Long, candidate: Long): Boolean {
    if (!acceptsVpnCapability(candidate)) return false
    vpnCommandCapabilities[generation] = candidate
    return true
  }

  @Synchronized
  internal fun acceptsVpnCommand(generation: Long): Boolean =
      vpnCommandCapabilities[generation]?.let(::acceptsVpnCapability) == true

  /** Keeps revocation from interleaving with the one Android call that creates/protects state. */
  @Synchronized
  internal fun <T> withVpnCommandAuthorization(generation: Long, block: () -> T): T? {
    if (!acceptsVpnCommand(generation)) return null
    return block()
  }

  /** Serializes desired-ON persistence/binding with rejected-envelope containment decisions. */
  @Synchronized internal fun <T> withRegistryLock(block: () -> T): T = block()

  /** Revokes only the capability that owned this exact terminal generation. */
  @Synchronized
  internal fun revokeVpnCommand(generation: Long) {
    val candidate = vpnCommandCapabilities.remove(generation) ?: return
    if (vpnCapability != candidate) return
    vpnCapability = NO_CAPABILITY
    vpnCommandCapabilities.entries.removeAll { it.value == candidate }
  }

  @JvmStatic
  @Synchronized
  fun revokeLoginCapability(candidate: Long) {
    if (!loginCapabilities.remove(candidate)) return
    loginRequestCapabilities.entries.removeAll { it.value == candidate }
  }

  @JvmStatic
  @Synchronized
  fun revokeVpnCapability(candidate: Long) {
    if (vpnCapability != candidate) return
    vpnCapability = NO_CAPABILITY
    vpnCommandCapabilities.entries.removeAll { it.value == candidate }
  }

  internal fun markNativeEngineStarted() {
    check(hasEngineCapability())
    nativeEngineStarted = true
  }

  @JvmStatic fun canSendNativeLogs(): Boolean = hasEngineCapability() && nativeEngineStarted

  private fun newCapability(): Long {
    var candidate = NO_CAPABILITY
    while (candidate == NO_CAPABILITY) {
      val uuid = UUID.randomUUID()
      candidate = uuid.mostSignificantBits xor uuid.leastSignificantBits
    }
    return candidate
  }

  /** JVM-test hook. Production revocation of the engine capability is process death. */
  @Synchronized
  internal fun resetForTests() {
    engineCapability = NO_CAPABILITY
    nativeEngineStarted = false
    vpnCapability = NO_CAPABILITY
    loginCapabilities.clear()
    loginRequestCapabilities.clear()
    vpnCommandCapabilities.clear()
  }
}
