package server

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"popcorn/internal/config"
	"popcorn/internal/media"
)

func TestPlaybackPlanDirectH264AACMP4(t *testing.T) {
	plan := testPlan(testItem("mp4", 4_000_000), testAndroidProfile("mp4", "h264", "aac", nil), []media.MediaStream{
		testVideo(0, "h264", 1920, 1080, "sdr", 3_500_000),
		testAudio(1, "aac", 2, 192_000),
	}, PlaybackPlanRequest{})
	if plan.Mode != planModeDirect || !plan.Playable {
		t.Fatalf("mode = %s playable=%v reasons=%v, want direct", plan.Mode, plan.Playable, plan.Reasons)
	}
}

func TestPlaybackPlanAndroidUnsupportedAudioUsesFullTranscodeForSync(t *testing.T) {
	plan := testPlan(testItem("mkv", 8_000_000), testAndroidProfile("mkv", "h264", "aac", nil), []media.MediaStream{
		testVideo(0, "h264", 1920, 1080, "sdr", 7_500_000),
		testAudio(1, "dts", 6, 768_000),
	}, PlaybackPlanRequest{})
	if plan.Mode != planModeFullTranscode {
		t.Fatalf("mode = %s reasons=%v, want full-transcode", plan.Mode, plan.Reasons)
	}
	if plan.Outputs.Video.Codec != "h264" || plan.Outputs.Audio.Codec != "aac" {
		t.Fatalf("outputs = %+v, want video h264 audio aac", plan.Outputs)
	}
}

func TestPlaybackPlanAudioOnlyTranscodeForUnsupportedAudio(t *testing.T) {
	profile := testAndroidProfile("mkv", "h264", "aac", nil)
	profile.Client = "generic"
	plan := testPlan(testItem("mkv", 8_000_000), profile, []media.MediaStream{
		testVideo(0, "h264", 1920, 1080, "sdr", 7_500_000),
		testAudio(1, "dts", 6, 768_000),
	}, PlaybackPlanRequest{})
	if plan.Mode != planModeAudioTranscode {
		t.Fatalf("mode = %s reasons=%v, want audio-transcode", plan.Mode, plan.Reasons)
	}
	if plan.Outputs.Video.Codec != "copy" || plan.Outputs.Audio.Codec != "aac" {
		t.Fatalf("outputs = %+v, want video copy audio aac", plan.Outputs)
	}
}

// Resuming used to force a full re-encode so audio and video could start
// together. Copied video instead starts on the keyframe at or before the
// requested position, and the plan reports that keyframe so clients agree with
// what ffmpeg actually produces.
func TestPlaybackPlanHLSSeekRemuxesFromPrecedingKeyframe(t *testing.T) {
	profile := testAndroidProfile("mp4", "h264", "aac", nil)
	streams := []media.MediaStream{
		testVideo(0, "h264", 1920, 1080, "sdr", 7_500_000),
		testAudio(1, "aac", 2, 192_000),
	}
	initial := testPlan(testItem("mkv", 8_000_000), profile, streams, PlaybackPlanRequest{})
	if initial.Mode != planModeRemux {
		t.Fatalf("initial mode = %s reasons=%v, want remux", initial.Mode, initial.Reasons)
	}
	seek := testPlanWithKeyframes(t, profile, PlaybackPlanRequest{StartPositionMS: 13_000}, 4, 8, 12)
	if seek.Mode != planModeRemux {
		t.Fatalf("seek mode = %s reasons=%v, want remux", seek.Mode, seek.Reasons)
	}
	if seek.Outputs.Video.Codec != "copy" || seek.Outputs.Audio.Codec != "copy" {
		t.Fatalf("seek outputs = %+v, want copied streams", seek.Outputs)
	}
	if seek.StartPositionMS != 12_000 {
		t.Fatalf("start = %dms, want the 12s keyframe", seek.StartPositionMS)
	}
}

// Keyframes after the requested position must not be chosen: starting late
// would silently skip content the viewer asked to see.
func TestPlaybackPlanHLSSeekIgnoresLaterKeyframes(t *testing.T) {
	seek := testPlanWithKeyframes(t, testAndroidProfile("mp4", "h264", "aac", nil),
		PlaybackPlanRequest{StartPositionMS: 13_000}, 4, 12, 20, 28)
	if seek.StartPositionMS != 12_000 {
		t.Fatalf("start = %dms, want the 12s keyframe", seek.StartPositionMS)
	}
}

