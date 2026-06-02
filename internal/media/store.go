package media

import (
	"context"
	"database/sql"
	"sort"
	"strconv"
	"strings"
)

type Store struct {
	db *sql.DB
}

type SearchOptions struct {
	Query     string
	LibraryID string
	Kind      string
	Genre     string
	Sort      string
	Limit     int
	Offset    int
}

func NewStore(db *sql.DB) *Store {
	return &Store{db: db}
}

func (s *Store) DB() *sql.DB {
	return s.db
}

func (s *Store) UpsertItem(ctx context.Context, item Item) error {
	_, err := s.db.ExecContext(ctx, `
INSERT INTO media_items (
	library_id, path, kind, title, sort_title, original_title, year, duration_ms, container,
	video_codec, audio_codec, imdb_id, tmdb_id, tvdb_id, width, height, size_bytes, mtime_unix,
	nfo_path, nfo_mtime_unix, poster_path, poster_mtime_unix, backdrop_path, backdrop_mtime_unix,
	overview, tagline, genres, rating, premiered, show_title, season_number, episode_number, episode_title, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
ON CONFLICT(path) DO UPDATE SET
	library_id=excluded.library_id,
	kind=excluded.kind,
	title=excluded.title,
	sort_title=excluded.sort_title,
	original_title=excluded.original_title,
	year=excluded.year,
	duration_ms=excluded.duration_ms,
	container=excluded.container,
	video_codec=excluded.video_codec,
	audio_codec=excluded.audio_codec,
	imdb_id=excluded.imdb_id,
	tmdb_id=excluded.tmdb_id,
	tvdb_id=excluded.tvdb_id,
	width=excluded.width,
	height=excluded.height,
	size_bytes=excluded.size_bytes,
	mtime_unix=excluded.mtime_unix,
	nfo_path=excluded.nfo_path,
	nfo_mtime_unix=excluded.nfo_mtime_unix,
	poster_path=excluded.poster_path,
	poster_mtime_unix=excluded.poster_mtime_unix,
	backdrop_path=excluded.backdrop_path,
	backdrop_mtime_unix=excluded.backdrop_mtime_unix,
	overview=excluded.overview,
	tagline=excluded.tagline,
	genres=excluded.genres,
	rating=excluded.rating,
	premiered=excluded.premiered,
	show_title=excluded.show_title,
	season_number=excluded.season_number,
	episode_number=excluded.episode_number,
	episode_title=excluded.episode_title,
	updated_at=CURRENT_TIMESTAMP`,
		item.LibraryID, item.Path, item.Kind, item.Title, item.SortTitle, nullString(item.OriginalTitle), nullableInt(item.Year),
		nullableInt64(item.DurationMS), item.Container, item.VideoCodec, item.AudioCodec,
		nullString(item.IMDbID), nullString(item.TMDbID), nullString(item.TVDbID),
		nullableInt(item.Width), nullableInt(item.Height), item.SizeBytes, item.MTimeUnix,
		nullString(item.NFOPath), item.NFOMTimeUnix, nullString(item.PosterPath), item.PosterMTimeUnix,
		nullString(item.BackdropPath), item.BackdropMTimeUnix,
		nullString(item.Overview), nullString(item.Tagline), nullString(item.Genres), nullableFloat(item.Rating), nullString(item.Premiered),
		nullString(item.ShowTitle), nullableInt(item.SeasonNumber), nullableInt(item.EpisodeNumber),
		nullString(item.EpisodeTitle))
	return err
}

