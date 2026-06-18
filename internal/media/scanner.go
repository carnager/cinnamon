package media

import (
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"popcorn/internal/config"
)

var videoExts = map[string]struct{}{
	".mkv": {}, ".mp4": {}, ".m4v": {}, ".mov": {}, ".avi": {}, ".webm": {}, ".ts": {}, ".wmv": {},
	".m2ts": {}, ".mts": {}, ".mpg": {}, ".mpeg": {}, ".vob": {}, ".flv": {}, ".divx": {}, ".ogm": {},
}

var episodePattern = regexp.MustCompile(`(?i)(?:^|[\s._-])s(\d{1,2})e(\d{1,3})(?:[\s._-]|$)`)

const metadataBackfillNFOActors = "nfo-metadata-actors-v2"
const scanImportBatchSize = 64

var ErrScanAlreadyRunning = errors.New("scan already running")

var scanRunning atomic.Bool

func ScanInProgress() bool {
	return scanRunning.Load()
}

func TryStartScan() (func(), bool) {
	if !scanRunning.CompareAndSwap(false, true) {
		return nil, false
	}
	return func() {
		scanRunning.Store(false)
	}, true
}

type Scanner struct {
	cfg        config.Config
	store      *Store
	log        *slog.Logger
	nfoCache   sync.Map
	mtimeCache sync.Map
}

func NewScanner(cfg config.Config, store *Store, log *slog.Logger) *Scanner {
	return &Scanner{cfg: cfg, store: store, log: log}
}

func (s *Scanner) Scan(ctx context.Context) error {
	release, ok := TryStartScan()
	if !ok {
		return ErrScanAlreadyRunning
	}
	defer release()
	return s.scan(ctx)
}

func (s *Scanner) ScanWithLease(ctx context.Context, release func()) error {
	defer release()
	return s.scan(ctx)
}

func (s *Scanner) scan(ctx context.Context) error {
	s.clearScanCaches()
	if len(s.cfg.Libraries) == 0 {
		return nil
	}
	for _, lib := range s.cfg.Libraries {
		if err := s.scanLibrary(ctx, lib); err != nil {
			return err
		}
	}
	return nil
}

func (s *Scanner) ScanPaths(ctx context.Context, lib config.Library, paths []string) error {
	release, ok := TryStartScan()
	if !ok {
		return ErrScanAlreadyRunning
	}
	defer release()
	return s.scanPaths(ctx, lib, paths)
}

func (s *Scanner) scanPaths(ctx context.Context, lib config.Library, paths []string) error {
	s.clearScanCaches()
	if len(paths) == 0 {
		return nil
	}
	started := time.Now().UTC().Format(time.RFC3339)
	stats := &scanStats{}
	_ = s.store.SetScanStatus(ctx, ScanStatus{LibraryID: lib.ID, StartedAt: started, Status: "running", Message: "incremental"})
	s.log.Info("incremental scan started", "library", lib.ID, "paths", len(paths))
	snapshot, err := s.store.LibrarySnapshot(ctx, lib.ID)
	if err != nil {
		return err
	}
	jobs := map[string]scanJob{}
	removed := map[string]struct{}{}
	seen := map[string]struct{}{}
	var scannedDirs []string
	for _, path := range paths {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		abs, err := filepath.Abs(path)
		if err != nil {
			abs = filepath.Clean(path)
		}
		if info, err := os.Stat(abs); err == nil && info.IsDir() {
			scannedDirs = append(scannedDirs, abs)
		}
		if err := s.collectScanJobs(ctx, lib, path, snapshot, jobs, removed, seen, stats); err != nil {
			stats.errors.Add(1)
			s.log.Warn("incremental scan path failed", "library", lib.ID, "path", path, "error", err)
		}
	}
	for prefix := range removed {
		if err := s.store.RemovePathPrefix(ctx, lib.ID, prefix); err != nil {
			return err
		}
	}
	// Prune items that no longer exist on disk within the directories we fully
	// walked. A deleted file (or whole sub-folder) bumps its parent directory's
	// mtime, so that parent is in the changed set and gets reconciled here.
	var pruned []string
	for itemPath := range snapshot {
		if _, ok := seen[itemPath]; ok {
			continue
		}
		if pathUnderAnyDir(itemPath, scannedDirs) {
			pruned = append(pruned, itemPath)
		}
	}
	if len(pruned) > 0 {
		if err := s.store.RemovePaths(ctx, lib.ID, pruned); err != nil {
			return err
		}
	}
	batch := make([]Item, 0, scanImportBatchSize)
	flush := func() error {
		if len(batch) == 0 {
			return nil
		}
		if err := s.store.UpsertItems(ctx, batch); err != nil {
			stats.errors.Add(1)
			return err
		}
		stats.itemsImported.Add(int64(len(batch)))
		batch = batch[:0]
		return nil
	}
	for _, job := range jobs {
		batch = append(batch, s.buildItem(ctx, lib, job.path, job.info, snapshot[job.path]))
		if len(batch) >= scanImportBatchSize {
			if err := flush(); err != nil {
				return err
			}
		}
	}
	if err := flush(); err != nil {
		return err
	}
	finalStatus := ScanStatus{
		LibraryID:     lib.ID,
		StartedAt:     started,
		FinishedAt:    time.Now().UTC().Format(time.RFC3339),
		Status:        "finished",
		Message:       "incremental",
		FilesSeen:     stats.filesSeen.Load(),
		MediaFound:    stats.mediaFound.Load(),
		ItemsImported: stats.itemsImported.Load(),
		FilesSkipped:  stats.filesSeen.Load() - stats.mediaFound.Load(),
		Errors:        stats.errors.Load(),
	}
	_ = s.store.SetScanStatus(context.Background(), finalStatus)
	s.log.Info("incremental scan finished",
		"library", lib.ID,
		"paths", len(paths),
		"mediaFound", finalStatus.MediaFound,
		"itemsImported", finalStatus.ItemsImported,
		"removedPrefixes", len(removed),
		"prunedItems", len(pruned),
		"errors", finalStatus.Errors,
	)
	return nil
}