// With no keyframe to snap to, the start would be off by an unknown amount, so
// the old accurate-seek transcode has to take over.
func TestPlaybackPlanHLSSeekFallsBackWhenNoKeyframeFound(t *testing.T) {
	seek := testPlanWithKeyframes(t, testAndroidProfile("mp4", "h264", "aac", nil),
		PlaybackPlanRequest{StartPositionMS: 13_000}, 20, 28)
	if seek.Mode != planModeFullTranscode {
		t.Fatalf("seek mode = %s reasons=%v, want full-transcode", seek.Mode, seek.Reasons)
	}
	if seek.Outputs.Video.Codec != "h264" || seek.Outputs.Audio.Codec != "aac" {
		t.Fatalf("seek outputs = %+v, want decoded h264/aac", seek.Outputs)
	}
	if seek.StartPositionMS != 13_000 {
		t.Fatalf("start = %dms, want the requested position preserved", seek.StartPositionMS)
	}
}

// A start of zero needs no probe at all — the file already begins on a keyframe.
func TestPlaybackPlanHLSStartAtZeroSkipsKeyframeProbe(t *testing.T) {
	plan := testPlan(testItem("mkv", 8_000_000), testAndroidProfile("mp4", "h264", "aac", nil), []media.MediaStream{
		testVideo(0, "h264", 1920, 1080, "sdr", 7_500_000),
		testAudio(1, "aac", 2, 192_000),
	}, PlaybackPlanRequest{})
	if plan.Mode != planModeRemux || plan.StartPositionMS != 0 {
		t.Fatalf("plan mode = %s start = %d, want remux at 0", plan.Mode, plan.StartPositionMS)
	}
}

func TestPlaybackPlanBitrateCapForcesFullTranscode(t *testing.T) {
	cap := 8000
	plan := testPlan(testItem("mkv", 40_000_000), testAndroidProfile("mkv", "hevc", "eac3", []string{"hdr10"}), []media.MediaStream{
		testVideo(0, "hevc", 3840, 2160, "sdr", 38_000_000),
		testAudio(1, "eac3", 6, 768_000),
	}, PlaybackPlanRequest{BandwidthKbps: &cap})
	if plan.Mode != planModeFullTranscode {
		t.Fatalf("mode = %s reasons=%v, want full-transcode", plan.Mode, plan.Reasons)
	}
}

func TestPlaybackPlanHDRSupport(t *testing.T) {
	streams := []media.MediaStream{
		testVideo(0, "hevc", 3840, 2160, "hdr10", 20_000_000),
		testAudio(1, "eac3", 6, 768_000),
	}
	withHDR := testPlan(testItem("mkv", 21_000_000), testAndroidProfile("mkv", "hevc", "eac3", []string{"hdr10"}), streams, PlaybackPlanRequest{})
	if withHDR.Mode != planModeDirect {
		t.Fatalf("hdr-capable mode = %s reasons=%v, want direct", withHDR.Mode, withHDR.Reasons)
	}
	withoutHDR := testPlan(testItem("mkv", 21_000_000), testAndroidProfile("mkv", "hevc", "eac3", nil), streams, PlaybackPlanRequest{})
	if withoutHDR.Mode != planModeFullTranscode {
		t.Fatalf("no-hdr mode = %s reasons=%v, want full-transcode", withoutHDR.Mode, withoutHDR.Reasons)
	}
}

// An image subtitle is disabled in every mode, so selecting one must not cost
// the viewer direct play — they would get the same picture, plus a transcode.
func TestPlaybackPlanImageSubtitleKeepsDirectPlay(t *testing.T) {
	pgs := 2
	plan := testPlan(testItem("mkv", 4_000_000), testAndroidProfile("mkv", "h264", "aac", nil), []media.MediaStream{
		testVideo(0, "h264", 1920, 1080, "sdr", 3_500_000),
		testAudio(1, "aac", 2, 192_000),
		{Index: 2, Type: "subtitle", Codec: "hdmv_pgs_subtitle"},
	}, PlaybackPlanRequest{SubtitleIndex: &pgs})
	if plan.Mode != planModeDirect {
		t.Fatalf("mode = %s reasons=%v, want direct", plan.Mode, plan.Reasons)
	}
	// Direct play hands over the original file, so the track goes with it and the
	// client renders it or not on its own. That beats transcoding to a stream the
	// subtitle has been stripped out of.
	if plan.Outputs.Subtitle.Codec != "hdmv_pgs_subtitle" {
		t.Fatalf("subtitle output = %q, want the original track passed through", plan.Outputs.Subtitle.Codec)
	}
}

