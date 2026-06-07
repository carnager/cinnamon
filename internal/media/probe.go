package media

import (
	"context"
	"encoding/json"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

func ProbeMedia(ctx context.Context, ffprobe, path string) MediaProbe {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, ffprobe, "-v", "error", "-show_format", "-show_streams", "-of", "json", path)
	out, err := cmd.Output()
	if err != nil {
		return MediaProbe{}
	}
	var raw struct {
		Format struct {
			Duration string `json:"duration"`
			BitRate  string `json:"bit_rate"`
		} `json:"format"`
		Streams []json.RawMessage `json:"streams"`
	}
	if err := json.Unmarshal(out, &raw); err != nil {
		return MediaProbe{}
	}
	var probe MediaProbe
	if f, err := strconv.ParseFloat(raw.Format.Duration, 64); err == nil {
		probe.DurationMS = int64(f * 1000)
	}
	probe.BitRate = parseInt64(raw.Format.BitRate)
	for _, rawStream := range raw.Streams {
		stream := parseMediaStream(rawStream)
		if stream.Type == "" {
			continue
		}
		probe.Streams = append(probe.Streams, stream)
	}
	return probe
}

func parseMediaStream(raw json.RawMessage) MediaStream {
	var st struct {
		Index          int               `json:"index"`
		CodecType      string            `json:"codec_type"`
		CodecName      string            `json:"codec_name"`
		CodecLongName  string            `json:"codec_long_name"`
		Profile        string            `json:"profile"`
		Level          int               `json:"level"`
		Width          int               `json:"width"`
		Height         int               `json:"height"`
		PixFmt         string            `json:"pix_fmt"`
		ColorRange     string            `json:"color_range"`
		ColorSpace     string            `json:"color_space"`
		ColorTransfer  string            `json:"color_transfer"`
		ColorPrimaries string            `json:"color_primaries"`
		BitRate        string            `json:"bit_rate"`
		Channels       int               `json:"channels"`
		ChannelLayout  string            `json:"channel_layout"`
		SampleRate     string            `json:"sample_rate"`
		Tags           map[string]string `json:"tags"`
		Disposition    map[string]int    `json:"disposition"`
		SideDataList   []map[string]any  `json:"side_data_list"`
	}
	if err := json.Unmarshal(raw, &st); err != nil {
		return MediaStream{}
	}
	switch st.CodecType {
	case "video", "audio", "subtitle":
	default:
		return MediaStream{}
	}
	disposition, _ := json.Marshal(st.Disposition)
	return MediaStream{
		Index:           st.Index,
		Type:            st.CodecType,
		Codec:           strings.ToLower(st.CodecName),
		CodecLongName:   st.CodecLongName,
		Profile:         st.Profile,
		Level:           st.Level,
		Width:           st.Width,
		Height:          st.Height,
		PixelFormat:     st.PixFmt,
		ColorRange:      st.ColorRange,
		ColorSpace:      st.ColorSpace,
		ColorTransfer:   st.ColorTransfer,
		ColorPrimaries:  st.ColorPrimaries,
		HDRFormat:       detectHDRFormat(st.ColorTransfer, st.SideDataList),
		BitRate:         parseInt64(st.BitRate),
		Channels:        st.Channels,
		ChannelLayout:   st.ChannelLayout,
		SampleRate:      parseInt(st.SampleRate),
		Language:        st.Tags["language"],
		Title:           st.Tags["title"],
		Default:         st.Disposition["default"] == 1,
		Forced:          st.Disposition["forced"] == 1,
		DispositionJSON: string(disposition),
		RawJSON:         string(raw),
	}
}

func detectHDRFormat(colorTransfer string, sideData []map[string]any) string {
	rawSideData, _ := json.Marshal(sideData)
	lowerSideData := strings.ToLower(string(rawSideData))
	if strings.Contains(lowerSideData, "dolby vision") || strings.Contains(lowerSideData, "dovi") {
		return "dolby_vision"
	}
	switch strings.ToLower(colorTransfer) {
	case "smpte2084":
		return "hdr10"
	case "arib-std-b67":
		return "hlg"
	case "bt709", "bt470bg", "smpte170m", "smpte240m":
		return "sdr"
	default:
		if colorTransfer == "" {
			return ""
		}
		return "unknown"
	}
}

func parseInt(value string) int {
	n, _ := strconv.Atoi(strings.TrimSpace(value))
	return n
}

func parseInt64(value string) int64 {
	n, _ := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
	return n
}

func PrimaryVideoStream(streams []MediaStream) (MediaStream, bool) {
	for _, stream := range streams {
		if stream.Type == "video" {
			return stream, true
		}
	}
	return MediaStream{}, false
}

func PrimaryAudioStream(streams []MediaStream) (MediaStream, bool) {
	for _, stream := range streams {
		if stream.Type == "audio" && stream.Default {
			return stream, true
		}
	}
	for _, stream := range streams {
		if stream.Type == "audio" {
			return stream, true
		}
	}
	return MediaStream{}, false
}
