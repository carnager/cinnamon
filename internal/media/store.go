package media

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"popcorn/internal/database"
)

type Store struct {
	db *sql.DB
}

type SearchOptions struct {
	Query          string
	LibraryID      string
	Kind           string
	Genre          string
	Sort           string
	SeenStatus     string
	UserID         int64
	NameStartsWith string
	TitleOnly      bool
	MinRating      float64
	Limit          int
	Offset         int
}

type ShowOptions struct {
	Query          string
	LibraryID      string
	Genre          string
	Sort           string
	SeenStatus     string
	UserID         int64
	NameStartsWith string
	MinRating      float64
	Limit          int
	Offset         int
}

type AlphabetOptions struct {
	LibraryID string
	Kind      string
	Genre     string
}

func NewStore(db *sql.DB) *Store {
	return &Store{db: db}
}

func (s *Store) DB() *sql.DB {
	return s.db
}

func (s *Store) UpsertItem(ctx context.Context, item Item) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := upsertItemTx(ctx, tx, item); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) UpsertItems(ctx context.Context, items []Item) error {
	if len(items) == 0 {
		return nil
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, item := range items {
		if err := upsertItemTx(ctx, tx, item); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func upsertItemTx(ctx context.Context, tx *sql.Tx, item Item) error {
	if err := reconcileItemIdentityTx(ctx, tx, item); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
INSERT INTO media_items (
	library_id, path, kind, title, sort_title, original_title, year, duration_ms, container,
	video_codec, audio_codec, imdb_id, tmdb_id, tvdb_id, width, height, bit_rate, size_bytes, mtime_unix,
	nfo_path, nfo_mtime_unix, poster_path, poster_mtime_unix, backdrop_path, backdrop_mtime_unix,
	overview, tagline, official_rating, genres, tags, studios, directors, writers, countries, rating, premiered,
	show_title, season_number, episode_number, episode_title, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
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
	bit_rate=excluded.bit_rate,
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
	official_rating=excluded.official_rating,
	genres=excluded.genres,
	tags=excluded.tags,
	studios=excluded.studios,
	directors=excluded.directors,
	writers=excluded.writers,
	countries=excluded.countries,
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
		nullableInt(item.Width), nullableInt(item.Height), item.BitRate, item.SizeBytes, item.MTimeUnix,
		nullString(item.NFOPath), item.NFOMTimeUnix, nullString(item.PosterPath), item.PosterMTimeUnix,
		nullString(item.BackdropPath), item.BackdropMTimeUnix,
		nullString(item.Overview), nullString(item.Tagline), nullString(item.OfficialRating), nullString(item.Genres),
		nullString(item.Tags), nullString(item.Studios), nullString(item.Directors), nullString(item.Writers), nullString(item.Countries),
		nullableFloat(item.Rating), nullString(item.Premiered),
		nullString(item.ShowTitle), nullableInt(item.SeasonNumber), nullableInt(item.EpisodeNumber),
		nullString(item.EpisodeTitle)); err != nil {
		return err
	}
	var itemID int64
	if err := tx.QueryRowContext(ctx, `SELECT id FROM media_items WHERE path = ?`, item.Path).Scan(&itemID); err != nil {
		return err
	}
	if err := replaceActorsTx(ctx, tx, "item", itemID, item.LibraryID, item.ShowTitle, item.SeasonNumber, item.Actors); err != nil {
		return err
	}
	if item.StreamsKnown {
		if err := replaceMediaStreamsTx(ctx, tx, itemID, item.Streams); err != nil {
			return err
		}
	}
	if item.ShowMetadata != nil {
		if err := upsertShowMetadataTx(ctx, tx, *item.ShowMetadata); err != nil {
			return err
		}
	}
	if item.SeasonMetadata != nil {
		if err := upsertSeasonMetadataTx(ctx, tx, *item.SeasonMetadata); err != nil {
			return err
		}
	}
	return nil
}

func reconcileItemIdentityTx(ctx context.Context, tx *sql.Tx, item Item) error {
	if strings.TrimSpace(item.Path) == "" || strings.TrimSpace(item.LibraryID) == "" {
		return nil
	}
	if item.Kind == "episode" && strings.TrimSpace(item.ShowTitle) != "" && item.SeasonNumber > 0 && item.EpisodeNumber > 0 {
		return reconcileEpisodeIdentityTx(ctx, tx, item)
	}
	if item.Kind == "movie" && hasStableMovieID(item) {
		return reconcileMovieIdentityTx(ctx, tx, item)
	}
	return reconcilePathCaseTx(ctx, tx, item)
}

func hasStableMovieID(item Item) bool {
	return strings.TrimSpace(item.IMDbID) != "" || strings.TrimSpace(item.TMDbID) != "" || strings.TrimSpace(item.TVDbID) != ""
}

func reconcileMovieIdentityTx(ctx context.Context, tx *sql.Tx, item Item) error {
	rows, err := tx.QueryContext(ctx, `
SELECT id, path
FROM media_items
WHERE library_id = ?
AND kind = 'movie'
AND (
	(? != '' AND imdb_id = ?)
	OR (? != '' AND tmdb_id = ?)
	OR (? != '' AND tvdb_id = ?)
)
ORDER BY id`, item.LibraryID, strings.TrimSpace(item.IMDbID), strings.TrimSpace(item.IMDbID), strings.TrimSpace(item.TMDbID), strings.TrimSpace(item.TMDbID), strings.TrimSpace(item.TVDbID), strings.TrimSpace(item.TVDbID))
	if err != nil {
		return err
	}
	defer rows.Close()
	candidates, err := scanIdentityCandidates(rows)
	if err != nil {
		return err
	}
	return claimIdentityCandidatesTx(ctx, tx, item, candidates)
}

type identityCandidate struct {
	id   int64
	path string
}

func scanIdentityCandidates(rows *sql.Rows) ([]identityCandidate, error) {
	candidates := []identityCandidate{}
	for rows.Next() {
		var c identityCandidate
		if err := rows.Scan(&c.id, &c.path); err != nil {
			return nil, err
		}
		candidates = append(candidates, c)
	}
	return candidates, rows.Err()
}

// claimIdentityCandidatesTx reconciles item against existing rows that share
// its logical identity (external IDs for movies, show/season/episode for
// episodes). A candidate whose file is gone from disk is the same item renamed
// or moved, so the row is claimed (keeping watch state) and its path updated.
// A candidate whose file still exists is a different physical file that merely
// claims the same identity (e.g. a misfiled release); it must be left alone,
// otherwise the two files would steal one row from each other on every scan.
func claimIdentityCandidatesTx(ctx context.Context, tx *sql.Tx, item Item, candidates []identityCandidate) error {
	var keep identityCandidate
	var claimable []identityCandidate
	for _, c := range candidates {
		switch {
		case c.path == item.Path:
			keep = c
		case fileGone(c.path):
			claimable = append(claimable, c)
		}
		// A candidate whose file still exists belongs to another live file and
		// is deliberately left untouched.
	}
	merge := claimable
	if keep.id == 0 && len(claimable) > 0 {
		keep, merge = claimable[0], claimable[1:]
	}
	if keep.id == 0 {
		return reconcilePathCaseTx(ctx, tx, item)
	}
	for _, c := range merge {
		if err := mergeDuplicateItemTx(ctx, tx, keep.id, c.id); err != nil {
			return err
		}
	}
	if keep.path != item.Path {
		if _, err := tx.ExecContext(ctx, `UPDATE media_items SET path = ? WHERE id = ?`, item.Path, keep.id); err != nil {
			return err
		}
	}
	return nil
}

// fileGone reports whether nothing exists at path anymore, meaning a database
// row pointing there may be claimed by a renamed/moved file. Stat errors other
// than "not exist" (permissions, flaky mounts) count as still present so a
// transient failure never lets one file hijack another's row.
func fileGone(path string) bool {
	if strings.TrimSpace(path) == "" {
		return true
	}
	_, err := os.Stat(path)
	return errors.Is(err, os.ErrNotExist)
}

func reconcilePathCaseTx(ctx context.Context, tx *sql.Tx, item Item) error {
	var id int64
	var path string
	err := tx.QueryRowContext(ctx, `
SELECT id, path
FROM media_items
WHERE library_id = ? AND lower(path) = lower(?)
ORDER BY id
LIMIT 1`, item.LibraryID, item.Path).Scan(&id, &path)
	if err == sql.ErrNoRows {
		return nil
	}
	if err != nil {
		return err
	}
	if path == item.Path {
		return nil
	}
	_, err = tx.ExecContext(ctx, `UPDATE media_items SET path = ? WHERE id = ?`, item.Path, id)
	return err
}

func reconcileEpisodeIdentityTx(ctx context.Context, tx *sql.Tx, item Item) error {
	rows, err := tx.QueryContext(ctx, `
SELECT id, path
FROM media_items
WHERE library_id = ?
AND kind = 'episode'
AND show_title = ?
AND COALESCE(season_number, 0) = ?
AND COALESCE(episode_number, 0) = ?
ORDER BY id`, item.LibraryID, item.ShowTitle, item.SeasonNumber, item.EpisodeNumber)
	if err != nil {
		return err
	}
	defer rows.Close()
	candidates, err := scanIdentityCandidates(rows)
	if err != nil {
		return err
	}
	return claimIdentityCandidatesTx(ctx, tx, item, candidates)
}

func mergeDuplicateItemTx(ctx context.Context, tx *sql.Tx, keepID, duplicateID int64) error {
	if keepID == duplicateID {
		return nil
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE playback_progress
SET item_id = ?
WHERE item_id = ?
AND NOT EXISTS (
	SELECT 1 FROM playback_progress keep
	WHERE keep.user_id = playback_progress.user_id AND keep.item_id = ?
)`, keepID, duplicateID, keepID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM playback_progress WHERE item_id = ?`, duplicateID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE user_watchlist
SET item_id = ?
WHERE item_id = ?
AND NOT EXISTS (
	SELECT 1 FROM user_watchlist keep
	WHERE keep.user_id = user_watchlist.user_id AND keep.watch_key = user_watchlist.watch_key
)`, keepID, duplicateID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM external_ratings_cache WHERE item_id = ? AND EXISTS(SELECT 1 FROM external_ratings_cache WHERE item_id = ?)`, duplicateID, keepID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE external_ratings_cache SET item_id = ? WHERE item_id = ?`, keepID, duplicateID); err != nil {
		return err
	}
	_, err := tx.ExecContext(ctx, `DELETE FROM media_items WHERE id = ?`, duplicateID)
	return err
}

func replaceMediaStreamsTx(ctx context.Context, tx *sql.Tx, itemID int64, streams []MediaStream) error {
	if _, err := tx.ExecContext(ctx, `DELETE FROM media_streams WHERE item_id = ?`, itemID); err != nil {
		return err
	}
	for _, stream := range streams {
		if strings.TrimSpace(stream.Type) == "" || strings.TrimSpace(stream.Codec) == "" {
			continue
		}
		dispositionJSON := stream.DispositionJSON
		if dispositionJSON == "" {
			b, _ := json.Marshal(map[string]any{"default": stream.Default, "forced": stream.Forced})
			dispositionJSON = string(b)
		}
		rawJSON := stream.RawJSON
		if rawJSON == "" {
			rawJSON = "{}"
		}
		if _, err := tx.ExecContext(ctx, `
INSERT INTO media_streams(
	item_id, stream_index, type, codec, codec_long_name, profile, level, width, height,
	pix_fmt, color_range, color_space, color_transfer, color_primaries, hdr_format, bit_rate,
	channels, channel_layout, sample_rate, language, title, is_default, is_forced, disposition_json, raw_json, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)`,
			itemID, stream.Index, stream.Type, stream.Codec, nullString(stream.CodecLongName), nullString(stream.Profile), nullableInt(stream.Level),
			nullableInt(stream.Width), nullableInt(stream.Height), nullString(stream.PixelFormat), nullString(stream.ColorRange), nullString(stream.ColorSpace),
			nullString(stream.ColorTransfer), nullString(stream.ColorPrimaries), nullString(stream.HDRFormat), nullableInt64(stream.BitRate),
			nullableInt(stream.Channels), nullString(stream.ChannelLayout), nullableInt(stream.SampleRate), nullString(stream.Language), nullString(stream.Title),
			boolInt(stream.Default), boolInt(stream.Forced), nullString(dispositionJSON), rawJSON); err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) ReplaceMediaStreams(ctx context.Context, itemID int64, streams []MediaStream) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := replaceMediaStreamsTx(ctx, tx, itemID, streams); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) MediaStreams(ctx context.Context, itemID int64) ([]MediaStream, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT item_id, stream_index, type, COALESCE(codec, ''), COALESCE(codec_long_name, ''), COALESCE(profile, ''),
	COALESCE(level, 0), COALESCE(width, 0), COALESCE(height, 0), COALESCE(pix_fmt, ''), COALESCE(color_range, ''),
	COALESCE(color_space, ''), COALESCE(color_transfer, ''), COALESCE(color_primaries, ''), COALESCE(hdr_format, ''),
	COALESCE(bit_rate, 0), COALESCE(channels, 0), COALESCE(channel_layout, ''), COALESCE(sample_rate, 0),
	COALESCE(language, ''), COALESCE(title, ''), COALESCE(is_default, 0), COALESCE(is_forced, 0),
	COALESCE(disposition_json, ''), COALESCE(raw_json, '')
FROM media_streams
WHERE item_id = ?
ORDER BY stream_index`, itemID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []MediaStream{}
	for rows.Next() {
		var stream MediaStream
		var isDefault, isForced int
		if err := rows.Scan(&stream.ItemID, &stream.Index, &stream.Type, &stream.Codec, &stream.CodecLongName, &stream.Profile,
			&stream.Level, &stream.Width, &stream.Height, &stream.PixelFormat, &stream.ColorRange, &stream.ColorSpace,
			&stream.ColorTransfer, &stream.ColorPrimaries, &stream.HDRFormat, &stream.BitRate, &stream.Channels,
			&stream.ChannelLayout, &stream.SampleRate, &stream.Language, &stream.Title, &isDefault, &isForced,
			&stream.DispositionJSON, &stream.RawJSON); err != nil {
			return nil, err
		}
		stream.Default = isDefault != 0
		stream.Forced = isForced != 0
		out = append(out, stream)
	}
	return out, rows.Err()
}

func (s *Store) MediaStreamCount(ctx context.Context, itemID int64) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM media_streams WHERE item_id = ?`, itemID).Scan(&n)
	return n, err
}

func upsertShowMetadataTx(ctx context.Context, tx *sql.Tx, meta ShowMetadata) error {
	if strings.TrimSpace(meta.LibraryID) == "" || strings.TrimSpace(meta.Title) == "" {
		return nil
	}
	if meta.SortTitle == "" {
		meta.SortTitle = sortKey(meta.Title)
	}
	_, err := tx.ExecContext(ctx, `
INSERT INTO media_shows(library_id, show_title, sort_title, original_title, year, nfo_path, nfo_mtime_unix, overview, genres, rating, premiered, updated_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
ON CONFLICT(library_id, show_title) DO UPDATE SET
	sort_title=excluded.sort_title,
	original_title=excluded.original_title,
	year=excluded.year,
	nfo_path=excluded.nfo_path,
	nfo_mtime_unix=excluded.nfo_mtime_unix,
	overview=excluded.overview,
	genres=excluded.genres,
	rating=excluded.rating,
	premiered=excluded.premiered,
	updated_at=CURRENT_TIMESTAMP`,
		meta.LibraryID, meta.Title, meta.SortTitle, nullString(meta.OriginalTitle), nullableInt(meta.Year),
		nullString(meta.NFOPath), meta.NFOMTimeUnix, nullString(meta.Overview), nullString(meta.Genres),
		nullableFloat(meta.Rating), nullString(meta.Premiered))
	if err != nil {
		return err
	}
	return replaceActorsTx(ctx, tx, "show", 0, meta.LibraryID, meta.Title, 0, meta.Actors)
}

func upsertSeasonMetadataTx(ctx context.Context, tx *sql.Tx, meta SeasonMetadata) error {
	if strings.TrimSpace(meta.LibraryID) == "" || strings.TrimSpace(meta.ShowTitle) == "" {
		return nil
	}
	_, err := tx.ExecContext(ctx, `
INSERT INTO media_seasons(library_id, show_title, season_number, title, nfo_path, nfo_mtime_unix, poster_path, poster_mtime_unix, overview, rating, premiered, updated_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
ON CONFLICT(library_id, show_title, season_number) DO UPDATE SET
	title=excluded.title,
	nfo_path=excluded.nfo_path,
	nfo_mtime_unix=excluded.nfo_mtime_unix,
	poster_path=excluded.poster_path,
	poster_mtime_unix=excluded.poster_mtime_unix,
	overview=excluded.overview,
	rating=excluded.rating,
	premiered=excluded.premiered,
	updated_at=CURRENT_TIMESTAMP`,
		meta.LibraryID, meta.ShowTitle, meta.SeasonNumber, nullString(meta.Title), nullString(meta.NFOPath), meta.NFOMTimeUnix,
		nullString(meta.PosterPath), meta.PosterMTimeUnix, nullString(meta.Overview), nullableFloat(meta.Rating), nullString(meta.Premiered))
	if err != nil {
		return err
	}
	return replaceActorsTx(ctx, tx, "season", 0, meta.LibraryID, meta.ShowTitle, meta.SeasonNumber, meta.Actors)
}

func replaceActorsTx(ctx context.Context, tx *sql.Tx, scope string, itemID int64, libraryID, showTitle string, seasonNumber int, actors []Actor) error {
	switch scope {
	case "item":
		if _, err := tx.ExecContext(ctx, `DELETE FROM media_actors WHERE scope = 'item' AND item_id = ?`, itemID); err != nil {
			return err
		}
	case "show":
		if _, err := tx.ExecContext(ctx, `DELETE FROM media_actors WHERE scope = 'show' AND library_id = ? AND show_title = ?`, libraryID, showTitle); err != nil {
			return err
		}
	case "season":
		if _, err := tx.ExecContext(ctx, `DELETE FROM media_actors WHERE scope = 'season' AND library_id = ? AND show_title = ? AND season_number = ?`, libraryID, showTitle, seasonNumber); err != nil {
			return err
		}
	default:
		return nil
	}
	for i, actor := range actors {
		name := strings.TrimSpace(actor.Name)
		if name == "" {
			continue
		}
		order := actor.Order
		if order == 0 {
			order = i + 1
		}
		if _, err := tx.ExecContext(ctx, `
INSERT INTO media_actors(scope, item_id, library_id, show_title, season_number, name, role, thumb, sort_order)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			scope, nullableInt64(itemID), nullString(libraryID), nullString(showTitle), nullableInt(seasonNumber), name, nullString(actor.Role), nullString(actor.Thumb), order); err != nil {
			return err
		}
	}
	return nil
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
	if len(missing) == 0 {
		return nil
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, path := range missing {
		if _, err := tx.ExecContext(ctx, `DELETE FROM media_items WHERE path = ?`, path); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) RemovePaths(ctx context.Context, libraryID string, paths []string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, path := range paths {
		if strings.TrimSpace(path) == "" {
			continue
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM media_items WHERE library_id = ? AND path = ?`, libraryID, path); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) RemovePathPrefix(ctx context.Context, libraryID, prefix string) error {
	prefix = strings.TrimSpace(prefix)
	if prefix == "" {
		return nil
	}
	if _, err := s.db.ExecContext(ctx, `DELETE FROM media_items WHERE library_id = ? AND (path = ? OR path LIKE ?)`, libraryID, prefix, prefix+string(filepath.Separator)+"%"); err != nil {
		return err
	}
	return nil
}

// PruneOrphanShowRows deletes show- and season-level metadata rows (and their
// actor links) that no longer have any episodes — e.g. a ghost show scanned
// from a release folder that an external renamer has since moved away. It
// returns the number of show and season rows removed.
func (s *Store) PruneOrphanShowRows(ctx context.Context, libraryID string) (int64, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `
DELETE FROM media_actors
WHERE scope = 'show' AND library_id = ? AND NOT EXISTS (
	SELECT 1 FROM media_items mi
	WHERE mi.library_id = media_actors.library_id AND mi.show_title = media_actors.show_title
)`, libraryID); err != nil {
		return 0, err
	}
	if _, err := tx.ExecContext(ctx, `
DELETE FROM media_actors
WHERE scope = 'season' AND library_id = ? AND NOT EXISTS (
	SELECT 1 FROM media_items mi
	WHERE mi.library_id = media_actors.library_id AND mi.show_title = media_actors.show_title
	  AND COALESCE(mi.season_number, 0) = media_actors.season_number
)`, libraryID); err != nil {
		return 0, err
	}
	res, err := tx.ExecContext(ctx, `
DELETE FROM media_shows
WHERE library_id = ? AND NOT EXISTS (
	SELECT 1 FROM media_items mi
	WHERE mi.library_id = media_shows.library_id AND mi.show_title = media_shows.show_title
)`, libraryID)
	if err != nil {
		return 0, err
	}
	shows, _ := res.RowsAffected()
	res, err = tx.ExecContext(ctx, `
DELETE FROM media_seasons
WHERE library_id = ? AND NOT EXISTS (
	SELECT 1 FROM media_items mi
	WHERE mi.library_id = media_seasons.library_id AND mi.show_title = media_seasons.show_title
	  AND COALESCE(mi.season_number, 0) = media_seasons.season_number
)`, libraryID)
	if err != nil {
		return 0, err
	}
	seasons, _ := res.RowsAffected()
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return shows + seasons, nil
}

func (s *Store) ItemPathExists(ctx context.Context, libraryID, path string) (bool, error) {
	var exists int
	err := s.db.QueryRowContext(ctx, `
SELECT 1
FROM media_items
WHERE library_id = ? AND path = ?
LIMIT 1`, libraryID, path).Scan(&exists)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, nil
}

func (s *Store) ListItems(ctx context.Context, libraryID, q, genre, sort string, minRating float64, limit, offset int) ([]Item, error) {
	return s.ListItemsForUser(ctx, libraryID, q, genre, sort, "", 0, minRating, limit, offset)
}

func (s *Store) ListItemsForUser(ctx context.Context, libraryID, q, genre, sort, seenStatus string, userID int64, minRating float64, limit, offset int) ([]Item, error) {
	if limit <= 0 {
		limit = 250
	}
	if limit > 2000 {
		limit = 2000
	}
	return s.searchItems(ctx, SearchOptions{Query: q, LibraryID: libraryID, Genre: genre, Sort: sort, SeenStatus: seenStatus, UserID: userID, MinRating: minRating, Limit: limit, Offset: offset})
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

func (s *Store) AlphabetIndex(ctx context.Context, opts AlphabetOptions) ([]AlphabetEntry, error) {
	var (
		rows *sql.Rows
		err  error
	)
	genres := splitFilterList(opts.Genre)
	switch opts.Kind {
	case "tv":
		genreWhere, genreArgs := itemGenreFilterSQL("genres", genres)
		args := []any{opts.LibraryID, opts.LibraryID}
		args = append(args, genreArgs...)
		rows, err = s.db.QueryContext(ctx, `
SELECT LOWER(COALESCE(show_title, '')) AS sort_title
FROM media_items
WHERE kind = 'episode'
AND (? = '' OR library_id = ?)
`+genreWhere+`
AND show_title IS NOT NULL AND show_title != ''
GROUP BY library_id, show_title
ORDER BY sort_title`, args...)
	default:
		genreWhere, genreArgs := itemGenreFilterSQL("genres", genres)
		args := []any{opts.LibraryID, opts.LibraryID}
		args = append(args, genreArgs...)
		rows, err = s.db.QueryContext(ctx, `
SELECT LOWER(COALESCE(NULLIF(sort_title, ''), title, '')) AS sort_title
FROM media_items
WHERE kind = 'movie'
AND (? = '' OR library_id = ?)
`+genreWhere+`
ORDER BY sort_title`, args...)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []AlphabetEntry{}
	byLetter := map[string]int{}
	offset := 0
	for rows.Next() {
		var title string
		if err := rows.Scan(&title); err != nil {
			return nil, err
		}
		letter := alphabetLetter(title)
		if idx, ok := byLetter[letter]; ok {
			out[idx].Count++
		} else {
			byLetter[letter] = len(out)
			out = append(out, AlphabetEntry{Letter: letter, Offset: offset, Count: 1})
		}
		offset++
	}
	return out, rows.Err()
}

func alphabetLetter(title string) string {
	title = strings.TrimSpace(title)
	runes := []rune(title)
	if len(runes) == 0 {
		return "#"
	}
	r := runes[0]
	switch {
	case r >= 'a' && r <= 'z':
		return strings.ToUpper(string(r))
	case r >= 'A' && r <= 'Z':
		return string(r)
	default:
		return "#"
	}
}

func (s *Store) searchItems(ctx context.Context, opts SearchOptions) ([]Item, error) {
	q := opts.Query
	libraryID := opts.LibraryID
	kind := opts.Kind
	genre := opts.Genre
	genres := splitFilterList(genre)
	genreWhere, genreArgs := itemGenreFilterSQL("genres", genres)
	nameStartsWith := strings.TrimSpace(opts.NameStartsWith)
	seenStatus := normalizedSeenStatus(opts.SeenStatus)
	userID := opts.UserID
	sortMode := normalizedSort(opts.Sort)
	orderBy := itemOrderBy(sortMode)
	if sortMode == "" {
		orderBy = `ORDER BY
	CASE
		WHEN ? = '' THEN 0
		WHEN title LIKE ? || '%' THEN 0
		WHEN original_title LIKE ? || '%' THEN 1
		WHEN show_title LIKE ? || '%' THEN 2
		WHEN episode_title LIKE ? || '%' THEN 3
		ELSE 3
	END,
	sort_title, season_number, episode_number`
	}
	// nq is a relaxed, normalized form of the query (lowercased, punctuation and
	// diacritics stripped) compared against searchnorm()-normalized columns so
	// "Lets Dance" matches "Let's Dance". The empty-query guard still tests the
	// raw query so a query that is only punctuation isn't treated as empty.
	nq := database.SearchNormalize(q)
	textWhere := `AND (
	? = ''
	OR searchnorm(title) LIKE '%' || ? || '%'
	OR searchnorm(sort_title) LIKE '%' || ? || '%'
	OR searchnorm(original_title) LIKE '%' || ? || '%'
	OR searchnorm(show_title) LIKE '%' || ? || '%'
	OR searchnorm(episode_title) LIKE '%' || ? || '%'
	OR searchnorm(overview) LIKE '%' || ? || '%'
	OR searchnorm(genres) LIKE '%' || ? || '%'
	OR CAST(year AS TEXT) = ?
	OR EXISTS (
		SELECT 1 FROM media_actors ma
		WHERE ma.scope = 'item'
		AND ma.item_id = media_items.id
		AND searchnorm(ma.name) LIKE '%' || ? || '%'
	)
)`
	textArgs := []any{q, nq, nq, nq, nq, nq, nq, nq, q, nq}
	if opts.TitleOnly {
		textWhere = `AND (
	? = ''
	OR searchnorm(title) LIKE '%' || ? || '%'
	OR searchnorm(sort_title) LIKE '%' || ? || '%'
	OR searchnorm(original_title) LIKE '%' || ? || '%'
	OR CAST(year AS TEXT) = ?
)`
		textArgs = []any{q, nq, nq, nq, q}
	}
	query := itemSelect + `
FROM media_items
WHERE (? = '' OR library_id = ?)
AND (? = '' OR kind = ?)
` + genreWhere + `
AND (? <= 0 OR COALESCE(rating, 0) >= ?)
AND (
	? = ''
	OR (? = 'seen' AND ? > 0 AND EXISTS (
		SELECT 1 FROM playback_progress pp
		WHERE pp.user_id = ? AND pp.item_id = media_items.id AND pp.completed = 1
	))
	OR (? = 'unseen' AND (? <= 0 OR NOT EXISTS (
		SELECT 1 FROM playback_progress pp
		WHERE pp.user_id = ? AND pp.item_id = media_items.id AND pp.completed = 1
	)))
	OR (? = 'started' AND ? > 0 AND EXISTS (
		SELECT 1 FROM playback_progress pp
		WHERE pp.user_id = ? AND pp.item_id = media_items.id
		AND pp.completed = 0
		AND pp.position_ms >= 60000
		AND (pp.duration_ms <= 0 OR pp.duration_ms - pp.position_ms >= 60000)
	))
)
AND (
	? = ''
	OR (? = '#' AND LOWER(COALESCE(NULLIF(sort_title, ''), title, '')) NOT GLOB '[a-z]*')
	OR (? != '#' AND LOWER(COALESCE(NULLIF(sort_title, ''), title, '')) LIKE LOWER(?) || '%')
)
` + textWhere + `
` + orderBy + `
LIMIT ? OFFSET ?`
	args := []any{
		libraryID, libraryID,
		kind, kind,
	}
	args = append(args, genreArgs...)
	args = append(args,
		opts.MinRating, opts.MinRating,
		seenStatus, seenStatus, userID, userID, seenStatus, userID, userID, seenStatus, userID, userID,
		nameStartsWith, nameStartsWith, nameStartsWith, nameStartsWith,
	)
	args = append(args, textArgs...)
	if sortMode == "" {
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

func itemOrderBy(sortMode string) string {
	switch sortMode {
	case "title_desc":
		return `ORDER BY sort_title DESC, season_number DESC, episode_number DESC`
	case "year":
		return `ORDER BY COALESCE(year, 0), sort_title, season_number, episode_number`
	case "year_desc":
		return `ORDER BY COALESCE(year, 0) DESC, sort_title, season_number, episode_number`
	case "recent":
		return `ORDER BY updated_at DESC, sort_title, season_number, episode_number`
	case "recent_asc":
		return `ORDER BY updated_at, sort_title, season_number, episode_number`
	case "mtime":
		return `ORDER BY mtime_unix DESC, sort_title, season_number, episode_number`
	case "mtime_asc":
		return `ORDER BY mtime_unix, sort_title, season_number, episode_number`
	case "rating":
		return `ORDER BY COALESCE(rating, 0) DESC, sort_title, season_number, episode_number`
	case "rating_asc":
		return `ORDER BY COALESCE(rating, 0), sort_title, season_number, episode_number`
	default:
		return `ORDER BY sort_title, season_number, episode_number`
	}
}

func normalizedSort(sortMode string) string {
	switch strings.ToLower(strings.TrimSpace(sortMode)) {
	case "title_desc", "year", "year_desc", "recent", "recent_asc", "mtime", "mtime_asc", "rating", "rating_asc":
		return strings.ToLower(strings.TrimSpace(sortMode))
	default:
		return ""
	}
}

func normalizedSeenStatus(status string) string {
	switch strings.ToLower(strings.TrimSpace(status)) {
	case "seen", "unseen", "started":
		return strings.ToLower(strings.TrimSpace(status))
	default:
		return ""
	}
}

func splitFilterList(value string) []string {
	seen := map[string]bool{}
	out := []string{}
	for _, part := range strings.FieldsFunc(value, func(r rune) bool { return r == ',' || r == '|' }) {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		key := strings.ToLower(part)
		if seen[key] {
			continue
		}
		seen[key] = true
		out = append(out, part)
	}
	return out
}

func itemGenreFilterSQL(column string, genres []string) (string, []any) {
	if len(genres) == 0 {
		return "", nil
	}
	clauses := make([]string, 0, len(genres))
	args := make([]any, 0, len(genres))
	for _, genre := range genres {
		clauses = append(clauses, column+" LIKE '%' || ? || '%'")
		args = append(args, genre)
	}
	return "AND (" + strings.Join(clauses, " OR ") + ")", args
}

func showGenreFilterSQL(genres []string) (string, []any) {
	if len(genres) == 0 {
		return "", nil
	}
	clauses := make([]string, 0, len(genres))
	args := make([]any, 0, len(genres)*2)
	for _, genre := range genres {
		clauses = append(clauses, "(mi.genres LIKE '%' || ? || '%' OR ms.genres LIKE '%' || ? || '%')")
		args = append(args, genre, genre)
	}
	return "AND (" + strings.Join(clauses, " OR ") + ")", args
}

const itemSelectColumns = `SELECT id, library_id, path, kind, title, sort_title, COALESCE(original_title, ''), COALESCE(year, 0), COALESCE(duration_ms, 0),
COALESCE(container, ''), COALESCE(video_codec, ''), COALESCE(audio_codec, ''), COALESCE(imdb_id, ''), COALESCE(tmdb_id, ''), COALESCE(tvdb_id, ''), COALESCE(width, 0),
COALESCE(height, 0), COALESCE(bit_rate, 0), size_bytes, mtime_unix, COALESCE(nfo_path, ''), COALESCE(nfo_mtime_unix, 0), COALESCE(poster_path, ''), COALESCE(poster_mtime_unix, 0), COALESCE(backdrop_path, ''), COALESCE(backdrop_mtime_unix, 0),
COALESCE(overview, ''), COALESCE(tagline, ''), COALESCE(official_rating, ''), COALESCE(genres, ''), COALESCE(tags, ''),
COALESCE(studios, ''), COALESCE(directors, ''), COALESCE(writers, ''), COALESCE(countries, ''), COALESCE(rating, 0), COALESCE(premiered, ''),
COALESCE(show_title, ''), COALESCE(season_number, 0), COALESCE(episode_number, 0), COALESCE(episode_title, ''),`

const itemSelect = itemSelectColumns + `
0`

const itemSelectWithStreamState = itemSelectColumns + `
EXISTS(SELECT 1 FROM media_streams ms WHERE ms.item_id = id LIMIT 1)`

const itemSelectMIColumns = `SELECT mi.id, mi.library_id, mi.path, mi.kind, mi.title, mi.sort_title, COALESCE(mi.original_title, ''), COALESCE(mi.year, 0), COALESCE(mi.duration_ms, 0),
COALESCE(mi.container, ''), COALESCE(mi.video_codec, ''), COALESCE(mi.audio_codec, ''), COALESCE(mi.imdb_id, ''), COALESCE(mi.tmdb_id, ''), COALESCE(mi.tvdb_id, ''), COALESCE(mi.width, 0),
COALESCE(mi.height, 0), COALESCE(mi.bit_rate, 0), mi.size_bytes, mi.mtime_unix, COALESCE(mi.nfo_path, ''), COALESCE(mi.nfo_mtime_unix, 0), COALESCE(mi.poster_path, ''), COALESCE(mi.poster_mtime_unix, 0), COALESCE(mi.backdrop_path, ''), COALESCE(mi.backdrop_mtime_unix, 0),
COALESCE(mi.overview, ''), COALESCE(mi.tagline, ''), COALESCE(mi.official_rating, ''), COALESCE(mi.genres, ''), COALESCE(mi.tags, ''),
COALESCE(mi.studios, ''), COALESCE(mi.directors, ''), COALESCE(mi.writers, ''), COALESCE(mi.countries, ''), COALESCE(mi.rating, 0), COALESCE(mi.premiered, ''),
COALESCE(mi.show_title, ''), COALESCE(mi.season_number, 0), COALESCE(mi.episode_number, 0), COALESCE(mi.episode_title, ''),`

const itemSelectMI = itemSelectMIColumns + `
0`

func (s *Store) GetItem(ctx context.Context, id int64) (Item, error) {
	row := s.db.QueryRowContext(ctx, itemSelect+` FROM media_items WHERE id = ?`, id)
	item, err := scanItem(row)
	if err != nil {
		return Item{}, err
	}
	item.Actors, err = s.ListItemActors(ctx, item.ID)
	return item, err
}

func (s *Store) ItemsByIDs(ctx context.Context, ids []int64) ([]Item, error) {
	if len(ids) == 0 {
		return nil, nil
	}
	seen := map[int64]bool{}
	orderedIDs := make([]int64, 0, len(ids))
	for _, id := range ids {
		if id <= 0 || seen[id] {
			continue
		}
		seen[id] = true
		orderedIDs = append(orderedIDs, id)
	}
	if len(orderedIDs) == 0 {
		return nil, nil
	}
	placeholders := make([]string, len(orderedIDs))
	args := make([]any, len(orderedIDs))
	for i, id := range orderedIDs {
		placeholders[i] = "?"
		args[i] = id
	}
	rows, err := s.db.QueryContext(ctx, itemSelect+`
FROM media_items
WHERE id IN (`+strings.Join(placeholders, ",")+`)`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	byID := map[int64]Item{}
	for rows.Next() {
		item, err := scanItem(rows)
		if err != nil {
			return nil, err
		}
		byID[item.ID] = item
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]Item, 0, len(byID))
	for _, id := range orderedIDs {
		if item, ok := byID[id]; ok {
			out = append(out, item)
		}
	}
	return out, nil
}

// ItemsByExternalIDs returns library items whose tmdb_id or imdb_id is in the
// given sets. Used to intersect external recommendations with the local
// library. Order is not significant; the caller re-orders by its own ranking.
func (s *Store) ItemsByExternalIDs(ctx context.Context, kind string, tmdbIDs, imdbIDs []string) ([]Item, error) {
	tmdbIDs = nonEmptyUniqueStrings(tmdbIDs)
	imdbIDs = nonEmptyUniqueStrings(imdbIDs)
	if len(tmdbIDs) == 0 && len(imdbIDs) == 0 {
		return nil, nil
	}
	var conds []string
	var args []any
	if kind = strings.TrimSpace(kind); kind != "" {
		conds = append(conds, "kind = ?")
		args = append(args, kind)
	}
	var idConds []string
	if len(tmdbIDs) > 0 {
		ph := make([]string, len(tmdbIDs))
		for i, v := range tmdbIDs {
			ph[i] = "?"
			args = append(args, v)
		}
		idConds = append(idConds, "tmdb_id IN ("+strings.Join(ph, ",")+")")
	}
	if len(imdbIDs) > 0 {
		ph := make([]string, len(imdbIDs))
		for i, v := range imdbIDs {
			ph[i] = "?"
			args = append(args, v)
		}
		idConds = append(idConds, "imdb_id IN ("+strings.Join(ph, ",")+")")
	}
	conds = append(conds, "("+strings.Join(idConds, " OR ")+")")
	rows, err := s.db.QueryContext(ctx, itemSelect+` FROM media_items WHERE `+strings.Join(conds, " AND "), args...)
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

func nonEmptyUniqueStrings(in []string) []string {
	if len(in) == 0 {
		return nil
	}
	seen := make(map[string]struct{}, len(in))
	out := make([]string, 0, len(in))
	for _, v := range in {
		v = strings.TrimSpace(v)
		if v == "" {
			continue
		}
		if _, ok := seen[v]; ok {
			continue
		}
		seen[v] = struct{}{}
		out = append(out, v)
	}
	return out
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
	rows, err := s.db.QueryContext(ctx, itemSelectWithStreamState+` FROM media_items WHERE library_id = ?`, libraryID)
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

func (s *Store) ShowSampleItem(ctx context.Context, libraryID, showTitle string) (Item, error) {
	row := s.db.QueryRowContext(ctx, itemSelect+`
FROM media_items
WHERE library_id = ? AND show_title = ? AND kind = 'episode'
ORDER BY season_number, episode_number, id
LIMIT 1`, libraryID, showTitle)
	return scanItem(row)
}

func (s *Store) MetadataBackfillNeeded(ctx context.Context, libraryID, name string) (bool, error) {
	var exists int
	err := s.db.QueryRowContext(ctx, `SELECT 1 FROM metadata_backfills WHERE library_id = ? AND name = ?`, libraryID, name).Scan(&exists)
	if err == nil {
		return false, nil
	}
	if err == sql.ErrNoRows {
		return true, nil
	}
	return false, err
}

func (s *Store) MarkMetadataBackfillComplete(ctx context.Context, libraryID, name string) error {
	_, err := s.db.ExecContext(ctx, `
INSERT INTO metadata_backfills(library_id, name, completed_at)
VALUES (?, ?, CURRENT_TIMESTAMP)
ON CONFLICT(library_id, name) DO UPDATE SET completed_at = CURRENT_TIMESTAMP`, libraryID, name)
	return err
}

func (s *Store) ListShows(ctx context.Context, libraryID, q, genre, sort string, minRating float64, limit, offset int) ([]ShowSummary, error) {
	return s.ListShowsForUser(ctx, libraryID, q, genre, sort, "", 0, minRating, limit, offset)
}

func (s *Store) ListShowsForUser(ctx context.Context, libraryID, q, genre, sort, seenStatus string, userID int64, minRating float64, limit, offset int) ([]ShowSummary, error) {
	return s.SearchShows(ctx, ShowOptions{
		Query:      q,
		LibraryID:  libraryID,
		Genre:      genre,
		Sort:       sort,
		SeenStatus: seenStatus,
		UserID:     userID,
		MinRating:  minRating,
		Limit:      limit,
		Offset:     offset,
	})
}

func (s *Store) SearchShows(ctx context.Context, opts ShowOptions) ([]ShowSummary, error) {
	libraryID := opts.LibraryID
	q := opts.Query
	nq := database.SearchNormalize(q)
	genre := opts.Genre
	genres := splitFilterList(genre)
	genreWhere, genreArgs := showGenreFilterSQL(genres)
	nameStartsWith := strings.TrimSpace(opts.NameStartsWith)
	seenStatus := normalizedSeenStatus(opts.SeenStatus)
	userID := opts.UserID
	limit := opts.Limit
	offset := opts.Offset
	if limit <= 0 {
		limit = 250
	}
	if limit > 1000 {
		limit = 1000
	}
	orderBy := "ORDER BY sort_title"
	switch normalizedSort(opts.Sort) {
	case "title_desc":
		orderBy = "ORDER BY sort_title DESC"
	case "year":
		orderBy = "ORDER BY COALESCE(ms.year, MIN(NULLIF(mi.year, 0)), 0), sort_title"
	case "year_desc":
		orderBy = "ORDER BY COALESCE(ms.year, MIN(NULLIF(mi.year, 0)), 0) DESC, sort_title"
	case "recent":
		orderBy = "ORDER BY MAX(mi.updated_at) DESC, sort_title"
	case "recent_asc":
		orderBy = "ORDER BY MAX(mi.updated_at), sort_title"
	case "mtime":
		orderBy = "ORDER BY MAX(mi.mtime_unix) DESC, sort_title"
	case "mtime_asc":
		orderBy = "ORDER BY MAX(mi.mtime_unix), sort_title"
	case "rating":
		orderBy = "ORDER BY COALESCE(ms.rating, MAX(mi.rating), 0) DESC, sort_title"
	case "rating_asc":
		orderBy = "ORDER BY COALESCE(ms.rating, MAX(mi.rating), 0), sort_title"
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT mi.library_id,
	COALESCE(mi.show_title, '') AS show_title,
	COALESCE(ms.sort_title, LOWER(COALESCE(mi.show_title, ''))) AS sort_title,
	COALESCE(ms.original_title, CASE WHEN COUNT(DISTINCT NULLIF(mi.original_title, '')) = 1 THEN COALESCE(MAX(NULLIF(mi.original_title, '')), '') ELSE '' END, ''),
	COALESCE(ms.year, MIN(NULLIF(mi.year, 0)), 0),
	COALESCE(MAX(NULLIF(mi.year, 0)), 0),
	COUNT(*),
	COUNT(DISTINCT mi.season_number),
	COALESCE(MIN(CASE WHEN mi.poster_path IS NOT NULL AND mi.poster_path != '' THEN mi.id END), 0),
	COALESCE(MAX(mi.poster_mtime_unix), 0),
	COALESCE(MIN(CASE WHEN mi.backdrop_path IS NOT NULL AND mi.backdrop_path != '' THEN mi.id END), 0),
	COALESCE(MAX(mi.backdrop_mtime_unix), 0),
	COALESCE(ms.overview, MAX(NULLIF(mi.overview, '')), ''),
	COALESCE(ms.genres, MAX(NULLIF(mi.genres, '')), ''),
	COALESCE(ms.rating, MAX(mi.rating), 0),
	COALESCE(ms.premiered, MAX(NULLIF(mi.premiered, '')), '')
FROM media_items mi
LEFT JOIN media_shows ms ON ms.library_id = mi.library_id AND ms.show_title = mi.show_title
WHERE mi.kind = 'episode'
AND (? = '' OR mi.library_id = ?)
`+genreWhere+`
AND mi.show_title IS NOT NULL AND mi.show_title != ''
AND (
	? = ''
	OR (? = 'seen' AND ? > 0 AND NOT EXISTS (
		SELECT 1 FROM media_items e
		LEFT JOIN playback_progress pp ON pp.user_id = ? AND pp.item_id = e.id AND pp.completed = 1
		WHERE e.kind = 'episode' AND e.library_id = mi.library_id AND e.show_title = mi.show_title
		AND pp.item_id IS NULL
	))
	OR (? = 'unseen' AND (? <= 0 OR EXISTS (
		SELECT 1 FROM media_items e
		LEFT JOIN playback_progress pp ON pp.user_id = ? AND pp.item_id = e.id AND pp.completed = 1
		WHERE e.kind = 'episode' AND e.library_id = mi.library_id AND e.show_title = mi.show_title
		AND pp.item_id IS NULL
	)))
	OR (? = 'started' AND ? > 0 AND EXISTS (
		SELECT 1 FROM media_items e
		JOIN playback_progress pp ON pp.user_id = ? AND pp.item_id = e.id
		WHERE e.kind = 'episode' AND e.library_id = mi.library_id AND e.show_title = mi.show_title
		AND (
			pp.completed = 1
			OR (
				pp.completed = 0
				AND pp.position_ms >= 60000
				AND (pp.duration_ms <= 0 OR pp.duration_ms - pp.position_ms >= 60000)
			)
		)
	) AND EXISTS (
		SELECT 1 FROM media_items e
		LEFT JOIN playback_progress pp ON pp.user_id = ? AND pp.item_id = e.id AND pp.completed = 1
		WHERE e.kind = 'episode' AND e.library_id = mi.library_id AND e.show_title = mi.show_title
		AND pp.item_id IS NULL
	))
)
AND (
	? = ''
	OR (? = '#' AND LOWER(COALESCE(NULLIF(ms.sort_title, ''), mi.show_title, '')) NOT GLOB '[a-z]*')
	OR (? != '#' AND LOWER(COALESCE(NULLIF(ms.sort_title, ''), mi.show_title, '')) LIKE LOWER(?) || '%')
)
AND (
	? = ''
	OR searchnorm(mi.show_title) LIKE '%' || ? || '%'
	OR searchnorm(mi.original_title) LIKE '%' || ? || '%'
	OR searchnorm(ms.original_title) LIKE '%' || ? || '%'
	OR EXISTS (
		SELECT 1 FROM media_actors ma
		WHERE searchnorm(ma.name) LIKE '%' || ? || '%'
		AND (
			(ma.scope = 'show' AND ma.library_id = mi.library_id AND ma.show_title = mi.show_title)
			OR (ma.scope = 'season' AND ma.library_id = mi.library_id AND ma.show_title = mi.show_title)
			OR (ma.scope = 'item' AND ma.item_id = mi.id)
		)
	)
)
GROUP BY mi.library_id, mi.show_title
HAVING (? <= 0 OR COALESCE(ms.rating, MAX(mi.rating), 0) >= ?)
`+orderBy+`
LIMIT ? OFFSET ?`, append(append([]any{libraryID, libraryID}, genreArgs...), seenStatus, seenStatus, userID, userID, seenStatus, userID, userID, seenStatus, userID, userID, userID, nameStartsWith, nameStartsWith, nameStartsWith, nameStartsWith, q, nq, nq, nq, nq, opts.MinRating, opts.MinRating, limit, offset)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	shows := []ShowSummary{}
	for rows.Next() {
		var show ShowSummary
		if err := rows.Scan(&show.LibraryID, &show.Title, &show.SortTitle, &show.OriginalTitle, &show.Year, &show.EndYear, &show.EpisodeCount, &show.SeasonCount, &show.PosterItemID, &show.PosterMTimeUnix, &show.BackdropItemID, &show.BackdropMTimeUnix, &show.Overview, &show.Genres, &show.Rating, &show.Premiered); err != nil {
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
AND genres IS NOT NULL AND genres != ''
UNION ALL
SELECT COALESCE(genres, '')
FROM media_shows
WHERE (? = '' OR library_id = ?)
AND genres IS NOT NULL AND genres != ''`, libraryID, libraryID, libraryID, libraryID)
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
SELECT mi.library_id,
	COALESCE(mi.show_title, ''),
	COALESCE(mi.season_number, 0),
	COUNT(*),
	COALESCE(SUM(mi.duration_ms), 0),
	COALESCE(MIN(mi.id), 0),
	COALESCE(ms.poster_path, ''),
	COALESCE(ms.poster_mtime_unix, 0),
	COALESCE(MIN(CASE WHEN mi.backdrop_path IS NOT NULL AND mi.backdrop_path != '' THEN mi.id END), 0),
	COALESCE(ms.title, ''),
	COALESCE(ms.overview, ''),
	COALESCE(ms.rating, MAX(mi.rating), 0),
	COALESCE(ms.premiered, '')
FROM media_items mi
LEFT JOIN media_seasons ms ON ms.library_id = mi.library_id AND ms.show_title = mi.show_title AND ms.season_number = COALESCE(mi.season_number, 0)
WHERE mi.kind = 'episode'
AND mi.library_id = ?
AND mi.show_title = ?
GROUP BY mi.library_id, mi.show_title, mi.season_number
ORDER BY mi.season_number`, libraryID, showTitle)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	seasons := []SeasonSummary{}
	for rows.Next() {
		var season SeasonSummary
		if err := rows.Scan(&season.LibraryID, &season.ShowTitle, &season.SeasonNumber, &season.EpisodeCount, &season.DurationMS, &season.PosterItemID, &season.PosterPath, &season.PosterMTimeUnix, &season.BackdropItemID, &season.Title, &season.Overview, &season.Rating, &season.Premiered); err != nil {
			return nil, err
		}
		if season.Title == "" && season.SeasonNumber > 0 {
			season.Title = "Season " + strconv.Itoa(season.SeasonNumber)
		}
		seasons = append(seasons, season)
	}
	return seasons, rows.Err()
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

func (s *Store) ListItemsByActor(ctx context.Context, actorName, libraryID, kind, sort string, limit, offset int) ([]Item, error) {
	if limit <= 0 {
		limit = 100
	}
	if limit > 500 {
		limit = 500
	}
	if offset < 0 {
		offset = 0
	}
	rows, err := s.db.QueryContext(ctx, itemSelectMI+`
FROM media_items mi
JOIN media_actors ma ON ma.scope = 'item' AND ma.item_id = mi.id
WHERE ma.name = ?
AND (? = '' OR mi.library_id = ?)
AND (? = '' OR mi.kind = ?)
GROUP BY mi.id
`+actorItemsOrderBy(normalizedSort(sort))+`
LIMIT ? OFFSET ?`, actorName, libraryID, libraryID, kind, kind, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []Item{}
	ids := make([]int64, 0, limit)
	for rows.Next() {
		item, err := scanItem(rows)
		if err != nil {
			return nil, err
		}
		ids = append(ids, item.ID)
		items = append(items, item)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	actors, err := s.ListActorsForItems(ctx, ids)
	if err != nil {
		return nil, err
	}
	for i := range items {
		items[i].Actors = actors[items[i].ID]
	}
	return items, nil
}

func actorItemsOrderBy(sortMode string) string {
	switch sortMode {
	case "recent", "mtime":
		return `ORDER BY mi.mtime_unix DESC, sort_title`
	case "rating":
		return `ORDER BY COALESCE(mi.rating, 0) DESC, sort_title`
	default:
		return `ORDER BY sort_title`
	}
}

func (s *Store) ListShowsByActor(ctx context.Context, actorName, libraryID, sort string, limit, offset int) ([]ShowSummary, error) {
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
SELECT DISTINCT
	COALESCE(NULLIF(ma.library_id, ''), mi.library_id) AS library_id,
	COALESCE(NULLIF(ma.show_title, ''), mi.show_title) AS show_title,
	COALESCE(ms.sort_title, LOWER(COALESCE(NULLIF(ma.show_title, ''), mi.show_title, ''))) AS sort_title,
	COALESCE(ms.rating, 0) AS rating,
	COALESCE(MAX(mi.mtime_unix), 0) AS mtime
FROM media_actors ma
LEFT JOIN media_items mi ON ma.scope = 'item' AND ma.item_id = mi.id
LEFT JOIN media_shows ms ON ms.library_id = COALESCE(NULLIF(ma.library_id, ''), mi.library_id) AND ms.show_title = COALESCE(NULLIF(ma.show_title, ''), mi.show_title)
WHERE ma.name = ?
AND (
	ma.scope = 'show'
	OR ma.scope = 'season'
	OR (ma.scope = 'item' AND mi.kind = 'episode')
)
AND (? = '' OR COALESCE(NULLIF(ma.library_id, ''), mi.library_id) = ?)
AND COALESCE(NULLIF(ma.show_title, ''), mi.show_title, '') != ''
GROUP BY 1, 2
`+actorShowsOrderBy(normalizedSort(sort))+`
LIMIT ? OFFSET ?`, actorName, libraryID, libraryID, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	type key struct {
		libraryID string
		showTitle string
		sortTitle string
		rating    float64
		mtime     int64
	}
	keys := []key{}
	for rows.Next() {
		var k key
		if err := rows.Scan(&k.libraryID, &k.showTitle, &k.sortTitle, &k.rating, &k.mtime); err != nil {
			return nil, err
		}
		keys = append(keys, k)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	shows := make([]ShowSummary, 0, len(keys))
	for _, k := range keys {
		candidates, err := s.SearchShows(ctx, ShowOptions{LibraryID: k.libraryID, Query: k.showTitle, Limit: 20})
		if err != nil {
			return nil, err
		}
		for _, show := range candidates {
			if show.LibraryID == k.libraryID && strings.EqualFold(show.Title, k.showTitle) {
				show.Actors, _ = s.ListShowActors(ctx, show.LibraryID, show.Title)
				shows = append(shows, show)
				break
			}
		}
	}
	return shows, nil
}

func actorShowsOrderBy(sortMode string) string {
	switch sortMode {
	case "recent", "mtime":
		return `ORDER BY mtime DESC, sort_title`
	case "rating":
		return `ORDER BY rating DESC, sort_title`
	default:
		return `ORDER BY sort_title`
	}
}

func (s *Store) ActorCreditCounts(ctx context.Context, actorName string) (movies, series int, err error) {
	if err := s.db.QueryRowContext(ctx, `
SELECT COUNT(DISTINCT mi.id)
FROM media_actors ma
JOIN media_items mi ON ma.scope = 'item' AND ma.item_id = mi.id
WHERE ma.name = ? AND mi.kind = 'movie'`, actorName).Scan(&movies); err != nil {
		return 0, 0, err
	}
	if err := s.db.QueryRowContext(ctx, `
SELECT COUNT(*)
FROM (
	SELECT COALESCE(NULLIF(ma.library_id, ''), mi.library_id) AS library_id,
		COALESCE(NULLIF(ma.show_title, ''), mi.show_title) AS show_title
	FROM media_actors ma
	LEFT JOIN media_items mi ON ma.scope = 'item' AND ma.item_id = mi.id
	WHERE ma.name = ?
	AND (
		ma.scope = 'show'
		OR ma.scope = 'season'
		OR (ma.scope = 'item' AND mi.kind = 'episode')
	)
	AND COALESCE(NULLIF(ma.show_title, ''), mi.show_title, '') != ''
	GROUP BY 1, 2
)`, actorName).Scan(&series); err != nil {
		return 0, 0, err
	}
	return movies, series, nil
}

func (s *Store) ListItemActors(ctx context.Context, itemID int64) ([]Actor, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT name, COALESCE(role, ''), COALESCE(thumb, ''), sort_order
FROM media_actors
WHERE scope = 'item' AND item_id = ?
ORDER BY sort_order, name`, itemID)
	if err != nil {
		return nil, err
	}
	return scanActors(rows)
}

func (s *Store) ListActorsForItems(ctx context.Context, itemIDs []int64) (map[int64][]Actor, error) {
	out := make(map[int64][]Actor, len(itemIDs))
	if len(itemIDs) == 0 {
		return out, nil
	}
	placeholders := make([]string, len(itemIDs))
	args := make([]any, len(itemIDs))
	for i, id := range itemIDs {
		placeholders[i] = "?"
		args[i] = id
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT item_id, name, COALESCE(role, ''), COALESCE(thumb, ''), sort_order
FROM media_actors
WHERE scope = 'item' AND item_id IN (`+strings.Join(placeholders, ",")+`)
ORDER BY item_id, sort_order, name`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var itemID int64
		var actor Actor
		if err := rows.Scan(&itemID, &actor.Name, &actor.Role, &actor.Thumb, &actor.Order); err != nil {
			return nil, err
		}
		out[itemID] = append(out[itemID], actor)
	}
	return out, rows.Err()
}

func (s *Store) ListShowActors(ctx context.Context, libraryID, showTitle string) ([]Actor, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT name, COALESCE(role, ''), COALESCE(thumb, ''), sort_order
FROM media_actors
WHERE scope = 'show' AND library_id = ? AND show_title = ?
ORDER BY sort_order, name`, libraryID, showTitle)
	if err != nil {
		return nil, err
	}
	return scanActors(rows)
}

func (s *Store) ListSeasonActors(ctx context.Context, libraryID, showTitle string, seasonNumber int) ([]Actor, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT name, COALESCE(role, ''), COALESCE(thumb, ''), sort_order
FROM media_actors
WHERE scope = 'season' AND library_id = ? AND show_title = ? AND season_number = ?
ORDER BY sort_order, name`, libraryID, showTitle, seasonNumber)
	if err != nil {
		return nil, err
	}
	return scanActors(rows)
}

func (s *Store) ListActors(ctx context.Context, q string, limit, offset int) ([]Actor, error) {
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
SELECT name,
	'' AS role,
	COALESCE(MAX(NULLIF(thumb, '')), '') AS thumb,
	MIN(sort_order) AS sort_order
FROM media_actors
WHERE (? = '' OR name LIKE '%' || ? || '%')
GROUP BY name
ORDER BY name
LIMIT ? OFFSET ?`, q, q, limit, offset)
	if err != nil {
		return nil, err
	}
	return scanActors(rows)
}

func (s *Store) ActorByName(ctx context.Context, name string) (Actor, error) {
	row := s.db.QueryRowContext(ctx, `
SELECT name, role, thumb, sort_order
FROM media_actors
WHERE name = ?
ORDER BY
	CASE
		WHEN TRIM(COALESCE(thumb, '')) != '' AND thumb NOT LIKE 'http://%' AND thumb NOT LIKE 'https://%' AND thumb NOT LIKE '/api/%' THEN 0
		WHEN TRIM(COALESCE(thumb, '')) != '' THEN 1
		ELSE 2
	END,
	sort_order,
	id
LIMIT 1`, name)
	var actor Actor
	if err := row.Scan(&actor.Name, &actor.Role, &actor.Thumb, &actor.Order); err != nil {
		return Actor{}, err
	}
	return actor, nil
}

func (s *Store) CachedActorInfo(ctx context.Context, name string) (ActorInfo, bool) {
	row := s.db.QueryRowContext(ctx, `
SELECT name, COALESCE(tmdb_id, ''), COALESCE(imdb_id, ''), COALESCE(biography, ''),
	COALESCE(birthday, ''), COALESCE(deathday, ''), COALESCE(place_of_birth, ''),
	COALESCE(known_for_department, ''), COALESCE(profile_path, ''), source, fetched_at
FROM actor_metadata_cache
WHERE name = ?`, name)
	var info ActorInfo
	if err := row.Scan(&info.Name, &info.TMDbID, &info.IMDbID, &info.Biography, &info.Birthday, &info.Deathday, &info.PlaceOfBirth, &info.KnownForDepartment, &info.ProfilePath, &info.Source, &info.FetchedAt); err != nil {
		return ActorInfo{}, false
	}
	return info, true
}

func (s *Store) SaveActorInfo(ctx context.Context, info ActorInfo) error {
	if strings.TrimSpace(info.Name) == "" {
		return nil
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO actor_metadata_cache(name, tmdb_id, imdb_id, biography, birthday, deathday, place_of_birth, known_for_department, profile_path, source, fetched_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(name) DO UPDATE SET
	tmdb_id=excluded.tmdb_id,
	imdb_id=excluded.imdb_id,
	biography=excluded.biography,
	birthday=excluded.birthday,
	deathday=excluded.deathday,
	place_of_birth=excluded.place_of_birth,
	known_for_department=excluded.known_for_department,
	profile_path=excluded.profile_path,
	source=excluded.source,
	fetched_at=excluded.fetched_at`,
		info.Name, nullString(info.TMDbID), nullString(info.IMDbID), nullString(info.Biography),
		nullString(info.Birthday), nullString(info.Deathday), nullString(info.PlaceOfBirth),
		nullString(info.KnownForDepartment), nullString(info.ProfilePath), info.Source, info.FetchedAt)
	return err
}

func scanActors(rows *sql.Rows) ([]Actor, error) {
	defer rows.Close()
	actors := []Actor{}
	for rows.Next() {
		var actor Actor
		if err := rows.Scan(&actor.Name, &actor.Role, &actor.Thumb, &actor.Order); err != nil {
			return nil, err
		}
		actors = append(actors, actor)
	}
	return actors, rows.Err()
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
	var streamsKnown bool
	err := row.Scan(&item.ID, &item.LibraryID, &item.Path, &item.Kind, &item.Title, &item.SortTitle,
		&item.OriginalTitle, &item.Year, &item.DurationMS, &item.Container, &item.VideoCodec, &item.AudioCodec,
		&item.IMDbID, &item.TMDbID, &item.TVDbID, &item.Width, &item.Height, &item.BitRate, &item.SizeBytes, &item.MTimeUnix, &item.NFOPath,
		&item.NFOMTimeUnix, &item.PosterPath, &item.PosterMTimeUnix, &item.BackdropPath, &item.BackdropMTimeUnix,
		&item.Overview, &item.Tagline, &item.OfficialRating, &item.Genres, &item.Tags,
		&item.Studios, &item.Directors, &item.Writers, &item.Countries,
		&item.Rating, &item.Premiered, &item.ShowTitle, &item.SeasonNumber,
		&item.EpisodeNumber, &item.EpisodeTitle, &streamsKnown)
	item.StreamsKnown = streamsKnown
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
	completed=MAX(playback_progress.completed, excluded.completed),
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
	COALESCE(SUM(CASE WHEN p.completed = 1 THEN 1 ELSE 0 END), 0),
	COALESCE(MAX(p.updated_at), '')
FROM media_items mi
LEFT JOIN playback_progress p ON p.item_id = mi.id AND p.user_id = ?
WHERE mi.kind = 'episode'
AND mi.show_title IS NOT NULL AND mi.show_title != ''
GROUP BY mi.library_id, mi.show_title
ORDER BY MAX(p.updated_at) IS NULL, MAX(p.updated_at) DESC, mi.library_id, mi.show_title`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ShowProgress{}
	for rows.Next() {
		var progress ShowProgress
		if err := rows.Scan(&progress.LibraryID, &progress.ShowTitle, &progress.EpisodeCount, &progress.CompletedCount, &progress.LastWatched); err != nil {
			return nil, err
		}
		progress.Completed = progress.EpisodeCount > 0 && progress.CompletedCount >= progress.EpisodeCount
		progress.HasAnyCompletion = progress.CompletedCount > 0
		out = append(out, progress)
	}
	return out, rows.Err()
}

func (s *Store) NextUnwatchedEpisodesForShows(ctx context.Context, userID int64, shows []ShowProgress, limit int) ([]Item, error) {
	if len(shows) == 0 || limit == 0 {
		return nil, nil
	}
	if limit < 0 {
		limit = len(shows)
	}
	if limit > len(shows) {
		limit = len(shows)
	}
	clauses := make([]string, 0, len(shows))
	args := []any{userID}
	wanted := map[string]bool{}
	showKeys := make([]string, 0, limit)
	for _, show := range shows {
		libraryID := strings.TrimSpace(show.LibraryID)
		showTitle := strings.TrimSpace(show.ShowTitle)
		if libraryID == "" || showTitle == "" {
			continue
		}
		key := showEpisodeKey(libraryID, showTitle)
		if wanted[key] {
			continue
		}
		wanted[key] = true
		showKeys = append(showKeys, key)
		clauses = append(clauses, "(mi.library_id = ? AND mi.show_title = ?)")
		args = append(args, libraryID, showTitle)
	}
	if len(clauses) == 0 {
		return nil, nil
	}
	rows, err := s.db.QueryContext(ctx, itemSelectMI+`
FROM media_items mi
LEFT JOIN playback_progress pp ON pp.user_id = ? AND pp.item_id = mi.id
WHERE mi.kind = 'episode'
AND (`+strings.Join(clauses, " OR ")+`)
AND COALESCE(pp.completed, 0) = 0
ORDER BY mi.library_id, mi.show_title, COALESCE(mi.season_number, 0), COALESCE(mi.episode_number, 0), mi.id`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	firstByShow := map[string]Item{}
	for rows.Next() {
		item, err := scanItem(rows)
		if err != nil {
			return nil, err
		}
		key := showEpisodeKey(item.LibraryID, item.ShowTitle)
		if !wanted[key] {
			continue
		}
		if _, ok := firstByShow[key]; ok {
			continue
		}
		firstByShow[key] = item
		if len(firstByShow) >= limit || len(firstByShow) >= len(wanted) {
			break
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]Item, 0, len(firstByShow))
	for _, key := range showKeys {
		if item, ok := firstByShow[key]; ok {
			out = append(out, item)
			if len(out) >= limit {
				break
			}
		}
	}
	return out, nil
}

func showEpisodeKey(libraryID, showTitle string) string {
	return libraryID + "\n" + strings.ToLower(strings.TrimSpace(showTitle))
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
	COALESCE(ms.sort_title, LOWER(COALESCE(mi.show_title, ''))) AS sort_title,
	COALESCE(ms.original_title, CASE WHEN COUNT(DISTINCT NULLIF(mi.original_title, '')) = 1 THEN COALESCE(MAX(NULLIF(mi.original_title, '')), '') ELSE '' END, ''),
	COALESCE(ms.year, MIN(NULLIF(mi.year, 0)), 0),
	COALESCE(MAX(NULLIF(mi.year, 0)), 0),
	COUNT(*),
	COUNT(DISTINCT mi.season_number),
	COALESCE(MIN(CASE WHEN mi.poster_path IS NOT NULL AND mi.poster_path != '' THEN mi.id END), 0),
	COALESCE(MAX(mi.poster_mtime_unix), 0),
	COALESCE(MIN(CASE WHEN mi.backdrop_path IS NOT NULL AND mi.backdrop_path != '' THEN mi.id END), 0),
	COALESCE(MAX(mi.backdrop_mtime_unix), 0),
	COALESCE(ms.overview, MAX(NULLIF(mi.overview, '')), ''),
	COALESCE(ms.genres, MAX(NULLIF(mi.genres, '')), ''),
	COALESCE(ms.rating, MAX(mi.rating), 0),
	COALESCE(ms.premiered, MAX(NULLIF(mi.premiered, '')), ''),
	MAX(w.updated_at)
FROM user_watchlist w
JOIN media_items mi ON mi.library_id = w.library_id AND mi.show_title = w.show_title
LEFT JOIN media_shows ms ON ms.library_id = mi.library_id AND ms.show_title = mi.show_title
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
		if err := rows.Scan(&show.LibraryID, &show.Title, &show.SortTitle, &show.OriginalTitle, &show.Year, &show.EndYear, &show.EpisodeCount, &show.SeasonCount, &show.PosterItemID, &show.PosterMTimeUnix, &show.BackdropItemID, &show.BackdropMTimeUnix, &show.Overview, &show.Genres, &show.Rating, &show.Premiered, &updatedAt); err != nil {
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
