package server

import (
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"popcorn/internal/config"
)

func TestTranscodeSeekArgsUsesFastInputSeekForLargeJumps(t *testing.T) {
	inputSeek, outputSeek := transcodeSeekArgs(2761.333)
	if want := []string{"-ss", "2753.333"}; !slices.Equal(inputSeek, want) {
		t.Fatalf("input seek = %#v, want %#v", inputSeek, want)
	}
	if want := []string{"-ss", "8.000"}; !slices.Equal(outputSeek, want) {
		t.Fatalf("output seek = %#v, want %#v", outputSeek, want)
	}
}

func TestTranscodeSeekArgsUsesAccurateOutputSeekNearStart(t *testing.T) {
	inputSeek, outputSeek := transcodeSeekArgs(4.25)
	if len(inputSeek) != 0 {
		t.Fatalf("input seek = %#v, want none", inputSeek)
	}
	if want := []string{"-ss", "4.250"}; !slices.Equal(outputSeek, want) {
		t.Fatalf("output seek = %#v, want %#v", outputSeek, want)
	}
}

func TestHLSArgsPlaceSeekAroundInputAndMapRequestedTracks(t *testing.T) {
	audio := 4
	subtitle := 7
	args := hlsArgs(config.Config{}, "/media/movie.mkv", "/tmp/seg_%05d.m4s", "/tmp/index.m3u8", 5000, 120.5, &audio, &subtitle)
	inputIndex := indexOf(args, "-i")
	if inputIndex < 0 {
		t.Fatalf("missing -i in args: %v", args)
	}
	if got := strings.Join(args[:inputIndex], " "); !strings.Contains(got, "-ss 112.500") {
		t.Fatalf("args before -i = %q, want input seek to 112.500", got)
	}
	if got := strings.Join(args[inputIndex+2:], " "); !strings.Contains(got, "-ss 8.000") {
		t.Fatalf("args after input = %q, want output seek to 8.000", got)
	}
	if !containsPair(args, "-map", "0:4?") {
		t.Fatalf("args missing requested audio map 0:4?: %v", args)
	}
	if !containsPair(args, "-map", "0:7?") {
		t.Fatalf("args missing requested subtitle map 0:7?: %v", args)
	}
	if contains(args, "-sn") {
		t.Fatalf("args include -sn even though a subtitle stream was requested: %v", args)
	}
}

func TestCleanSessionID(t *testing.T) {
	valid := []string{"android_1_123", "abc-DEF_123", strings.Repeat("a", 64)}
	for _, id := range valid {
		if got := cleanSessionID(id); got != id {
			t.Fatalf("cleanSessionID(%q) = %q, want unchanged", id, got)
		}
	}
	invalid := []string{"../x", "with space", "semi;colon", strings.Repeat("a", 65)}
	for _, id := range invalid {
		if got := cleanSessionID(id); got != "" {
			t.Fatalf("cleanSessionID(%q) = %q, want empty", id, got)
		}
	}
}

func TestHLSSessionOwnerUsesDeviceScopedAndroidIDs(t *testing.T) {
	if got, want := hlsSessionOwner("android_dev_ddf855546f1512d4322d0847_1326_1780598803359"), "android_dev_ddf855546f1512d4322d0847"; got != want {
		t.Fatalf("hlsSessionOwner = %q, want %q", got, want)
	}
	if got, want := hlsSessionOwner("phone_user_aruntha_1326_1780598803359"), "phone_user_aruntha"; got != want {
		t.Fatalf("phone hlsSessionOwner = %q, want %q", got, want)
	}
	if got, want := hlsSessionOwner("android_1326_1780598803359"), "android"; got != want {
		t.Fatalf("legacy hlsSessionOwner = %q, want %q", got, want)
	}
}

func TestTranscodeRatesCapStereoAACBitrate(t *testing.T) {
	videoRate, audioRate := transcodeRates(5000)
	if videoRate != 4808 || audioRate != 192 {
		t.Fatalf("transcodeRates(5000) = %d/%d, want 4808/192", videoRate, audioRate)
	}
	videoRate, audioRate = transcodeRates(500)
	if videoRate != 404 || audioRate != 96 {
		t.Fatalf("transcodeRates(500) = %d/%d, want 404/96", videoRate, audioRate)
	}
}

func TestRewritePlaylistSegments(t *testing.T) {
	path := filepath.Join(t.TempDir(), "index.m3u8")
	input := strings.Join([]string{
		"#EXTM3U",
		`#EXT-X-MAP:URI="init.mp4"`,
		"#EXTINF:4.000,",
		"seg_00001.m4s",
		"#EXTINF:4.000,",
		"/api/items/1/hls/session/seg_00002.m4s",
		"",
	}, "\n")
	if err := os.WriteFile(path, []byte(input), 0o644); err != nil {
		t.Fatal(err)
	}
	prefix := "/api/items/1/hls/session/"
	if err := rewritePlaylistSegments(path, prefix); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	got := string(b)
	if !strings.Contains(got, `#EXT-X-MAP:URI="`+prefix+`init.mp4"`) {
		t.Fatalf("rewritten playlist missing prefixed init.mp4:\n%s", got)
	}
	if !strings.Contains(got, prefix+"seg_00001.m4s") {
		t.Fatalf("rewritten playlist missing prefixed segment:\n%s", got)
	}
	if strings.Count(got, prefix+"seg_00002.m4s") != 1 {
		t.Fatalf("already-prefixed segment should not be duplicated:\n%s", got)
	}
}

func indexOf(values []string, target string) int {
	for i, value := range values {
		if value == target {
			return i
		}
	}
	return -1
}

func contains(values []string, target string) bool {
	return indexOf(values, target) >= 0
}

func containsPair(values []string, key, value string) bool {
	for i := 0; i+1 < len(values); i++ {
		if values[i] == key && values[i+1] == value {
			return true
		}
	}
	return false
}
