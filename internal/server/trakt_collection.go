package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"popcorn/internal/media"
)

// Trakt knows what you have watched and what you want to watch, but not what is
// on the shelf — so it recommends films that are already in the library. Sync
// the library as a Trakt collection and its own recommendations can skip them.
//
// Removal is opt-in and limited to what popcorn itself sent: every entry it
// posts is recorded, so a prune can take back its own and leave alone whatever
// was collected from a phone, a Trakt import, or years before this existed.

// Trakt allows one write per second per user, and a first sync is a couple of
// dozen of them: send fewer, larger requests and pace them.
const (
	traktCollectionMovieChunk = 250
	traktCollectionShowChunk  = 40
	traktCollectionRetries    = 4
)

// A var so a test does not have to wait out the pacing.
var traktCollectionWriteDelay = 1100 * time.Millisecond

type traktCollectionResult struct {
	Movies       int `json:"movies"`
	Shows        int `json:"shows"`
	Episodes     int `json:"episodes"`
	AlreadyThere int `json:"alreadyCollected"`
	Skipped      int `json:"skipped"`
	Removed      int `json:"removed"`
}

// A collected entry as popcorn sent it: enough to take it back once the file it
// described is gone from the library.
type traktCollectionRecord struct {
	Kind    string         `json:"kind"`
	IDs     map[string]any `json:"ids,omitempty"`
	Title   string         `json:"title,omitempty"`
	Year    int            `json:"year,omitempty"`
	Show    string         `json:"show,omitempty"`
	Season  int            `json:"season,omitempty"`
	Episode int            `json:"episode,omitempty"`
}

func (a *App) traktSyncCollection(ctx context.Context, userID int64, bearer string, prune bool) (traktCollectionResult, error) {
	result := traktCollectionResult{}
	items, err := a.store.AllItems(ctx)
	if err != nil {
		return result, err
	}
	sent, err := a.store.TraktCollectionEntries(ctx, userID)
	if err != nil {
		return result, err
	}
	posted := map[string]string{}
	postedKinds := map[string]string{}
	present := map[string]bool{}
	collectedMovies, collectedEpisodes, err := a.traktCollected(ctx, bearer)
	if err != nil {
		return result, err
	}

	movies := make([]map[string]any, 0, 256)
	byShow := map[string]*traktCollectionShow{}
	showOrder := make([]string, 0, 64)
	collectedAt := time.Now().UTC().Format(time.RFC3339)

	for _, item := range items {
		switch item.Kind {
		case "movie":
			key := traktCollectionMovieKey(item)
			if key != "" {
				present[key] = true
			}
			if key != "" && collectedMovies[key] {
				result.AlreadyThere++
				continue
			}
			entry := traktCollectionMovieEntry(item, collectedAt)
			if entry == nil {
				result.Skipped++
				continue
			}
			movies = append(movies, entry)
			if key != "" {
				record := traktCollectionRecord{Kind: "movie", Title: item.Title, Year: item.Year}
				if ids := traktIDs(item); len(ids) > 0 {
					record.IDs = ids
				}
				posted[key] = mustJSON(record)
				postedKinds[key] = "movie"
			}
		case "episode":
			if item.ShowTitle == "" || item.SeasonNumber <= 0 || item.EpisodeNumber <= 0 {
				result.Skipped++
				continue
			}
			showKey := strings.ToLower(strings.TrimSpace(item.ShowTitle))
			episodeKey := traktCollectionEpisodeKey(showKey, item.SeasonNumber, item.EpisodeNumber)
			present[episodeKey] = true
			if collectedEpisodes[episodeKey] {
				result.AlreadyThere++
				continue
			}
			posted[episodeKey] = mustJSON(traktCollectionRecord{
				Kind: "episode", Show: item.ShowTitle, Season: item.SeasonNumber, Episode: item.EpisodeNumber,
			})
			postedKinds[episodeKey] = "episode"
			show, ok := byShow[showKey]
			if !ok {
				show = &traktCollectionShow{title: item.ShowTitle, year: item.Year, seasons: map[int][]int{}}
				byShow[showKey] = show
				showOrder = append(showOrder, showKey)
			}
			if show.year == 0 && item.Year > 0 {
				show.year = item.Year
			}
			show.seasons[item.SeasonNumber] = append(show.seasons[item.SeasonNumber], item.EpisodeNumber)
			result.Episodes++
		}
	}

	shows := make([]map[string]any, 0, len(showOrder))
	for _, key := range showOrder {
		shows = append(shows, byShow[key].payload(collectedAt))
	}
	result.Movies = len(movies)
	result.Shows = len(shows)

	for _, chunk := range chunkMaps(movies, traktCollectionMovieChunk) {
		if err := a.traktPostCollection(ctx, bearer, map[string]any{"movies": chunk}); err != nil {
			return result, err
		}
	}
	for _, chunk := range chunkMaps(shows, traktCollectionShowChunk) {
		if err := a.traktPostCollection(ctx, bearer, map[string]any{"shows": chunk}); err != nil {
			return result, err
		}
	}
	if err := a.store.SaveTraktCollectionEntries(ctx, userID, posted, postedKinds); err != nil {
		return result, err
	}
	if !prune {
		return result, nil
	}
	removed, err := a.traktPruneCollection(ctx, userID, bearer, sent, present)
	if err != nil {
		return result, err
	}
	result.Removed = removed
	return result, nil
}

