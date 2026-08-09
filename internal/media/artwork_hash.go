package media

import (
	"context"
	"encoding/base64"
	"fmt"
	"image"
	"os"
	"sync"
)

// ArtworkHashTarget is one piece of artwork the background pass still owes a
// thumbhash for. Kind is "poster" or "backdrop".
type ArtworkHashTarget struct {
	ItemID    int64
	Kind      string
	Path      string
	MTimeUnix int64
}

// PendingArtworkHashes returns up to limit pieces of artwork whose thumbhash is
// missing or was derived from a different artwork mtime than the one currently
// recorded. Replacing a poster on disk bumps its mtime, which is what makes a
// stale hash reappear here.
func (s *Store) PendingArtworkHashes(ctx context.Context, limit int) ([]ArtworkHashTarget, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT id, 'poster', poster_path, poster_mtime_unix
FROM media_items
WHERE poster_path IS NOT NULL AND poster_path != ''
  AND (poster_thumbhash IS NULL OR poster_thumbhash_src != poster_mtime_unix)
UNION ALL
SELECT id, 'backdrop', backdrop_path, backdrop_mtime_unix
FROM media_items
WHERE backdrop_path IS NOT NULL AND backdrop_path != ''
  AND (backdrop_thumbhash IS NULL OR backdrop_thumbhash_src != backdrop_mtime_unix)
LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var targets []ArtworkHashTarget
	for rows.Next() {
		var t ArtworkHashTarget
		if err := rows.Scan(&t.ItemID, &t.Kind, &t.Path, &t.MTimeUnix); err != nil {
			return nil, err
		}
		targets = append(targets, t)
	}
	return targets, rows.Err()
}

// SaveArtworkHashes writes computed thumbhashes back in one transaction.
//
// It updates every row sharing the same artwork path rather than only the row
// the target came from: an entire series points at one show poster, so hashing
// it once and fanning the result out over all its episodes is what keeps the
// pass proportional to distinct artwork instead of to library size.
//
// A hash is only stored while the recorded mtime still matches the one it was
// computed from, so a rescan that lands mid-pass cannot persist a hash of
// artwork that has already been replaced.
func (s *Store) SaveArtworkHashes(ctx context.Context, results []ArtworkHashResult) error {
	if len(results) == 0 {
		return nil
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	for _, r := range results {
		var column, srcColumn, pathColumn, mtimeColumn string
		switch r.Kind {
		case "poster":
			column, srcColumn = "poster_thumbhash", "poster_thumbhash_src"
			pathColumn, mtimeColumn = "poster_path", "poster_mtime_unix"
		case "backdrop":
			column, srcColumn = "backdrop_thumbhash", "backdrop_thumbhash_src"
			pathColumn, mtimeColumn = "backdrop_path", "backdrop_mtime_unix"
		default:
			continue
		}
		if _, err := tx.ExecContext(ctx, fmt.Sprintf(
			`UPDATE media_items SET %s = ?, %s = ? WHERE %s = ? AND %s = ?`,
			column, srcColumn, pathColumn, mtimeColumn,
			// Deliberately not nullString: a failed decode stores an empty
			// string, which is a value the pending predicate treats as done.
			// Writing NULL would make the row match "IS NULL" forever and
			// re-decode the same broken file on every sweep.
		), r.Hash, r.MTimeUnix, r.Path, r.MTimeUnix); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// ArtworkHashResult is a computed hash ready to be written back. An empty Hash
// records a permanent failure (unreadable or undecodable artwork) so the pass
// does not retry the same broken file on every sweep.
type ArtworkHashResult struct {
	Kind      string
	Path      string
	MTimeUnix int64
	Hash      string
}

// HashArtworkFile decodes the image at path and returns its base64 thumbhash.
// It returns "" for anything it cannot turn into a hash; callers persist that
// as a tombstone rather than treating it as a retryable error.
func HashArtworkFile(path string) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()

	img, _, err := image.Decode(f)
	if err != nil {
		return ""
	}
	hash := ImageToThumbHash(img)
	if len(hash) == 0 {
		return ""
	}
	return base64.StdEncoding.EncodeToString(hash)
}

// HashArtworkTargets computes hashes for targets using workers goroutines,
// decoding each distinct artwork file only once no matter how many items
// reference it. It returns one result per distinct (kind, path, mtime).
func HashArtworkTargets(ctx context.Context, targets []ArtworkHashTarget, workers int) []ArtworkHashResult {
	if workers < 1 {
		workers = 1
	}

	// Collapse to distinct artwork first. A 200-episode series contributes 200
	// targets pointing at one poster file; decoding it once is the difference
	// between seconds and minutes.
	type key struct {
		kind, path string
		mtime      int64
	}
	seen := make(map[key]struct{}, len(targets))
	var distinct []ArtworkHashTarget
	for _, t := range targets {
		k := key{t.Kind, t.Path, t.MTimeUnix}
		if _, dup := seen[k]; dup {
			continue
		}
		seen[k] = struct{}{}
		distinct = append(distinct, t)
	}

	results := make([]ArtworkHashResult, len(distinct))
	jobs := make(chan int)
	var wg sync.WaitGroup
	for range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range jobs {
				t := distinct[i]
				results[i] = ArtworkHashResult{
					Kind:      t.Kind,
					Path:      t.Path,
					MTimeUnix: t.MTimeUnix,
					Hash:      HashArtworkFile(ResolveExistingPath(t.Path)),
				}
			}
		}()
	}
	for i := range distinct {
		select {
		case <-ctx.Done():
			close(jobs)
			wg.Wait()
			return results[:i]
		case jobs <- i:
		}
	}
	close(jobs)
	wg.Wait()
	return results
}