// A text subtitle the client cannot render itself still needs the server to
// convert it, which only the HLS path can do.
func TestPlaybackPlanUnsupportedTextSubtitleLeavesDirectPlay(t *testing.T) {
	profile := testAndroidProfile("mkv", "h264", "aac", nil)
	profile.Subtitles = []string{"webvtt"}
	srt := 2
	plan := testPlan(testItem("mkv", 4_000_000), profile, []media.MediaStream{
		testVideo(0, "h264", 1920, 1080, "sdr", 3_500_000),
		testAudio(1, "aac", 2, 192_000),
		{Index: 2, Type: "subtitle", Codec: "subrip"},
	}, PlaybackPlanRequest{SubtitleIndex: &srt})
	if plan.Mode == planModeDirect {
		t.Fatalf("mode = %s, want an HLS mode that can convert the subtitle", plan.Mode)
	}
}

func TestPlaybackPlanSubtitlePolicies(t *testing.T) {
	profile := testAndroidProfile("mkv", "h264", "aac", nil)
	// Once something else has already forced HLS — here the audio — an image
	// subtitle cannot ride along, because nothing in that pipeline renders it.
	pgs := 2
	plan := testPlan(testItem("mkv", 4_000_000), profile, []media.MediaStream{
		testVideo(0, "h264", 1920, 1080, "sdr", 3_500_000),
		testAudio(1, "dts", 6, 768_000),
		{Index: 2, Type: "subtitle", Codec: "hdmv_pgs_subtitle"},
	}, PlaybackPlanRequest{SubtitleIndex: &pgs})
	if plan.Mode == planModeDirect {
		t.Fatalf("mode = %s, want an HLS mode for the unsupported audio", plan.Mode)
	}
	if plan.Outputs.Subtitle.Codec != "none" {
		t.Fatalf("pgs subtitle output = %q, want disabled", plan.Outputs.Subtitle.Codec)
	}
	srt := 3
	plan = testPlan(testItem("mkv", 4_000_000), profile, []media.MediaStream{
		testVideo(0, "h264", 1920, 1080, "sdr", 3_500_000),
		testAudio(1, "aac", 2, 192_000),
		{Index: 3, Type: "subtitle", Codec: "subrip"},
	}, PlaybackPlanRequest{SubtitleIndex: &srt})
	if plan.Outputs.Subtitle.Codec == "none" {
		t.Fatalf("srt subtitle was disabled: %+v", plan)
	}
}

func TestPlaybackPlanForceDirectFailsWhenAudioUnsupported(t *testing.T) {
	plan := testPlan(testItem("mkv", 8_000_000), testAndroidProfile("mkv", "h264", "aac", nil), []media.MediaStream{
		testVideo(0, "h264", 1920, 1080, "sdr", 7_500_000),
		testAudio(1, "dts", 6, 768_000),
	}, PlaybackPlanRequest{ForceMode: "direct"})
	if plan.Playable || plan.Mode != planModeDirect {
		t.Fatalf("plan = %+v, want unplayable direct", plan)
	}
}

func TestHLSPlanArgsAudioOnlyTranscodeCopiesVideo(t *testing.T) {
	audio := 4
	plan := PlaybackPlan{
		Selected:      PlaybackSelection{VideoIndex: 0, AudioIndex: &audio},
		BandwidthKbps: 5000,
		Outputs: PlaybackOutputs{
			Video: PlaybackOutputStream{Codec: "copy"},
			Audio: PlaybackOutputStream{Codec: "aac", BitrateKbps: 192},
		},
	}
	args := hlsPlanArgs(config.Config{}, "/media/movie.mkv", "/tmp/seg_%05d.m4s", "/tmp/index.m3u8", plan)
	if !containsPair(args, "-c:v", "copy") || !containsPair(args, "-c:a", "aac") || !containsPair(args, "-map", "0:4?") {
		t.Fatalf("args = %v, want video copy, audio aac, selected audio map", args)
	}
}

