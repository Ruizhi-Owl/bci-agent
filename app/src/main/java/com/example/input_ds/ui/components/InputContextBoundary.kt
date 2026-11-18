package com.example.input_ds.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

val LocalInputContextChanged = staticCompositionLocalOf<() -> Unit> { {} }

/** Cancel in-flight EEG work when a menu/dialog takes or releases input ownership. */
@Composable
fun InputContextBoundary(vararg keys: Any?) {
    val invalidate = LocalInputContextChanged.current
    DisposableEffect(*keys) {
        invalidate()
        onDispose { invalidate() }
    }
}