// pathUnderAnyDir reports whether path lives inside one of the given directories.
func pathUnderAnyDir(path string, dirs []string) bool {
	for _, dir := range dirs {
		if dir == "" {
			continue
		}
		if strings.HasPrefix(path, dir+string(filepath.Separator)) {
			return true
		}
	}
	return false
}

func (s *Scanner) collectScanJobs(ctx context.Context, lib config.Library, path string, snapshot map[string]Item, jobs map[string]scanJob, removed, seen map[string]struct{}, stats *scanStats) error {
	abs, err := filepath.Abs(path)
	if err != nil {
		abs = filepath.Clean(path)
	}
	info, err := os.Stat(abs)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			removed[abs] = struct{}{}
			return nil
		}
		return err
	}
	if info.IsDir() {
		return filepath.WalkDir(abs, func(candidate string, d os.DirEntry, err error) error {
			if err != nil {
				stats.errors.Add(1)
				return nil
			}
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if d.IsDir() {
				name := d.Name()
				if strings.HasPrefix(name, ".") || name == "@eaDir" {
					return filepath.SkipDir
				}
				return nil
			}
			return s.addScanFile(ctx, lib, candidate, snapshot, jobs, seen, stats)
		})
	}
	if _, ok := videoExts[strings.ToLower(filepath.Ext(abs))]; ok {
		return s.addScanFile(ctx, lib, abs, snapshot, jobs, seen, stats)
	}
	if !isMetadataOrArtwork(abs) {
		stats.filesSeen.Add(1)
		return nil
	}
	root := filepath.Dir(abs)
	return filepath.WalkDir(root, func(candidate string, d os.DirEntry, err error) error {
		if err != nil {
			stats.errors.Add(1)
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if d.IsDir() {
			name := d.Name()
			if strings.HasPrefix(name, ".") || name == "@eaDir" {
				return filepath.SkipDir
			}
			return nil
		}
		return s.addScanFile(ctx, lib, candidate, snapshot, jobs, seen, stats)
	})
}

func (s *Scanner) addScanFile(ctx context.Context, lib config.Library, path string, snapshot map[string]Item, jobs map[string]scanJob, seen map[string]struct{}, stats *scanStats) error {
	if ctx.Err() != nil {
		return ctx.Err()
	}
	stats.filesSeen.Add(1)
	if _, ok := videoExts[strings.ToLower(filepath.Ext(path))]; !ok {
		return nil
	}
	if lib.Type == "movies" && isAuxiliaryVideo(path) {
		return nil
	}
	info, err := os.Stat(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			if abs, err := filepath.Abs(path); err == nil {
				_ = s.store.RemovePaths(ctx, lib.ID, []string{abs})
			}
			return nil
		}
		return err
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		abs = filepath.Clean(path)
	}
	stats.mediaFound.Add(1)
	if seen != nil {
		seen[abs] = struct{}{}
	}
	existing := snapshot[abs]
	if !s.scanFileChanged(lib, abs, info, existing) {
		return nil
	}
	jobs[abs] = scanJob{path: abs, info: info}
	return nil
}

func (s *Scanner) scanFileChanged(lib config.Library, path string, info os.FileInfo, existing Item) bool {
	return existing.Path == "" ||
		existing.SizeBytes != info.Size() ||
		existing.MTimeUnix != info.ModTime().Unix() ||
		existing.NFOMTimeUnix != expectedNFOMTime(lib, path, s.fileMTimeUnix) ||
		existing.PosterMTimeUnix != expectedPosterMTime(lib, path, s.fileMTimeUnix) ||
		existing.BackdropMTimeUnix != expectedBackdropMTime(lib, path, s.fileMTimeUnix) ||
		!existing.StreamsKnown
}

