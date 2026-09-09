package server

import (
	"bufio"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestFormatVTTTimeElidesHoursLikeFFmpeg(t *testing.T) {
	cases := []struct {
		ms   int64
		want string
	}{
		{0, "00:00.000"},
		{1014431, "16:54.431"},
		{3979393, "01:06:19.393"},
		{-5, "00:00.000"},
	}
	for _, c := range cases {
		if got := formatVTTTime(c.ms); got != c.want {
			t.Errorf("formatVTTTime(%d) = %q, want %q", c.ms, got, c.want)
		}
	}
}

func TestFormatASSCentisRoundsAndCarries(t *testing.T) {
	cases := []struct {
		ms   int64
		want string
	}{
		{3979393, "1:06:19.39"},
		{89845, "0:01:29.85"}, // rounds up, as ffmpeg does
		{93335, "0:01:33.34"},
		{89999, "0:01:30.00"}, // the carry must reach the seconds field
		{0, "0:00:00.00"},
	}
	for _, c := range cases {
		if got := formatASSCentis(assCentis(c.ms)); got != c.want {
			t.Errorf("formatASSCentis(assCentis(%d)) = %q, want %q", c.ms, got, c.want)
		}
	}
}

// ffmpeg writes a cue's end as its rounded start plus its rounded duration.
// Rounding the summed end instead lands a centisecond either side: a block at
// 62.276s lasting 2.788s ends at 62.28+2.79, not round(65.064).
func TestRenderASSEndIsStartPlusRoundedDuration(t *testing.T) {
	cues := []subtitleCue{{StartMS: 62276, EndMS: 65064, Text: "0,0,Default,,0,0,0,,x"}}
	got := string(renderASS([]byte("[Events]\n"), cues, 0))
	if !strings.Contains(got, "0:01:02.28,0:01:05.07,") {
		t.Fatalf("end time not derived from the duration:\n%s", got)
	}
}

// Matroska leaves Layer empty on many tracks; emitting a bare comma produces a
// malformed script that libass rejects.
func TestRenderASSDefaultsEmptyLayer(t *testing.T) {
	cues := []subtitleCue{{StartMS: 1000, EndMS: 2000, Text: "7,,Default,,0,0,0,,Hallo"}}
	got := string(renderASS([]byte("[Events]\n"), cues, 0))
	if !strings.Contains(got, "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hallo") {
		t.Fatalf("dialogue line malformed:\n%s", got)
	}
}

// The source SRT in a Matroska block keeps its CRLF line endings; ffmpeg
// normalised them, so the index path has to as well or the two outputs diverge.
func TestNormalizeSubtitleTextCollapsesCRLFAndNULs(t *testing.T) {
	got := normalizeSubtitleText("Kannst du meine Schicht\r\nmorgen übernehmen?\x00")
	if strings.Contains(got, "\r") {
		t.Fatalf("carriage return survived: %q", got)
	}
	if strings.IndexByte(got, 0) >= 0 {
		t.Fatalf("NUL survived: %q", got)
	}
}

// A cue selected part-way into a film is rebased to the seek point and anything
// already finished is dropped — the same shape an input-seeked ffmpeg run
// produced, but without reading the file again.
func TestRenderWebVTTRebasesFromStart(t *testing.T) {
	cues := []subtitleCue{
		{StartMS: 1000, EndMS: 2000, Text: "gone"},
		{StartMS: 10000, EndMS: 12000, Text: "kept"},
	}
	got := string(renderWebVTT(cues, false, 5000))
	if strings.Contains(got, "gone") {
		t.Fatalf("cue before the seek point survived:\n%s", got)
	}
	if !strings.Contains(got, "00:05.000 --> 00:07.000") {
		t.Fatalf("cue was not rebased:\n%s", got)
	}
}

// A cue still on screen at the seek point is kept, clamped to zero.
func TestRenderWebVTTKeepsCueSpanningTheSeekPoint(t *testing.T) {
	cues := []subtitleCue{{StartMS: 4000, EndMS: 9000, Text: "spanning"}}
	got := string(renderWebVTT(cues, false, 5000))
	if !strings.Contains(got, "00:00.000 --> 00:04.000") {
		t.Fatalf("spanning cue not clamped:\n%s", got)
	}
}

func TestSSADialogueTextStripsPositioningKeepsEmphasis(t *testing.T) {
	cases := []struct {
		name  string
		block string
		want  string
	}{
		{"positioning dropped", "0,0,Default,,0,0,0,,{\\an8}Hallo\\NWelt", "Hallo\nWelt"},
		{"italics become tags", "0,0,Default,,0,0,0,,{\\i1}SULTENFUSS{\\i0}", "<i>SULTENFUSS</i>"},
		{"unclosed italics close at the end", "0,0,Default,,0,0,0,,{\\i1}They' re dead?", "<i>They' re dead?</i>"},
		{"bold weight", "0,0,Default,,0,0,0,,{\\b700}fett{\\b0}", "<b>fett</b>"},
		{"weight below bold is not bold", "0,0,Default,,0,0,0,,{\\b400}normal", "normal"},
		{"nested tags stay nested", "0,0,Default,,0,0,0,,{\\i1}a{\\b1}b{\\i0}c", "<i>a<b>b</b></i><b>c</b>"},
		{"colour overrides dropped", "0,0,Default,,0,0,0,,{\\c&HFFFFFF&}weiss", "weiss"},
		{"hard space", "0,0,Default,,0,0,0,,a\\hb", "a b"},
	}
	for _, c := range cases {
		if got := ssaDialogueText(c.block); got != c.want {
			t.Errorf("%s: ssaDialogueText = %q, want %q", c.name, got, c.want)
		}
	}
}

func TestParseVintWidths(t *testing.T) {
	if v, n := parseVint([]byte{0x81}); v != 1 || n != 1 {
		t.Fatalf("parseVint(0x81) = %d,%d want 1,1", v, n)
	}
	if v, n := parseVint([]byte{0x41, 0x02}); v != 258 || n != 2 {
		t.Fatalf("parseVint(0x4102) = %d,%d want 258,2", v, n)
	}
	if _, n := parseVint([]byte{0x00}); n != 0 {
		t.Fatalf("parseVint(0x00) should be rejected")
	}
}

// A file that cannot be read through its index must say so rather than return
// half a track, so the handler falls back to the ffmpeg conversion.
func TestMatroskaSubtitleCuesRejectsNonMatroska(t *testing.T) {
	path := filepath.Join(t.TempDir(), "not.mkv")
	if err := os.WriteFile(path, []byte("this is not matroska"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := matroskaSubtitleCues(path, 0); err != errNoSubtitleIndex {
		t.Fatalf("err = %v, want errNoSubtitleIndex", err)
	}
}

type probeStream struct {
	Index     int    `json:"index"`
	CodecType string `json:"codec_type"`
	CodecName string `json:"codec_name"`
}

// TestIndexedSubtitleMatchesFFmpeg checks the index path against the tool it
// replaces, over real files. Point POPCORN_SUBTITLE_CORPUS at a file listing
// one media path per line.
func TestIndexedSubtitleMatchesFFmpeg(t *testing.T) {
	list := os.Getenv("POPCORN_SUBTITLE_CORPUS")
	if list == "" {
		t.Skip("set POPCORN_SUBTITLE_CORPUS to a file listing media paths")
	}
	f, err := os.Open(list)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 1<<20), 1<<20)
	checked, skipped := 0, 0
	for scanner.Scan() {
		path := strings.TrimSpace(scanner.Text())
		if path == "" {
			continue
		}
		out, err := exec.Command("ffprobe", "-v", "error", "-show_streams",
			"-select_streams", "s", "-of", "json", path).Output()
		if err != nil {
			t.Errorf("%s: ffprobe: %v", filepath.Base(path), err)
			continue
		}
		var probed struct {
			Streams []probeStream `json:"streams"`
		}
		if err := json.Unmarshal(out, &probed); err != nil {
			t.Errorf("%s: parse ffprobe: %v", filepath.Base(path), err)
			continue
		}
		for _, s := range probed.Streams {
			if !isTextSubtitleCodec(s.CodecName) {
				continue
			}
			indexStart := time.Now()
			cues, track, err := matroskaSubtitleCues(path, s.Index)
			indexTook := time.Since(indexStart)
			if err != nil {
				t.Logf("%s#%d: no index, falls back to ffmpeg", filepath.Base(path), s.Index)
				skipped++
				continue
			}
			_, ssa, ok := subtitleTextCodec(track.CodecID)
			if !ok {
				skipped++
				continue
			}
			got := string(renderWebVTT(cues, ssa, 0))
			ffmpegStart := time.Now()
			ref, err := exec.Command("ffmpeg", "-hide_banner", "-loglevel", "error",
				"-i", path, "-map", "0:"+strconv.Itoa(s.Index), "-c:s", "webvtt", "-f", "webvtt", "-").Output()
			ffmpegTook := time.Since(ffmpegStart)
			t.Logf("%s#%d: index %v, ffmpeg %v (%d cues)", filepath.Base(path), s.Index, indexTook.Round(time.Millisecond), ffmpegTook.Round(time.Millisecond), len(cues))
			if err != nil {
				t.Errorf("%s#%d: ffmpeg reference: %v", filepath.Base(path), s.Index, err)
				continue
			}
			checked++
			gotCues := vttCueLines(got)
			refCues := vttCueLines(normalizeSubtitleText(string(ref)))
			if len(gotCues) != len(refCues) {
				t.Errorf("%s#%d: %d cues, ffmpeg produced %d", filepath.Base(path), s.Index, len(gotCues), len(refCues))
				continue
			}
			for i := range gotCues {
				if gotCues[i] != refCues[i] {
					t.Errorf("%s#%d cue %d:\n  index:  %q\n  ffmpeg: %q",
						filepath.Base(path), s.Index, i, gotCues[i], refCues[i])
					break
				}
			}
			if !ssa {
				continue
			}
			// The web player asks for ".ass" and renders it with libass, so the
			// reconstructed script has to match too — Matroska hoists a
			// ReadOrder field to the front of each block and drops the
			// timestamps, and putting them back wrongly shifts every line.
			gotASS := assDialogueLines(string(renderASS(track.CodecPrivate, cues, 0)))
			refASSOut, err := exec.Command("ffmpeg", "-hide_banner", "-loglevel", "error",
				"-i", path, "-map", "0:"+strconv.Itoa(s.Index), "-c:s", "ass", "-f", "ass", "-").Output()
			if err != nil {
				t.Errorf("%s#%d: ffmpeg ass reference: %v", filepath.Base(path), s.Index, err)
				continue
			}
			refASS := assDialogueLines(normalizeSubtitleText(string(refASSOut)))
			if len(gotASS) != len(refASS) {
				t.Errorf("%s#%d: %d dialogue lines, ffmpeg produced %d",
					filepath.Base(path), s.Index, len(gotASS), len(refASS))
				continue
			}
			for i := range gotASS {
				if gotASS[i] != refASS[i] {
					t.Errorf("%s#%d dialogue %d:\n  index:  %q\n  ffmpeg: %q",
						filepath.Base(path), s.Index, i, gotASS[i], refASS[i])
					break
				}
			}
		}
	}
	t.Logf("compared %d tracks against ffmpeg, %d fell back", checked, skipped)
	if checked == 0 {
		t.Fatal("no tracks were comparable")
	}
}

// assDialogueLines keeps only the events, so the comparison ignores how the
// two writers lay out the script header.
func assDialogueLines(s string) []string {
	var out []string
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "Dialogue:") {
			out = append(out, strings.Join(strings.Fields(line), " "))
		}
	}
	return out
}

// vttCueLines reduces a VTT to "timing|text" records so the comparison ignores
// blank-line and header formatting.
func vttCueLines(s string) []string {
	var out []string
	lines := strings.Split(s, "\n")
	for i := 0; i < len(lines); i++ {
		if !strings.Contains(lines[i], "-->") {
			continue
		}
		timing := strings.TrimSpace(lines[i])
		var text []string
		for j := i + 1; j < len(lines) && strings.TrimSpace(lines[j]) != ""; j++ {
			text = append(text, strings.TrimSpace(lines[j]))
		}
		out = append(out, timing+"|"+strings.Join(text, " "))
	}
	return out
}
