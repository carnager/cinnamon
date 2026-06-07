package dev.popcorn.companion

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

fun buildPlaybackProfile(context: Context): JSONObject {
    val video = linkedMapOf<String, JSONObject>()
    val audio = linkedMapOf<String, JSONObject>()
    for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
        if (info.isEncoder) continue
        for (mime in info.supportedTypes) {
            when (val codec = mimeToCodec(mime)) {
                "h264", "hevc", "vp9", "av1" -> {
                    val caps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
                    video[codec] = JSONObject()
                        .put("codec", codec)
                        .put("mime", mime)
                        .put("maxWidth", caps?.supportedWidths?.upper ?: 3840)
                        .put("maxHeight", caps?.supportedHeights?.upper ?: 2160)
                        .put("maxBitrate", caps?.bitrateRange?.upper?.toLong() ?: 80_000_000L)
                        .put("hdrFormats", JSONArray(listOf("sdr")))
                }
                "aac", "ac3", "eac3", "opus", "flac", "mp3", "vorbis" -> {
                    val caps = runCatching { info.getCapabilitiesForType(mime).audioCapabilities }.getOrNull()
                    audio[codec] = JSONObject()
                        .put("codec", codec)
                        .put("maxChannels", caps?.maxInputChannelCount?.takeIf { it > 0 } ?: 8)
                }
            }
        }
    }
    listOf("aac", "mp3", "flac", "opus", "vorbis").forEach { codec ->
        audio.putIfAbsent(codec, JSONObject().put("codec", codec).put("maxChannels", 8))
    }
    return JSONObject()
        .put("schemaVersion", 1)
        .put("client", "android-phone")
        .put("appVersionCode", appVersionCode(context))
        .put("appVersionName", appVersionName(context))
        .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        .put("osSdk", Build.VERSION.SDK_INT)
        .put("display", JSONObject()
            .put("width", context.resources.displayMetrics.widthPixels)
            .put("height", context.resources.displayMetrics.heightPixels)
            .put("hdrFormats", JSONArray())
            .put("refreshRates", JSONArray()))
        .put("protocols", JSONObject()
            .put("directFile", true)
            .put("httpRange", true)
            .put("hlsFmp4", true))
        .put("containers", JSONArray(listOf("mp4", "mkv", "webm", "m4v", "mov")))
        .put("video", JSONArray(video.values))
        .put("audio", JSONArray(audio.values))
        .put("subtitles", JSONArray(listOf("srt", "subrip", "ass", "ssa", "webvtt", "mov_text")))
}

private fun mimeToCodec(mime: String): String = when (mime.lowercase()) {
    "video/avc" -> "h264"
    "video/hevc" -> "hevc"
    "video/x-vnd.on2.vp9" -> "vp9"
    "video/av01" -> "av1"
    "audio/mp4a-latm" -> "aac"
    "audio/ac3" -> "ac3"
    "audio/eac3" -> "eac3"
    "audio/opus" -> "opus"
    "audio/flac" -> "flac"
    "audio/mpeg" -> "mp3"
    "audio/vorbis" -> "vorbis"
    else -> ""
}

private fun appVersionCode(context: Context): Int {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
}

private fun appVersionName(context: Context): String {
    return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
}
