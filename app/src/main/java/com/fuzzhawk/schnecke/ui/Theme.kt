package com.fuzzhawk.schnecke.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.fuzzhawk.schnecke.core.model.Voice

object Palette {
    val background = Color(0xFF121214)
    val surface = Color(0xFF1C1C21)
    val surfaceHigh = Color(0xFF26262D)
    val outline = Color(0xFF3A3A44)
    val amber = Color(0xFFF2B33D)
    val sky = Color(0xFF4FC3F7)
    val record = Color(0xFFEF5350)
    val text = Color(0xFFECECF1)
    val textDim = Color(0xFF9D9DAA)

    fun voice(v: Voice): Color = when (v) {
        Voice.KICK -> Color(0xFFFF7A45)
        Voice.SNARE -> Color(0xFFF2B33D)
        Voice.HIHAT -> Color(0xFF4FC3F7)
        Voice.OPEN_HAT -> Color(0xFF26D0C9)
        Voice.PERC -> Color(0xFFB57BFF)
    }
}

@Composable
fun SchneckeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.amber,
            onPrimary = Color(0xFF231A00),
            secondary = Palette.sky,
            onSecondary = Color(0xFF00222E),
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.surfaceHigh,
            onSurfaceVariant = Palette.textDim,
            surfaceContainer = Palette.surface,
            surfaceContainerHigh = Palette.surfaceHigh,
            surfaceContainerHighest = Color(0xFF2E2E36),
            outline = Palette.outline,
            error = Palette.record,
        ),
        content = content,
    )
}
