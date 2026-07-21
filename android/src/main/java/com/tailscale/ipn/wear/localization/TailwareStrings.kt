// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear.localization

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.tailscale.ipn.R
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.wear.TailwareLanguage
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves every Tailware string through one Android resource catalog.
 *
 * The selected language is app state rather than a process-global assumption because a cold Tile,
 * notification or boot receiver can render before the activity applies its locale.
 */
object TailwareStrings {
  private const val PREFERENCES_FILE = "tailware_wear_preferences"
  private const val KEY_LANGUAGE = "language"
  const val SYSTEM_LANGUAGE_PREFERENCE = "system"
  private val localizedContexts = ConcurrentHashMap<String, Context>()

  @JvmStatic
  fun selectedLanguage(baseContext: Context): TailwareLanguage {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val applicationLocales =
          baseContext.getSystemService(android.app.LocaleManager::class.java).applicationLocales
      TailwareLanguage.fromSupportedValue(applicationLocales.get(0)?.toLanguageTag())?.let {
        return it
      }
      return bestSupportedSystemLanguage(baseContext)
    }
    val stored =
        baseContext
            .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
    if (stored.isNullOrBlank() || stored == SYSTEM_LANGUAGE_PREFERENCE) {
      return bestSupportedSystemLanguage(baseContext)
    }
    return TailwareLanguage.fromSupportedValue(stored) ?: bestSupportedSystemLanguage(baseContext)
  }

  fun followsSystemLanguage(baseContext: Context): Boolean =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        baseContext
            .getSystemService(android.app.LocaleManager::class.java)
            .applicationLocales
            .isEmpty
      } else {
        baseContext
            .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
            .let { it.isNullOrBlank() || it == SYSTEM_LANGUAGE_PREFERENCE }
      }

  fun preferenceValue(baseContext: Context): String =
      if (followsSystemLanguage(baseContext)) {
        SYSTEM_LANGUAGE_PREFERENCE
      } else {
        selectedLanguage(baseContext).languageTag
      }

  private fun bestSupportedSystemLanguage(baseContext: Context): TailwareLanguage {
    val locales = baseContext.resources.configuration.locales
    for (index in 0 until locales.size()) {
      TailwareLanguage.fromSupportedValue(locales[index].toLanguageTag())?.let {
        return it
      }
    }
    return TailwareLanguage.English
  }

  @JvmStatic
  fun selectedText(
      baseContext: Context,
      @StringRes resourceId: Int,
      vararg formatArguments: Any,
  ): String =
      text(
          baseContext,
          selectedLanguage(baseContext),
          resourceId,
          *formatArguments,
      )

  fun text(
      language: TailwareLanguage,
      @StringRes resourceId: Int,
      vararg formatArguments: Any,
  ): String = text(UninitializedApp.get(), language, resourceId, *formatArguments)

  fun text(
      baseContext: Context,
      language: TailwareLanguage,
      @StringRes resourceId: Int,
      vararg formatArguments: Any,
  ): String = context(baseContext, language).resources.getString(resourceId, *formatArguments)

  fun quantity(
      language: TailwareLanguage,
      @PluralsRes resourceId: Int,
      quantity: Int,
      vararg formatArguments: Any,
  ): String = quantity(UninitializedApp.get(), language, resourceId, quantity, *formatArguments)

  fun quantity(
      baseContext: Context,
      language: TailwareLanguage,
      @PluralsRes resourceId: Int,
      quantity: Int,
      vararg formatArguments: Any,
  ): String =
      context(baseContext, language)
          .resources
          .getQuantityString(resourceId, quantity, *formatArguments)

  /** Keeps technical LTR values readable when inserted into Arabic, Persian, Hebrew or Urdu. */
  fun ltr(language: TailwareLanguage, value: String): String =
      BidiFormatter.getInstance(Locale.forLanguageTag(language.languageTag))
          .unicodeWrap(value, TextDirectionHeuristics.LTR)

  /** Isolates a user/server supplied name while preserving its own first-strong direction. */
  fun isolate(language: TailwareLanguage, value: String): String =
      BidiFormatter.getInstance(Locale.forLanguageTag(language.languageTag))
          .unicodeWrap(value, TextDirectionHeuristics.FIRSTSTRONG_LTR)

  private fun context(baseContext: Context, language: TailwareLanguage): Context =
      localizedContexts.getOrPut(language.languageTag) {
        val applicationContext = baseContext.applicationContext
        val locale = Locale.forLanguageTag(language.languageTag)
        val configuration = Configuration(applicationContext.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        applicationContext.createConfigurationContext(configuration)
      }
}

data class TailwareHealthResources(
    @StringRes val title: Int,
    @StringRes val message: Int,
)

fun tailwareHealthResources(warnableCode: String): TailwareHealthResources {
  val code = warnableCode.lowercase(Locale.ROOT)
  return when {
    "derp" in code || "relay" in code ->
        TailwareHealthResources(
            R.string.wear_health_relay_unavailable,
            R.string.wear_health_relay_unavailable_message,
        )
    "dns" in code ->
        TailwareHealthResources(
            R.string.wear_health_dns_problem,
            R.string.wear_health_dns_problem_message,
        )
    "auth" in code || "login" in code || "key" in code ->
        TailwareHealthResources(
            R.string.wear_health_account_problem,
            R.string.wear_health_account_problem_message,
        )
    "clock" in code || "time-skew" in code || "timesync" in code ->
        TailwareHealthResources(
            R.string.wear_health_clock_problem,
            R.string.wear_health_clock_problem_message,
        )
    else ->
        TailwareHealthResources(
            R.string.wear_health_problem,
            R.string.wear_health_problem_message,
        )
  }
}
