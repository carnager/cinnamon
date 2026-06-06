package media

import (
	"context"
	"encoding/xml"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"popcorn/internal/config"
)

func TestReadNFOIDs(t *testing.T) {
	meta := nfoMovie{
		ID:     "tt1179933",
		TMDbID: "333371",
		UniqueIDs: []nfoID{
			{Type: "tmdb", Value: "333371"},
			{Type: "imdb", Value: "tt1179933"},
		},
	}
	if got := meta.imdbID(); got != "tt1179933" {
		t.Fatalf("imdbID() = %q, want tt1179933", got)
	}
	if got := meta.tmdbID(); got != "333371" {
		t.Fatalf("tmdbID() = %q, want 333371", got)
	}
}

func TestReadNFOExternalIDs(t *testing.T) {
	meta := nfoMovie{
		ID:     "5482170",
		TMDbID: "1161836",
		TVDbID: "5482170",
		UniqueIDs: []nfoID{
			{Type: "imdb", Value: "tt4460418"},
			{Type: "tmdb", Value: "1138311"},
			{Type: "tvdb", Value: "5387592"},
		},
	}
	if got := meta.imdbID(); got != "tt4460418" {
		t.Fatalf("imdbID() = %q, want tt4460418", got)
	}
	if got := meta.tmdbID(); got != "1161836" {
		t.Fatalf("tmdbID() = %q, want 1161836", got)
	}
	if got := meta.tvdbID(); got != "5482170" {
		t.Fatalf("tvdbID() = %q, want 5482170", got)
	}
}

func TestReadNFOIDsFromTinyMediaManagerXML(t *testing.T) {
	var meta nfoMovie
	err := xml.Unmarshal([]byte(`<movie>
  <id>tt1179933</id>
  <tmdbid>333371</tmdbid>
  <uniqueid default="false" type="tmdb">333371</uniqueid>
  <uniqueid default="true" type="imdb">tt1179933</uniqueid>
</movie>`), &meta)
	if err != nil {
		t.Fatal(err)
	}
	if got := meta.imdbID(); got != "tt1179933" {
		t.Fatalf("imdbID() = %q, want tt1179933", got)
	}
	if got := meta.tmdbID(); got != "333371" {
		t.Fatalf("tmdbID() = %q, want 333371", got)
	}
}

func TestBestRatingPrefersIMDbThenTVDb(t *testing.T) {
	ratings := []nfoRating{
		{Name: "tmdb", Default: "true", Max: 10, Value: 6.2},
		{Name: "tvdb", Max: 10, Value: 7.1},
		{Name: "imdb", Max: 10, Value: 8.4},
	}
	if got := bestRating(5.5, ratings); got != 8.4 {
		t.Fatalf("bestRating with imdb = %.1f, want 8.4", got)
	}
}

func TestBestRatingFallsBackToTVDbWhenIMDbMissing(t *testing.T) {
	ratings := []nfoRating{
		{Name: "tmdb", Default: "true", Max: 10, Value: 6.2},
		{Name: "TheTVDB", Max: 10, Value: 7.1},
	}
	if got := bestRating(5.5, ratings); got != 7.1 {
		t.Fatalf("bestRating without imdb = %.1f, want TVDb 7.1", got)
	}
}

