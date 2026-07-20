package dev.popcorn.tv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// PlaybackPrefs holds the user's preferred playback languages: Compose state
// backed by SharedPreferences. The detail page reads it to pre-select tracks,
// the settings dialog writes it. Subtitles default to OFF — a sideloaded
// subtitle delays direct-play start, so subs are opt-in.
object PlaybackPrefs {
    const val SUBS_OFF = "off"
    const val TRACK_DEFAULT = "default"

    // "" = follow the file's default audio track.
    var audioLang by mutableStateOf("")
        private set

    // SUBS_OFF | TRACK_DEFAULT | a language code.
    var subtitleLang by mutableStateOf(SUBS_OFF)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("popcorn", Context.MODE_PRIVATE)
        audioLang = prefs.getString("prefAudioLang", "") ?: ""
        subtitleLang = prefs.getString("prefSubtitleLang", SUBS_OFF) ?: SUBS_OFF
    }

    fun setAudio(context: Context, value: String) {
        audioLang = value
        context.getSharedPreferences("popcorn", Context.MODE_PRIVATE)
            .edit().putString("prefAudioLang", value).apply()
    }

    fun setSubtitle(context: Context, value: String) {
        subtitleLang = value
        context.getSharedPreferences("popcorn", Context.MODE_PRIVATE)
            .edit().putString("prefSubtitleLang", value).apply()
    }
}

// normalizeLang folds the common ISO 639-1/639-2 spellings together so "ger",
// "deu" and "de" all match a "de" preference.
fun normalizeLang(value: String): String = when (value.trim().lowercase()) {
    "ger", "deu" -> "de"
    "eng" -> "en"
    "fre", "fra" -> "fr"
    "spa" -> "es"
    "ita" -> "it"
    "jpn" -> "ja"
    "kor" -> "ko"
    else -> value.trim().lowercase()
}

fun langMatches(streamLang: String, preferred: String): Boolean =
    preferred.isNotBlank() && normalizeLang(streamLang) == normalizeLang(preferred)

val prefLanguageChoices = listOf(
    "de" to "Deutsch",
    "en" to "English",
    "fr" to "Français",
    "es" to "Español",
    "it" to "Italiano",
    "ja" to "日本語",
)
