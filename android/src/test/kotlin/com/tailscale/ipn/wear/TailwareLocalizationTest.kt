// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import com.tailscale.ipn.wear.ui.legalTextLayoutDirection
import com.tailscale.ipn.wear.ui.tailwareLayoutDirection
import com.tailscale.ipn.wear.ui.wearTechnicalTextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TailwareLocalizationTest {
  @Test
  fun languageRegistryContainsExactlyTheSupportedTwentyEightLanguages() {
    assertEquals(
        listOf(
            "en",
            "ar",
            "bn",
            "cs",
            "de",
            "el",
            "es",
            "fa",
            "fr",
            "hi",
            "hu",
            "id",
            "it",
            "he",
            "ja",
            "ko",
            "nl",
            "pl",
            "pt",
            "ro",
            "ru",
            "sv",
            "th",
            "tr",
            "uk",
            "ur",
            "vi",
            "zh",
        ),
        TailwareLanguage.entries.map(TailwareLanguage::languageTag),
    )
  }

  @Test
  fun legacyAndroidLanguageAliasesMigrateToCanonicalTags() {
    assertEquals(TailwareLanguage.Indonesian, TailwareLanguage.fromStoredValue("in"))
    assertEquals(TailwareLanguage.Hebrew, TailwareLanguage.fromStoredValue("iw"))
  }

  @Test
  fun previousEnumNamesStillMigrate() {
    assertEquals(TailwareLanguage.Dutch, TailwareLanguage.fromStoredValue("Dutch"))
    assertEquals(TailwareLanguage.French, TailwareLanguage.fromStoredValue("French"))
    assertEquals(TailwareLanguage.German, TailwareLanguage.fromStoredValue("German"))
    assertEquals(TailwareLanguage.Russian, TailwareLanguage.fromStoredValue("Russian"))
  }

  @Test
  fun unknownLanguageFallsBackToEnglish() {
    assertEquals(TailwareLanguage.English, TailwareLanguage.fromStoredValue("not-a-language"))
  }

  @Test
  fun androidPerAppLocaleTagsResolveOnlyToSupportedLanguages() {
    assertEquals(TailwareLanguage.Portuguese, TailwareLanguage.fromSupportedValue("pt-BR"))
    assertEquals(TailwareLanguage.Hebrew, TailwareLanguage.fromSupportedValue("iw-IL"))
    assertNull(TailwareLanguage.fromSupportedValue("fi-FI"))
  }

  @Test
  fun rtlLanguagesSwitchTheWholeComposeLayoutDirection() {
    assertEquals(LayoutDirection.Rtl, tailwareLayoutDirection(TailwareLanguage.Arabic))
    assertEquals(LayoutDirection.Rtl, tailwareLayoutDirection(TailwareLanguage.Persian))
    assertEquals(LayoutDirection.Rtl, tailwareLayoutDirection(TailwareLanguage.Hebrew))
    assertEquals(LayoutDirection.Rtl, tailwareLayoutDirection(TailwareLanguage.Urdu))
    assertEquals(LayoutDirection.Ltr, tailwareLayoutDirection(TailwareLanguage.Dutch))
  }

  @Test
  fun technicalLoginFieldsRemainLeftToRightInsideRtlLayouts() {
    assertEquals(TextDirection.Ltr, wearTechnicalTextDirection())
  }

  @Test
  fun originalLicenseBodyRemainsLeftToRightInsideRtlLayouts() {
    assertEquals(LayoutDirection.Ltr, legalTextLayoutDirection())
  }

  @Test
  fun deviceNamesUseTheSelectedLanguagesCollationOrder() {
    val peers =
        listOf("Ängel", "Örebro", "Åland", "Alfa").mapIndexed { index, name ->
          WearPeer(
              stableId = index.toString(),
              displayName = name,
              dnsName = "",
              ipv4 = null,
              ipv6 = null,
              addresses = emptyList(),
              online = true,
              isSelf = false,
              os = null,
              lastSeen = null,
              keyExpiry = null,
          )
        }
    assertEquals(
        listOf("Alfa", "Åland", "Ängel", "Örebro"),
        peers
            .sortedWith(localizedPeerComparator(TailwareLanguage.Swedish))
            .map(WearPeer::displayName),
    )
  }
}