// traktPruneCollection takes back the entries popcorn posted for files that are
// no longer in the library. Anything it did not post is not in the record, and
// so is never touched.
func (a *App) traktPruneCollection(ctx context.Context, userID int64, bearer string, sent map[string]string, present map[string]bool) (int, error) {
	movies := []map[string]any{}
	byShow := map[string]*traktCollectionShow{}
	showOrder := []string{}
	gone := []string{}

	for key, payload := range sent {
		if present[key] {
			continue
		}
		var record traktCollectionRecord
		if err := json.Unmarshal([]byte(payload), &record); err != nil {
			continue
		}
		gone = append(gone, key)
		switch record.Kind {
		case "movie":
			entry := map[string]any{}
			if len(record.IDs) > 0 {
				entry["ids"] = record.IDs
			} else if record.Title != "" {
				entry["title"] = record.Title
				if record.Year > 0 {
					entry["year"] = record.Year
				}
			} else {
				continue
			}
			movies = append(movies, entry)
		case "episode":
			showKey := strings.ToLower(strings.TrimSpace(record.Show))
			show, ok := byShow[showKey]
			if !ok {
				show = &traktCollectionShow{title: record.Show, seasons: map[int][]int{}}
				byShow[showKey] = show
				showOrder = append(showOrder, showKey)
			}
			show.seasons[record.Season] = append(show.seasons[record.Season], record.Episode)
		}
	}
	if len(gone) == 0 {
		return 0, nil
	}
	shows := make([]map[string]any, 0, len(showOrder))
	for _, key := range showOrder {
		shows = append(shows, byShow[key].payload(""))
	}
	for _, chunk := range chunkMaps(movies, traktCollectionMovieChunk) {
		if err := a.traktRemoveCollection(ctx, bearer, map[string]any{"movies": chunk}); err != nil {
			return 0, err
		}
	}
	for _, chunk := range chunkMaps(shows, traktCollectionShowChunk) {
		if err := a.traktRemoveCollection(ctx, bearer, map[string]any{"shows": chunk}); err != nil {
			return 0, err
		}
	}
	if err := a.store.DeleteTraktCollectionEntries(ctx, userID, gone); err != nil {
		return 0, err
	}
	return len(gone), nil
}

func mustJSON(value any) string {
	payload, err := json.Marshal(value)
	if err != nil {
		return "{}"
	}
	return string(payload)
}

type traktCollectionShow struct {
	title   string
	year    int
	seasons map[int][]int
}

func (s *traktCollectionShow) payload(collectedAt string) map[string]any {
	numbers := make([]int, 0, len(s.seasons))
	for number := range s.seasons {
		numbers = append(numbers, number)
	}
	sort.Ints(numbers)
	seasons := make([]map[string]any, 0, len(numbers))
	for _, number := range numbers {
		episodes := append([]int(nil), s.seasons[number]...)
		sort.Ints(episodes)
		entries := make([]map[string]any, 0, len(episodes))
		for _, episode := range episodes {
			entry := map[string]any{"number": episode}
			if collectedAt != "" {
				entry["collected_at"] = collectedAt
			}
			entries = append(entries, entry)
		}
		seasons = append(seasons, map[string]any{"number": number, "episodes": entries})
	}
	show := map[string]any{"title": s.title, "seasons": seasons}
	if s.year > 0 {
		show["year"] = s.year
	}
	return show
}