func (s *Scanner) scanLibrary(ctx context.Context, lib config.Library) error {
	started := time.Now().UTC().Format(time.RFC3339)
	stats := &scanStats{}
	lastProgress := &atomic.Int64{}
	status := func(state, message string) ScanStatus {
		filesSeen := stats.filesSeen.Load()
		mediaFound := stats.mediaFound.Load()
		return ScanStatus{
			LibraryID:     lib.ID,
			StartedAt:     started,
			Status:        state,
			Message:       message,
			FilesSeen:     filesSeen,
			MediaFound:    mediaFound,
			ItemsImported: stats.itemsImported.Load(),
			FilesSkipped:  filesSeen - mediaFound,
			Errors:        stats.errors.Load(),
		}
	}
	_ = s.store.SetScanStatus(ctx, status("running", ""))
	s.log.Info("scan library started", "library", lib.ID, "type", lib.Type, "path", lib.Path)
	snapshot, err := s.store.LibrarySnapshot(ctx, lib.ID)
	if err != nil {
		return err
	}
	backfillNeeded, err := s.store.MetadataBackfillNeeded(ctx, lib.ID, metadataBackfillNFOActors)
	if err != nil {
		return err
	}
	if backfillNeeded {
		s.log.Info("scan metadata backfill started", "library", lib.ID, "name", metadataBackfillNFOActors)
		snapshot = map[string]Item{}
	}
	s.log.Debug("scan snapshot loaded", "library", lib.ID, "items", len(snapshot))
	seen := map[string]struct{}{}
	var seenMu sync.Mutex
	jobs := make(chan scanJob, runtime.NumCPU()*2)
	items := make(chan Item, scanImportBatchSize*2)
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var wg sync.WaitGroup
	var writerWG sync.WaitGroup
	var firstErr error
	var errMu sync.Mutex
	recordErr := func(err error) {
		if err == nil {
			return
		}
		stats.errors.Add(1)
		errMu.Lock()
		if firstErr == nil {
			firstErr = err
			cancel()
		}
		errMu.Unlock()
	}
	writerWG.Add(1)
	go func() {
		defer writerWG.Done()
		batch := make([]Item, 0, scanImportBatchSize)
		flush := func() bool {
			if len(batch) == 0 {
				return true
			}
			if err := s.store.UpsertItems(ctx, batch); err != nil {
				s.log.Warn("scan import batch failed", "library", lib.ID, "items", len(batch), "error", err)
				recordErr(err)
				return false
			}
			imported := stats.itemsImported.Add(int64(len(batch)))
			if imported <= 10 || imported%250 == 0 {
				s.log.Debug("scan items imported", "library", lib.ID, "imported", imported)
			}
			batch = batch[:0]
			return true
		}
		for item := range items {
			batch = append(batch, item)
			if len(batch) >= scanImportBatchSize && !flush() {
				return
			}
		}
		_ = flush()
	}()
	workers := runtime.NumCPU()
	if workers < 2 {
		workers = 2
	}
	if workers > 8 {
		workers = 8
	}
	for range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for job := range jobs {
				item := s.buildItem(ctx, lib, job.path, job.info, snapshot[job.path])
				select {
				case items <- item:
				case <-ctx.Done():
					return
				}
			}
		}()
	}
	walkErr := filepath.WalkDir(lib.Path, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			stats.errors.Add(1)
			if path == lib.Path {
				s.log.Error("scan root inaccessible", "library", lib.ID, "path", path, "error", err)
				return err
			}
			s.log.Warn("scan skip", "path", path, "error", err)
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if d.IsDir() {
			name := d.Name()
			if strings.HasPrefix(name, ".") || name == "@eaDir" {
				return filepath.SkipDir
			}
			return nil
		}
		stats.filesSeen.Add(1)
		if _, ok := videoExts[strings.ToLower(filepath.Ext(path))]; !ok {
			return nil
		}
		if lib.Type == "movies" && isAuxiliaryVideo(path) {
			s.log.Debug("scan auxiliary video skipped", "library", lib.ID, "path", path)
			return nil
		}
		mediaFound := stats.mediaFound.Add(1)
		if mediaFound <= 10 || mediaFound%250 == 0 {
			s.log.Debug("scan media found", "library", lib.ID, "mediaFound", mediaFound, "path", path)
		}
		if mediaFound%500 == 0 {
			s.storeProgress(context.Background(), lib.ID, started, stats, lastProgress)
		}
		info, err := d.Info()
		if err != nil {
			stats.errors.Add(1)
			s.log.Warn("scan stat failed", "library", lib.ID, "path", path, "error", err)
			return nil
		}
		abs, _ := filepath.Abs(path)
		seenMu.Lock()
		seen[abs] = struct{}{}
		seenMu.Unlock()
		if !s.scanFileChanged(lib, abs, info, snapshot[abs]) {
			return nil
		}
		select {
		case jobs <- scanJob{path: abs, info: info}:
			return nil
		case <-ctx.Done():
			return ctx.Err()
		}
	})
	close(jobs)
	wg.Wait()
	close(items)
	writerWG.Wait()
	errMu.Lock()
	if firstErr != nil && walkErr == nil {
		walkErr = firstErr
	}
	errMu.Unlock()
	err = walkErr
	if err == nil {
		err = s.store.RemoveMissing(ctx, lib.ID, seen)
	}
	if err == nil && backfillNeeded {
		if markErr := s.store.MarkMetadataBackfillComplete(ctx, lib.ID, metadataBackfillNFOActors); markErr != nil {
			err = markErr
		} else {
			s.log.Info("scan metadata backfill finished", "library", lib.ID, "name", metadataBackfillNFOActors)
		}
	}
	finalStatus := status("finished", "")
	finalStatus.FinishedAt = time.Now().UTC().Format(time.RFC3339)
	if err != nil {
		finalStatus.Status = "failed"
		finalStatus.Message = err.Error()
	}
	_ = s.store.SetScanStatus(context.Background(), finalStatus)
	s.log.Info("scan library finished",
		"library", lib.ID,
		"status", finalStatus.Status,
		"filesSeen", finalStatus.FilesSeen,
		"mediaFound", finalStatus.MediaFound,
		"itemsImported", finalStatus.ItemsImported,
		"filesSkipped", finalStatus.FilesSkipped,
		"errors", finalStatus.Errors,
		"message", finalStatus.Message,
	)
	return err
}

func (s *Scanner) storeProgress(ctx context.Context, libraryID, started string, stats *scanStats, last *atomic.Int64) {
	imported := stats.itemsImported.Load()
	if imported == last.Load() {
		return
	}
	last.Store(imported)
	filesSeen := stats.filesSeen.Load()
	mediaFound := stats.mediaFound.Load()
	_ = s.store.SetScanStatus(ctx, ScanStatus{
		LibraryID:     libraryID,
		StartedAt:     started,
		Status:        "running",
		FilesSeen:     filesSeen,
		MediaFound:    mediaFound,
		ItemsImported: imported,
		FilesSkipped:  filesSeen - mediaFound,
		Errors:        stats.errors.Load(),
	})
	s.log.Info("scan progress", "library", libraryID, "filesSeen", filesSeen, "mediaFound", mediaFound, "itemsImported", imported, "errors", stats.errors.Load())
}

type scanStats struct {
	filesSeen     atomic.Int64
	mediaFound    atomic.Int64
	itemsImported atomic.Int64
	errors        atomic.Int64
}

type scanJob struct {
	path string
	info os.FileInfo
}

