// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.UninitializedApp.Companion.notificationManager
import com.tailscale.ipn.runBestEffortNotificationUpdate
import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.model.Health.UnhealthyState
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wear.localization.TailwareStrings
import com.tailscale.ipn.wear.localization.tailwareHealthResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

internal fun healthNotificationCodesToRemove(
    droppedWarnings: Collection<UnhealthyState>,
    remainingWarnings: Collection<UnhealthyState>,
): Set<String> {
  val remainingHighSeverityCodes =
      remainingWarnings
          .asSequence()
          .filter { it.Severity == Health.Severity.high }
          .map { it.WarnableCode }
          .toSet()
  return droppedWarnings
      .asSequence()
      .filter { it.Severity == Health.Severity.high }
      .map { it.WarnableCode }
      .filterNot { it in remainingHighSeverityCodes }
      .toSet()
}

class HealthNotifier(
    healthStateFlow: StateFlow<Health.State?>,
    ipnStateFlow: StateFlow<Ipn.State>,
    scope: CoroutineScope,
) {
  companion object {
    const val HEALTH_CHANNEL_ID = "tailscale-health"
  }

  private val TAG = "health"
  private val ignoredWarnableCodes: Set<String> =
      setOf(
          // Ignored on Android because installing unstable takes quite some effort
          "is-using-unstable-version",

          // Ignored on Android because we already have a dedicated connected/not connected
          // notification
          "wantrunning-false")

  init {
    // This roughly matches the iOS/macOS implementation in terms of debouncing, and ignoring
    // health warnings in various states.
    scope.launch {
      ipnStateFlow
          .flatMapLatest { ipnState ->
            if (ipnState == Ipn.State.Running) {
              healthStateFlow
                  .distinctUntilChanged { old, new ->
                    old?.Warnings.orEmpty() == new?.Warnings.orEmpty()
                  }
                  .debounce(3000)
            } else {
              TSLog.d(TAG, "Ignoring and dropping all health messages in state $ipnState")
              dropAllWarnings()
              emptyFlow()
            }
          }
          .collect { health ->
            TSLog.d(TAG, "Health updated: ${health?.Warnings?.keys?.sorted()}")
            health?.Warnings?.values?.filterNotNull()?.toTypedArray()?.let(::notifyHealthUpdated)
          }
    }
  }

  val currentWarnings: StateFlow<Set<UnhealthyState>> = MutableStateFlow(setOf())
  val currentIcon: StateFlow<Int?> = MutableStateFlow(null)

  @Synchronized
  private fun notifyHealthUpdated(warnings: Array<UnhealthyState>) {
    val warningsBeforeAdd = currentWarnings.value
    val currentWarnableCodes = warnings.map { it.WarnableCode }.toSet()
    val addedWarnings: MutableSet<UnhealthyState> = mutableSetOf()
    val removedByNewDependency: MutableSet<UnhealthyState> = mutableSetOf()
    val isWarmingUp = warnings.any { it.WarnableCode == "warming-up" }

    /**
     * dropDependenciesForAddedWarning checks if there is any warning in `warningsBeforeAdd` that
     * needs to be removed because the new warning `w` is listed as a dependency of a warning
     * already in `warningsBeforeAdd`, and removes it.
     */
    fun dropDependenciesForAddedWarning(w: UnhealthyState) {
      for (warning in warningsBeforeAdd) {
        warning.DependsOn?.let {
          if (it.contains(w.WarnableCode)) {
            removedByNewDependency.add(warning)
          }
        }
      }
    }

    for (warning in warnings) {
      if (ignoredWarnableCodes.contains(warning.WarnableCode)) {
        continue
      }

      addedWarnings.add(warning)

      if (this.currentWarnings.value.contains(warning)) {
        // Already notified, skip
        continue
      } else if (warning.hiddenByDependencies(currentWarnableCodes)) {
        // Ignore this warning because a dependency is also unhealthy
        TSLog.d(TAG, "Ignoring ${warning.WarnableCode} because of dependency")
        continue
      } else if (!isWarmingUp) {
        TSLog.d(TAG, "Adding health warning: ${warning.WarnableCode}")
        this.currentWarnings.set(this.currentWarnings.value + warning)
        dropDependenciesForAddedWarning(warning)
        if (warning.Severity == Health.Severity.high) {
          this.sendNotification(warning.WarnableCode)
        }
      } else {
        TSLog.d(TAG, "Ignoring ${warning.WarnableCode} because warming up")
      }
    }

    val warningsToDrop = warningsBeforeAdd.minus(addedWarnings).union(removedByNewDependency)
    val remainingWarnings = currentWarnings.value.subtract(warningsToDrop)
    if (warningsToDrop.isNotEmpty()) {
      TSLog.d(TAG, "Dropping health warnings with codes $warningsToDrop")
      val notificationCodesToRemove =
          healthNotificationCodesToRemove(
              droppedWarnings = warningsToDrop,
              remainingWarnings = remainingWarnings,
          )
      this.removeNotifications(
          warningsToDrop.filterTo(mutableSetOf()) { it.WarnableCode in notificationCodesToRemove })
    }
    currentWarnings.set(remainingWarnings)
    this.updateIcon()
  }

  /**
   * Sets the icon displayed to represent the overall health state.
   * - If there are any high severity warnings, or warnings that affect internet connectivity, a
   *   warning icon is displayed.
   * - If there are any other kind of warnings, an info icon is displayed.
   * - If there are no warnings at all, no icon is set.
   */
  private fun updateIcon() {
    if (currentWarnings.value.isEmpty()) {
      this.currentIcon.set(null)
      return
    }
    if (currentWarnings.value.any {
      (it.Severity == Health.Severity.high || it.ImpactsConnectivity == true)
    }) {
      this.currentIcon.set(R.drawable.warning_rounded)
    } else {
      this.currentIcon.set(R.drawable.info)
    }
  }

  private fun sendNotification(code: String) {
    TSLog.d(TAG, "Sending notification for $code")
    val resources = tailwareHealthResources(code)
    val context = App.get().applicationContext
    val title = TailwareStrings.selectedText(context, resources.title)
    val text = TailwareStrings.selectedText(context, resources.message)
    val notification =
        NotificationCompat.Builder(context, HEALTH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    if (ActivityCompat.checkSelfPermission(
        App.get().applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED) {
      TSLog.d(TAG, "Notification permission not granted")
      return
    }
    runBestEffortNotificationUpdate(
        update = { notificationManager.notify(code.hashCode(), notification) },
        onFailure = { error ->
          TSLog.e(TAG, "Unable to publish the $code health notification", error)
        },
    )
  }

  /** Reposts active high-severity warnings so a language change is visible immediately. */
  @Synchronized
  fun refreshLocalizedNotifications() {
    currentWarnings.value
        .asSequence()
        .filter { it.Severity == Health.Severity.high }
        .map { it.WarnableCode }
        .forEach(::sendNotification)
  }

  /**
   * Removes all warnings currently displayed, including any system notifications, and updates the
   * icon (causing it to be set to null since the set of warnings is empty).
   */
  @Synchronized
  private fun dropAllWarnings() {
    removeNotifications(this.currentWarnings.value)
    this.currentWarnings.set(emptySet())
    this.updateIcon()
  }

  private fun removeNotifications(warnings: Set<UnhealthyState>) {
    TSLog.d(TAG, "Removing notifications for $warnings")
    for (warning in warnings) {
      runBestEffortNotificationUpdate(
          update = { notificationManager.cancel(warning.WarnableCode.hashCode()) },
          onFailure = { error ->
            TSLog.e(TAG, "Unable to remove the ${warning.WarnableCode} health notification", error)
          },
      )
    }
  }
}
