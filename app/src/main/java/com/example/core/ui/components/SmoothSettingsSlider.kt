package com.example.core.ui.components

import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Zero-lag Material 3 slider for settings screens:
 * - Tracks drag position purely in local Compose state at 60/120fps (ZERO DataStore writes or
 *   full-screen ViewModel recompositions during finger movement!).
 * - Invokes [onDragValueChanged] optionally for local label updates only.
 * - Commits the final value ONCE on release via [onValueCommit].
 */
@Composable
fun SmoothSettingsSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onDragValueChanged: ((Float) -> Unit)? = null,
) {
    val clampedExternal = value.coerceIn(valueRange.start, valueRange.endInclusive)
    var localValue by remember { mutableFloatStateOf(clampedExternal) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(clampedExternal, isDragging) {
        if (!isDragging) {
            localValue = clampedExternal
        }
    }

    Slider(
        value = localValue.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = { next ->
            isDragging = true
            localValue = next
            onDragValueChanged?.invoke(next)
        },
        onValueChangeFinished = {
            val finalValue = localValue.coerceIn(valueRange.start, valueRange.endInclusive)
            isDragging = false
            onValueCommit(finalValue)
        },
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier,
    )
}
