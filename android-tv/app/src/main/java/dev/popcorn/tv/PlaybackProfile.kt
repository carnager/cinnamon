package dev.popcorn.tv

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioCapabilities
import org.json.JSONArray
import org.json.JSONObject

fun buildPlaybackProfile(context: Context): JSONObject {
    val display = context.display
    val hdrFormats = displayHdrFormats(display)
    val video = linkedMapOf<String, JSONObject>()
    val audio = linkedMapOf<String, JSONObject>()
    for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
        if (info.isEncoder) continue
        for (mime in info.supportedTypes) {
            when (val codec = mimeToCodec(mime)) {
                "h264", "hevc", "vp9", "av1" -> {
                    val caps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
                    val entry = JSONObject()
                        .put("codec", codec)
                        .put("mime", mime)
                        .put("maxWidth", caps?.supportedWidths?.upper ?: 3840)
                        .put("maxHeight", caps?.supportedHeights?.upper ?: 2160)
                        .put("maxBitrate", caps?.bitrateRange?.upper?.toLong() ?: 80_000_000L)
                    val supportedHdr = JSONArray().put("sdr")
                    hdrFormats.forEach { supportedHdr.put(it) }
                    entry.put("hdrFormats", supportedHdr)
                    video[codec] = entry
                }
                "aac", "ac3", "eac3", "dts", "truehd", "opus", "flac", "mp3", "vorbis" -> {
                    val caps = runCatching { info.getCapabilitiesForType(mime).audioCapabilities }.getOrNull()
                    audio[codec] = JSONObject()
                        .put("codec", codec)
                        .put("maxChannels", caps?.maxInputChannelCount?.takeIf { it > 0 } ?: 8)
                }
            }
        }
    }
    // Bitstream formats are usually played by passing them through to the
    // receiver rather than decoding them; MediaCodecList only lists decoders and
    // misses audio-sink capabilities. Ask the sink what the attached amplifier
    // actually accepts — reporting DTS the device cannot output would be worse
    // than transcoding, and not reporting DTS it can output costs a needless
    // re-encode of the whole film.
    val sinkCaps = runCatching { AudioCapabilities.getCapabilities(context) }.getOrNull()
    fun addIfSinkSupports(encoding: Int, codec: String, channels: Int) {
        if (sinkCaps?.supportsEncoding(encoding) == true) {
            audio.putIfAbsent(codec, JSONObject().put("codec", codec).put("maxChannels", channels))
        }
    }
    addIfSinkSupports(C.ENCODING_AC3, "ac3", 6)
    addIfSinkSupports(C.ENCODING_E_AC3, "eac3", 8)
    addIfSinkSupports(C.ENCODING_DTS, "dts", 6)
    addIfSinkSupports(C.ENCODING_DTS_HD, "dts-hd", 8)
    addIfSinkSupports(C.ENCODING_DOLBY_TRUEHD, "truehd", 8)
    listOf("aac", "mp3", "flac", "opus", "vorbis").forEach { codec ->
        audio.putIfAbsent(codec, JSONObject().put("codec", codec).put("maxChannels", 8))
    }
    return JSONObject()
        .put("schemaVersion", 1)
        .put("client", "android-tv")
        .put("appVersionCode", appVersionCode(context))
        .put("appVersionName", appVersionName(context))
        .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        .put("osSdk", Build.VERSION.SDK_INT)
        .put("display", JSONObject()
            .put("width", display?.mode?.physicalWidth ?: 1920)
            .put("height", display?.mode?.physicalHeight ?: 1080)
            .put("hdrFormats", JSONArray(hdrFormats))
            .put("refreshRates", JSONArray().apply {
                display?.supportedModes?.forEach { put(it.refreshRate.toDouble()) }
            }))
        .put("protocols", JSONObject()
            .put("directFile", true)
            .put("httpRange", true)
            .put("hlsFmp4", true))
        .put("containers", JSONArray(listOf("mkv", "mp4", "webm", "m4v", "mov")))
        .put("video", JSONArray(video.values))
        .put("audio", JSONArray(audio.values))
        .put("subtitles", JSONArray(listOf("srt", "subrip", "ass", "ssa", "webvtt", "mov_text")))
}

private fun displayHdrFormats(display: Display?): List<String> {
    val caps = display?.hdrCapabilities ?: return emptyList()
    return caps.supportedHdrTypes.toList().mapNotNull {
        when (it) {
            Display.HdrCapabilities.HDR_TYPE_HDR10,
            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "hdr10"
            Display.HdrCapabilities.HDR_TYPE_HLG -> "hlg"
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "dolby_vision"
            else -> null
        }
    }.distinct()
}

private fun mimeToCodec(mime: String): String = when (mime.lowercase()) {
    "video/avc" -> "h264"
    "video/hevc" -> "hevc"
    "video/x-vnd.on2.vp9" -> "vp9"
    "video/av01" -> "av1"
    "audio/mp4a-latm" -> "aac"
    "audio/ac3" -> "ac3"
    "audio/eac3" -> "eac3"
    "audio/vnd.dts" -> "dts"
    "audio/vnd.dts.hd" -> "dts-hd"
    "audio/true-hd" -> "truehd"
    "audio/opus" -> "opus"
    "audio/flac" -> "flac"
    "audio/mpeg" -> "mp3"
    "audio/vorbis" -> "vorbis"
    else -> ""
}

fun appVersionCode(context: Context): Int {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
}

fun appVersionName(context: Context): String {
    return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
}
