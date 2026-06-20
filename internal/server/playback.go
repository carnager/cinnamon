package server

import (
	"encoding/json"
	"fmt"
	"net/http"
	"path/filepath"
	"strings"
	"time"

	"popcorn/internal/media"
)

const (
	planModeDirect            = "direct"
	planModeRemux             = "remux"
	planModeAudioTranscode    = "audio-transcode"
	planModeSubtitleTranscode = "subtitle-transcode"
	planModeFullTranscode     = "full-transcode"
)

type PlaybackPlanRequest struct {
	ItemID          int64           `json:"itemId"`
	StartPositionMS int64           `json:"startPositionMs"`
	AudioIndex      *int            `json:"audioIndex"`
	SubtitleIndex   *int            `json:"subtitleIndex"`
	BandwidthKbps   *int            `json:"bandwidthKbps"`
	ForceMode       string          `json:"forceMode"`
	Profile         PlaybackProfile `json:"profile"`
}

type PlaybackProfile struct {
	SchemaVersion  int                  `json:"schemaVersion"`
	Client         string               `json:"client"`
	AppVersionCode int                  `json:"appVersionCode"`
	AppVersionName string               `json:"appVersionName"`
	DeviceModel    string               `json:"deviceModel"`
	OSSDK          int                  `json:"osSdk"`
	Display        PlaybackDisplay      `json:"display"`
	Protocols      PlaybackProtocols    `json:"protocols"`
	Containers     []string             `json:"containers"`
	Video          []PlaybackVideoCodec `json:"video"`
	Audio          []PlaybackAudioCodec `json:"audio"`
	Subtitles      []string             `json:"subtitles"`
}

type PlaybackDisplay struct {
	Width        int       `json:"width"`
	Height       int       `json:"height"`
	HDRFormats   []string  `json:"hdrFormats"`
	RefreshRates []float64 `json:"refreshRates"`
}

type PlaybackProtocols struct {
	DirectFile bool `json:"directFile"`
	HTTPRange  bool `json:"httpRange"`
	HLSFMP4    bool `json:"hlsFmp4"`
}

type PlaybackVideoCodec struct {
	Codec      string   `json:"codec"`
	Mime       string   `json:"mime,omitempty"`
	MaxWidth   int      `json:"maxWidth,omitempty"`
	MaxHeight  int      `json:"maxHeight,omitempty"`
	MaxBitrate int64    `json:"maxBitrate,omitempty"`
	HDRFormats []string `json:"hdrFormats,omitempty"`
}

type PlaybackAudioCodec struct {
	Codec       string `json:"codec"`
	MaxChannels int    `json:"maxChannels,omitempty"`
}

type PlaybackPlan struct {
	ID              string              `json:"planId"`
	ItemID          int64               `json:"itemId"`
	Mode            string              `json:"mode"`
	Playable        bool                `json:"playable"`
	Container       string              `json:"container"`
	URL             string              `json:"url"`
	SessionID       string              `json:"sessionId,omitempty"`
	StartPositionMS int64               `json:"startPositionMs"`
	DurationMS      int64               `json:"durationMs"`
	BandwidthKbps   int                 `json:"bandwidthKbps,omitempty"`
	Selected        PlaybackSelection   `json:"selected"`
	Outputs         PlaybackOutputs     `json:"outputs"`
	Reasons         []string            `json:"reasons"`
	Fallback        *PlaybackPlanBrief  `json:"fallback,omitempty"`
	Profile         PlaybackProfile     `json:"-"`
	UserID          int64               `json:"-"`
	Item            media.Item          `json:"-"`
	Streams         []media.MediaStream `json:"-"`
}

type PlaybackSelection struct {
	VideoIndex    int  `json:"videoIndex"`
	AudioIndex    *int `json:"audioIndex,omitempty"`
	SubtitleIndex *int `json:"subtitleIndex,omitempty"`
}

type PlaybackOutputs struct {
	Video    PlaybackOutputStream `json:"video"`
	Audio    PlaybackOutputStream `json:"audio"`
	Subtitle PlaybackOutputStream `json:"subtitle"`
}

type PlaybackOutputStream struct {
	Codec       string `json:"codec"`
	BitrateKbps int    `json:"bitrateKbps,omitempty"`
	Channels    int    `json:"channels,omitempty"`
}