// traktCollectionMovieEntry describes the copy that is actually on the shelf,
// which is the part a collection is for: 4K or 1080p, and what the audio track
// is. Anything popcorn cannot name confidently is left out rather than guessed.
func traktCollectionMovieEntry(item media.Item, collectedAt string) map[string]any {
	entry := map[string]any{"collected_at": collectedAt, "media_type": "digital"}
	if ids := traktIDs(item); len(ids) > 0 {
		entry["ids"] = ids
	} else if strings.TrimSpace(item.Title) != "" {
		entry["title"] = item.Title
		if item.Year > 0 {
			entry["year"] = item.Year
		}
	} else {
		return nil
	}
	if resolution := traktResolution(item.Height); resolution != "" {
		entry["resolution"] = resolution
	}
	if audio := traktAudio(item.AudioCodec); audio != "" {
		entry["audio"] = audio
	}
	return entry
}

func traktResolution(height int) string {
	switch {
	case height >= 2000:
		return "uhd_4k"
	case height >= 1000:
		return "hd_1080p"
	case height >= 700:
		return "hd_720p"
	case height >= 550:
		return "sd_576p"
	case height > 0:
		return "sd_480p"
	}
	return ""
}

func traktAudio(codec string) string {
	switch strings.ToLower(strings.TrimSpace(codec)) {
	case "ac3", "ac-3":
		return "dolby_digital"
	case "eac3", "e-ac-3", "ec-3":
		return "dolby_digital_plus"
	case "truehd":
		return "dolby_truehd"
	case "dts":
		return "dts"
	case "dts-hd", "dts_hd":
		return "dts_ma"
	case "aac":
		return "aac"
	case "mp3":
		return "mp3"
	case "flac":
		return "flac"
	case "opus", "vorbis":
		return "ogg"
	case "pcm", "lpcm":
		return "lpcm"
	}
	return ""
}

func (a *App) traktPostCollection(ctx context.Context, bearer string, body map[string]any) error {
	return a.traktCollectionWrite(ctx, bearer, "/sync/collection", body)
}

func (a *App) traktRemoveCollection(ctx context.Context, bearer string, body map[string]any) error {
	return a.traktCollectionWrite(ctx, bearer, "/sync/collection/remove", body)
}

// A rate-limited response has an empty body, so failing on it told you nothing.
// Wait for as long as Trakt asks and try again; report the status when it is
// something else.
func (a *App) traktCollectionWrite(ctx context.Context, bearer, path string, body map[string]any) error {
	for attempt := 0; attempt < traktCollectionRetries; attempt++ {
		// One write per second per user, counted from the previous one.
		if wait := traktCollectionWriteDelay - time.Since(a.traktLastWrite); wait > 0 {
			if err := sleepContext(ctx, wait); err != nil {
				return err
			}
		}
		resp, err := a.traktRequest(ctx, bearer, http.MethodPost, path, body)
		if err != nil {
			return err
		}
		payload, _ := io.ReadAll(io.LimitReader(resp.Body, 16*1024))
		retryAfter := resp.Header.Get("Retry-After")
		status := resp.StatusCode
		resp.Body.Close()

		switch {
		case status >= 200 && status <= 299:
			a.traktLastWrite = time.Now()
			return nil
		case status == http.StatusTooManyRequests || status == 420:
			if a.log != nil {
				a.log.Debug("trakt rate limited, waiting", "path", path, "retryAfter", retryAfter)
			}
			if err := sleepContext(ctx, traktRetryAfter(retryAfter)); err != nil {
				return err
			}
		default:
			message := strings.TrimSpace(string(payload))
			if message == "" {
				message = http.StatusText(status)
			}
			return fmt.Errorf("trakt %s failed (%d): %s", path, status, message)
		}
	}
	return fmt.Errorf("trakt %s failed: still rate limited after %d attempts", path, traktCollectionRetries)
}

func traktRetryAfter(header string) time.Duration {
	if seconds, err := strconv.Atoi(strings.TrimSpace(header)); err == nil && seconds > 0 {
		return time.Duration(seconds)*time.Second + 250*time.Millisecond
	}
	return 2 * time.Second
}

