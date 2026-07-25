package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"popcorn/internal/config"
	"popcorn/internal/media"
)

type hlsSession struct {
	dir        string
	cmd        *exec.Cmd
	owner      string
	userID     int64
	started    time.Time
	lastAccess atomic.Int64
	done       chan struct{}
}

func (s *hlsSession) touch() {
	s.lastAccess.Store(time.Now().UnixNano())
}

func (s *hlsSession) idleFor() time.Duration {
	last := s.lastAccess.Load()
	if last == 0 {
		return time.Since(s.started)
	}
	return time.Since(time.Unix(0, last))
}

// hlsScratchDir returns the directory for HLS transcode output. Full-movie
// sessions hold gigabytes of segments, so they live next to the database on
// real disk instead of /tmp, which is usually a RAM-backed tmpfs.
func (a *App) hlsScratchDir() string {
	if a.cfg.DatabasePath == "" {
		return ""
	}
	dir := filepath.Join(filepath.Dir(a.cfg.DatabasePath), "hls-cache")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		a.log.Warn("hls scratch dir unavailable, falling back to system temp", "dir", dir, "error", err)
		return ""
	}
	return dir
}

// cleanHLSScratch removes leftover session directories from previous runs.
// On tmpfs a reboot cleared them; on disk we have to do it ourselves.
func (a *App) cleanHLSScratch() {
	scratch := a.hlsScratchDir()
	if scratch == "" {
		return
	}
	entries, err := os.ReadDir(scratch)
	if err != nil {
		return
	}
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), "popcorn-hls-") {
			_ = os.RemoveAll(filepath.Join(scratch, entry.Name()))
		}
	}
}

// reapIdleHLSSessions stops ffmpeg sessions no client has touched recently.
// Clients are expected to stop their sessions, but a killed app or dropped
// connection must not leave a transcoder running for hours.
func (a *App) reapIdleHLSSessions() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-a.ctx.Done():
			return
		case <-ticker.C:
		}
		idle := map[string]*hlsSession{}
		a.hlsMu.Lock()
		for id, sess := range a.hlsSessions {
			if sess.idleFor() > 3*time.Minute {
				idle[id] = sess
				delete(a.hlsSessions, id)
			}
		}
		a.hlsMu.Unlock()
		for id, sess := range idle {
			a.log.Info("hls session reaped after idle timeout", "session", id)
			a.stopHLSSession(id, sess)
		}
	}
}

func (a *App) transcode(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	bandwidth := parseBandwidth(r.URL.Query().Get("bandwidth"))
	start := parseStart(r.URL.Query().Get("start"), item.DurationMS)
	audio := parseOptionalInt(r.URL.Query().Get("audio"))
	requestedSubtitle := parseOptionalInt(r.URL.Query().Get("subtitle"))
	subtitle := a.textSubtitleStream(r.Context(), item, requestedSubtitle, "transcode")
	path := media.ResolveExistingPath(item.Path)
	if path == "" {
		http.Error(w, "media unavailable", http.StatusNotFound)
		return
	}
	args := transcodeArgs(a.cfg, path, bandwidth, start, audio, subtitle, item.VideoCodec)
	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Popcorn-Start", strconv.FormatFloat(start, 'f', 3, 64))
	cmd := exec.CommandContext(r.Context(), a.cfg.FFmpegPath, args...)
	stderr, _ := cmd.StderrPipe()
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if err := cmd.Start(); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	go func() {
		b, _ := io.ReadAll(io.LimitReader(stderr, 64*1024))
		if len(b) > 0 {
			a.log.Debug("ffmpeg", "item", item.ID, "stderr", string(b))
		}
	}()
	_, copyErr := io.Copy(w, stdout)
	waitErr := cmd.Wait()
	if copyErr != nil && !errors.Is(copyErr, context.Canceled) {
		a.log.Debug("transcode copy", "error", copyErr)
	}
	if waitErr != nil && r.Context().Err() == nil {
		a.log.Debug("transcode exit", "error", waitErr)
	}
}

