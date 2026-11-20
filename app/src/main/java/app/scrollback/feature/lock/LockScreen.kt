// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.scrollback.ui.components.glass
import app.scrollback.ui.components.pressable
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbMotion
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Telegram-style passcode screen: dots + glass keypad, shake on a wrong PIN. */
@Composable
fun LockScreen(verify: suspend (String) -> Boolean, onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    fun submit(p: String) {
        scope.launch {
            if (verify(p)) {
                haptics.confirm(); onUnlocked()
            } else {
                haptics.reject()
                for (x in listOf(18f, -16f, 12f, -8f, 4f, 0f)) shake.animateTo(x, SbMotion.spring())
                pin = ""
            }
        }
    }

    fun press(d: String) {
        if (pin.length >= 8) return
        haptics.press()
        pin += d
        // PINs are 4–8 digits; try automatically from 4 digits on, Telegram-style.
        if (pin.length >= 4) scope.launch { if (verify(pin)) { haptics.confirm(); onUnlocked() } }
    }

    Column(
        Modifier.fillMaxSize().background(SbColors.Background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Lock, null, tint = SbColors.TextPrimary, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(SbSpace.l))
        Text("Enter PIN", style = MaterialTheme.typography.titleLarge, color = SbColors.TextPrimary)
        Spacer(Modifier.height(SbSpace.xl))
        Row(
            Modifier.offset { IntOffset(shake.value.roundToInt(), 0) },
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            repeat(maxOf(4, pin.length)) { i ->
                val filled = i < pin.length
                val s by animateFloatAsState(if (filled) 1f else 0.7f, SbMotion.spring(), label = "dot")
                Box(
                    Modifier.size(14.dp * s).clip(SbRadius.Pill)
                        .background(if (filled) SbColors.TextPrimary else SbColors.GlassFillStrong),
                )
            }
        }
        Spacer(Modifier.height(48.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("ok", "0", "del"))
        Column(verticalArrangement = Arrangement.spacedBy(SbSpace.l)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(SbSpace.xl)) {
                    row.forEach { k ->
                        val interaction = remember { MutableInteractionSource() }
                        Box(
                            Modifier
                                .pressable(interaction, 0.88f)
                                .size(76.dp)
                                .then(if (k == "ok" || k == "del") Modifier else Modifier.glass(SbRadius.Pill, elevation = 0.dp))
                                .clip(SbRadius.Pill)
                                .clickable(interaction, indication = null) {
                                    when (k) {
                                        "del" -> { haptics.tick(); pin = pin.dropLast(1) }
                                        "ok" -> if (pin.length >= 4) submit(pin)
                                        else -> press(k)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            when (k) {
                                "del" -> Icon(Icons.AutoMirrored.Outlined.Backspace, "Delete", tint = SbColors.TextSecondary)
                                "ok" -> Text("OK", style = MaterialTheme.typography.titleMedium, color = if (pin.length >= 4) SbColors.TextPrimary else SbColors.TextTertiary)
                                else -> Text(k, style = MaterialTheme.typography.headlineMedium, color = SbColors.TextPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}