func TestHLSPlanArgsRemuxCopiesVideoAndAudio(t *testing.T) {
	audio := 1
	plan := PlaybackPlan{
		Selected: PlaybackSelection{VideoIndex: 0, AudioIndex: &audio},
		Outputs: PlaybackOutputs{
			Video: PlaybackOutputStream{Codec: "copy"},
			Audio: PlaybackOutputStream{Codec: "copy"},
		},
	}
	args := hlsPlanArgs(config.Config{}, "/media/movie.mkv", "/tmp/seg_%05d.m4s", "/tmp/index.m3u8", plan)
	if !containsPair(args, "-c:v", "copy") || !containsPair(args, "-c:a", "copy") {
		t.Fatalf("args = %v, want copy remux", args)
	}
}

func TestHLSPlanArgsCopiedVideoAvoidsDesynchronizingOutputSeek(t *testing.T) {
	audio := 1
	plan := PlaybackPlan{
		StartPositionMS: 13_000,
		Selected:        PlaybackSelection{VideoIndex: 0, AudioIndex: &audio},
		Outputs: PlaybackOutputs{
			Video: PlaybackOutputStream{Codec: "copy"},
			Audio: PlaybackOutputStream{Codec: "copy"},
		},
	}
	args := hlsPlanArgs(config.Config{}, "/media/movie.mkv", "/tmp/seg_%05d.m4s", "/tmp/index.m3u8", plan)
	input := indexOf(args, "-i")
	if input < 2 || args[input-2] != "-ss" || args[input-1] != "13.000" {
		t.Fatalf("args = %v, want input seek at 13.000", args)
	}
	if contains(args[input+1:], "-ss") {
		t.Fatalf("args = %v, copied video must not use a desynchronizing output seek", args)
	}
}

func TestHLSPlanArgsFullTranscodeUsesHardwareCodecArgs(t *testing.T) {
	audio := 1
	plan := PlaybackPlan{
		Selected:      PlaybackSelection{VideoIndex: 0, AudioIndex: &audio},
		BandwidthKbps: 5000,
		Item:          media.Item{VideoCodec: "h264"},
		Outputs: PlaybackOutputs{
			Video: PlaybackOutputStream{Codec: "h264"},
			Audio: PlaybackOutputStream{Codec: "aac"},
		},
	}
	args := hlsPlanArgs(config.Config{HWAccel: "qsv"}, "/media/movie.mkv", "/tmp/seg_%05d.m4s", "/tmp/index.m3u8", plan)
	if !containsPair(args, "-c:v", "h264_qsv") || !containsPair(args, "-c:a", "aac") {
		t.Fatalf("args = %v, want qsv h264/aac", args)
	}
	// A QSV-decodable source uses GPU decode + the vpp_qsv filter.
	if !containsPair(args, "-hwaccel", "qsv") || !containsPair(args, "-vf", "vpp_qsv=format=nv12") {
		t.Fatalf("args = %v, want qsv hardware decode + vpp_qsv", args)
	}
}

func TestHLSPlanArgsQSVSeekSoftwareDecodesForAccurateContent(t *testing.T) {
	audio := 1
	plan := PlaybackPlan{
		StartPositionMS: 13_000,
		Selected:        PlaybackSelection{VideoIndex: 0, AudioIndex: &audio},
		Item:            media.Item{VideoCodec: "h264"},
		Outputs: PlaybackOutputs{
			Video: PlaybackOutputStream{Codec: "h264"},
			Audio: PlaybackOutputStream{Codec: "aac"},
		},
	}
	args := hlsPlanArgs(config.Config{HWAccel: "qsv"}, "/media/movie.mkv", "/tmp/seg_%05d.m4s", "/tmp/index.m3u8", plan)
	if containsPair(args, "-hwaccel", "qsv") {
		t.Fatalf("args = %v, seek must software-decode so QSV cannot jump video to a later keyframe", args)
	}
	if containsPair(args, "-vf", "vpp_qsv=format=nv12") {
		t.Fatalf("args = %v, software-decoded frames must not use vpp_qsv", args)
	}
	if !containsPair(args, "-vf", "format=nv12") || !containsPair(args, "-c:v", "h264_qsv") {
		t.Fatalf("args = %v, want software decode with QSV encode", args)
	}
}