func (s *Scanner) buildItem(ctx context.Context, lib config.Library, path string, info os.FileInfo, existing Item) Item {
	nfo := findSidecar(path, []string{".nfo"})
	nfoMTime := s.fileMTimeUnix(nfo)
	meta := s.readNFO(nfo)
	title := meta.Title
	if title == "" {
		title = cleanTitle(filepath.Base(strings.TrimSuffix(path, filepath.Ext(path))))
	}
	kind := "movie"
	showTitle := ""
	seasonNumber := 0
	episodeNumber := 0
	episodeTitle := ""
	showNFO := ""
	showMeta := nfoMovie{}
	seasonNFO := ""
	seasonMeta := nfoMovie{}
	if lib.Type == "tv" {
		kind = "episode"
		episodeTitle = title
		showTitle = meta.ShowTitle
		seasonNumber = meta.Season
		episodeNumber = meta.Episode
		if seasonNumber == 0 || episodeNumber == 0 {
			seasonNumber, episodeNumber = parseEpisodeNumbers(path)
		}
		showNFO = findShowNFO(lib.Path, path)
		showMeta = s.readNFO(showNFO)
		if showTitle == "" {
			showTitle = firstNonEmpty(showMeta.Title, readShowTitle(lib.Path, path), fallbackShowTitle(lib.Path, path))
		}
		if meta.OriginalTitle == "" {
			meta.OriginalTitle = showMeta.OriginalTitle
		}
		if showMeta.Title == "" {
			showMeta.Title = showTitle
		}
		seasonNFO = findSeasonNFO(path, seasonNumber)
		seasonMeta = s.readNFO(seasonNFO)
		nfoMTime = maxInt64(nfoMTime, s.fileMTimeUnix(showNFO))
		nfoMTime = maxInt64(nfoMTime, s.fileMTimeUnix(seasonNFO))
		title = episodeDisplayTitle(showTitle, seasonNumber, episodeNumber, episodeTitle)
	}
	posterPath, backdropPath := artworkPaths(lib, path)
	posterMTime := s.fileMTimeUnix(posterPath)
	backdropMTime := s.fileMTimeUnix(backdropPath)
	actorDirs := itemActorDirs(lib, path)
	itemActors := actorsFromNFO(meta.Actors, actorDirs...)
	var showMetadata *ShowMetadata
	var seasonMetadata *SeasonMetadata
	if lib.Type == "tv" && showTitle != "" {
		showActorDirs := tvActorDirs(lib.Path, path)
		showMetadata = &ShowMetadata{
			LibraryID:     lib.ID,
			Title:         showTitle,
			SortTitle:     sortKey(showTitle),
			OriginalTitle: showMeta.OriginalTitle,
			Year:          showMeta.Year,
			NFOPath:       showNFO,
			NFOMTimeUnix:  s.fileMTimeUnix(showNFO),
			Overview:      firstNonEmpty(showMeta.Plot, showMeta.Outline),
			Genres:        strings.Join(showMeta.Genres, ", "),
			Rating:        showMeta.Rating,
			Premiered:     firstNonEmpty(showMeta.Premiered, showMeta.Released),
			Actors:        actorsFromNFO(showMeta.Actors, showActorDirs...),
		}
		seasonPoster := seasonImagePath(path, seasonNumber)
		seasonMetadata = &SeasonMetadata{
			LibraryID:       lib.ID,
			ShowTitle:       showTitle,
			SeasonNumber:    seasonNumber,
			Title:           seasonMeta.Title,
			NFOPath:         seasonNFO,
			NFOMTimeUnix:    s.fileMTimeUnix(seasonNFO),
			PosterPath:      seasonPoster,
			PosterMTimeUnix: s.fileMTimeUnix(seasonPoster),
			Overview:        firstNonEmpty(seasonMeta.Plot, seasonMeta.Outline),
			Rating:          seasonMeta.Rating,
			Premiered:       firstNonEmpty(seasonMeta.Premiered, seasonMeta.Released),
			Actors:          actorsFromNFO(seasonMeta.Actors, showActorDirs...),
		}
	}
	probed := MediaProbe{DurationMS: existing.DurationMS, BitRate: existing.BitRate}
	if existing.VideoCodec != "" || existing.AudioCodec != "" || existing.Width > 0 || existing.Height > 0 {
		if existing.VideoCodec != "" {
			probed.Streams = append(probed.Streams, MediaStream{Index: 0, Type: "video", Codec: existing.VideoCodec, Width: existing.Width, Height: existing.Height})
		}
		if existing.AudioCodec != "" {
			probed.Streams = append(probed.Streams, MediaStream{Index: 1, Type: "audio", Codec: existing.AudioCodec})
		}
	}
	needsProbe := existing.Path == "" || existing.SizeBytes != info.Size() || existing.MTimeUnix != info.ModTime().Unix() || existing.DurationMS == 0 || !existing.StreamsKnown
	var streamsToStore []MediaStream
	streamsKnown := false
	if needsProbe {
		nextProbe := ProbeMedia(ctx, s.cfg.FFprobePath, path)
		if nextProbe.DurationMS > 0 || nextProbe.BitRate > 0 || len(nextProbe.Streams) > 0 {
			probed = nextProbe
			streamsToStore = nextProbe.Streams
			streamsKnown = true
		} else {
			s.log.Debug("scan probe returned no media details", "library", lib.ID, "path", path)
		}
	}
	videoStream, _ := PrimaryVideoStream(probed.Streams)
	audioStream, _ := PrimaryAudioStream(probed.Streams)
	return Item{
		LibraryID:         lib.ID,
		Path:              path,
		Kind:              kind,
		Title:             title,
		SortTitle:         sortKey(title),
		OriginalTitle:     meta.OriginalTitle,
		Year:              meta.Year,
		DurationMS:        probed.DurationMS,
		Container:         strings.TrimPrefix(strings.ToLower(filepath.Ext(path)), "."),
		VideoCodec:        videoStream.Codec,
		AudioCodec:        audioStream.Codec,
		IMDbID:            meta.imdbID(),
		TMDbID:            meta.tmdbID(),
		TVDbID:            meta.tvdbID(),
		Width:             videoStream.Width,
		Height:            videoStream.Height,
		BitRate:           probed.BitRate,
		SizeBytes:         info.Size(),
		MTimeUnix:         info.ModTime().Unix(),
		NFOPath:           nfo,
		NFOMTimeUnix:      nfoMTime,
		PosterPath:        posterPath,
		PosterMTimeUnix:   posterMTime,
		BackdropPath:      backdropPath,
		BackdropMTimeUnix: backdropMTime,
		Overview:          firstNonEmpty(meta.Plot, meta.Outline),
		Tagline:           meta.Tagline,
		OfficialRating:    firstNonEmpty(meta.Certification, meta.MPAA),
		Genres:            strings.Join(meta.Genres, ", "),
		Tags:              strings.Join(cleanStrings(meta.Tags), ", "),
		Studios:           strings.Join(cleanStrings(meta.Studios), ", "),
		Directors:         strings.Join(cleanNFOText(meta.Directors), ", "),
		Writers:           strings.Join(cleanNFOText(meta.Credits), ", "),
		Countries:         strings.Join(cleanStrings(meta.Countries), ", "),
		Rating:            meta.Rating,
		Premiered:         firstNonEmpty(meta.Premiered, meta.Released),
		ShowTitle:         showTitle,
		SeasonNumber:      seasonNumber,
		EpisodeNumber:     episodeNumber,
		EpisodeTitle:      episodeTitle,
		Actors:            itemActors,
		Streams:           streamsToStore,
		StreamsKnown:      streamsKnown,
		ShowMetadata:      showMetadata,
		SeasonMetadata:    seasonMetadata,
	}
}