func (a *App) subtitle(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	// ".ass" keeps the original styling and positioning for clients that render
	// SSA themselves; ".vtt" is the lossy conversion every browser understands.
	requested := strings.TrimSpace(r.PathValue("subtitle"))
	asSSA := strings.HasSuffix(requested, ".ass")
	rawIndex := strings.TrimSuffix(strings.TrimSuffix(requested, ".vtt"), ".ass")
	index, err := strconv.Atoi(rawIndex)
	if err != nil {
		http.Error(w, "invalid subtitle index", http.StatusBadRequest)
		return
	}
	streams, err := a.itemStreams(r.Context(), item)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	var selected *media.MediaStream
	for i := range streams {
		if streams[i].Type == "subtitle" && streams[i].Index == index {
			selected = &streams[i]
			break
		}
	}
	if selected == nil {
		http.Error(w, "subtitle stream not found", http.StatusNotFound)
		return
	}
	if !isTextSubtitleCodec(selected.Codec) {
		http.Error(w, "subtitle stream is not text", http.StatusUnsupportedMediaType)
		return
	}
	start := parseStart(r.URL.Query().Get("start"), item.DurationMS)
	path := media.ResolveExistingPath(item.Path)
	if path == "" {
		http.Error(w, "media unavailable", http.StatusNotFound)
		return
	}
	// Extracting an embedded subtitle demuxes the entire file, which can take
	// well over the client's ~8s HTTP read timeout on big files. Stream the
	// conversion instead of buffering it: -flush_packets pushes each cue
	// through ffmpeg's output buffer immediately (a whole movie's VTT is
	// smaller than that buffer), and flushing per chunk keeps bytes moving so
	// the client never sees a silent connection.
	args := subtitleTranscodeArgs(path, index, start, asSSA)
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, a.cfg.FFmpegPath, args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		http.Error(w, "subtitle conversion failed", http.StatusInternalServerError)
		return
	}
	if err := cmd.Start(); err != nil {
		a.log.Warn("subtitle conversion failed", "item", item.ID, "subtitle", index, "start", start, "error", err)
		http.Error(w, "subtitle conversion failed", http.StatusInternalServerError)
		return
	}
	if asSSA {
		w.Header().Set("Content-Type", "text/x-ssa; charset=utf-8")
	} else {
		w.Header().Set("Content-Type", "text/vtt; charset=utf-8")
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Popcorn-Start", strconv.FormatFloat(start, 'f', 3, 64))
	flusher, _ := w.(http.Flusher)
	if flusher != nil {
		flusher.Flush()
	}
	buf := make([]byte, 16<<10)
	for {
		n, readErr := stdout.Read(buf)
		if n > 0 {
			if _, writeErr := w.Write(buf[:n]); writeErr != nil {
				break
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		if readErr != nil {
			break
		}
	}
	if err := cmd.Wait(); err != nil && r.Context().Err() == nil {
		a.log.Warn("subtitle conversion failed", "item", item.ID, "subtitle", index, "start", start, "error", err, "stderr", strings.TrimSpace(stderr.String()))
	}
}

// subtitleTranscodeArgs uses an accurate output seek and then rebases cues to
// zero. Input seeking is fast, but subtitle streams seek to the previous cue;
// that makes the resulting WebVTT offset depend on whichever cue happened to
// precede the requested position.
func subtitleTranscodeArgs(path string, index int, start float64, asSSA bool) []string {
	format := "webvtt"
	if asSSA {
		format = "ass"
	}
	args := []string{"-hide_banner", "-loglevel", "error", "-i", path}
	if start > 0 {
		formatted := strconv.FormatFloat(start, 'f', 3, 64)
		args = append(args, "-ss", formatted)
	}
	args = append(args,
		"-map", fmt.Sprintf("0:%d", index),
		"-c:s", format,
	)
	if start > 0 {
		args = append(args, "-output_ts_offset", "-"+strconv.FormatFloat(start, 'f', 3, 64))
	}
	return append(args,
		"-f", format,
		"-flush_packets", "1",
		"-",
	)
}

func (a *App) hlsPlaylist(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	sessionID := cleanSessionID(r.PathValue("session"))
	if sessionID == "" {
		http.Error(w, "invalid session", http.StatusBadRequest)
		return
	}
	planID := strings.TrimSpace(r.URL.Query().Get("plan"))
	if planID != "" {
		plan, ok := a.lookupPlaybackPlan(planID)
		if !ok || plan.ItemID != item.ID || plan.UserID != user.ID || !plan.Playable || plan.Mode == planModeDirect {
			http.Error(w, "invalid playback plan", http.StatusBadRequest)
			return
		}
		a.log.Info("hls playlist requested", "item", item.ID, "session", sessionID, "plan", planID, "mode", plan.Mode, "bandwidth", plan.BandwidthKbps, "startMs", plan.StartPositionMS)
		sess, err := a.ensureHLSSessionForPlan(r.Context(), sessionID, user.ID, plan)
		if err != nil {
			a.log.Warn("hls session failed", "item", item.ID, "session", sessionID, "plan", planID, "error", err)
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		a.serveHLSPlaylist(w, r, item.ID, sessionID, sess)
		return
	}
	bandwidth := parseBandwidth(r.URL.Query().Get("bandwidth"))
	start := parseStart(r.URL.Query().Get("start"), item.DurationMS)
	audio := parseOptionalInt(r.URL.Query().Get("audio"))
	requestedSubtitle := parseOptionalInt(r.URL.Query().Get("subtitle"))
	subtitle := a.textSubtitleStream(r.Context(), item, requestedSubtitle, "hls")
	a.log.Info("hls playlist requested", "item", item.ID, "session", sessionID, "bandwidth", bandwidth, "start", start, "audio", optionalIntValue(audio), "subtitle", optionalIntValue(subtitle), "requestedSubtitle", optionalIntValue(requestedSubtitle))
	sess, err := a.ensureHLSSession(r.Context(), sessionID, user.ID, item, bandwidth, start, audio, subtitle)
	if err != nil {
		a.log.Warn("hls session failed", "item", item.ID, "session", sessionID, "error", err)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if sess.userID != user.ID {
		http.NotFound(w, r)
		return
	}
	a.serveHLSPlaylist(w, r, item.ID, sessionID, sess)
}

func (a *App) serveHLSPlaylist(w http.ResponseWriter, r *http.Request, itemID int64, sessionID string, sess *hlsSession) {
	sess.touch()
	playlist := filepath.Join(sess.dir, "index.m3u8")
	if err := waitForFileOrDone(r.Context(), playlist, 8*time.Second, sess.done); err != nil {
		a.log.Warn("hls playlist timeout", "item", itemID, "session", sessionID, "error", err)
		http.Error(w, err.Error(), http.StatusGatewayTimeout)
		return
	}
	// Rewrite in memory only: index.m3u8 belongs to ffmpeg, which rewrites it
	// after every segment. Writing our rewritten copy back to disk raced those
	// updates and could leave clients a stale playlist that never gains new
	// segments or the final ENDLIST.
	b, err := os.ReadFile(playlist)
	if err != nil || len(b) == 0 {
		a.log.Warn("hls playlist read failed", "item", itemID, "session", sessionID, "error", err)
		http.Error(w, "playlist unavailable", http.StatusServiceUnavailable)
		return
	}
	body := rewritePlaylistBody(b, fmt.Sprintf("/api/items/%d/hls/%s/", itemID, sessionID), hlsPlaylistAuthQuery(r))
	a.log.Debug("hls playlist served", "item", itemID, "session", sessionID, "bytes", len(body))
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(body)
}

func (a *App) hlsSegment(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	sessionID := cleanSessionID(r.PathValue("session"))
	segment := filepath.Base(r.PathValue("segment"))
	if sessionID == "" || segment == "." || segment == "" {
		http.Error(w, "invalid segment", http.StatusBadRequest)
		return
	}
	a.hlsMu.Lock()
	sess := a.hlsSessions[sessionID]
	a.hlsMu.Unlock()
	if sess == nil {
		http.NotFound(w, r)
		return
	}
	if sess.userID != user.ID {
		http.NotFound(w, r)
		return
	}
	sess.touch()
	path := filepath.Join(sess.dir, segment)
	if err := waitForFileOrDone(r.Context(), path, 10*time.Second, sess.done); err != nil {
		a.log.Warn("hls segment unavailable", "session", sessionID, "segment", segment, "error", err)
		http.NotFound(w, r)
		return
	}
	if info, err := os.Stat(path); err == nil {
		a.log.Debug("hls segment served", "session", sessionID, "segment", segment, "bytes", info.Size())
	}
	if strings.HasSuffix(segment, ".m4s") {
		w.Header().Set("Content-Type", "video/iso.segment")
	} else {
		w.Header().Set("Content-Type", "video/mp4")
	}
	w.Header().Set("Cache-Control", "public, max-age=3600")
	http.ServeFile(w, r, path)
}

func (a *App) hlsStop(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	sessionID := cleanSessionID(r.PathValue("session"))
	if sessionID == "" {
		http.Error(w, "invalid session", http.StatusBadRequest)
		return
	}
	a.hlsMu.Lock()
	sess := a.hlsSessions[sessionID]
	if sess != nil && sess.userID != user.ID {
		a.hlsMu.Unlock()
		http.NotFound(w, r)
		return
	}
	delete(a.hlsSessions, sessionID)
	a.hlsMu.Unlock()
	if sess != nil {
		a.log.Info("hls session stopped by client", "session", sessionID)
		a.stopHLSSession(sessionID, sess)
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) ensureHLSSession(ctx context.Context, sessionID string, userID int64, item media.Item, bandwidth int, start float64, audio, subtitle *int) (*hlsSession, error) {
	path := media.ResolveExistingPath(item.Path)
	if path == "" {
		return nil, fmt.Errorf("media unavailable")
	}
	args := hlsArgs(a.cfg, path, "", "", bandwidth, start, audio, subtitle, item.VideoCodec)
	return a.ensureHLSSessionWithArgs(ctx, sessionID, userID, hlsSessionOwner(sessionID), item.ID, start, bandwidth, args)
}

func (a *App) ensureHLSSessionForPlan(ctx context.Context, sessionID string, userID int64, plan PlaybackPlan) (*hlsSession, error) {
	start := float64(plan.StartPositionMS) / 1000.0
	path := media.ResolveExistingPath(plan.Item.Path)
	if path == "" {
		return nil, fmt.Errorf("media unavailable")
	}
	args := hlsPlanArgs(a.cfg, path, "", "", plan)
	return a.ensureHLSSessionWithArgs(ctx, sessionID, userID, hlsSessionOwner(sessionID), plan.ItemID, start, plan.BandwidthKbps, args)
}

func (a *App) ensureHLSSessionWithArgs(ctx context.Context, sessionID string, userID int64, owner string, itemID int64, start float64, bandwidth int, args []string) (*hlsSession, error) {
	a.hlsMu.Lock()
	if sess := a.hlsSessions[sessionID]; sess != nil {
		a.hlsMu.Unlock()
		return sess, nil
	}
	oldSessions := map[string]*hlsSession{}
	for id, sess := range a.hlsSessions {
		if (sess.userID == userID && sess.owner == owner) || time.Since(sess.started) > 6*time.Hour {
			oldSessions[id] = sess
			delete(a.hlsSessions, id)
		}
	}
	dir, err := os.MkdirTemp(a.hlsScratchDir(), "popcorn-hls-"+sessionID+"-")
	if err != nil {
		a.hlsMu.Unlock()
		return nil, err
	}
	args = completeHLSOutputArgs(args, filepath.Join(dir, "seg_%05d.m4s"), filepath.Join(dir, "index.m3u8"))
	cmd := exec.CommandContext(a.ctx, a.cfg.FFmpegPath, args...)
	stderr, _ := cmd.StderrPipe()
	if err := cmd.Start(); err != nil {
		a.hlsMu.Unlock()
		_ = os.RemoveAll(dir)
		return nil, err
	}
	sess := &hlsSession{dir: dir, cmd: cmd, owner: owner, userID: userID, started: time.Now(), done: make(chan struct{})}
	a.hlsSessions[sessionID] = sess
	a.hlsMu.Unlock()
	for id, old := range oldSessions {
		a.stopHLSSession(id, old)
	}
	a.log.Info("hls session started", "item", itemID, "session", sessionID, "owner", owner, "user", userID, "start", start, "bandwidth", bandwidth, "args", strings.Join(args, " "))
	go func() {
		defer close(sess.done)
		b, _ := io.ReadAll(io.LimitReader(stderr, 128*1024))
		if len(b) > 0 {
			a.log.Info("hls ffmpeg stderr", "item", itemID, "session", sessionID, "stderr", string(b))
		}
		err := cmd.Wait()
		if err != nil {
			a.log.Warn("hls ffmpeg exited", "item", itemID, "session", sessionID, "error", err)
		} else {
			a.log.Info("hls ffmpeg finished", "item", itemID, "session", sessionID)
		}
		_ = ctx
	}()
	return sess, nil
}

func hlsSessionOwner(sessionID string) string {
	parts := strings.Split(sessionID, "_")
	if len(parts) >= 4 && isDigits(parts[len(parts)-2]) && isDigits(parts[len(parts)-3]) {
		return strings.Join(parts[:len(parts)-3], "_")
	}
	if len(parts) >= 3 && isDigits(parts[len(parts)-1]) && isDigits(parts[len(parts)-2]) {
		return strings.Join(parts[:len(parts)-2], "_")
	}
	if len(parts) > 0 && parts[0] != "" {
		return parts[0]
	}
	return sessionID
}

func isDigits(value string) bool {
	if value == "" {
		return false
	}
	for _, r := range value {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func (a *App) stopHLSSession(id string, sess *hlsSession) {
	if sess == nil {
		return
	}
	if sess.cmd != nil && sess.cmd.Process != nil {
		a.log.Info("hls session stopping", "session", id, "pid", sess.cmd.Process.Pid)
		_ = sess.cmd.Process.Kill()
	}
	if sess.done != nil {
		select {
		case <-sess.done:
		case <-time.After(2 * time.Second):
			a.log.Warn("hls session did not exit promptly", "session", id)
		}
	}
	if sess.dir != "" {
		_ = os.RemoveAll(sess.dir)
	}
}

func parseBandwidth(v string) int {
	n, err := strconv.Atoi(v)
	if err != nil || n <= 0 {
		return 4000
	}
	if n < 500 {
		return 500
	}
	if n > 50000 {
		return 50000
	}
	return n
}

func parseStart(v string, durationMS int64) float64 {
	start, err := strconv.ParseFloat(v, 64)
	if err != nil || start < 0 {
		return 0
	}
	if durationMS > 0 {
		maxStart := float64(durationMS/1000) - 2
		if maxStart > 0 && start > maxStart {
			return maxStart
		}
	}
	return start
}

func parseOptionalInt(v string) *int {
	if v == "" || v == "none" {
		return nil
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 0 {
		return nil
	}
	return &n
}

func optionalIntValue(v *int) any {
	if v == nil {
		return nil
	}
	return *v
}

func (a *App) textSubtitleStream(ctx context.Context, item media.Item, subtitle *int, target string) *int {
	if subtitle == nil {
		return nil
	}
	path := media.ResolveExistingPath(item.Path)
	if path == "" {
		a.log.Warn("subtitle probe failed", "item", item.ID, "subtitle", *subtitle, "target", target, "error", "media unavailable")
		return nil
	}
	streams, err := probeStreams(ctx, a.cfg.FFprobePath, path)
	if err != nil {
		a.log.Warn("subtitle probe failed", "item", item.ID, "subtitle", *subtitle, "target", target, "error", err)
		return nil
	}
	for _, st := range streams {
		if st.Index != *subtitle || st.Type != "subtitle" {
			continue
		}
		if isTextSubtitleCodec(st.Codec) {
			return subtitle
		}
		a.log.Info("subtitle disabled", "item", item.ID, "subtitle", *subtitle, "target", target, "codec", st.Codec, "reason", "non-text subtitle cannot be converted")
		return nil
	}
	a.log.Info("subtitle disabled", "item", item.ID, "subtitle", *subtitle, "target", target, "reason", "subtitle stream not found")
	return nil
}

func isTextSubtitleCodec(codec string) bool {
	switch strings.ToLower(codec) {
	case "subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text":
		return true
	default:
		return false
	}
}

func transcodeArgs(cfg config.Config, input string, bandwidth int, start float64, audio, subtitle *int, videoCodec string) []string {
	videoRate, audioRate := transcodeRates(bandwidth)
	args := []string{"-hide_banner", "-loglevel", "warning"}
	inputSeek, outputSeek := transcodeSeekArgs(start)
	args = append(args, inputSeek...)
	hardwareInput := hwInputArgs(cfg.HWAccel, videoCodec)
	if start > 0 {
		hardwareInput = nil
	}
	args = append(args, hardwareInput...)
	args = append(args, "-i", input)
	args = append(args, outputSeek...)
	args = append(args, "-map", "0:v:0")
	if audio != nil {
		args = append(args, "-map", fmt.Sprintf("0:%d?", *audio))
	} else {
		args = append(args, "-map", "0:a:0?")
	}
	if subtitle != nil {
		args = append(args, "-map", fmt.Sprintf("0:%d?", *subtitle))
	} else {
		args = append(args, "-sn")
	}
	args = append(args, "-dn")
	args = append(args, hwCodecArgs(cfg.HWAccel, cfg.HWDevice, videoCodec, len(hardwareInput) > 0)...)
	args = append(args,
		"-b:v", fmt.Sprintf("%dk", videoRate),
		"-maxrate", fmt.Sprintf("%dk", videoRate),
		"-bufsize", fmt.Sprintf("%dk", videoRate*2),
		"-c:a", "aac",
		"-b:a", fmt.Sprintf("%dk", audioRate),
		"-ac", "2",
		"-c:s", "mov_text",
		"-movflags", "frag_keyframe+empty_moov+default_base_moof",
		"-f", "mp4",
		"pipe:1",
	)
	return args
}

func hlsArgs(cfg config.Config, input, segmentPattern, playlist string, bandwidth int, start float64, audio, subtitle *int, videoCodec string) []string {
	videoRate, audioRate := transcodeRates(bandwidth)
	args := []string{"-hide_banner", "-loglevel", "warning"}
	inputSeek, outputSeek := transcodeSeekArgs(start)
	args = append(args, inputSeek...)
	hardwareInput := hwInputArgs(cfg.HWAccel, videoCodec)
	if start > 0 {
		hardwareInput = nil
	}
	args = append(args, hardwareInput...)
	args = append(args, "-i", input)
	args = append(args, outputSeek...)
	args = append(args, "-map", "0:v:0")
	if audio != nil {
		args = append(args, "-map", fmt.Sprintf("0:%d?", *audio))
	} else {
		args = append(args, "-map", "0:a:0?")
	}
	if subtitle != nil {
		args = append(args, "-map", fmt.Sprintf("0:%d?", *subtitle))
	} else {
		args = append(args, "-sn")
	}
	args = append(args, "-dn")
	args = append(args, hwCodecArgs(cfg.HWAccel, cfg.HWDevice, videoCodec, len(hardwareInput) > 0)...)
	args = append(args,
		"-b:v", fmt.Sprintf("%dk", videoRate),
		"-maxrate", fmt.Sprintf("%dk", videoRate),
		"-bufsize", fmt.Sprintf("%dk", videoRate*2),
		"-force_key_frames", "expr:gte(t,n_forced*4)",
		"-c:a", "aac",
		"-b:a", fmt.Sprintf("%dk", audioRate),
		"-ac", "2",
		"-c:s", "webvtt",
		"-f", "hls",
		"-hls_time", "4",
		"-hls_init_time", "1",
		"-hls_list_size", "0",
		"-hls_flags", "independent_segments",
		"-hls_segment_type", "fmp4",
		"-hls_fmp4_init_filename", "init.mp4",
		"-hls_segment_filename", segmentPattern,
		playlist,
	)
	return args
}

func hlsPlanArgs(cfg config.Config, input, segmentPattern, playlist string, plan PlaybackPlan) []string {
	start := float64(plan.StartPositionMS) / 1000.0
	bandwidth := plan.BandwidthKbps
	if bandwidth <= 0 {
		bandwidth = 8000
	}
	videoRate, audioRate := transcodeRates(bandwidth)
	if plan.Outputs.Audio.BitrateKbps > 0 {
		audioRate = plan.Outputs.Audio.BitrateKbps
	}
	args := []string{"-hide_banner", "-loglevel", "warning"}
	inputSeek, outputSeek := hlsPlanSeekArgs(start, plan.Outputs.Video.Codec)
	args = append(args, inputSeek...)
	hardwareInput := []string(nil)
	if plan.Outputs.Video.Codec != "copy" {
		hardwareInput = hwInputArgs(cfg.HWAccel, plan.Item.VideoCodec)
		if start > 0 {
			hardwareInput = nil
		}
		args = append(args, hardwareInput...)
	}
	args = append(args, "-i", input)
	args = append(args, outputSeek...)
	args = append(args, "-map", fmt.Sprintf("0:%d", plan.Selected.VideoIndex))
	if plan.Outputs.Audio.Codec == "" || plan.Outputs.Audio.Codec == "none" {
		args = append(args, "-an")
	} else if plan.Selected.AudioIndex != nil {
		args = append(args, "-map", fmt.Sprintf("0:%d?", *plan.Selected.AudioIndex))
	} else {
		args = append(args, "-map", "0:a:0?")
	}
	if plan.Outputs.Subtitle.Codec == "" || plan.Outputs.Subtitle.Codec == "none" || plan.Selected.SubtitleIndex == nil {
		args = append(args, "-sn")
	} else {
		args = append(args, "-map", fmt.Sprintf("0:%d?", *plan.Selected.SubtitleIndex))
	}
	args = append(args, "-dn")
	switch plan.Outputs.Video.Codec {
	case "copy":
		args = append(args, "-c:v", "copy")
	default:
		args = append(args, hwCodecArgs(cfg.HWAccel, cfg.HWDevice, plan.Item.VideoCodec, len(hardwareInput) > 0)...)
		args = append(args,
			"-b:v", fmt.Sprintf("%dk", videoRate),
			"-maxrate", fmt.Sprintf("%dk", videoRate),
			"-bufsize", fmt.Sprintf("%dk", videoRate*2),
			"-force_key_frames", "expr:gte(t,n_forced*4)",
		)
	}
	switch plan.Outputs.Audio.Codec {
	case "", "none":
	case "copy":
		args = append(args, "-c:a", "copy")
	default:
		args = append(args, "-c:a", "aac", "-b:a", fmt.Sprintf("%dk", audioRate), "-ac", "2")
	}
	switch plan.Outputs.Subtitle.Codec {
	case "", "none":
	case "copy":
		args = append(args, "-c:s", "copy")
	default:
		args = append(args, "-c:s", "webvtt")
	}
	args = append(args,
		"-f", "hls",
		"-hls_time", "4",
		"-hls_init_time", "1",
		"-hls_list_size", "0",
		"-hls_flags", "independent_segments",
		"-hls_segment_type", "fmp4",
		"-hls_fmp4_init_filename", "init.mp4",
		"-hls_segment_filename", segmentPattern,
		playlist,
	)
	return args
}

// hlsPlanSeekArgs is a defensive fallback for old or persisted plans. Current
// planning transcodes video for non-zero HLS starts, but if a copied-video plan
// reaches the runner it must seek only at the input. Accurate output seeking
// drops video until the next keyframe while audio starts immediately.
func hlsPlanSeekArgs(start float64, videoCodec string) ([]string, []string) {
	if start <= 0 {
		return nil, nil
	}
	if videoCodec == "copy" {
		return []string{"-ss", strconv.FormatFloat(start, 'f', 3, 64)}, nil
	}
	return transcodeSeekArgs(start)
}

func completeHLSOutputArgs(args []string, segmentPattern, playlist string) []string {
	out := append([]string(nil), args...)
	for i := 0; i < len(out)-1; i++ {
		if out[i] == "-hls_segment_filename" {
			out[i+1] = segmentPattern
			break
		}
	}
	if len(out) > 0 {
		out[len(out)-1] = playlist
	}
	return out
}

func transcodeRates(bandwidth int) (videoRate int, audioRate int) {
	audioRate = 192
	switch {
	case bandwidth < 700:
		audioRate = 96
	case bandwidth < 1400:
		audioRate = 128
	}
	videoRate = bandwidth - audioRate
	if videoRate < 300 {
		videoRate = bandwidth * 85 / 100
		audioRate = bandwidth - videoRate
	}
	if audioRate < 64 {
		audioRate = 64
	}
	return videoRate, audioRate
}

func transcodeSeekArgs(start float64) ([]string, []string) {
	if start <= 0 {
		return nil, nil
	}
	const accurateWindow = 8.0
	if start <= accurateWindow {
		return nil, []string{"-ss", strconv.FormatFloat(start, 'f', 3, 64)}
	}
	return []string{"-ss", strconv.FormatFloat(start-accurateWindow, 'f', 3, 64)}, []string{"-ss", strconv.FormatFloat(accurateWindow, 'f', 3, 64)}
}

func cleanSessionID(v string) string {
	if len(v) > 64 {
		return ""
	}
	for _, r := range v {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_' {
			continue
		}
		return ""
	}
	return v
}

func waitForFile(ctx context.Context, path string, timeout time.Duration) error {
	return waitForFileOrDone(ctx, path, timeout, nil)
}

func waitForFileOrDone(ctx context.Context, path string, timeout time.Duration, done <-chan struct{}) error {
	deadline := time.Now().Add(timeout)
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()
	for {
		info, err := os.Stat(path)
		if err == nil && info.Size() > 0 {
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("timed out waiting for %s", filepath.Base(path))
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-done:
			return fmt.Errorf("transcoder exited before %s was written", filepath.Base(path))
		case <-ticker.C:
		}
	}
}

func hlsPlaylistAuthQuery(r *http.Request) string {
	token := strings.TrimSpace(r.URL.Query().Get("api_key"))
	if token == "" {
		token = queryTokenCaseInsensitive(r, "api_key")
	}
	if token == "" {
		return ""
	}
	return "api_key=" + url.QueryEscape(token)
}

func rewritePlaylistBody(b []byte, prefix, authQuery string) []byte {
	lines := strings.Split(string(b), "\n")
	for i, line := range lines {
		if rewritten, ok := rewritePlaylistMapLine(line, prefix, authQuery); ok {
			lines[i] = rewritten
			continue
		}
		if strings.HasPrefix(line, "seg_") {
			lines[i] = appendPlaylistAuth(prefix+line, authQuery)
			continue
		}
		if strings.HasPrefix(line, prefix) {
			lines[i] = appendPlaylistAuth(line, authQuery)
		}
	}
	return []byte(strings.Join(lines, "\n"))
}

func rewritePlaylistMapLine(line, prefix, authQuery string) (string, bool) {
	const marker = `URI="`
	start := strings.Index(line, marker)
	if !strings.HasPrefix(line, "#EXT-X-MAP:") || start < 0 {
		return line, false
	}
	valueStart := start + len(marker)
	valueEnd := strings.Index(line[valueStart:], `"`)
	if valueEnd < 0 {
		return line, false
	}
	valueEnd += valueStart
	uri := line[valueStart:valueEnd]
	if !strings.Contains(uri, "init.mp4") {
		return line, false
	}
	if !strings.HasPrefix(uri, prefix) && !strings.HasPrefix(uri, "http://") && !strings.HasPrefix(uri, "https://") {
		uri = prefix + uri
	}
	uri = appendPlaylistAuth(uri, authQuery)
	return line[:valueStart] + uri + line[valueEnd:], true
}

func appendPlaylistAuth(uri, authQuery string) string {
	if authQuery == "" || strings.Contains(strings.ToLower(uri), "api_key=") {
		return uri
	}
	if strings.Contains(uri, "?") {
		return uri + "&" + authQuery
	}
	return uri + "?" + authQuery
}

type streamInfo struct {
	Index    int    `json:"index"`
	Type     string `json:"type"`
	Codec    string `json:"codec,omitempty"`
	Language string `json:"language,omitempty"`
	Title    string `json:"title,omitempty"`
	Default  bool   `json:"default,omitempty"`
	Forced   bool   `json:"forced,omitempty"`
}

func probeStreams(ctx context.Context, ffprobe, path string) ([]streamInfo, error) {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, ffprobe, "-v", "error", "-show_streams", "-of", "json", path)
	out, err := cmd.Output()
	if err != nil {
		return nil, err
	}
	var raw struct {
		Streams []struct {
			Index       int               `json:"index"`
			CodecType   string            `json:"codec_type"`
			CodecName   string            `json:"codec_name"`
			Tags        map[string]string `json:"tags"`
			Disposition struct {
				Default int `json:"default"`
				Forced  int `json:"forced"`
			} `json:"disposition"`
		} `json:"streams"`
	}
	if err := json.Unmarshal(out, &raw); err != nil {
		return nil, err
	}
	outStreams := []streamInfo{}
	for _, st := range raw.Streams {
		if st.CodecType != "audio" && st.CodecType != "subtitle" {
			continue
		}
		outStreams = append(outStreams, streamInfo{
			Index:    st.Index,
			Type:     st.CodecType,
			Codec:    st.CodecName,
			Language: st.Tags["language"],
			Title:    st.Tags["title"],
			Default:  st.Disposition.Default == 1,
			Forced:   st.Disposition.Forced == 1,
		})
	}
	return outStreams, nil
}

// qsvCanDecode reports whether Intel Quick Sync can hardware-decode the given
// source video codec. Codecs outside this set — notably MPEG-4 ASP (DivX/Xvid),
// WMV and other legacy formats — either fail QSV decode or emit broken
// timestamps (a flood of "non monotonically increasing dts"), so the transcoder
// never produces output. Those must be decoded in software while still encoding
// on the GPU.
func qsvCanDecode(videoCodec string) bool {
	switch normalizeCodec(videoCodec) {
	case "h264", "hevc", "mpeg2video", "vc1", "vp8", "vp9", "av1", "mjpeg":
		return true
	default:
		return false
	}
}

func hwInputArgs(mode, videoCodec string) []string {
	switch strings.ToLower(mode) {
	case "none", "":
		return nil
	case "vaapi":
		return nil
	case "qsv":
		// Only force QSV hardware decode for codecs it can actually decode;
		// otherwise fall back to software decode (the encoder stays on the GPU).
		if qsvCanDecode(videoCodec) {
			return []string{"-hwaccel", "qsv", "-hwaccel_output_format", "qsv"}
		}
		return nil
	case "cuda", "nvenc", "auto":
		return []string{"-hwaccel", "auto"}
	default:
		return []string{"-hwaccel", mode}
	}
}

func hwCodecArgs(mode, device, videoCodec string, hardwareInput bool) []string {
	switch strings.ToLower(mode) {
	case "nvenc", "cuda":
		return []string{"-c:v", "h264_nvenc", "-preset", "p4"}
	case "qsv":
		// vpp_qsv converts 10-bit sources (HEVC Main10 etc.) to 8-bit NV12 on the
		// GPU; h264_qsv rejects 10-bit input outright. forced_idr makes the encoder
		// honor force_key_frames as IDR frames — without it segments grow to the
		// encoder's default GOP (~10s) instead of the requested hls_time.
		if hardwareInput && qsvCanDecode(videoCodec) {
			return []string{"-vf", "vpp_qsv=format=nv12", "-c:v", "h264_qsv", "-preset", "veryfast", "-forced_idr", "1"}
		}
		// Software-decoded source: frames are in system memory, so convert to NV12
		// on the CPU and let h264_qsv upload them (vpp_qsv requires QSV frames).
		return []string{"-vf", "format=nv12", "-c:v", "h264_qsv", "-preset", "veryfast", "-forced_idr", "1"}
	case "vaapi":
		args := []string{}
		if device != "" {
			args = append(args, "-vaapi_device", device)
		}
		return append(args, "-vf", "format=nv12,hwupload", "-c:v", "h264_vaapi")
	default:
		return []string{"-c:v", "libx264", "-preset", "veryfast"}
	}
}
