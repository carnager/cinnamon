package server

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

const maxAvatarBytes = 10 * 1024 * 1024

// avatarExts maps the sniffed content type of an upload to the extension the
// file is stored (and later served) under.
var avatarExts = map[string]string{
	"image/jpeg": ".jpg",
	"image/png":  ".png",
	"image/gif":  ".gif",
	"image/webp": ".webp",
}

// avatarDir lives next to the database like the thumb cache and app updates,
// so uploaded avatars survive reboots on hosts with a RAM-backed /tmp.
func (a *App) avatarDir() string {
	if a.cfg.DatabasePath == "" {
		return ""
	}
	return filepath.Join(filepath.Dir(a.cfg.DatabasePath), "avatars")
}

func avatarUserID(w http.ResponseWriter, r *http.Request) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		http.Error(w, "invalid user id", http.StatusBadRequest)
		return 0, false
	}
	return id, true
}

// avatarPath returns the stored avatar file for a user, or "" if none exists.
func (a *App) avatarPath(id int64) string {
	dir := a.avatarDir()
	if dir == "" {
		return ""
	}
	for _, ext := range avatarExts {
		path := filepath.Join(dir, strconv.FormatInt(id, 10)+ext)
		if info, err := os.Stat(path); err == nil && info.Mode().IsRegular() {
			return path
		}
	}
	return ""
}

func (a *App) removeAvatarFiles(id int64) {
	dir := a.avatarDir()
	if dir == "" {
		return
	}
	for _, ext := range avatarExts {
		_ = os.Remove(filepath.Join(dir, strconv.FormatInt(id, 10)+ext))
	}
}

func (a *App) userAvatar(w http.ResponseWriter, r *http.Request) {
	id, ok := avatarUserID(w, r)
	if !ok {
		return
	}
	path := a.avatarPath(id)
	if path == "" {
		http.NotFound(w, r)
		return
	}
	// Clients version these URLs with ?v= from the user's avatar field, so a
	// replaced image changes the URL and immutable is safe.
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	http.ServeFile(w, r, path)
}

func (a *App) uploadUserAvatar(w http.ResponseWriter, r *http.Request) {
	if _, ok := a.requireAdmin(w, r); !ok {
		return
	}
	id, ok := avatarUserID(w, r)
	if !ok {
		return
	}
	if _, err := a.auth.User(r.Context(), id); err != nil {
		http.Error(w, "user not found", http.StatusNotFound)
		return
	}
	dir := a.avatarDir()
	if dir == "" {
		http.Error(w, "avatar storage is not configured", http.StatusServiceUnavailable)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxAvatarBytes)
	if err := r.ParseMultipartForm(maxAvatarBytes); err != nil {
		http.Error(w, "invalid upload: "+err.Error(), http.StatusBadRequest)
		return
	}
	file, header, err := r.FormFile("avatar")
	if err != nil {
		http.Error(w, "avatar file is required", http.StatusBadRequest)
		return
	}
	defer file.Close()
	if header.Size <= 0 {
		http.Error(w, "avatar file is empty", http.StatusBadRequest)
		return
	}
	sniff := make([]byte, 512)
	n, _ := io.ReadFull(file, sniff)
	ext, ok := avatarExts[http.DetectContentType(sniff[:n])]
	if !ok {
		http.Error(w, "avatar must be a JPEG, PNG, GIF, or WebP image", http.StatusBadRequest)
		return
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	tmp, err := os.CreateTemp(dir, ".upload-*"+ext)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	tmpPath := tmp.Name()
	_, copyErr := io.Copy(tmp, file)
	closeErr := tmp.Close()
	if copyErr == nil {
		copyErr = closeErr
	}
	if copyErr == nil {
		copyErr = os.Rename(tmpPath, filepath.Join(dir, strconv.FormatInt(id, 10)+ext))
	}
	if copyErr != nil {
		_ = os.Remove(tmpPath)
		http.Error(w, copyErr.Error(), http.StatusInternalServerError)
		return
	}
	// Drop stale copies under other extensions so avatarPath is unambiguous.
	for _, other := range avatarExts {
		if other != ext {
			_ = os.Remove(filepath.Join(dir, strconv.FormatInt(id, 10)+other))
		}
	}
	version := fmt.Sprintf("%d", time.Now().Unix())
	if err := a.auth.SetAvatar(r.Context(), id, version); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	user, err := a.auth.User(r.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, user)
}

func (a *App) deleteUserAvatar(w http.ResponseWriter, r *http.Request) {
	if _, ok := a.requireAdmin(w, r); !ok {
		return
	}
	id, ok := avatarUserID(w, r)
	if !ok {
		return
	}
	if _, err := a.auth.User(r.Context(), id); err != nil {
		http.Error(w, "user not found", http.StatusNotFound)
		return
	}
	a.removeAvatarFiles(id)
	if err := a.auth.SetAvatar(r.Context(), id, ""); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	user, err := a.auth.User(r.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, user)
}