func artworkPaths(lib config.Library, video string) (string, string) {
	if lib.Type == "tv" {
		poster, backdrop := TVShowArtworkPaths(lib.Path, video)
		if episodeThumb := EpisodeArtworkPath(video); episodeThumb != "" {
			backdrop = episodeThumb
		}
		return poster, backdrop
	}
	poster := findSidecar(video, []string{"-poster.jpg", "-poster.png", ".jpg", ".png"})
	backdrop := findSidecar(video, []string{"-fanart.jpg", "-fanart.png", "-backdrop.jpg", "-backdrop.png"})
	if movieFolder := filepath.Dir(video); movieFolder != filepath.Clean(lib.Path) {
		if poster == "" {
			poster = findNamed(movieFolder, []string{"poster.jpg", "poster.png", "folder.jpg", "folder.png"})
		}
		if backdrop == "" {
			backdrop = findNamed(movieFolder, []string{"fanart.jpg", "fanart.png", "backdrop.jpg", "backdrop.png"})
		}
	}
	return poster, backdrop
}

func EpisodeArtworkPath(video string) string {
	return findSidecar(video, []string{
		"-thumb.jpg", "-thumb.jpeg", "-thumb.png", "-thumb.webp",
		".thumb.jpg", ".thumb.jpeg", ".thumb.png", ".thumb.webp",
		"-landscape.jpg", "-landscape.jpeg", "-landscape.png", "-landscape.webp",
		".jpg", ".jpeg", ".png", ".webp",
	})
}

func expectedNFOMTime(lib config.Library, video string, mtime func(string) int64) int64 {
	nfo := findSidecar(video, []string{".nfo"})
	out := mtime(nfo)
	if lib.Type == "tv" {
		out = maxInt64(out, mtime(findShowNFO(lib.Path, video)))
		season, _ := parseEpisodeNumbers(video)
		out = maxInt64(out, mtime(findSeasonNFO(video, season)))
	}
	return out
}

func expectedPosterMTime(lib config.Library, video string, mtime func(string) int64) int64 {
	poster, _ := artworkPaths(lib, video)
	return mtime(poster)
}

func expectedBackdropMTime(lib config.Library, video string, mtime func(string) int64) int64 {
	_, backdrop := artworkPaths(lib, video)
	return mtime(backdrop)
}

func isMetadataOrArtwork(path string) bool {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".nfo", ".jpg", ".jpeg", ".png", ".webp":
		return true
	default:
		return false
	}
}

func TVShowArtworkPaths(root, video string) (string, string) {
	showDir := showDir(root, video)
	poster := findNamed(showDir, []string{
		"poster.jpg", "poster.png",
		"folder.jpg", "folder.png",
		"cover.jpg", "cover.png",
		"tvshow-poster.jpg", "tvshow-poster.png",
	})
	backdrop := findNamed(showDir, []string{
		"fanart.jpg", "fanart.png",
		"backdrop.jpg", "backdrop.png",
		"landscape.jpg", "landscape.png",
		"clearart.jpg", "clearart.png",
	})
	return poster, backdrop
}

func SeasonArtworkPath(video string, seasonNumber int) string {
	return seasonImagePath(video, seasonNumber)
}

func ClearLogoArtworkPath(root string, item Item) string {
	dirs := []string{filepath.Dir(item.Path)}
	if item.Kind == "episode" && root != "" {
		dirs = append([]string{showDir(root, item.Path)}, dirs...)
	}
	names := []string{
		"clearlogoart.png", "clearlogoart.jpg", "clearlogoart.jpeg", "clearlogoart.webp",
		"clearlogo.png", "clearlogo.jpg", "clearlogo.jpeg", "clearlogo.webp",
		"logo.png", "logo.jpg", "logo.jpeg", "logo.webp",
		"clearart.png", "clearart.jpg", "clearart.jpeg", "clearart.webp",
	}
	for _, dir := range dirs {
		if path := findNamed(dir, names); path != "" {
			return path
		}
	}
	return ""
}

func seasonImagePath(video string, seasonNumber int) string {
	if seasonNumber <= 0 {
		return ""
	}
	dir := filepath.Dir(video)
	show := filepath.Dir(dir)
	n := fmt.Sprintf("%02d", seasonNumber)
	names := []string{
		"season" + n + "-poster.jpg", "season" + n + "-poster.png",
		"season" + n + ".jpg", "season" + n + ".png",
		"season" + strconv.Itoa(seasonNumber) + "-poster.jpg", "season" + strconv.Itoa(seasonNumber) + "-poster.png",
		"season" + strconv.Itoa(seasonNumber) + ".jpg", "season" + strconv.Itoa(seasonNumber) + ".png",
		"poster.jpg", "poster.png", "folder.jpg", "folder.png",
	}
	for _, base := range []string{dir, show} {
		for _, name := range names {
			candidate := filepath.Join(base, name)
			if _, err := os.Stat(candidate); err == nil {
				return candidate
			}
		}
	}
	return ""
}

func fileMTimeUnix(path string) int64 {
	if path == "" {
		return 0
	}
	info, err := os.Stat(path)
	if err != nil {
		return 0
	}
	return info.ModTime().Unix()
}

func (s *Scanner) clearScanCaches() {
	s.nfoCache = sync.Map{}
	s.mtimeCache = sync.Map{}
}