func TestBuildItemRefreshesNFOAndArtworkForUnchangedMovie(t *testing.T) {
	root := t.TempDir()
	movieDir := filepath.Join(root, "Movies", "Hoppers")
	mustMkdirAll(t, movieDir)
	video := filepath.Join(movieDir, "Hoppers.mkv")
	nfo := filepath.Join(movieDir, "Hoppers.nfo")
	poster := filepath.Join(movieDir, "Hoppers-poster.jpg")
	backdrop := filepath.Join(movieDir, "Hoppers-fanart.jpg")
	mustWrite(t, video, "fake video")
	mustWrite(t, nfo, `<movie>
  <title>Hoppers</title>
  <originaltitle>Hoppsan</originaltitle>
  <year>2026</year>
  <uniqueid type="imdb">tt1234567</uniqueid>
  <uniqueid type="tmdb">98765</uniqueid>
  <plot>Updated from sidecar.</plot>
  <rating>7.4</rating>
</movie>`)
	mustWrite(t, poster, "poster")
	mustWrite(t, backdrop, "backdrop")
	info := mustStat(t, video)

	existing := Item{
		Path:       video,
		Title:      "Old Title",
		DurationMS: 123456,
		VideoCodec: "h264",
		AudioCodec: "aac",
		SizeBytes:  info.Size(),
		MTimeUnix:  info.ModTime().Unix(),
	}
	scanner := NewScanner(config.Config{}, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
	item := scanner.buildItem(context.Background(), config.Library{ID: "movies", Type: "movies", Path: filepath.Join(root, "Movies")}, video, info, existing)

	if item.Title != "Hoppers" {
		t.Fatalf("Title = %q, want Hoppers", item.Title)
	}
	if item.OriginalTitle != "Hoppsan" {
		t.Fatalf("OriginalTitle = %q, want Hoppsan", item.OriginalTitle)
	}
	if item.IMDbID != "tt1234567" || item.TMDbID != "98765" {
		t.Fatalf("IDs = imdb %q tmdb %q, want tt1234567 / 98765", item.IMDbID, item.TMDbID)
	}
	if item.DurationMS != existing.DurationMS || item.VideoCodec != existing.VideoCodec || item.AudioCodec != existing.AudioCodec {
		t.Fatalf("probe fields were not reused: got duration=%d video=%q audio=%q", item.DurationMS, item.VideoCodec, item.AudioCodec)
	}
	if item.NFOPath != nfo || item.NFOMTimeUnix != fileMTimeUnix(nfo) {
		t.Fatalf("NFO tracking = %q/%d, want %q/%d", item.NFOPath, item.NFOMTimeUnix, nfo, fileMTimeUnix(nfo))
	}
	if item.PosterPath != poster || item.PosterMTimeUnix != fileMTimeUnix(poster) {
		t.Fatalf("Poster tracking = %q/%d, want %q/%d", item.PosterPath, item.PosterMTimeUnix, poster, fileMTimeUnix(poster))
	}
	if item.BackdropPath != backdrop || item.BackdropMTimeUnix != fileMTimeUnix(backdrop) {
		t.Fatalf("Backdrop tracking = %q/%d, want %q/%d", item.BackdropPath, item.BackdropMTimeUnix, backdrop, fileMTimeUnix(backdrop))
	}
}

func TestBuildItemUsesTVShowMetadataAndArtwork(t *testing.T) {
	root := t.TempDir()
	showDir := filepath.Join(root, "Battlestar Galactica")
	seasonDir := filepath.Join(showDir, "Season 01")
	mustMkdirAll(t, seasonDir)
	video := filepath.Join(seasonDir, "Battlestar Galactica - S01E01.mkv")
	episodeNFO := filepath.Join(seasonDir, "Battlestar Galactica - S01E01.nfo")
	showNFO := filepath.Join(showDir, "tvshow.nfo")
	showPoster := filepath.Join(showDir, "poster.jpg")
	showBackdrop := filepath.Join(showDir, "fanart.jpg")
	seasonPoster := filepath.Join(seasonDir, "poster.jpg")
	episodeThumb := filepath.Join(seasonDir, "Battlestar Galactica - S01E01-thumb.jpg")
	mustWrite(t, video, "fake video")
	mustWrite(t, showNFO, `<tvshow>
  <title>Battlestar Galactica</title>
  <originaltitle>BSG</originaltitle>
  <plot>The last battlestar leads the fleet.</plot>
  <rating>8.6</rating>
  <actor><name>Edward James Olmos</name><role>William Adama</role><order>1</order></actor>
</tvshow>`)
	mustWrite(t, episodeNFO, `<episodedetails>
  <title>33</title>
  <season>1</season>
  <episode>1</episode>
  <plot>The fleet keeps jumping.</plot>
  <actor><name>Mary McDonnell</name><role>Laura Roslin</role></actor>
</episodedetails>`)
	mustWrite(t, filepath.Join(seasonDir, "season.nfo"), `<season>
  <title>Season One</title>
  <plot>The opening season.</plot>
  <actor><name>Katee Sackhoff</name><role>Kara Thrace</role></actor>
</season>`)
	mustWrite(t, showPoster, "poster")
	mustWrite(t, showBackdrop, "backdrop")
	mustWrite(t, seasonPoster, "season poster")
	mustWrite(t, episodeThumb, "thumb")
	info := mustStat(t, video)
	existing := Item{
		Path:       video,
		DurationMS: 42_000,
		SizeBytes:  info.Size(),
		MTimeUnix:  info.ModTime().Unix(),
	}
	scanner := NewScanner(config.Config{}, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
	item := scanner.buildItem(context.Background(), config.Library{ID: "tv", Type: "tv", Path: root}, video, info, existing)

	if item.Kind != "episode" {
		t.Fatalf("Kind = %q, want episode", item.Kind)
	}
	if item.ShowTitle != "Battlestar Galactica" || item.EpisodeTitle != "33" {
		t.Fatalf("titles = show %q episode %q, want Battlestar Galactica / 33", item.ShowTitle, item.EpisodeTitle)
	}
	if item.Title != "Battlestar Galactica - S01E01 - 33" {
		t.Fatalf("Title = %q, want formatted episode title", item.Title)
	}
	if item.SeasonNumber != 1 || item.EpisodeNumber != 1 {
		t.Fatalf("episode numbers = S%dE%d, want S1E1", item.SeasonNumber, item.EpisodeNumber)
	}
	if item.OriginalTitle != "BSG" || item.Rating != 0 {
		t.Fatalf("show metadata fallback = original %q rating %.1f, want original title BSG but no inherited episode rating", item.OriginalTitle, item.Rating)
	}
	if item.Overview != "The fleet keeps jumping." {
		t.Fatalf("Overview = %q, want episode sidecar plot", item.Overview)
	}
	seasonNFO := filepath.Join(seasonDir, "season.nfo")
	if item.NFOMTimeUnix != maxInt64(maxInt64(fileMTimeUnix(episodeNFO), fileMTimeUnix(showNFO)), fileMTimeUnix(seasonNFO)) {
		t.Fatalf("NFOMTimeUnix = %d, want max episode/show/season nfo mtime", item.NFOMTimeUnix)
	}
	if len(item.Actors) != 1 || item.Actors[0].Name != "Mary McDonnell" || item.Actors[0].Role != "Laura Roslin" {
		t.Fatalf("Actors = %#v, want episode actor", item.Actors)
	}
	if item.ShowMetadata == nil || len(item.ShowMetadata.Actors) != 1 || item.ShowMetadata.Actors[0].Name != "Edward James Olmos" {
		t.Fatalf("ShowMetadata actors = %#v, want show actor", item.ShowMetadata)
	}
	if item.SeasonMetadata == nil || item.SeasonMetadata.Title != "Season One" || len(item.SeasonMetadata.Actors) != 1 || item.SeasonMetadata.Actors[0].Name != "Katee Sackhoff" {
		t.Fatalf("SeasonMetadata = %#v, want season nfo metadata and actor", item.SeasonMetadata)
	}
	if item.PosterPath != showPoster {
		t.Fatalf("PosterPath = %q, want show poster %q", item.PosterPath, showPoster)
	}
	if item.BackdropPath != episodeThumb {
		t.Fatalf("BackdropPath = %q, want episode thumb %q", item.BackdropPath, episodeThumb)
	}
	if got := SeasonArtworkPath(video, 1); got != seasonPoster {
		t.Fatalf("SeasonArtworkPath = %q, want %q", got, seasonPoster)
	}
}

func TestBuildItemUsesPlainEpisodeSidecarImage(t *testing.T) {
	root := t.TempDir()
	showDir := filepath.Join(root, "Widow's Bay")
	seasonDir := filepath.Join(showDir, "Season 01")
	mustMkdirAll(t, seasonDir)
	video := filepath.Join(seasonDir, "Widow's Bay - S01E05 - Was Sie auf Ihrer Reise erwartet.mkv")
	episodeImage := filepath.Join(seasonDir, "Widow's Bay - S01E05 - Was Sie auf Ihrer Reise erwartet.jpg")
	showPoster := filepath.Join(showDir, "poster.jpg")
	mustWrite(t, video, "fake video")
	mustWrite(t, filepath.Join(seasonDir, "Widow's Bay - S01E05 - Was Sie auf Ihrer Reise erwartet.nfo"), `<episodedetails>
  <title>Was Sie auf Ihrer Reise erwartet</title>
  <season>1</season>
  <episode>5</episode>
</episodedetails>`)
	mustWrite(t, showPoster, "show poster")
	mustWrite(t, episodeImage, "episode image")

	info := mustStat(t, video)
	scanner := NewScanner(config.Config{}, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
	item := scanner.buildItem(context.Background(), config.Library{ID: "tv", Type: "tv", Path: root}, video, info, Item{})

	if item.PosterPath != showPoster {
		t.Fatalf("PosterPath = %q, want show poster %q", item.PosterPath, showPoster)
	}
	if item.BackdropPath != episodeImage {
		t.Fatalf("BackdropPath = %q, want plain episode image %q", item.BackdropPath, episodeImage)
	}
}

func TestScanPathsRefreshesChangedSidecarOnly(t *testing.T) {
	store, ctx := newTestStore(t)
	root := t.TempDir()
	movieDir := filepath.Join(root, "Movies", "Hoppers")
	mustMkdirAll(t, movieDir)
	video := filepath.Join(movieDir, "Hoppers.mkv")
	nfo := filepath.Join(movieDir, "Hoppers.nfo")
	mustWrite(t, video, "fake video")
	mustWrite(t, nfo, `<movie><title>Old Hoppers</title></movie>`)
	mustChtimes(t, video, 1000)
	mustChtimes(t, nfo, 1000)
	info := mustStat(t, video)
	if err := store.UpsertItem(ctx, Item{
		LibraryID:    "movies",
		Path:         video,
		Kind:         "movie",
		Title:        "Old Hoppers",
		SortTitle:    "old hoppers",
		DurationMS:   123_000,
		SizeBytes:    info.Size(),
		MTimeUnix:    info.ModTime().Unix(),
		NFOPath:      nfo,
		NFOMTimeUnix: fileMTimeUnix(nfo),
	}); err != nil {
		t.Fatalf("upsert existing item: %v", err)
	}

	mustWrite(t, nfo, `<movie><title>New Hoppers</title><plot>Fresh sidecar.</plot></movie>`)
	mustChtimes(t, nfo, 2000)
	scanner := NewScanner(config.Config{FFprobePath: "ffprobe"}, store, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err := scanner.ScanPaths(ctx, config.Library{ID: "movies", Type: "movies", Path: filepath.Join(root, "Movies")}, []string{nfo}); err != nil {
		t.Fatalf("scan changed nfo: %v", err)
	}

	items, err := store.AllItems(ctx)
	if err != nil {
		t.Fatalf("list items: %v", err)
	}
	if len(items) != 1 {
		t.Fatalf("items = %#v, want one item", items)
	}
	item := items[0]
	if item.Title != "New Hoppers" || item.Overview != "Fresh sidecar." {
		t.Fatalf("item after sidecar refresh = title %q overview %q, want updated nfo data", item.Title, item.Overview)
	}
	if item.DurationMS != 123_000 {
		t.Fatalf("duration = %d, want existing probe data reused", item.DurationMS)
	}
	if item.NFOMTimeUnix != fileMTimeUnix(nfo) {
		t.Fatalf("nfo mtime = %d, want %d", item.NFOMTimeUnix, fileMTimeUnix(nfo))
	}
}

func mustMkdirAll(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(path, 0o755); err != nil {
		t.Fatal(err)
	}
}

func mustWrite(t *testing.T, path string, data string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
}

func mustChtimes(t *testing.T, path string, unix int64) {
	t.Helper()
	when := time.Unix(unix, 0)
	if err := os.Chtimes(path, when, when); err != nil {
		t.Fatal(err)
	}
}

func mustStat(t *testing.T, path string) os.FileInfo {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	return info
}
