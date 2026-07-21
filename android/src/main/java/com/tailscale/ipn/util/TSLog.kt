// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.util

import android.content.Context
import android.util.Log
import com.tailscale.ipn.wear.TailwareActivationCapability
import libtailscale.Libtailscale

object TSLog {
  private lateinit var appContext: Context
  var libtailscaleWrapper = LibtailscaleWrapper()

  fun init(context: Context) {
    appContext = context.applicationContext
  }

  fun d(tag: String?, message: String) {
    Log.d(tag, message)
    sendNativeLogIfReady(tag, message)
  }

  fun w(tag: String, message: String) {
    Log.w(tag, message)
    sendNativeLogIfReady(tag, message)
  }

  fun v(tag: String?, message: String) {
    if (::appContext.isInitialized && isUnstableRelease()) {
      Log.v(tag, message)
      sendNativeLogIfReady(tag, message)
    }
  }

  // Overloaded function without Throwable because Java does not support default parameters
  @JvmStatic
  fun e(tag: String?, message: String) {
    Log.e(tag, message)
    sendNativeLogIfReady(tag, message)
  }

  fun e(tag: String?, message: String, throwable: Throwable? = null) {
    if (throwable == null) {
      Log.e(tag, message)
      sendNativeLogIfReady(tag, message)
    } else {
      Log.e(tag, message, throwable)
      sendNativeLogIfReady(tag, "$message ${throwable.localizedMessage}")
    }
  }

  private fun sendNativeLogIfReady(tag: String?, message: String) {
    if (TailwareActivationCapability.canSendNativeLogs()) {
      libtailscaleWrapper.sendLog(tag, message)
    }
  }

  private fun isUnstableRelease(): Boolean {
    val versionName =
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName

    // Extract the middle number and check if it's odd
    val middleNumber = versionName.orEmpty().split(".").getOrNull(1)?.toIntOrNull()
    return middleNumber?.let { it % 2 == 1 } ?: false
  }

  class LibtailscaleWrapper {
    public fun sendLog(tag: String?, message: String) {
      val logTag = tag ?: ""
      Libtailscale.sendLog((logTag + ": " + message).toByteArray(Charsets.UTF_8))
    }
  }
}