func (s *Scanner) fileMTimeUnix(path string) int64 {
	if path == "" {
		return 0
	}
	if cached, ok := s.mtimeCache.Load(path); ok {
		return cached.(int64)
	}
	mtime := fileMTimeUnix(path)
	s.mtimeCache.Store(path, mtime)
	return mtime
}

func maxInt64(a, b int64) int64 {
	if b > a {
		return b
	}
	return a
}

func showDir(root, video string) string {
	dir := filepath.Dir(video)
	rootAbs, _ := filepath.Abs(root)
	for current := dir; ; current = filepath.Dir(current) {
		if _, err := os.Stat(filepath.Join(current, "tvshow.nfo")); err == nil {
			return current
		}
		if current == rootAbs || current == filepath.Dir(current) {
			break
		}
	}
	rel, err := filepath.Rel(root, video)
	if err != nil || strings.HasPrefix(rel, "..") {
		return dir
	}
	first := strings.Split(rel, string(os.PathSeparator))[0]
	return filepath.Join(root, first)
}

type nfoMovie struct {
	Title         string      `xml:"title"`
	OriginalTitle string      `xml:"originaltitle"`
	ID            string      `xml:"id"`
	IMDbID        string      `xml:"imdbid"`
	TMDbID        string      `xml:"tmdbid"`
	TVDbID        string      `xml:"tvdbid"`
	UniqueIDs     []nfoID     `xml:"uniqueid"`
	Year          int         `xml:"year"`
	Plot          string      `xml:"plot"`
	Outline       string      `xml:"outline"`
	Tagline       string      `xml:"tagline"`
	MPAA          string      `xml:"mpaa"`
	Certification string      `xml:"certification"`
	Genres        []string    `xml:"genre"`
	Tags          []string    `xml:"tag"`
	Studios       []string    `xml:"studio"`
	Directors     []nfoText   `xml:"director"`
	Credits       []nfoText   `xml:"credits"`
	Countries     []string    `xml:"country"`
	Rating        float64     `xml:"rating"`
	Ratings       []nfoRating `xml:"ratings>rating"`
	Premiered     string      `xml:"premiered"`
	Released      string      `xml:"releasedate"`
	ShowTitle     string      `xml:"showtitle"`
	Season        int         `xml:"season"`
	Episode       int         `xml:"episode"`
	Actors        []nfoActor  `xml:"actor"`
}

type nfoActor struct {
	Name  string `xml:"name"`
	Role  string `xml:"role"`
	Thumb string `xml:"thumb"`
	Order int    `xml:"order"`
}

type nfoText struct {
	Value string `xml:",chardata"`
}

type nfoID struct {
	Type  string `xml:"type,attr"`
	Value string `xml:",chardata"`
}

func (m nfoMovie) imdbID() string {
	return firstNonEmpty(m.IMDbID, idByType(m.UniqueIDs, "imdb"), imdbID(m.ID))
}

func (m nfoMovie) tmdbID() string {
	return firstNonEmpty(m.TMDbID, idByType(m.UniqueIDs, "tmdb"))
}

func (m nfoMovie) tvdbID() string {
	return firstNonEmpty(m.TVDbID, idByType(m.UniqueIDs, "tvdb"))
}

func idByType(ids []nfoID, typ string) string {
	for _, id := range ids {
		if strings.EqualFold(strings.TrimSpace(id.Type), typ) {
			return strings.TrimSpace(id.Value)
		}
	}
	return ""
}

func imdbID(v string) string {
	v = strings.TrimSpace(v)
	if strings.HasPrefix(strings.ToLower(v), "tt") {
		return v
	}
	return ""
}

type nfoRating struct {
	Default string  `xml:"default,attr"`
	Name    string  `xml:"name,attr"`
	Max     float64 `xml:"max,attr"`
	Value   float64 `xml:"value"`
}

func readNFO(path string) nfoMovie {
	if path == "" {
		return nfoMovie{}
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return nfoMovie{}
	}
	var m nfoMovie
	if err := xml.Unmarshal(b, &m); err != nil {
		return nfoMovie{}
	}
	m.Rating = bestRating(m.Rating, m.Ratings)
	return m
}

func ReadNFOIDs(path string) (string, string) {
	imdbID, tmdbID, _ := ReadNFOExternalIDs(path)
	return imdbID, tmdbID
}

func ReadNFOExternalIDs(path string) (string, string, string) {
	meta := readNFO(path)
	return meta.imdbID(), meta.tmdbID(), meta.tvdbID()
}

func ShowNFOPath(root, video string) string {
	return findShowNFO(root, video)
}

func ReadNFOSourceRatings(path string) SourceRatings {
	meta := readNFO(path)
	var out SourceRatings
	for _, rating := range meta.Ratings {
		name := strings.ToLower(strings.TrimSpace(rating.Name))
		value := normalizeRating(rating.Value, rating.Max)
		switch {
		case name == "imdb" || strings.Contains(name, "internet movie"):
			if value > 0 {
				out.IMDb = value
			}
		case name == "tmdb" || strings.Contains(name, "themoviedb") || strings.Contains(name, "movie db"):
			if value > 0 {
				out.TMDb = value
			}
		case strings.Contains(name, "rotten") || strings.Contains(name, "tomato"):
			if rating.Value > 0 {
				out.RottenTomatoes = normalizePercentRating(rating.Value, rating.Max)
			}
		case strings.Contains(name, "metacritic"):
			if rating.Value > 0 {
				out.Metacritic = normalizePercentRating(rating.Value, rating.Max)
			}
		}
	}
	return out
}

func (s *Scanner) readNFO(path string) nfoMovie {
	if path == "" {
		return nfoMovie{}
	}
	if cached, ok := s.nfoCache.Load(path); ok {
		return cached.(nfoMovie)
	}
	meta := readNFO(path)
	s.nfoCache.Store(path, meta)
	return meta
}