type PlaybackPlanBrief struct {
	Mode string `json:"mode"`
	URL  string `json:"url"`
}

func (a *App) playbackPlan(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	var req PlaybackPlanRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	item, err := a.store.GetItem(r.Context(), req.ItemID)
	if err != nil {
		http.Error(w, "item not found", http.StatusNotFound)
		return
	}
	streams, err := a.itemStreams(r.Context(), item)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	plan := a.buildPlaybackPlan(user.ID, item, streams, req, false)
	a.savePlaybackPlan(plan)
	a.log.Info("playback plan", "item", item.ID, "user", user.ID, "client", req.Profile.Client, "mode", plan.Mode, "video", plan.Outputs.Video.Codec, "audio", plan.Outputs.Audio.Codec, "subtitle", plan.Outputs.Subtitle.Codec, "bandwidth", plan.BandwidthKbps, "reasons", strings.Join(plan.Reasons, "; "))
	writeJSON(w, http.StatusOK, plan)
}

func (a *App) playbackFailure(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	var in struct {
		ItemID     int64  `json:"itemId"`
		PlanID     string `json:"planId"`
		Mode       string `json:"mode"`
		Client     string `json:"client"`
		ErrorCode  string `json:"errorCode"`
		Message    string `json:"message"`
		PositionMS int64  `json:"positionMs"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	plan, ok := a.lookupPlaybackPlan(in.PlanID)
	if !ok || plan.ItemID != in.ItemID {
		writeJSON(w, http.StatusOK, map[string]any{"retry": false})
		return
	}
	a.playbackMu.Lock()
	a.failHints[fmt.Sprintf("%d:%d:%s", user.ID, in.ItemID, in.Mode)] = time.Now().Add(30 * time.Minute)
	a.playbackMu.Unlock()
	// Resume where playback actually failed, not where the failed plan began:
	// after an error an hour into a session, the original plan's start position
	// would throw the viewer back to the beginning.
	startMS := plan.StartPositionMS
	if in.PositionMS > 0 {
		startMS = in.PositionMS
	}
	req := PlaybackPlanRequest{
		ItemID:          in.ItemID,
		StartPositionMS: startMS,
		AudioIndex:      plan.Selected.AudioIndex,
		SubtitleIndex:   plan.Selected.SubtitleIndex,
		BandwidthKbps:   nil,
		ForceMode:       "transcode",
		Profile:         plan.Profile,
	}
	fallback := a.buildPlaybackPlan(user.ID, plan.Item, plan.Streams, req, true)
	a.savePlaybackPlan(fallback)
	a.log.Warn("playback fallback", "item", in.ItemID, "user", user.ID, "client", in.Client, "original", in.Mode, "error", in.ErrorCode, "retry", fallback.Mode, "message", in.Message)
	writeJSON(w, http.StatusOK, map[string]any{"retry": fallback.Playable, "plan": fallback})
}

func (a *App) buildPlaybackPlan(userID int64, item media.Item, streams []media.MediaStream, req PlaybackPlanRequest, fallback bool) PlaybackPlan {
	force := strings.ToLower(strings.TrimSpace(req.ForceMode))
	if force == "" {
		force = "auto"
	}
	video, hasVideo := media.PrimaryVideoStream(streams)
	audio := selectAudioStream(streams, req.AudioIndex)
	subtitle := selectSubtitleStream(streams, req.SubtitleIndex)
	bandwidth := normalizePlanBandwidth(req.BandwidthKbps)
	audioRate := 0
	if bandwidth > 0 {
		_, audioRate = transcodeRates(bandwidth)
	}
	plan := PlaybackPlan{
		ID:              "pl_" + randomHex(12),
		ItemID:          item.ID,
		Playable:        true,
		StartPositionMS: req.StartPositionMS,
		DurationMS:      item.DurationMS,
		BandwidthKbps:   bandwidth,
		Profile:         req.Profile,
		UserID:          userID,
		Item:            item,
		Streams:         streams,
		Selected: PlaybackSelection{
			VideoIndex:    video.Index,
			AudioIndex:    streamIndexPtr(audio),
			SubtitleIndex: streamIndexPtr(subtitle),
		},
	}
	if !hasVideo {
		plan.Playable = false
		plan.Mode = "unplayable"
		plan.Reasons = append(plan.Reasons, "no video stream found")
		return plan
	}
	sourceRate := selectedBitrate(item, video, audio, subtitle)
	bitrateExceeded := bandwidth > 0 && sourceRate > 0 && int64(bandwidth)*1000 < sourceRate
	if bitrateExceeded {
		plan.Reasons = append(plan.Reasons, fmt.Sprintf("source bitrate %d kbps exceeds selected %d kbps", sourceRate/1000, bandwidth))
	}
	videoOK := videoSupported(req.Profile, video)
	audioOK := audio == nil || audioSupported(req.Profile, *audio)
	subtitleOK := subtitle == nil || subtitleSupported(req.Profile, *subtitle)
	container := itemContainerFromPath(item)
	containerOK := containsCodec(req.Profile.Containers, container)
	directOK := req.Profile.Protocols.DirectFile && req.Profile.Protocols.HTTPRange && containerOK && videoOK && audioOK && subtitleOK && !bitrateExceeded
	if force == "direct" {
		if directOK {
			return a.finishDirectPlan(plan, item, video, audio, subtitle)
		}
		plan.Playable = false
		plan.Mode = planModeDirect
		plan.Reasons = incompatibilityReasons(item, req.Profile, video, audio, subtitle, containerOK, videoOK, audioOK, subtitleOK, bitrateExceeded)
		return plan
	}
	if force == "transcode" || fallback {
		return a.finishHLSPlan(plan, item, req.Profile, planModeFullTranscode, "h264", "aac", subtitleOutputCodec(subtitle), audioRate)
	}
	if directOK {
		return a.finishDirectPlan(plan, item, video, audio, subtitle)
	}
	plan.Reasons = append(plan.Reasons, incompatibilityReasons(item, req.Profile, video, audio, subtitle, containerOK, videoOK, audioOK, subtitleOK, bitrateExceeded)...)
	if force == "remux" {
		if req.Profile.Protocols.HLSFMP4 && canCopyVideoToHLS(video) && (audio == nil || canCopyAudioToHLS(*audio)) && !bitrateExceeded {
			return a.finishHLSPlan(plan, item, req.Profile, planModeRemux, "copy", "copy", subtitleOutputCodec(subtitle), audioRate)
		}
		plan.Playable = false
		plan.Mode = planModeRemux
		return plan
	}
	if req.Profile.Protocols.HLSFMP4 && canCopyVideoToHLS(video) && videoOK && !bitrateExceeded {
		if audio != nil && (!audioOK || !canCopyAudioToHLS(*audio)) {
			if preferFullTranscodeForAudioTranscode(req.Profile) {
				plan.Reasons = append(plan.Reasons, "android hls audio-only transcode avoided to keep a/v timestamps stable")
				return a.finishHLSPlan(plan, item, req.Profile, planModeFullTranscode, "h264", "aac", subtitleOutputCodec(subtitle), audioRate)
			}
			return a.finishHLSPlan(plan, item, req.Profile, planModeAudioTranscode, "copy", "aac", subtitleOutputCodec(subtitle), audioRate)
		}
		if subtitle != nil && !subtitleOK && isTextSubtitleCodec(subtitle.Codec) {
			return a.finishHLSPlan(plan, item, req.Profile, planModeSubtitleTranscode, "copy", "copy", "webvtt", audioRate)
		}
		if audio == nil || canCopyAudioToHLS(*audio) {
			return a.finishHLSPlan(plan, item, req.Profile, planModeRemux, "copy", "copy", subtitleOutputCodec(subtitle), audioRate)
		}
	}
	return a.finishHLSPlan(plan, item, req.Profile, planModeFullTranscode, "h264", "aac", subtitleOutputCodec(subtitle), audioRate)
}

func (a *App) finishDirectPlan(plan PlaybackPlan, item media.Item, video media.MediaStream, audio, subtitle *media.MediaStream) PlaybackPlan {
	plan.Mode = planModeDirect
	plan.Container = itemContainerFromPath(item)
	plan.URL = fmt.Sprintf("/api/items/%d/stream", item.ID)
	plan.Outputs.Video = PlaybackOutputStream{Codec: video.Codec}
	if audio != nil {
		plan.Outputs.Audio = PlaybackOutputStream{Codec: audio.Codec, Channels: audio.Channels}
	}
	if subtitle != nil {
		plan.Outputs.Subtitle = PlaybackOutputStream{Codec: subtitle.Codec}
	}
	plan.Reasons = append(plan.Reasons, "direct play supported")
	return plan
}

func (a *App) finishHLSPlan(plan PlaybackPlan, item media.Item, profile PlaybackProfile, mode, videoCodec, audioCodec, subtitleCodec string, audioRate int) PlaybackPlan {
	plan.Mode = mode
	plan.Container = "hls-fmp4"
	plan.SessionID = cleanSessionID(fmt.Sprintf("%s_%d_%d_%s", hlsOwnerFromProfile(profile), item.ID, time.Now().UnixMilli(), randomHex(4)))
	if plan.SessionID == "" {
		plan.SessionID = "play_" + randomHex(10)
	}
	plan.Outputs.Video = PlaybackOutputStream{Codec: videoCodec}
	if audioCodec != "" {
		plan.Outputs.Audio = PlaybackOutputStream{Codec: audioCodec}
		if audioCodec == "aac" {
			if audioRate <= 0 {
				audioRate = 192
			}
			plan.Outputs.Audio.BitrateKbps = audioRate
			plan.Outputs.Audio.Channels = 2
		}
	}
	plan.Outputs.Subtitle = PlaybackOutputStream{Codec: subtitleCodec}
	plan.URL = fmt.Sprintf("/api/items/%d/hls/%s/index.m3u8?plan=%s", item.ID, plan.SessionID, plan.ID)
	if mode != planModeFullTranscode {
		plan.Fallback = &PlaybackPlanBrief{
			Mode: planModeFullTranscode,
			URL:  fmt.Sprintf("/api/items/%d/hls/%s/index.m3u8?plan=%s", item.ID, plan.SessionID+"_fallback", plan.ID),
		}
	}
	if mode == planModeFullTranscode {
		plan.Reasons = append(plan.Reasons, "safe h264/aac transcode selected")
	}
	return plan
}

func (a *App) savePlaybackPlan(plan PlaybackPlan) {
	a.playbackMu.Lock()
	defer a.playbackMu.Unlock()
	if a.plans == nil {
		a.plans = map[string]PlaybackPlan{}
	}
	a.plans[plan.ID] = plan
}

func (a *App) lookupPlaybackPlan(id string) (PlaybackPlan, bool) {
	a.playbackMu.Lock()
	defer a.playbackMu.Unlock()
	plan, ok := a.plans[id]
	return plan, ok
}

func selectAudioStream(streams []media.MediaStream, index *int) *media.MediaStream {
	if index != nil {
		for _, stream := range streams {
			if stream.Type == "audio" && stream.Index == *index {
				s := stream
				return &s
			}
		}
	}
	for _, stream := range streams {
		if stream.Type == "audio" && stream.Default {
			s := stream
			return &s
		}
	}
	for _, stream := range streams {
		if stream.Type == "audio" {
			s := stream
			return &s
		}
	}
	return nil
}

func selectSubtitleStream(streams []media.MediaStream, index *int) *media.MediaStream {
	if index == nil {
		return nil
	}
	for _, stream := range streams {
		if stream.Type == "subtitle" && stream.Index == *index {
			s := stream
			return &s
		}
	}
	return nil
}

func streamIndexPtr(stream *media.MediaStream) *int {
	if stream == nil {
		return nil
	}
	v := stream.Index
	return &v
}

func normalizePlanBandwidth(value *int) int {
	if value == nil || *value <= 0 {
		return 0
	}
	return parseBandwidth(fmt.Sprintf("%d", *value))
}

func selectedBitrate(item media.Item, video media.MediaStream, audio, subtitle *media.MediaStream) int64 {
	total := video.BitRate
	if audio != nil {
		total += audio.BitRate
	}
	if subtitle != nil {
		total += subtitle.BitRate
	}
	if total > 0 {
		return total
	}
	return item.BitRate
}

func videoSupported(profile PlaybackProfile, stream media.MediaStream) bool {
	codec := normalizeCodec(stream.Codec)
	for _, supported := range profile.Video {
		if normalizeCodec(supported.Codec) != codec {
			continue
		}
		if supported.MaxWidth > 0 && stream.Width > supported.MaxWidth {
			continue
		}
		if supported.MaxHeight > 0 && stream.Height > supported.MaxHeight {
			continue
		}
		if supported.MaxBitrate > 0 && stream.BitRate > supported.MaxBitrate {
			continue
		}
		hdr := stream.HDRFormat
		if hdr == "" {
			hdr = "sdr"
		}
		if hdr != "sdr" && !containsCodec(supported.HDRFormats, hdr) && !containsCodec(profile.Display.HDRFormats, hdr) {
			continue
		}
		return true
	}
	return false
}

func audioSupported(profile PlaybackProfile, stream media.MediaStream) bool {
	codec := normalizeCodec(stream.Codec)
	for _, supported := range profile.Audio {
		if normalizeCodec(supported.Codec) != codec {
			continue
		}
		if supported.MaxChannels > 0 && stream.Channels > supported.MaxChannels {
			continue
		}
		return true
	}
	return false
}

func subtitleSupported(profile PlaybackProfile, stream media.MediaStream) bool {
	return isTextSubtitleCodec(stream.Codec) && containsCodec(profile.Subtitles, normalizeCodec(stream.Codec))
}

func containsCodec(values []string, needle string) bool {
	needle = normalizeCodec(needle)
	for _, value := range values {
		if normalizeCodec(value) == needle {
			return true
		}
	}
	return false
}

func normalizeCodec(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	switch value {
	case "avc", "avc1":
		return "h264"
	case "hevc", "h265":
		return "hevc"
	case "subrip", "srt":
		return "subrip"
	case "matroska":
		return "mkv"
	case "quicktime":
		return "mov"
	default:
		return value
	}
}

func canCopyVideoToHLS(stream media.MediaStream) bool {
	switch normalizeCodec(stream.Codec) {
	case "h264", "hevc":
		return true
	default:
		return false
	}
}

func canCopyAudioToHLS(stream media.MediaStream) bool {
	switch normalizeCodec(stream.Codec) {
	case "aac", "ac3", "eac3", "mp3":
		return true
	default:
		return false
	}
}

func subtitleOutputCodec(subtitle *media.MediaStream) string {
	if subtitle == nil {
		return "none"
	}
	if isTextSubtitleCodec(subtitle.Codec) {
		return "webvtt"
	}
	return "none"
}

func incompatibilityReasons(item media.Item, profile PlaybackProfile, video media.MediaStream, audio, subtitle *media.MediaStream, containerOK, videoOK, audioOK, subtitleOK, bitrateExceeded bool) []string {
	reasons := []string{}
	if !containerOK {
		reasons = append(reasons, "container "+item.Container+" is not supported")
	}
	if !videoOK {
		reasons = append(reasons, "video codec "+video.Codec+" is not supported")
	}
	if audio != nil && !audioOK {
		reasons = append(reasons, "audio codec "+audio.Codec+" is not supported")
	}
	if subtitle != nil && !subtitleOK {
		if isTextSubtitleCodec(subtitle.Codec) {
			reasons = append(reasons, "subtitle "+subtitle.Codec+" requires conversion")
		} else {
			reasons = append(reasons, "subtitle "+subtitle.Codec+" is not text and will be disabled")
		}
	}
	if bitrateExceeded {
		reasons = append(reasons, "selected bandwidth cap requires video transcode")
	}
	if len(reasons) == 0 && profile.Client == "" {
		reasons = append(reasons, "client profile is empty")
	}
	return reasons
}

func hlsOwnerFromProfile(profile PlaybackProfile) string {
	client := strings.TrimSpace(profile.Client)
	if client == "" {
		client = "client"
	}
	model := strings.TrimSpace(profile.DeviceModel)
	if model == "" {
		model = "device"
	}
	owner := strings.ToLower(client + "_" + model)
	owner = strings.Map(func(r rune) rune {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_' || r == '-' {
			return r
		}
		return '_'
	}, owner)
	return strings.Trim(owner, "_")
}

func preferFullTranscodeForAudioTranscode(profile PlaybackProfile) bool {
	switch strings.ToLower(strings.TrimSpace(profile.Client)) {
	case "android-tv", "android-phone", "web":
		return true
	default:
		return false
	}
}

func itemContainerFromPath(item media.Item) string {
	if item.Container != "" {
		return item.Container
	}
	return strings.TrimPrefix(strings.ToLower(filepath.Ext(item.Path)), ".")
}