func (s *Store) RemoveMissing(ctx context.Context, libraryID string, seen map[string]struct{}) error {
	rows, err := s.db.QueryContext(ctx, `SELECT path FROM media_items WHERE library_id = ?`, libraryID)
	if err != nil {
		return err
	}
	defer rows.Close()
	var missing []string
	for rows.Next() {
		var path string
		if err := rows.Scan(&path); err != nil {
			return err
		}
		if _, ok := seen[path]; !ok {
			missing = append(missing, path)
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	for _, path := range missing {
		if _, err := s.db.ExecContext(ctx, `DELETE FROM media_items WHERE path = ?`, path); err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) ListItems(ctx context.Context, libraryID, q, genre, sort string, limit, offset int) ([]Item, error) {
	if limit <= 0 {
		limit = 250
	}
	if limit > 2000 {
		limit = 2000
	}
	return s.searchItems(ctx, SearchOptions{Query: q, LibraryID: libraryID, Genre: genre, Sort: sort, Limit: limit, Offset: offset})
}

func (s *Store) SearchItems(ctx context.Context, opts SearchOptions) ([]Item, error) {
	if opts.Limit <= 0 {
		opts.Limit = 100
	}
	if opts.Limit > 500 {
		opts.Limit = 500
	}
	if opts.Offset < 0 {
		opts.Offset = 0
	}
	return s.searchItems(ctx, opts)
}

func (s *Store) searchItems(ctx context.Context, opts SearchOptions) ([]Item, error) {
	q := opts.Query
	libraryID := opts.LibraryID
	kind := opts.Kind
	genre := opts.Genre
	orderBy := `ORDER BY
	CASE
		WHEN ? = '' THEN 0
		WHEN title LIKE ? || '%' THEN 0
		WHEN original_title LIKE ? || '%' THEN 1
		WHEN show_title LIKE ? || '%' THEN 2
		WHEN episode_title LIKE ? || '%' THEN 3
		ELSE 3
	END,
	sort_title, season_number, episode_number`
	if opts.Sort == "recent" {
		orderBy = `ORDER BY updated_at DESC, sort_title, season_number, episode_number`
	}
	query := itemSelect + `
FROM media_items
WHERE (? = '' OR library_id = ?)
AND (? = '' OR kind = ?)
AND (? = '' OR genres LIKE '%' || ? || '%')
AND (
	? = ''
	OR title LIKE '%' || ? || '%'
	OR sort_title LIKE '%' || ? || '%'
	OR original_title LIKE '%' || ? || '%'
	OR show_title LIKE '%' || ? || '%'
	OR episode_title LIKE '%' || ? || '%'
	OR overview LIKE '%' || ? || '%'
	OR genres LIKE '%' || ? || '%'
	OR CAST(year AS TEXT) = ?
)
` + orderBy + `
LIMIT ? OFFSET ?`
	args := []any{
		libraryID, libraryID,
		kind, kind,
		genre, genre,
		q, q, q, q, q, q, q, q, q,
	}
	if opts.Sort != "recent" {
		args = append(args, q, q, q, q, q)
	}
	args = append(args, opts.Limit, opts.Offset)
	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Item{}
	for rows.Next() {
		item, err := scanItem(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

const itemSelect = `SELECT id, library_id, path, kind, title, sort_title, COALESCE(original_title, ''), COALESCE(year, 0), COALESCE(duration_ms, 0),
COALESCE(container, ''), COALESCE(video_codec, ''), COALESCE(audio_codec, ''), COALESCE(imdb_id, ''), COALESCE(tmdb_id, ''), COALESCE(tvdb_id, ''), COALESCE(width, 0),
COALESCE(height, 0), size_bytes, mtime_unix, COALESCE(nfo_path, ''), COALESCE(nfo_mtime_unix, 0), COALESCE(poster_path, ''), COALESCE(poster_mtime_unix, 0), COALESCE(backdrop_path, ''), COALESCE(backdrop_mtime_unix, 0),
COALESCE(overview, ''), COALESCE(tagline, ''), COALESCE(genres, ''), COALESCE(rating, 0), COALESCE(premiered, ''),
COALESCE(show_title, ''), COALESCE(season_number, 0), COALESCE(episode_number, 0), COALESCE(episode_title, '')`

const itemSelectMI = `SELECT mi.id, mi.library_id, mi.path, mi.kind, mi.title, mi.sort_title, COALESCE(mi.original_title, ''), COALESCE(mi.year, 0), COALESCE(mi.duration_ms, 0),
COALESCE(mi.container, ''), COALESCE(mi.video_codec, ''), COALESCE(mi.audio_codec, ''), COALESCE(mi.imdb_id, ''), COALESCE(mi.tmdb_id, ''), COALESCE(mi.tvdb_id, ''), COALESCE(mi.width, 0),
COALESCE(mi.height, 0), mi.size_bytes, mi.mtime_unix, COALESCE(mi.nfo_path, ''), COALESCE(mi.nfo_mtime_unix, 0), COALESCE(mi.poster_path, ''), COALESCE(mi.poster_mtime_unix, 0), COALESCE(mi.backdrop_path, ''), COALESCE(mi.backdrop_mtime_unix, 0),
COALESCE(mi.overview, ''), COALESCE(mi.tagline, ''), COALESCE(mi.genres, ''), COALESCE(mi.rating, 0), COALESCE(mi.premiered, ''),
COALESCE(mi.show_title, ''), COALESCE(mi.season_number, 0), COALESCE(mi.episode_number, 0), COALESCE(mi.episode_title, '')`

func (s *Store) GetItem(ctx context.Context, id int64) (Item, error) {
	row := s.db.QueryRowContext(ctx, itemSelect+` FROM media_items WHERE id = ?`, id)
	return scanItem(row)
}

func (s *Store) AllItems(ctx context.Context) ([]Item, error) {
	rows, err := s.db.QueryContext(ctx, itemSelect+` FROM media_items ORDER BY kind, sort_title, season_number, episode_number`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Item{}
	for rows.Next() {
		item, err := scanItem(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

func (s *Store) LibrarySnapshot(ctx context.Context, libraryID string) (map[string]Item, error) {
	rows, err := s.db.QueryContext(ctx, itemSelect+` FROM media_items WHERE library_id = ?`, libraryID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]Item{}
	for rows.Next() {
		item, err := scanItem(rows)
		if err != nil {
			return nil, err
		}
		out[item.Path] = item
	}
	return out, rows.Err()
}

func (s *Store) ListShows(ctx context.Context, libraryID, q, genre, sort string, limit, offset int) ([]ShowSummary, error) {
	if limit <= 0 {
		limit = 250
	}
	if limit > 1000 {
		limit = 1000
	}
	orderBy := "ORDER BY sort_title"
	if sort == "recent" {
		orderBy = "ORDER BY MAX(updated_at) DESC, sort_title"
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT library_id,
	COALESCE(show_title, '') AS show_title,
	LOWER(COALESCE(show_title, '')) AS sort_title,
	CASE WHEN COUNT(DISTINCT NULLIF(original_title, '')) = 1 THEN COALESCE(MAX(NULLIF(original_title, '')), '') ELSE '' END,
	COALESCE(MIN(NULLIF(year, 0)), 0),
	COUNT(*),
	COUNT(DISTINCT season_number),
	COALESCE(MIN(CASE WHEN poster_path IS NOT NULL AND poster_path != '' THEN id END), 0),
	COALESCE(MAX(poster_mtime_unix), 0),
	COALESCE(MIN(CASE WHEN backdrop_path IS NOT NULL AND backdrop_path != '' THEN id END), 0),
	COALESCE(MAX(backdrop_mtime_unix), 0),
	COALESCE(MAX(NULLIF(overview, '')), ''),
	COALESCE(MAX(NULLIF(genres, '')), ''),
	COALESCE(MAX(rating), 0),
	COALESCE(MAX(NULLIF(premiered, '')), '')
FROM media_items
WHERE kind = 'episode'
AND (? = '' OR library_id = ?)
AND (? = '' OR genres LIKE '%' || ? || '%')
AND show_title IS NOT NULL AND show_title != ''
AND (? = '' OR show_title LIKE '%' || ? || '%' OR original_title LIKE '%' || ? || '%')
GROUP BY library_id, show_title
`+orderBy+`
LIMIT ? OFFSET ?`, libraryID, libraryID, genre, genre, q, q, q, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	shows := []ShowSummary{}
	for rows.Next() {
		var show ShowSummary
		if err := rows.Scan(&show.LibraryID, &show.Title, &show.SortTitle, &show.OriginalTitle, &show.Year, &show.EpisodeCount, &show.SeasonCount, &show.PosterItemID, &show.PosterMTimeUnix, &show.BackdropItemID, &show.BackdropMTimeUnix, &show.Overview, &show.Genres, &show.Rating, &show.Premiered); err != nil {
			return nil, err
		}
		shows = append(shows, show)
	}
	return shows, rows.Err()
}

func (s *Store) ListGenres(ctx context.Context, libraryID string) ([]string, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT COALESCE(genres, '')
FROM media_items
WHERE (? = '' OR library_id = ?)
AND genres IS NOT NULL AND genres != ''`, libraryID, libraryID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	seen := map[string]struct{}{}
	genres := []string{}
	for rows.Next() {
		var value string
		if err := rows.Scan(&value); err != nil {
			return nil, err
		}
		for _, genre := range splitGenreList(value) {
			key := strings.ToLower(genre)
			if _, ok := seen[key]; ok {
				continue
			}
			seen[key] = struct{}{}
			genres = append(genres, genre)
		}
	}
	sort.Slice(genres, func(i, j int) bool {
		return strings.ToLower(genres[i]) < strings.ToLower(genres[j])
	})
	return genres, rows.Err()
}

func (s *Store) ListSeasons(ctx context.Context, libraryID, showTitle string) ([]SeasonSummary, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT library_id,
	COALESCE(show_title, ''),
	COALESCE(season_number, 0),
	COALESCE(MIN(path), ''),
	COUNT(*),
	COALESCE(SUM(duration_ms), 0),
	COALESCE(MIN(id), 0),
	COALESCE(MIN(CASE WHEN backdrop_path IS NOT NULL AND backdrop_path != '' THEN id END), 0),
	COALESCE(MAX(rating), 0)
FROM media_items
WHERE kind = 'episode'
AND library_id = ?
AND show_title = ?
GROUP BY library_id, show_title, season_number
ORDER BY season_number`, libraryID, showTitle)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	seasons := []SeasonSummary{}
	for rows.Next() {
		var season SeasonSummary
		var firstEpisodePath string
		if err := rows.Scan(&season.LibraryID, &season.ShowTitle, &season.SeasonNumber, &firstEpisodePath, &season.EpisodeCount, &season.DurationMS, &season.PosterItemID, &season.BackdropItemID, &season.Rating); err != nil {
			return nil, err
		}
		enrichSeasonSummary(&season, firstEpisodePath)
		seasons = append(seasons, season)
	}
	return seasons, rows.Err()
}

func enrichSeasonSummary(season *SeasonSummary, firstEpisodePath string) {
	if firstEpisodePath == "" {
		return
	}
	nfo := findSeasonNFO(firstEpisodePath, season.SeasonNumber)
	season.PosterMTimeUnix = fileMTimeUnix(seasonImagePath(firstEpisodePath, season.SeasonNumber))
	if nfo == "" {
		if season.Title == "" && season.SeasonNumber > 0 {
			season.Title = "Season " + strconv.Itoa(season.SeasonNumber)
		}
		return
	}
	meta := readNFO(nfo)
	if meta.Title != "" {
		season.Title = meta.Title
	}
	if season.Overview == "" {
		season.Overview = firstNonEmpty(meta.Plot, meta.Outline)
	}
	if meta.Rating > 0 {
		season.Rating = meta.Rating
	}
	season.Premiered = firstNonEmpty(meta.Premiered, meta.Released)
}

func (s *Store) ListEpisodes(ctx context.Context, libraryID, showTitle string, seasonNumber int) ([]Item, error) {
	rows, err := s.db.QueryContext(ctx, itemSelect+`
FROM media_items
WHERE kind = 'episode'
AND library_id = ?
AND show_title = ?
AND (? < 0 OR COALESCE(season_number, 0) = ?)
ORDER BY season_number, episode_number, sort_title`, libraryID, showTitle, seasonNumber, seasonNumber)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	episodes := []Item{}
	for rows.Next() {
		item, err := scanItem(rows)
		if err != nil {
			return nil, err
		}
		episodes = append(episodes, item)
	}
	return episodes, rows.Err()
}

func (s *Store) SetScanStatus(ctx context.Context, status ScanStatus) error {
	_, err := s.db.ExecContext(ctx, `
INSERT INTO scan_state(library_id, started_at, finished_at, status, message, files_seen, media_found, items_imported, files_skipped, errors)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(library_id) DO UPDATE SET
	started_at=excluded.started_at,
	finished_at=excluded.finished_at,
	status=excluded.status,
	message=excluded.message,
	files_seen=excluded.files_seen,
	media_found=excluded.media_found,
	items_imported=excluded.items_imported,
	files_skipped=excluded.files_skipped,
	errors=excluded.errors`,
		status.LibraryID, nullString(status.StartedAt), nullString(status.FinishedAt), status.Status, status.Message,
		status.FilesSeen, status.MediaFound, status.ItemsImported, status.FilesSkipped, status.Errors)
	return err
}

func (s *Store) ScanStatus(ctx context.Context) ([]ScanStatus, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT library_id, COALESCE(started_at, ''), COALESCE(finished_at, ''), status, message, files_seen, media_found, items_imported, files_skipped, errors FROM scan_state ORDER BY library_id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ScanStatus{}
	for rows.Next() {
		var st ScanStatus
		if err := rows.Scan(&st.LibraryID, &st.StartedAt, &st.FinishedAt, &st.Status, &st.Message, &st.FilesSeen, &st.MediaFound, &st.ItemsImported, &st.FilesSkipped, &st.Errors); err != nil {
			return nil, err
		}
		out = append(out, st)
	}
	return out, rows.Err()
}

type rowScanner interface {
	Scan(dest ...any) error
}

func scanItem(row rowScanner) (Item, error) {
	var item Item
	err := row.Scan(&item.ID, &item.LibraryID, &item.Path, &item.Kind, &item.Title, &item.SortTitle,
		&item.OriginalTitle, &item.Year, &item.DurationMS, &item.Container, &item.VideoCodec, &item.AudioCodec,
		&item.IMDbID, &item.TMDbID, &item.TVDbID, &item.Width, &item.Height, &item.SizeBytes, &item.MTimeUnix, &item.NFOPath,
		&item.NFOMTimeUnix, &item.PosterPath, &item.PosterMTimeUnix, &item.BackdropPath, &item.BackdropMTimeUnix,
		&item.Overview, &item.Tagline, &item.Genres,
		&item.Rating, &item.Premiered, &item.ShowTitle, &item.SeasonNumber,
		&item.EpisodeNumber, &item.EpisodeTitle)
	return item, err
}

func (s *Store) SaveProgress(ctx context.Context, userID, itemID, positionMS, durationMS int64, completed bool) (PlaybackProgress, error) {
	if positionMS < 0 {
		positionMS = 0
	}
	if durationMS < 0 {
		durationMS = 0
	}
	if durationMS > 0 && positionMS > durationMS {
		positionMS = durationMS
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO playback_progress(user_id, item_id, position_ms, duration_ms, completed, updated_at)
VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
ON CONFLICT(user_id, item_id) DO UPDATE SET
	position_ms=excluded.position_ms,
	duration_ms=excluded.duration_ms,
	completed=excluded.completed,
	updated_at=CURRENT_TIMESTAMP`, userID, itemID, positionMS, durationMS, boolInt(completed))
	if err != nil {
		return PlaybackProgress{}, err
	}
	return s.Progress(ctx, userID, itemID)
}

func (s *Store) Progress(ctx context.Context, userID, itemID int64) (PlaybackProgress, error) {
	row := s.db.QueryRowContext(ctx, `
SELECT item_id, position_ms, duration_ms, completed, updated_at
FROM playback_progress
WHERE user_id = ? AND item_id = ?`, userID, itemID)
	return scanProgress(row)
}

func (s *Store) ListProgress(ctx context.Context, userID int64, limit, offset int) ([]PlaybackProgress, error) {
	if limit <= 0 {
		limit = 100
	}
	if limit > 500 {
		limit = 500
	}
	if offset < 0 {
		offset = 0
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT item_id, position_ms, duration_ms, completed, updated_at
FROM playback_progress
WHERE user_id = ?
ORDER BY updated_at DESC
LIMIT ? OFFSET ?`, userID, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []PlaybackProgress{}
	for rows.Next() {
		progress, err := scanProgress(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, progress)
	}
	return out, rows.Err()
}

func (s *Store) ListShowProgress(ctx context.Context, userID int64) ([]ShowProgress, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT mi.library_id,
	COALESCE(mi.show_title, ''),
	COUNT(*),
	COALESCE(SUM(CASE WHEN p.completed = 1 THEN 1 ELSE 0 END), 0)
FROM media_items mi
LEFT JOIN playback_progress p ON p.item_id = mi.id AND p.user_id = ?
WHERE mi.kind = 'episode'
AND mi.show_title IS NOT NULL AND mi.show_title != ''
GROUP BY mi.library_id, mi.show_title
ORDER BY mi.library_id, mi.show_title`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ShowProgress{}
	for rows.Next() {
		var progress ShowProgress
		if err := rows.Scan(&progress.LibraryID, &progress.ShowTitle, &progress.EpisodeCount, &progress.CompletedCount); err != nil {
			return nil, err
		}
		progress.Completed = progress.EpisodeCount > 0 && progress.CompletedCount >= progress.EpisodeCount
		progress.HasAnyCompletion = progress.CompletedCount > 0
		out = append(out, progress)
	}
	return out, rows.Err()
}

func (s *Store) DeleteProgress(ctx context.Context, userID, itemID int64) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM playback_progress WHERE user_id = ? AND item_id = ?`, userID, itemID)
	return err
}

func (s *Store) SaveItemWatchlist(ctx context.Context, userID int64, item Item) error {
	_, err := s.db.ExecContext(ctx, `
INSERT INTO user_watchlist(user_id, watch_key, kind, item_id, library_id, show_title, updated_at)
VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
ON CONFLICT(user_id, watch_key) DO UPDATE SET
	kind=excluded.kind,
	item_id=excluded.item_id,
	library_id=excluded.library_id,
	show_title=excluded.show_title,
	updated_at=CURRENT_TIMESTAMP`,
		userID, itemWatchKey(item.ID), item.Kind, item.ID, item.LibraryID, nullString(item.ShowTitle))
	return err
}

func (s *Store) DeleteItemWatchlist(ctx context.Context, userID, itemID int64) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM user_watchlist WHERE user_id = ? AND watch_key = ?`, userID, itemWatchKey(itemID))
	return err
}

func (s *Store) SaveShowWatchlist(ctx context.Context, userID int64, libraryID, showTitle string) error {
	_, err := s.db.ExecContext(ctx, `
INSERT INTO user_watchlist(user_id, watch_key, kind, library_id, show_title, updated_at)
VALUES (?, ?, 'show', ?, ?, CURRENT_TIMESTAMP)
ON CONFLICT(user_id, watch_key) DO UPDATE SET
	kind=excluded.kind,
	library_id=excluded.library_id,
	show_title=excluded.show_title,
	updated_at=CURRENT_TIMESTAMP`, userID, showWatchKey(libraryID, showTitle), libraryID, showTitle)
	return err
}

func (s *Store) DeleteShowWatchlist(ctx context.Context, userID int64, libraryID, showTitle string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM user_watchlist WHERE user_id = ? AND watch_key = ?`, userID, showWatchKey(libraryID, showTitle))
	return err
}

func (s *Store) ListWatchlist(ctx context.Context, userID int64, limit int) (Watchlist, error) {
	if limit <= 0 {
		limit = 200
	}
	if limit > 1000 {
		limit = 1000
	}
	items, err := s.listWatchlistItems(ctx, userID, limit)
	if err != nil {
		return Watchlist{}, err
	}
	shows, err := s.listWatchlistShows(ctx, userID, limit)
	if err != nil {
		return Watchlist{}, err
	}
	return Watchlist{Items: items, Shows: shows}, nil
}

func (s *Store) listWatchlistItems(ctx context.Context, userID int64, limit int) ([]Item, error) {
	rows, err := s.db.QueryContext(ctx, itemSelectMI+`
FROM user_watchlist w
JOIN media_items mi ON mi.id = w.item_id
WHERE w.user_id = ? AND w.item_id IS NOT NULL
ORDER BY w.updated_at DESC
LIMIT ?`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Item{}
	for rows.Next() {
		item, err := scanItem(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

func (s *Store) listWatchlistShows(ctx context.Context, userID int64, limit int) ([]ShowSummary, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT mi.library_id,
	COALESCE(mi.show_title, '') AS show_title,
	LOWER(COALESCE(mi.show_title, '')) AS sort_title,
	CASE WHEN COUNT(DISTINCT NULLIF(mi.original_title, '')) = 1 THEN COALESCE(MAX(NULLIF(mi.original_title, '')), '') ELSE '' END,
	COALESCE(MIN(NULLIF(mi.year, 0)), 0),
	COUNT(*),
	COUNT(DISTINCT mi.season_number),
	COALESCE(MIN(CASE WHEN mi.poster_path IS NOT NULL AND mi.poster_path != '' THEN mi.id END), 0),
	COALESCE(MAX(mi.poster_mtime_unix), 0),
	COALESCE(MIN(CASE WHEN mi.backdrop_path IS NOT NULL AND mi.backdrop_path != '' THEN mi.id END), 0),
	COALESCE(MAX(mi.backdrop_mtime_unix), 0),
	COALESCE(MAX(NULLIF(mi.overview, '')), ''),
	COALESCE(MAX(NULLIF(mi.genres, '')), ''),
	COALESCE(MAX(mi.rating), 0),
	COALESCE(MAX(NULLIF(mi.premiered, '')), ''),
	MAX(w.updated_at)
FROM user_watchlist w
JOIN media_items mi ON mi.library_id = w.library_id AND mi.show_title = w.show_title
WHERE w.user_id = ? AND w.kind = 'show' AND mi.kind = 'episode'
GROUP BY mi.library_id, mi.show_title
ORDER BY MAX(w.updated_at) DESC
LIMIT ?`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	shows := []ShowSummary{}
	for rows.Next() {
		var show ShowSummary
		var updatedAt string
		if err := rows.Scan(&show.LibraryID, &show.Title, &show.SortTitle, &show.OriginalTitle, &show.Year, &show.EpisodeCount, &show.SeasonCount, &show.PosterItemID, &show.PosterMTimeUnix, &show.BackdropItemID, &show.BackdropMTimeUnix, &show.Overview, &show.Genres, &show.Rating, &show.Premiered, &updatedAt); err != nil {
			return nil, err
		}
		shows = append(shows, show)
	}
	return shows, rows.Err()
}

func itemWatchKey(itemID int64) string {
	return "item:" + strconv.FormatInt(itemID, 10)
}

func showWatchKey(libraryID, showTitle string) string {
	return "show:" + strings.ToLower(strings.TrimSpace(libraryID)) + ":" + strings.ToLower(strings.TrimSpace(showTitle))
}

func (s *Store) SaveTraktAccount(ctx context.Context, account TraktAccount) error {
	_, err := s.db.ExecContext(ctx, `
INSERT INTO trakt_accounts(user_id, access_token, refresh_token, expires_at, updated_at)
VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
ON CONFLICT(user_id) DO UPDATE SET
	access_token=excluded.access_token,
	refresh_token=excluded.refresh_token,
	expires_at=excluded.expires_at,
	updated_at=CURRENT_TIMESTAMP`, account.UserID, account.AccessToken, account.RefreshToken, account.ExpiresAt)
	return err
}

func (s *Store) TraktAccount(ctx context.Context, userID int64) (TraktAccount, error) {
	row := s.db.QueryRowContext(ctx, `
SELECT user_id, access_token, refresh_token, expires_at, created_at, updated_at
FROM trakt_accounts
WHERE user_id = ?`, userID)
	var account TraktAccount
	err := row.Scan(&account.UserID, &account.AccessToken, &account.RefreshToken, &account.ExpiresAt, &account.CreatedAt, &account.UpdatedAt)
	return account, err
}

func (s *Store) DeleteTraktAccount(ctx context.Context, userID int64) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM trakt_accounts WHERE user_id = ?`, userID)
	return err
}

func scanProgress(row rowScanner) (PlaybackProgress, error) {
	var progress PlaybackProgress
	var completed int
	err := row.Scan(&progress.ItemID, &progress.PositionMS, &progress.DurationMS, &completed, &progress.UpdatedAt)
	progress.Completed = completed != 0
	return progress, err
}

func nullString(v string) any {
	if v == "" {
		return nil
	}
	return v
}

func nullableInt(v int) any {
	if v == 0 {
		return nil
	}
	return v
}

func nullableInt64(v int64) any {
	if v == 0 {
		return nil
	}
	return v
}

func nullableFloat(v float64) any {
	if v == 0 {
		return nil
	}
	return v
}

func boolInt(v bool) int {
	if v {
		return 1
	}
	return 0
}

func splitGenreList(value string) []string {
	fields := strings.FieldsFunc(value, func(r rune) bool {
		return r == ',' || r == '/' || r == '|' || r == ';'
	})
	out := make([]string, 0, len(fields))
	for _, field := range fields {
		field = strings.TrimSpace(field)
		if field != "" {
			out = append(out, field)
		}
	}
	return out
}
