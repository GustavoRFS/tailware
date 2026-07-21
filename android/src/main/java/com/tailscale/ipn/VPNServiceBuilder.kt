// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.net.IpPrefix as AndroidIpPrefix
import android.net.VpnService
import android.os.Build
import com.tailscale.ipn.wear.TailwareActivationCapability
import java.net.InetAddress
import libtailscale.ParcelFileDescriptor

class VPNServiceBuilder
internal constructor(
    private val builder: VpnService.Builder,
    private val commandGeneration: Long,
    private val isAuthorized: () -> Boolean,
) : libtailscale.VPNServiceBuilder {
  private fun <T> mutateAuthorized(operation: () -> T): T =
      TailwareActivationCapability.withVpnCommandAuthorization(commandGeneration) {
        check(isAuthorized()) {
          "VPN builder mutation requires the exact live Connect command generation."
        }
        operation()
      }
          ?: throw SecurityException(
              "VPN builder mutation requires the exact live Connect command generation.")

  override fun addAddress(p0: String, p1: Int) {
    mutateAuthorized { builder.addAddress(p0, p1) }
  }

  override fun addDNSServer(p0: String) {
    mutateAuthorized { builder.addDnsServer(p0) }
  }

  override fun addRoute(p0: String, p1: Int) {
    mutateAuthorized { builder.addRoute(p0, p1) }
  }

  override fun excludeRoute(p0: String, p1: Int) {
    mutateAuthorized {
      // Only run this for API level 33 and up
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val inetAddress = InetAddress.getByName(p0)
        val prefix = AndroidIpPrefix(inetAddress, p1)
        builder.excludeRoute(prefix)
      }
    }
  }

  override fun addSearchDomain(p0: String) {
    mutateAuthorized { builder.addSearchDomain(p0) }
  }

  override fun establish(): ParcelFileDescriptor? = mutateAuthorized {
    builder.establish()?.let { ParcelFileDescriptor(it) }
  }

  override fun setMTU(p0: Int) {
    mutateAuthorized { builder.setMtu(p0) }
  }
}

class ParcelFileDescriptor(private val fd: android.os.ParcelFileDescriptor) : ParcelFileDescriptor {
  override fun detach(): Int {
    return fd.detachFd()
  }
}