func ReadNFOExtras(path string) NFOExtras {
	meta := readNFO(path)
	return NFOExtras{
		Tagline:        strings.TrimSpace(meta.Tagline),
		OfficialRating: firstNonEmpty(meta.Certification, meta.MPAA),
		Tags:           cleanStrings(meta.Tags),
		Studios:        cleanStrings(meta.Studios),
		Directors:      cleanNFOText(meta.Directors),
		Writers:        cleanNFOText(meta.Credits),
		Countries:      cleanStrings(meta.Countries),
	}
}

func cleanNFOText(values []nfoText) []string {
	out := make([]string, 0, len(values))
	seen := map[string]struct{}{}
	for _, value := range values {
		text := strings.TrimSpace(value.Value)
		if text == "" {
			continue
		}
		key := strings.ToLower(text)
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, text)
	}
	return out
}

func cleanStrings(values []string) []string {
	out := make([]string, 0, len(values))
	seen := map[string]struct{}{}
	for _, value := range values {
		text := strings.TrimSpace(value)
		if text == "" {
			continue
		}
		key := strings.ToLower(text)
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, text)
	}
	return out
}

func actorsFromNFO(in []nfoActor, actorDirs ...string) []Actor {
	out := make([]Actor, 0, len(in))
	for i, actor := range in {
		name := strings.TrimSpace(actor.Name)
		if name == "" {
			continue
		}
		order := actor.Order
		if order == 0 {
			order = i + 1
		}
		thumb := actorThumbPath(name, actorDirs)
		if thumb == "" {
			thumb = strings.TrimSpace(actor.Thumb)
		}
		out = append(out, Actor{
			Name:  name,
			Role:  strings.TrimSpace(actor.Role),
			Thumb: thumb,
			Order: order,
		})
	}
	return out
}

func itemActorDirs(lib config.Library, path string) []string {
	if lib.Type == "tv" {
		return append(tvActorDirs(lib.Path, path), filepath.Join(filepath.Dir(path), ".actors"))
	}
	return []string{filepath.Join(filepath.Dir(path), ".actors")}
}

func ActorDirsForItem(lib config.Library, path string) []string {
	return itemActorDirs(lib, path)
}

func tvActorDirs(root, path string) []string {
	return []string{filepath.Join(showDir(root, path), ".actors")}
}

func ActorDirsForShow(root, path string) []string {
	return tvActorDirs(root, path)
}

func FindActorThumb(name string, actorDirs ...string) string {
	return actorThumbPath(name, actorDirs)
}

func actorThumbPath(name string, actorDirs []string) string {
	candidates := actorImageCandidates(name)
	for _, dir := range actorDirs {
		if dir == "" {
			continue
		}
		for _, candidate := range candidates {
			path := filepath.Join(dir, candidate)
			if info, err := os.Stat(path); err == nil && !info.IsDir() {
				return path
			}
		}
		if path := actorThumbPathCaseInsensitive(dir, candidates); path != "" {
			return path
		}
	}
	return ""
}

func actorImageCandidates(name string) []string {
	sanitized := sanitizeActorImageName(name)
	baseNames := []string{name, sanitized, strings.ReplaceAll(sanitized, " ", "_")}
	exts := []string{".jpg", ".jpeg", ".png", ".webp"}
	out := make([]string, 0, len(baseNames)*len(exts))
	seen := map[string]struct{}{}
	for _, base := range baseNames {
		base = strings.TrimSpace(base)
		if base == "" {
			continue
		}
		for _, ext := range exts {
			candidate := base + ext
			key := strings.ToLower(candidate)
			if _, ok := seen[key]; ok {
				continue
			}
			seen[key] = struct{}{}
			out = append(out, candidate)
		}
	}
	return out
}

func sanitizeActorImageName(name string) string {
	return strings.TrimSpace(strings.Map(func(r rune) rune {
		switch r {
		case '/', '\\', ':', '*', '?', '"', '<', '>', '|':
			return '_'
		default:
			return r
		}
	}, name))
}

func actorThumbPathCaseInsensitive(dir string, candidates []string) string {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return ""
	}
	wanted := map[string]struct{}{}
	for _, candidate := range candidates {
		wanted[strings.ToLower(candidate)] = struct{}{}
	}
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		if _, ok := wanted[strings.ToLower(entry.Name())]; ok {
			return filepath.Join(dir, entry.Name())
		}
	}
	return ""
}

func bestRating(simple float64, ratings []nfoRating) float64 {
	for _, rating := range ratings {
		if rating.Value > 0 && ratingNameMatches(rating.Name, "imdb") {
			return normalizeRating(rating.Value, rating.Max)
		}
	}
	for _, rating := range ratings {
		if rating.Value > 0 && ratingNameMatches(rating.Name, "tvdb") {
			return normalizeRating(rating.Value, rating.Max)
		}
	}
	if simple > 0 {
		return normalizeRating(simple, 10)
	}
	for _, rating := range ratings {
		if rating.Value > 0 && strings.EqualFold(rating.Default, "true") {
			return normalizeRating(rating.Value, rating.Max)
		}
	}
	for _, rating := range ratings {
		if rating.Value > 0 {
			return normalizeRating(rating.Value, rating.Max)
		}
	}
	return 0
}

func ratingNameMatches(name, source string) bool {
	name = strings.ToLower(strings.TrimSpace(name))
	source = strings.ToLower(strings.TrimSpace(source))
	if name == source {
		return true
	}
	switch source {
	case "imdb":
		return strings.Contains(name, "internet movie")
	case "tvdb":
		return strings.Contains(name, "tvdb") || strings.Contains(name, "the tv db") || strings.Contains(name, "thetvdb")
	default:
		return false
	}
}

func normalizeRating(value, max float64) float64 {
	if max > 0 && max != 10 {
		return value / max * 10
	}
	return value
}

func normalizePercentRating(value, max float64) int {
	if max > 0 && max != 100 {
		value = value / max * 100
	}
	if value < 0 {
		return 0
	}
	if value > 100 {
		return 100
	}
	return int(value + 0.5)
}

type nfoShow struct {
	Title string `xml:"title"`
}

