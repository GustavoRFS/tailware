// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

object TailwarePalette {
  val Ink = Color.Black
  val Deep = Color(0xFF0D2026)
  val DeepRaised = Color(0xFF153039)
  val Signal = Color(0xFF63F2C2)
  val SignalDim = Color(0xFF275E50)
  val Ice = Color(0xFFDDFCF3)
  val Muted = Color(0xFF8BA4AA)
  val Blue = Color(0xFF66C7FF)
  val Warning = Color(0xFFFFBC66)
  val Danger = Color(0xFFFF817A)
}

private val TailwareColors =
    Colors(
        primary = TailwarePalette.Signal,
        primaryVariant = TailwarePalette.SignalDim,
        secondary = TailwarePalette.Blue,
        secondaryVariant = TailwarePalette.DeepRaised,
        background = TailwarePalette.Ink,
        surface = TailwarePalette.Deep,
        error = TailwarePalette.Danger,
        onPrimary = TailwarePalette.Ink,
        onSecondary = TailwarePalette.Ink,
        onBackground = TailwarePalette.Ice,
        onSurface = TailwarePalette.Ice,
        onError = TailwarePalette.Ink,
    )

@Composable
fun TailwareTheme(content: @Composable () -> Unit) {
  MaterialTheme(colors = TailwareColors, content = content)
}
