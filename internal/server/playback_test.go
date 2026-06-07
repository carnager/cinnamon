package server

import (
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

func TestPlaybackPlanSubtitlePolicies(t *testing.T) {
	profile := testAndroidProfile("mkv", "h264", "aac", nil)
	pgs := 2
	plan := testPlan(testItem("mkv", 4_000_000), profile, []media.MediaStream{
		testVideo(0, "h264", 1920, 1080, "sdr", 3_500_000),
		testAudio(1, "aac", 2, 192_000),
		{Index: 2, Type: "subtitle", Codec: "hdmv_pgs_subtitle"},
	}, PlaybackPlanRequest{SubtitleIndex: &pgs})
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

func TestHLSPlanArgsFullTranscodeUsesHardwareCodecArgs(t *testing.T) {
	audio := 1
	plan := PlaybackPlan{
		Selected:      PlaybackSelection{VideoIndex: 0, AudioIndex: &audio},
		BandwidthKbps: 5000,
		Outputs: PlaybackOutputs{
			Video: PlaybackOutputStream{Codec: "h264"},
			Audio: PlaybackOutputStream{Codec: "aac"},
		},
	}
	args := hlsPlanArgs(config.Config{HWAccel: "qsv"}, "/media/movie.mkv", "/tmp/seg_%05d.m4s", "/tmp/index.m3u8", plan)
	if !containsPair(args, "-c:v", "h264_qsv") || !containsPair(args, "-c:a", "aac") {
		t.Fatalf("args = %v, want qsv h264/aac", args)
	}
}

func testPlan(item media.Item, profile PlaybackProfile, streams []media.MediaStream, req PlaybackPlanRequest) PlaybackPlan {
	req.ItemID = item.ID
	req.Profile = profile
	return (&App{}).buildPlaybackPlan(1, item, streams, req, false)
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
