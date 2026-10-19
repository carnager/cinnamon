package dev.popcorn.companion

import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Picture-in-picture for the phone player. The player page says when leaving
 * the app should shrink the video into a window (something is playing on it)
 * and at what aspect; the activity reads that to enter PiP, and reports back
 * whether it is in PiP so the page can drop its controls.
 */
object PipState {
    @Volatile
    var allowed: Boolean = false

    @Volatile
    var aspect: Rational? = null

    var inPip by mutableStateOf(false)
}

// PiP refuses aspect ratios outside roughly 1:2.39 to 2.39:1.
fun pipAspect(width: Int, height: Int, pixelRatio: Float): Rational? {
    if (width <= 0 || height <= 0) return null
    val ratio = (width * pixelRatio / height).coerceIn(1f / 2.39f, 2.39f)
    return Rational((ratio * 1000).toInt(), 1000)
}

fun pipParams(): PictureInPictureParams = PictureInPictureParams.Builder().apply {
    PipState.aspect?.let(::setAspectRatio)
    // Android 12+ enters PiP by itself on leaving the app while this is set,
    // with a smooth animation from the video; older versions go through
    // onUserLeaveHint instead.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(PipState.allowed)
}.build()

fun ComponentActivity.updatePipParams() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { setPictureInPictureParams(pipParams()) }
}