func sleepContext(ctx context.Context, d time.Duration) error {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

// traktCollected reads what Trakt already holds so a repeat sync posts only the
// difference — the library changes by a handful of files, not by 1600.
func (a *App) traktCollected(ctx context.Context, bearer string) (map[string]bool, map[string]bool, error) {
	movies := map[string]bool{}
	episodes := map[string]bool{}

	var movieRows []struct {
		Movie struct {
			Title string `json:"title"`
			Year  int    `json:"year"`
			IDs   struct {
				IMDb string `json:"imdb"`
				TMDb int    `json:"tmdb"`
			} `json:"ids"`
		} `json:"movie"`
	}
	if err := a.traktGetJSON(ctx, bearer, "/sync/collection/movies", &movieRows); err != nil {
		return nil, nil, err
	}
	for _, row := range movieRows {
		if row.Movie.IDs.IMDb != "" {
			movies["imdb:"+strings.ToLower(row.Movie.IDs.IMDb)] = true
		}
		if row.Movie.IDs.TMDb > 0 {
			movies["tmdb:"+strconv.Itoa(row.Movie.IDs.TMDb)] = true
		}
		if row.Movie.Title != "" {
			movies[traktCollectionTitleKey(row.Movie.Title, row.Movie.Year)] = true
		}
	}

	var showRows []struct {
		Show struct {
			Title string `json:"title"`
		} `json:"show"`
		Seasons []struct {
			Number   int `json:"number"`
			Episodes []struct {
				Number int `json:"number"`
			} `json:"episodes"`
		} `json:"seasons"`
	}
	if err := a.traktGetJSON(ctx, bearer, "/sync/collection/shows", &showRows); err != nil {
		return nil, nil, err
	}
	for _, row := range showRows {
		showKey := strings.ToLower(strings.TrimSpace(row.Show.Title))
		for _, season := range row.Seasons {
			for _, episode := range season.Episodes {
				episodes[traktCollectionEpisodeKey(showKey, season.Number, episode.Number)] = true
			}
		}
	}
	return movies, episodes, nil
}

func (a *App) traktGetJSON(ctx context.Context, bearer, path string, dest any) error {
	resp, err := a.traktRequest(ctx, bearer, http.MethodGet, path, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		payload, _ := io.ReadAll(io.LimitReader(resp.Body, 4*1024))
		message := strings.TrimSpace(string(payload))
		if message == "" {
			message = http.StatusText(resp.StatusCode)
		}
		return fmt.Errorf("trakt %s failed (%d): %s", path, resp.StatusCode, message)
	}
	return json.NewDecoder(io.LimitReader(resp.Body, 32*1024*1024)).Decode(dest)
}

// A library movie matches a collected one by any id it has, or by title and
// year when it has none.
func traktCollectionMovieKey(item media.Item) string {
	ids := traktIDs(item)
	if imdb, ok := ids["imdb"].(string); ok && imdb != "" {
		return "imdb:" + strings.ToLower(imdb)
	}
	if tmdb, ok := ids["tmdb"].(int); ok && tmdb > 0 {
		return "tmdb:" + strconv.Itoa(tmdb)
	}
	if strings.TrimSpace(item.Title) == "" {
		return ""
	}
	return traktCollectionTitleKey(item.Title, item.Year)
}

func traktCollectionTitleKey(title string, year int) string {
	return "title:" + strings.ToLower(strings.TrimSpace(title)) + ":" + strconv.Itoa(year)
}

func traktCollectionEpisodeKey(showKey string, season, episode int) string {
	return fmt.Sprintf("%s:%d:%d", showKey, season, episode)
}

func chunkMaps(entries []map[string]any, size int) [][]map[string]any {
	if len(entries) == 0 {
		return nil
	}
	out := make([][]map[string]any, 0, (len(entries)/size)+1)
	for start := 0; start < len(entries); start += size {
		end := start + size
		if end > len(entries) {
			end = len(entries)
		}
		out = append(out, entries[start:end])
	}
	return out
}

func (a *App) traktSyncCollectionEndpoint(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	account, err := a.traktAccountForRequest(r.Context(), user.ID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, "trakt account is not linked", http.StatusConflict)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	// A first sync of a large library is thousands of rows over several
	// requests; give it room rather than the default request timeout.
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Minute)
	defer cancel()
	prune := r.URL.Query().Get("prune") == "1"
	result, err := a.traktSyncCollection(ctx, user.ID, account.AccessToken, prune)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	if a.log != nil {
		a.log.Info("trakt collection synced", "user", user.ID, "movies", result.Movies, "shows", result.Shows, "episodes", result.Episodes, "already", result.AlreadyThere, "skipped", result.Skipped, "removed", result.Removed)
	}
	writeJSON(w, http.StatusOK, result)
}