func TestHLSPlanArgsSoftwareDecodesQSVUnsupportedSource(t *testing.T) {
	audio := 1
	// MPEG-4 ASP (DivX/Xvid) cannot be hardware-decoded by QSV; forcing it emits
	// broken timestamps and produces no output. The transcoder must fall back to
	// software decode while still encoding with h264_qsv.
	plan := PlaybackPlan{
		Selected:      PlaybackSelection{VideoIndex: 0, AudioIndex: &audio},
		BandwidthKbps: 5000,
		Item:          media.Item{VideoCodec: "mpeg4"},
		Outputs: PlaybackOutputs{
			Video: PlaybackOutputStream{Codec: "h264"},
			Audio: PlaybackOutputStream{Codec: "aac"},
		},
	}
	args := hlsPlanArgs(config.Config{HWAccel: "qsv"}, "/media/movie.mkv", "/tmp/seg_%05d.m4s", "/tmp/index.m3u8", plan)
	if containsPair(args, "-hwaccel", "qsv") {
		t.Fatalf("args = %v, want software decode (no -hwaccel qsv)", args)
	}
	if containsPair(args, "-vf", "vpp_qsv=format=nv12") {
		t.Fatalf("args = %v, want CPU format conversion, not vpp_qsv", args)
	}
	if !containsPair(args, "-vf", "format=nv12") || !containsPair(args, "-c:v", "h264_qsv") {
		t.Fatalf("args = %v, want format=nv12 + h264_qsv", args)
	}
}

func testPlan(item media.Item, profile PlaybackProfile, streams []media.MediaStream, req PlaybackPlanRequest) PlaybackPlan {
	req.ItemID = item.ID
	req.Profile = profile
	return (&App{}).buildPlaybackPlan(context.Background(), 1, item, streams, req, false)
}

// testPlanWithKeyframes plans against a real file whose video keyframes sit at
// the given timestamps, so the keyframe probe has something to find.
func testPlanWithKeyframes(t *testing.T, profile PlaybackProfile, req PlaybackPlanRequest, keyframes ...float64) PlaybackPlan {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "movie.mkv")
	if err := os.WriteFile(path, []byte("fake video"), 0o644); err != nil {
		t.Fatalf("write video: %v", err)
	}
	// Stand in for ffprobe: emit one keyframe packet per timestamp, plus a
	// non-keyframe in between so the flag parsing is actually exercised.
	var lines strings.Builder
	for _, ts := range keyframes {
		fmt.Fprintf(&lines, "%.6f,K__\\n%.6f,___\\n", ts, ts+0.04)
	}
	probe := filepath.Join(dir, "ffprobe")
	if err := os.WriteFile(probe, []byte("#!/bin/sh\nprintf '"+lines.String()+"'\n"), 0o755); err != nil {
		t.Fatalf("write fake ffprobe: %v", err)
	}
	item := media.Item{ID: 123, Path: path, Container: "mkv", DurationMS: 7_200_000, BitRate: 8_000_000}
	req.ItemID = item.ID
	req.Profile = profile
	streams := []media.MediaStream{
		testVideo(0, "h264", 1920, 1080, "sdr", 7_500_000),
		testAudio(1, "aac", 2, 192_000),
	}
	app := &App{cfg: config.Config{FFprobePath: probe}, log: slog.New(slog.DiscardHandler)}
	return app.buildPlaybackPlan(context.Background(), 1, item, streams, req, false)
}

func testItem(container string, bitRate int64) media.Item {
	return media.Item{ID: 123, Path: "/media/movie." + container, Container: container, DurationMS: 7_200_000, BitRate: bitRate}
}

func testVideo(index int, codec string, width, height int, hdr string, bitRate int64) media.MediaStream {
	return media.MediaStream{Index: index, Type: "video", Codec: codec, Width: width, Height: height, HDRFormat: hdr, BitRate: bitRate}
}

func testAudio(index int, codec string, channels int, bitRate int64) media.MediaStream {
	return media.MediaStream{Index: index, Type: "audio", Codec: codec, Channels: channels, BitRate: bitRate, Default: true}
}

func testAndroidProfile(container, video, audio string, hdr []string) PlaybackProfile {
	if hdr == nil {
		hdr = []string{"sdr"}
	}
	return PlaybackProfile{
		Client:     "android-tv",
		Containers: []string{container},
		Protocols:  PlaybackProtocols{DirectFile: true, HTTPRange: true, HLSFMP4: true},
		Display:    PlaybackDisplay{HDRFormats: hdr},
		Video: []PlaybackVideoCodec{{
			Codec:      video,
			MaxWidth:   3840,
			MaxHeight:  2160,
			MaxBitrate: 80_000_000,
			HDRFormats: hdr,
		}},
		Audio:     []PlaybackAudioCodec{{Codec: audio, MaxChannels: 8}},
		Subtitles: []string{"subrip", "srt", "ass", "ssa", "webvtt", "mov_text"},
	}
}
