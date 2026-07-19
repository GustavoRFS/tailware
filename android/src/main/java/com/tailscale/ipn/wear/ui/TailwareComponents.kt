// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.tailscale.ipn.wear.WearTunnelStatus

@Composable
fun TailwareListScreen(
    title: String,
    modifier: Modifier = Modifier,
    content: ScalingLazyListScope.() -> Unit,
) {
  val listState = rememberScalingLazyListState()
  val width = LocalConfiguration.current.screenWidthDp
  val sidePadding = if (width < 210) 8.dp else 14.dp

  Scaffold(
      modifier = modifier.fillMaxSize().background(TailwarePalette.Ink),
      timeText = { TimeText() },
      positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
  ) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding =
            PaddingValues(
                start = sidePadding,
                end = sidePadding,
                top = 34.dp,
                bottom = 28.dp,
            ),
        content = {
          item {
            Text(
                text = title,
                color = TailwarePalette.Ice,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.title3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
          content()
        },
    )
  }
}

@Composable
fun TailwareChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    enabled: Boolean = true,
    accent: Boolean = false,
) {
  Chip(
      modifier = modifier.fillMaxWidth(),
      onClick = onClick,
      enabled = enabled,
      label = {
        Text(
            text = label,
            maxLines = 3,
            fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
        )
      },
      secondaryLabel =
          secondary?.let { value ->
            {
              Text(
                  text = value,
                  maxLines = 4,
                  color =
                      if (accent) TailwarePalette.Ink.copy(alpha = 0.72f)
                      else TailwarePalette.Muted,
              )
            }
          },
      colors =
          if (accent) {
            ChipDefaults.primaryChipColors(
                backgroundColor = TailwarePalette.Signal,
                contentColor = TailwarePalette.Ink,
            )
          } else {
            ChipDefaults.secondaryChipColors(
                backgroundColor = TailwarePalette.Deep,
                contentColor = TailwarePalette.Ice,
                secondaryContentColor = TailwarePalette.Muted,
            )
          },
  )
}

@Composable
fun TailwareToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    enabled: Boolean = true,
) {
  ToggleChip(
      modifier = modifier.fillMaxWidth(),
      checked = checked,
      onCheckedChange = onCheckedChange,
      enabled = enabled,
      label = { Text(label, maxLines = 3) },
      secondaryLabel =
          secondary?.let { value -> { Text(value, maxLines = 4, color = TailwarePalette.Muted) } },
      toggleControl = { Switch(checked = checked) },
  )
}

@Composable
fun TailwareInfo(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    valueColor: Color = TailwarePalette.Ice,
) {
  Column(
      modifier =
          modifier
              .fillMaxWidth()
              .semantics(mergeDescendants = true) {}
              .clip(RoundedCornerShape(18.dp))
              .background(TailwarePalette.Deep)
              .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
        text = label.uppercase(),
        color = TailwarePalette.Muted,
        fontSize = 10.sp,
        letterSpacing = 0.7.sp,
        textAlign = TextAlign.Center,
    )
    Text(
        text = value,
        color = valueColor,
        fontSize = if (mono) 12.sp else 13.sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        textAlign = TextAlign.Center,
        maxLines = 6,
    )
  }
}

@Composable
fun TailwareErrorChip(message: String, dismissLabel: String, onDismiss: () -> Unit) {
  Chip(
      modifier = Modifier.fillMaxWidth(),
      onClick = onDismiss,
      label = { Text(message, maxLines = 6) },
      secondaryLabel = { Text(dismissLabel, maxLines = 1) },
      colors =
          ChipDefaults.secondaryChipColors(
              backgroundColor = TailwarePalette.Danger.copy(alpha = 0.22f),
              contentColor = TailwarePalette.Ice,
              secondaryContentColor = TailwarePalette.Danger,
          ),
  )
}

@Composable
fun ConnectionOrb(
    status: WearTunnelStatus,
    onClick: () -> Unit,
    enabled: Boolean,
    contentDescription: String,
    stateDescription: String,
    actionLabel: String,
    modifier: Modifier = Modifier,
) {
  val accent =
      when (status) {
        WearTunnelStatus.Connected -> TailwarePalette.Signal
        WearTunnelStatus.Connecting,
        WearTunnelStatus.Disconnecting -> TailwarePalette.Warning
        WearTunnelStatus.Disconnected -> TailwarePalette.Muted
        WearTunnelStatus.Unknown -> TailwarePalette.Blue
      }
  val surface =
      if (status == WearTunnelStatus.Connected) TailwarePalette.SignalDim.copy(alpha = 0.48f)
      else TailwarePalette.Deep

  Box(
      contentAlignment = Alignment.Center,
      modifier =
          modifier
              .size(118.dp)
              .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
              }
              .clip(CircleShape)
              .background(surface)
              .clickable(
                  enabled = enabled,
                  onClickLabel = actionLabel,
                  role = Role.Button,
                  onClick = onClick,
              ),
  ) {
    Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
      val stroke = size.minDimension * 0.055f
      val center = Offset(size.width / 2f, size.height / 2f)
      drawCircle(accent.copy(alpha = 0.35f), style = Stroke(width = stroke))
      drawArc(
          color = accent,
          startAngle = -53f,
          sweepAngle = 286f,
          useCenter = false,
          topLeft = Offset(stroke * 1.35f, stroke * 1.35f),
          size =
              androidx.compose.ui.geometry.Size(
                  size.width - stroke * 2.7f,
                  size.height - stroke * 2.7f,
              ),
          style = Stroke(width = stroke, cap = StrokeCap.Round),
      )

      val topY = center.y - size.height * 0.13f
      val left = center.x - size.width * 0.23f
      val right = center.x + size.width * 0.23f
      val nodeRadius = stroke * 0.72f
      drawLine(accent, Offset(left, topY), Offset(right, topY), stroke, StrokeCap.Round)
      drawLine(
          accent,
          Offset(center.x, topY),
          Offset(center.x, center.y + size.height * 0.22f),
          stroke,
          StrokeCap.Round,
      )
      listOf(
              Offset(left, topY),
              Offset(center.x, topY),
              Offset(right, topY),
              Offset(center.x, center.y + size.height * 0.22f),
          )
          .forEach { point ->
            drawCircle(TailwarePalette.Deep, radius = nodeRadius, center = point)
            drawCircle(
                accent, radius = nodeRadius, center = point, style = Stroke(width = stroke * 0.5f))
          }
    }
  }
}

@Composable
fun rememberQrCode(content: String?, size: Int = 280): ImageBitmap? =
    remember(content, size) {
      content?.let {
        runCatching {
              val matrix =
                  QRCodeWriter()
                      .encode(
                          it,
                          BarcodeFormat.QR_CODE,
                          size,
                          size,
                          mapOf(EncodeHintType.MARGIN to 2),
                      )
              createBitmap(size, size).also { bitmap ->
                for (x in 0 until size) {
                  for (y in 0 until size) {
                    bitmap[x, y] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
                  }
                }
              }
            }
            .getOrNull()
            ?.asImageBitmap()
      }
    }
