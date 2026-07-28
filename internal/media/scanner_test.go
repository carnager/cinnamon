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

func TestResolveExistingPathHandlesCaseOnlyRename(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "Season 4")
	mustMkdirAll(t, dir)
	path := filepath.Join(dir, "FROM - S04E06 - Das Herz ist ein einsamer Jaeger.jpg")
	mustWrite(t, path, "image")

	stale := filepath.Join(dir, "FROM - S04E06 - Das Herz ist ein Einsamer Jaeger.jpg")
	if got := ResolveExistingPath(stale); got != path {
		t.Fatalf("ResolveExistingPath(%q) = %q, want %q", stale, got, path)
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

func TestScanPathsPrunesDeletedFilesInDir(t *testing.T) {
	store, ctx := newTestStore(t)
	root := t.TempDir()
	libDir := filepath.Join(root, "Movies")
	keepDir := filepath.Join(libDir, "Keeper")
	goneDir := filepath.Join(libDir, "Goner")
	mustMkdirAll(t, keepDir)
	mustMkdirAll(t, goneDir)

	keepVideo := filepath.Join(keepDir, "Keeper.mkv")
	goneVideo := filepath.Join(goneDir, "Goner.mkv")
	mustWrite(t, keepVideo, "fake video")
	mustWrite(t, goneVideo, "fake video")

	for _, path := range []string{keepVideo, goneVideo} {
		mustChtimes(t, path, 1000)
		info := mustStat(t, path)
		if err := store.UpsertItem(ctx, Item{
			LibraryID:    "movies",
			Path:         path,
			Kind:         "movie",
			Title:        filepath.Base(path),
			SortTitle:    filepath.Base(path),
			DurationMS:   123_000,
			SizeBytes:    info.Size(),
			MTimeUnix:    info.ModTime().Unix(),
			StreamsKnown: true,
		}); err != nil {
			t.Fatalf("upsert %s: %v", path, err)
		}
	}

	// The Goner folder is deleted on disk, which bumps the library directory's
	// mtime. The incremental scan of the library dir must prune its item.
	if err := os.RemoveAll(goneDir); err != nil {
		t.Fatalf("remove goner dir: %v", err)
	}

	scanner := NewScanner(config.Config{FFprobePath: "ffprobe"}, store, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err := scanner.ScanPaths(ctx, config.Library{ID: "movies", Type: "movies", Path: libDir}, []string{libDir}); err != nil {
		t.Fatalf("scan library dir: %v", err)
	}

	items, err := store.AllItems(ctx)
	if err != nil {
		t.Fatalf("list items: %v", err)
	}
	if len(items) != 1 || items[0].Path != keepVideo {
		t.Fatalf("items = %#v, want only the keeper to remain", items)
	}
}

func TestScanPathsIgnoresRootLevelFile(t *testing.T) {
	store, ctx := newTestStore(t)
	root := t.TempDir()
	libDir := filepath.Join(root, "Movies")
	mustMkdirAll(t, libDir)

	// A freshly added release dropped directly in the library root. An external
	// renamer will move it into its own folder shortly; the scanner must not
	// import the transient root-level file (which would otherwise be orphaned).
	rootVideo := filepath.Join(libDir, "Some.Movie.2026.1080p.WEB.h264-GROUP.mkv")
	mustWrite(t, rootVideo, "fake video")
	mustChtimes(t, rootVideo, 1000)

	scanner := NewScanner(config.Config{FFprobePath: "ffprobe"}, store, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err := scanner.ScanPaths(ctx, config.Library{ID: "movies", Type: "movies", Path: libDir}, []string{rootVideo}); err != nil {
		t.Fatalf("scan root-level file: %v", err)
	}

	items, err := store.AllItems(ctx)
	if err != nil {
		t.Fatalf("list items: %v", err)
	}
	if len(items) != 0 {
		t.Fatalf("items = %#v, want none (root-level file ignored)", items)
	}
}

func TestScanPathsIgnoresShowRootVideo(t *testing.T) {
	store, ctx := newTestStore(t)
	root := t.TempDir()
	libDir := filepath.Join(root, "TV Shows")
	releaseDir := filepath.Join(libDir, "Some.Show.S01.GERMAN.WEBRip.H264-GROUP")
	mustMkdirAll(t, releaseDir)
	flatVideo := filepath.Join(releaseDir, "Some.Show.S01E01.GERMAN.WEBRip.H264-GROUP.mkv")
	mustWrite(t, flatVideo, "fake video")
	mustChtimes(t, flatVideo, 1000)

	scanner := NewScanner(config.Config{FFprobePath: "ffprobe"}, store, slog.New(slog.NewTextHandler(io.Discard, nil)))
	lib := config.Library{ID: "tv_shows", Type: "tv", Path: libDir}

	// A raw release folder dropped into the library root holds its episodes
	// directly (library/Release.Name/file.mkv). The renamer will move them into
	// Show/Season folders shortly, so the scanner must not import this state.
	if err := scanner.ScanPaths(ctx, lib, []string{releaseDir}); err != nil {
		t.Fatalf("scan release dir: %v", err)
	}
	items, err := store.AllItems(ctx)
	if err != nil {
		t.Fatalf("list items: %v", err)
	}
	if len(items) != 0 {
		t.Fatalf("items = %#v, want none (show-root video ignored)", items)
	}

	// The renamer moves the file into its final season folder; the very next
	// scoped scan imports it.
	showDir := filepath.Join(libDir, "Some Show (2019)")
	seasonDir := filepath.Join(showDir, "Season 1")
	mustMkdirAll(t, seasonDir)
	final := filepath.Join(seasonDir, "Some Show - S01E01 - Pilot.mkv")
	if err := os.Rename(flatVideo, final); err != nil {
		t.Fatalf("rename into season folder: %v", err)
	}
	if err := os.RemoveAll(releaseDir); err != nil {
		t.Fatalf("remove release dir: %v", err)
	}
	mustChtimes(t, final, 1000)
	if err := scanner.ScanPaths(ctx, lib, []string{showDir, releaseDir}); err != nil {
		t.Fatalf("scan show dir: %v", err)
	}
	items, err = store.AllItems(ctx)
	if err != nil {
		t.Fatalf("list items: %v", err)
	}
	if len(items) != 1 || items[0].Path != final {
		t.Fatalf("items = %#v, want only the season-folder episode", items)
	}
}

func TestScanPathsPrunesFlatEpisodeAndOrphanShowRow(t *testing.T) {
	store, ctx := newTestStore(t)
	root := t.TempDir()
	libDir := filepath.Join(root, "TV Shows")
	showDir := filepath.Join(libDir, "Ghost Show")
	mustMkdirAll(t, showDir)
	video := filepath.Join(showDir, "Ghost.Show.S01E01.mkv")
	mustWrite(t, video, "fake video")
	mustChtimes(t, video, 1000)
	info := mustStat(t, video)

	// A leftover from before the show-root rule: the flat file was imported and
	// got show/season metadata rows. Scanning its directory must drop the item
	// (the file is skipped, hence no longer seen) and the now-empty show and
	// season rows with it.
	if err := store.UpsertItems(ctx, []Item{{
		LibraryID:      "tv_shows",
		Path:           video,
		Kind:           "episode",
		Title:          "Ghost Show - S01E01",
		SortTitle:      "ghost show s01e01",
		ShowTitle:      "Ghost Show",
		SeasonNumber:   1,
		EpisodeNumber:  1,
		SizeBytes:      info.Size(),
		MTimeUnix:      info.ModTime().Unix(),
		StreamsKnown:   true,
		ShowMetadata:   &ShowMetadata{LibraryID: "tv_shows", Title: "Ghost Show"},
		SeasonMetadata: &SeasonMetadata{LibraryID: "tv_shows", ShowTitle: "Ghost Show", SeasonNumber: 1},
	}}); err != nil {
		t.Fatalf("upsert flat episode: %v", err)
	}

	scanner := NewScanner(config.Config{FFprobePath: "ffprobe"}, store, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err := scanner.ScanPaths(ctx, config.Library{ID: "tv_shows", Type: "tv", Path: libDir}, []string{showDir}); err != nil {
		t.Fatalf("scan show dir: %v", err)
	}

	items, err := store.AllItems(ctx)
	if err != nil {
		t.Fatalf("list items: %v", err)
	}
	if len(items) != 0 {
		t.Fatalf("items = %#v, want flat episode pruned", items)
	}
	var showRows, seasonRows int
	if err := store.DB().QueryRow(`SELECT COUNT(*) FROM media_shows WHERE show_title = 'Ghost Show'`).Scan(&showRows); err != nil {
		t.Fatalf("count show rows: %v", err)
	}
	if err := store.DB().QueryRow(`SELECT COUNT(*) FROM media_seasons WHERE show_title = 'Ghost Show'`).Scan(&seasonRows); err != nil {
		t.Fatalf("count season rows: %v", err)
	}
	if showRows != 0 || seasonRows != 0 {
		t.Fatalf("show rows = %d, season rows = %d, want orphaned metadata pruned", showRows, seasonRows)
	}
}

func TestSkipAuxiliaryVideo(t *testing.T) {
	movies := config.Library{ID: "movies", Type: "movies", Path: "/lib/movies"}
	tv := config.Library{ID: "tv_shows", Type: "tv", Path: "/lib/tv"}
	cases := []struct {
		lib  config.Library
		path string
		want bool
	}{
		{movies, "/lib/movies/Hoppers/Hoppers-trailer.mkv", true},
		{movies, "/lib/movies/Hoppers/Hoppers.mkv", false},
		{tv, "/lib/tv/Mr. Inbetween (2018)/tvshow-trailer.mp4", true},
		{tv, "/lib/tv/Show/Season 1/sample.mkv", true},
		// Anything carrying an SxxEyy marker is a real episode, even when its
		// title happens to contain an auxiliary word.
		{tv, "/lib/tv/Show/Season 1/Show - S01E03 - Sample.mkv", false},
		{tv, "/lib/tv/Family Guy (1999)/Season 08/Family Guy - S08E12 - Extra Large Medium.avi", false},
	}
	for _, tc := range cases {
		if got := skipAuxiliaryVideo(tc.lib, tc.path); got != tc.want {
			t.Errorf("skipAuxiliaryVideo(%s, %q) = %v, want %v", tc.lib.Type, tc.path, got, tc.want)
		}
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

// The Trakt back-fill hangs off this hook, so a scan reporting a file it had
// already imported would ask Trakt about the whole library every time a single
// directory changed — and worse, could re-mark something the user had since
// un-watched.
func TestScanPathsReportsOnlyNewItems(t *testing.T) {
	store, ctx := newTestStore(t)
	root := t.TempDir()
	libDir := filepath.Join(root, "Movies")
	oldDir := filepath.Join(libDir, "Old Hoppers")
	newDir := filepath.Join(libDir, "New Hoppers")
	mustMkdirAll(t, oldDir)
	mustMkdirAll(t, newDir)
	oldVideo := filepath.Join(oldDir, "Old Hoppers.mkv")
	newVideo := filepath.Join(newDir, "New Hoppers.mkv")
	mustWrite(t, oldVideo, "fake video")
	mustWrite(t, newVideo, "fake video")
	info := mustStat(t, oldVideo)
	if err := store.UpsertItem(ctx, Item{
		LibraryID: "movies",
		Path:      oldVideo,
		Kind:      "movie",
		Title:     "Old Hoppers",
		SortTitle: "old hoppers",
		SizeBytes: info.Size(),
		MTimeUnix: info.ModTime().Unix(),
	}); err != nil {
		t.Fatalf("upsert existing item: %v", err)
	}

	var added []string
	scanner := NewScanner(config.Config{FFprobePath: "ffprobe"}, store, slog.New(slog.NewTextHandler(io.Discard, nil)))
	scanner.OnItemsAdded = func(paths []string) { added = append(added, paths...) }
	lib := config.Library{ID: "movies", Type: "movies", Path: libDir}
	if err := scanner.ScanPaths(ctx, lib, []string{libDir}); err != nil {
		t.Fatalf("scan library: %v", err)
	}
	if len(added) != 1 || added[0] != newVideo {
		t.Fatalf("added = %#v, want only %q", added, newVideo)
	}

	// The existing item is rescanned here (its stream state is still unknown, so
	// the scan rebuilds it), which must not make it look new.
	added = nil
	if err := scanner.ScanPaths(ctx, lib, []string{libDir}); err != nil {
		t.Fatalf("rescan library: %v", err)
	}
	if len(added) != 0 {
		t.Fatalf("added on rescan = %#v, want nothing new", added)
	}
}

// A full scan blanks its snapshot when a metadata backfill is due, so it
// rebuilds every item. Newness has to be decided from what the library held
// before that, or a backfill pass reports the entire library as new.
func TestFullScanReportsOnlyNewItemsDuringMetadataBackfill(t *testing.T) {
	store, ctx := newTestStore(t)
	root := t.TempDir()
	libDir := filepath.Join(root, "Movies")
	oldDir := filepath.Join(libDir, "Old Hoppers")
	newDir := filepath.Join(libDir, "New Hoppers")
	mustMkdirAll(t, oldDir)
	mustMkdirAll(t, newDir)
	oldVideo := filepath.Join(oldDir, "Old Hoppers.mkv")
	newVideo := filepath.Join(newDir, "New Hoppers.mkv")
	mustWrite(t, oldVideo, "fake video")
	mustWrite(t, newVideo, "fake video")
	info := mustStat(t, oldVideo)
	if err := store.UpsertItem(ctx, Item{
		LibraryID: "movies",
		Path:      oldVideo,
		Kind:      "movie",
		Title:     "Old Hoppers",
		SortTitle: "old hoppers",
		SizeBytes: info.Size(),
		MTimeUnix: info.ModTime().Unix(),
	}); err != nil {
		t.Fatalf("upsert existing item: %v", err)
	}
	backfillDue, err := store.MetadataBackfillNeeded(ctx, "movies", metadataBackfillNFOActors)
	if err != nil {
		t.Fatalf("backfill needed: %v", err)
	}
	if !backfillDue {
		t.Fatal("expected a metadata backfill to be due, so the scan blanks its snapshot")
	}

	var added []string
	cfg := config.Config{
		FFprobePath: "ffprobe",
		Libraries:   []config.Library{{ID: "movies", Type: "movies", Path: libDir}},
	}
	scanner := NewScanner(cfg, store, slog.New(slog.NewTextHandler(io.Discard, nil)))
	scanner.OnItemsAdded = func(paths []string) { added = append(added, paths...) }
	if err := scanner.Scan(ctx); err != nil {
		t.Fatalf("full scan: %v", err)
	}
	if len(added) != 1 || added[0] != newVideo {
		t.Fatalf("added = %#v, want only %q", added, newVideo)
	}
}
