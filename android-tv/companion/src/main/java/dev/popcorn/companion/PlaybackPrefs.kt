package dev.popcorn.companion

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object PlaybackPrefs {
    const val SUBS_OFF = "off"
    const val TRACK_DEFAULT = "default"

    var audioLang by mutableStateOf("")
        private set
    var subtitleLang by mutableStateOf(SUBS_OFF)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("popcorn-remote", Context.MODE_PRIVATE)
        audioLang = prefs.getString("prefAudioLang", "") ?: ""
        subtitleLang = prefs.getString("prefSubtitleLang", SUBS_OFF) ?: SUBS_OFF
    }

    fun setAudio(context: Context, value: String) {
        audioLang = value
        context.getSharedPreferences("popcorn-remote", Context.MODE_PRIVATE).edit().putString("prefAudioLang", value).apply()
    }

    fun setSubtitle(context: Context, value: String) {
        subtitleLang = value
        context.getSharedPreferences("popcorn-remote", Context.MODE_PRIVATE).edit().putString("prefSubtitleLang", value).apply()
    }
}

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

fun preferredAudioIndex(tracks: List<StreamInfo>): Int? {
    val preferred = PlaybackPrefs.audioLang
    return tracks.firstOrNull { langMatches(it.language, preferred) }?.index
        ?: tracks.firstOrNull { it.default }?.index
        ?: tracks.firstOrNull()?.index
}

fun preferredSubtitleIndex(tracks: List<StreamInfo>): Int? = when (val preferred = PlaybackPrefs.subtitleLang) {
    PlaybackPrefs.SUBS_OFF -> null
    PlaybackPrefs.TRACK_DEFAULT -> tracks.firstOrNull { it.default }?.index
    else -> tracks.firstOrNull { langMatches(it.language, preferred) }?.index
}