func readShowTitle(root, video string) string {
	candidate := findShowNFO(root, video)
	if candidate == "" {
		return ""
	}
	if b, err := os.ReadFile(candidate); err == nil {
		var show nfoShow
		if xml.Unmarshal(b, &show) == nil && strings.TrimSpace(show.Title) != "" {
			return strings.TrimSpace(show.Title)
		}
	}
	return ""
}

func findShowNFO(root, video string) string {
	dir := filepath.Dir(video)
	rootAbs, _ := filepath.Abs(root)
	for {
		candidate := filepath.Join(dir, "tvshow.nfo")
		if _, err := os.Stat(candidate); err == nil {
			return candidate
		}
		if dir == rootAbs || dir == filepath.Dir(dir) {
			return ""
		}
		dir = filepath.Dir(dir)
	}
}

func findSeasonNFO(video string, seasonNumber int) string {
	dir := filepath.Dir(video)
	show := filepath.Dir(dir)
	n := fmt.Sprintf("%02d", seasonNumber)
	names := []string{
		"season.nfo",
		"season" + n + ".nfo",
		"season" + strconv.Itoa(seasonNumber) + ".nfo",
	}
	for _, base := range []string{dir, show} {
		for _, name := range names {
			candidate := filepath.Join(base, name)
			if _, err := os.Stat(candidate); err == nil {
				return candidate
			}
		}
	}
	return ""
}

func fallbackShowTitle(root, video string) string {
	rel, err := filepath.Rel(root, video)
	if err != nil || strings.HasPrefix(rel, "..") {
		return cleanTitle(filepath.Base(filepath.Dir(video)))
	}
	first := strings.Split(rel, string(os.PathSeparator))[0]
	return cleanTitle(first)
}

func parseEpisodeNumbers(path string) (int, int) {
	matches := episodePattern.FindStringSubmatch(filepath.Base(path))
	if len(matches) != 3 {
		return 0, 0
	}
	season, _ := strconv.Atoi(matches[1])
	episode, _ := strconv.Atoi(matches[2])
	return season, episode
}

func episodeDisplayTitle(show string, season, episode int, episodeTitle string) string {
	code := ""
	if season > 0 && episode > 0 {
		code = "S" + twoDigits(season) + "E" + twoDigits(episode)
	}
	parts := []string{}
	if show != "" {
		parts = append(parts, show)
	}
	if code != "" {
		parts = append(parts, code)
	}
	if episodeTitle != "" {
		parts = append(parts, episodeTitle)
	}
	if len(parts) == 0 {
		return "Episode"
	}
	return strings.Join(parts, " - ")
}

func twoDigits(v int) string {
	if v < 10 {
		return "0" + strconv.Itoa(v)
	}
	return strconv.Itoa(v)
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value != "" {
			return value
		}
	}
	return ""
}

func findSidecar(video string, names []string) string {
	dir := filepath.Dir(video)
	base := strings.TrimSuffix(filepath.Base(video), filepath.Ext(video))
	for _, name := range names {
		var candidate string
		if strings.HasPrefix(name, ".") || strings.HasPrefix(name, "-") {
			candidate = filepath.Join(dir, base+name)
		} else {
			candidate = filepath.Join(dir, name)
		}
		if _, err := os.Stat(candidate); err == nil {
			abs, _ := filepath.Abs(candidate)
			return abs
		}
	}
	return ""
}

func findNamed(dir string, names []string) string {
	for _, name := range names {
		candidate := filepath.Join(dir, name)
		if _, err := os.Stat(candidate); err == nil {
			abs, _ := filepath.Abs(candidate)
			return abs
		}
	}
	return ""
}

func ResolveExistingPath(path string) string {
	if path == "" {
		return ""
	}
	if info, err := os.Stat(path); err == nil && !info.IsDir() {
		return path
	}
	cleaned := filepath.Clean(path)
	volume := filepath.VolumeName(cleaned)
	rest := strings.TrimPrefix(cleaned, volume)
	absolute := strings.HasPrefix(rest, string(filepath.Separator))
	parts := strings.Split(strings.Trim(rest, string(filepath.Separator)), string(filepath.Separator))
	current := volume
	if absolute {
		current += string(filepath.Separator)
	}
	for _, part := range parts {
		if part == "" || part == "." {
			continue
		}
		next := filepath.Join(current, part)
		if _, err := os.Stat(next); err == nil {
			current = next
			continue
		}
		entries, err := os.ReadDir(current)
		if err != nil {
			return ""
		}
		found := ""
		for _, entry := range entries {
			if strings.EqualFold(entry.Name(), part) {
				found = entry.Name()
				break
			}
		}
		if found == "" {
			return ""
		}
		current = filepath.Join(current, found)
	}
	if info, err := os.Stat(current); err == nil && !info.IsDir() {
		return current
	}
	return ""
}

func isAuxiliaryVideo(path string) bool {
	name := strings.ToLower(strings.TrimSuffix(filepath.Base(path), filepath.Ext(path)))
	name = strings.ReplaceAll(name, "_", " ")
	name = strings.ReplaceAll(name, ".", " ")
	name = strings.ReplaceAll(name, "-", " ")
	words := strings.Fields(name)
	if len(words) == 0 {
		return false
	}
	aux := map[string]struct{}{
		"trailer": {}, "sample": {}, "teaser": {}, "featurette": {}, "extra": {}, "extras": {}, "behind": {},
	}
	for _, word := range words {
		if _, ok := aux[word]; ok {
			return true
		}
	}
	return false
}

func cleanTitle(name string) string {
	name = strings.ReplaceAll(name, ".", " ")
	name = strings.ReplaceAll(name, "_", " ")
	fields := strings.Fields(name)
	return strings.Join(fields, " ")
}

func sortKey(title string) string {
	key := strings.ToLower(strings.TrimSpace(title))
	for _, prefix := range []string{"the ", "a ", "an "} {
		key = strings.TrimPrefix(key, prefix)
	}
	return key
}

func IsVideo(path string) bool {
	_, ok := videoExts[strings.ToLower(filepath.Ext(path))]
	return ok
}

var ErrNotVideo = errors.New("not a supported video file")
