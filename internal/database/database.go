package database

import (
	"database/sql"
	"database/sql/driver"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"unicode"

	sqlite "modernc.org/sqlite"
)

// SearchNormalize folds a string into a relaxed form for searching: lowercase,
// with diacritics and any non-alphanumeric characters (apostrophes, hyphens,
// punctuation) dropped and runs of whitespace collapsed to single spaces. This
// lets "Lets Dance" match "Let's Dance". It is exposed to SQLite as the
// searchnorm() function and must produce identical output on both sides of a
// comparison, so query parameters are passed through this same function.
func SearchNormalize(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	pendingSpace := false
	for _, r := range strings.ToLower(s) {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			if pendingSpace && b.Len() > 0 {
				b.WriteByte(' ')
			}
			pendingSpace = false
			b.WriteRune(foldRune(r))
		case unicode.IsSpace(r):
			pendingSpace = true
		default:
			// drop punctuation entirely so "let's" == "lets"
		}
	}
	return b.String()
}

// foldRune maps the most common Latin-1 accented letters to their ASCII base so
// "Amelie" matches "Amélie". Anything without a mapping is returned unchanged.
func foldRune(r rune) rune {
	switch r {
	case 'à', 'á', 'â', 'ã', 'ä', 'å', 'ā':
		return 'a'
	case 'ç', 'č':
		return 'c'
	case 'è', 'é', 'ê', 'ë', 'ē':
		return 'e'
	case 'ì', 'í', 'î', 'ï', 'ī':
		return 'i'
	case 'ñ':
		return 'n'
	case 'ò', 'ó', 'ô', 'õ', 'ö', 'ø', 'ō':
		return 'o'
	case 'ù', 'ú', 'û', 'ü', 'ū':
		return 'u'
	case 'ý', 'ÿ':
		return 'y'
	case 'ß':
		return 's'
	}
	return r
}

var registerSearchnorm sync.Once

func Open(path string) (*sql.DB, error) {
	registerSearchnorm.Do(func() {
		sqlite.MustRegisterDeterministicScalarFunction("searchnorm", 1, func(_ *sqlite.FunctionContext, args []driver.Value) (driver.Value, error) {
			s, _ := args[0].(string)
			return SearchNormalize(s), nil
		})
	})
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)&_pragma=foreign_keys(ON)&_pragma=temp_store(MEMORY)&_pragma=cache_size(-20000)&_pragma=mmap_size(268435456)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(8)
	db.SetMaxIdleConns(8)
	if err := migrate(db); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

func migrate(db *sql.DB) error {
	_, err := db.Exec(`
CREATE TABLE IF NOT EXISTS media_items (
	id INTEGER PRIMARY KEY,
	library_id TEXT NOT NULL,
	path TEXT NOT NULL UNIQUE,
	kind TEXT NOT NULL,
	title TEXT NOT NULL,
	sort_title TEXT NOT NULL,
	original_title TEXT,
	year INTEGER,
	duration_ms INTEGER,
	container TEXT,
	video_codec TEXT,
	audio_codec TEXT,
	imdb_id TEXT,
	tmdb_id TEXT,
	tvdb_id TEXT,
	width INTEGER,
	height INTEGER,
	bit_rate INTEGER NOT NULL DEFAULT 0,
	size_bytes INTEGER NOT NULL DEFAULT 0,
	mtime_unix INTEGER NOT NULL,
	nfo_path TEXT,
	nfo_mtime_unix INTEGER NOT NULL DEFAULT 0,
	poster_path TEXT,
	poster_mtime_unix INTEGER NOT NULL DEFAULT 0,
	backdrop_path TEXT,
	backdrop_mtime_unix INTEGER NOT NULL DEFAULT 0,
	overview TEXT,
	tagline TEXT,
	official_rating TEXT,
	genres TEXT,
	tags TEXT,
	studios TEXT,
	directors TEXT,
	writers TEXT,
	countries TEXT,
	rating REAL,
	premiered TEXT,
	show_title TEXT,
	season_number INTEGER,
	episode_number INTEGER,
	episode_title TEXT,
	created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_media_library_sort ON media_items(library_id, sort_title);
CREATE INDEX IF NOT EXISTS idx_media_updated ON media_items(updated_at);
CREATE INDEX IF NOT EXISTS idx_media_library_kind_sort ON media_items(library_id, kind, sort_title, season_number, episode_number);
CREATE INDEX IF NOT EXISTS idx_media_library_kind_updated ON media_items(library_id, kind, updated_at);
CREATE INDEX IF NOT EXISTS idx_media_library_kind_mtime ON media_items(library_id, kind, mtime_unix);
CREATE INDEX IF NOT EXISTS idx_media_library_kind_rating ON media_items(library_id, kind, rating);
CREATE INDEX IF NOT EXISTS idx_media_path_library ON media_items(path, library_id);
CREATE TABLE IF NOT EXISTS media_streams (
	item_id INTEGER NOT NULL REFERENCES media_items(id) ON DELETE CASCADE,
	stream_index INTEGER NOT NULL,
	type TEXT NOT NULL,
	codec TEXT NOT NULL,
	codec_long_name TEXT,
	profile TEXT,
	level INTEGER,
	width INTEGER,
	height INTEGER,
	pix_fmt TEXT,
	color_range TEXT,
	color_space TEXT,
	color_transfer TEXT,
	color_primaries TEXT,
	hdr_format TEXT,
	bit_rate INTEGER,
	channels INTEGER,
	channel_layout TEXT,
	sample_rate INTEGER,
	language TEXT,
	title TEXT,
	is_default INTEGER NOT NULL DEFAULT 0,
	is_forced INTEGER NOT NULL DEFAULT 0,
	disposition_json TEXT,
	raw_json TEXT NOT NULL DEFAULT '{}',
	updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY(item_id, stream_index)
);
CREATE INDEX IF NOT EXISTS idx_media_streams_item_type ON media_streams(item_id, type);
CREATE TABLE IF NOT EXISTS media_shows (
	library_id TEXT NOT NULL,
	show_title TEXT NOT NULL,
	sort_title TEXT NOT NULL,
	original_title TEXT,
	year INTEGER,
	nfo_path TEXT,
	nfo_mtime_unix INTEGER NOT NULL DEFAULT 0,
	overview TEXT,
	genres TEXT,
	rating REAL,
	premiered TEXT,
	updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY(library_id, show_title)
);
CREATE INDEX IF NOT EXISTS idx_media_shows_sort ON media_shows(library_id, sort_title);
CREATE TABLE IF NOT EXISTS media_seasons (
	library_id TEXT NOT NULL,
	show_title TEXT NOT NULL,
	season_number INTEGER NOT NULL,
	title TEXT,
	nfo_path TEXT,
	nfo_mtime_unix INTEGER NOT NULL DEFAULT 0,
	poster_path TEXT,
	poster_mtime_unix INTEGER NOT NULL DEFAULT 0,
	overview TEXT,
	rating REAL,
	premiered TEXT,
	updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY(library_id, show_title, season_number)
);
CREATE INDEX IF NOT EXISTS idx_media_seasons_show ON media_seasons(library_id, show_title, season_number);
CREATE TABLE IF NOT EXISTS media_actors (
	id INTEGER PRIMARY KEY,
	scope TEXT NOT NULL,
	item_id INTEGER REFERENCES media_items(id) ON DELETE CASCADE,
	library_id TEXT,
	show_title TEXT,
	season_number INTEGER,
	name TEXT NOT NULL,
	role TEXT,
	thumb TEXT,
	sort_order INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_media_actors_item ON media_actors(scope, item_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_media_actors_show ON media_actors(scope, library_id, show_title, sort_order);
CREATE INDEX IF NOT EXISTS idx_media_actors_season ON media_actors(scope, library_id, show_title, season_number, sort_order);
CREATE INDEX IF NOT EXISTS idx_media_actors_name_item ON media_actors(name, scope, item_id);
CREATE INDEX IF NOT EXISTS idx_media_actors_name_show ON media_actors(name, scope, library_id, show_title);
CREATE TABLE IF NOT EXISTS actor_metadata_cache (
	name TEXT PRIMARY KEY,
	tmdb_id TEXT,
	imdb_id TEXT,
	biography TEXT,
	birthday TEXT,
	deathday TEXT,
	place_of_birth TEXT,
	known_for_department TEXT,
	profile_path TEXT,
	source TEXT NOT NULL DEFAULT '',
	fetched_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS metadata_backfills (
	library_id TEXT NOT NULL,
	name TEXT NOT NULL,
	completed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY(library_id, name)
);
CREATE TABLE IF NOT EXISTS scan_state (
	library_id TEXT PRIMARY KEY,
	started_at TEXT,
	finished_at TEXT,
	status TEXT NOT NULL,
	message TEXT NOT NULL DEFAULT '',
	files_seen INTEGER NOT NULL DEFAULT 0,
	media_found INTEGER NOT NULL DEFAULT 0,
	items_imported INTEGER NOT NULL DEFAULT 0,
	files_skipped INTEGER NOT NULL DEFAULT 0,
	errors INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS users (
	id INTEGER PRIMARY KEY,
	username TEXT NOT NULL UNIQUE,
	display_name TEXT NOT NULL,
	password_hash TEXT NOT NULL,
	is_admin INTEGER NOT NULL DEFAULT 0,
	disabled INTEGER NOT NULL DEFAULT 0,
	created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS auth_sessions (
	token TEXT PRIMARY KEY,
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	last_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	expires_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS auth_qr_codes (
	code TEXT PRIMARY KEY,
	device_name TEXT NOT NULL DEFAULT '',
	user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
	token TEXT REFERENCES auth_sessions(token) ON DELETE CASCADE,
	expires_at TEXT NOT NULL,
	created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	completed_at TEXT
);
CREATE TABLE IF NOT EXISTS playback_progress (
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	item_id INTEGER NOT NULL REFERENCES media_items(id) ON DELETE CASCADE,
	position_ms INTEGER NOT NULL DEFAULT 0,
	duration_ms INTEGER NOT NULL DEFAULT 0,
	completed INTEGER NOT NULL DEFAULT 0,
	updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY(user_id, item_id)
);
CREATE TABLE IF NOT EXISTS trakt_accounts (
	user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
	access_token TEXT NOT NULL,
	refresh_token TEXT NOT NULL,
	expires_at TEXT NOT NULL,
	created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS user_watchlist (
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	watch_key TEXT NOT NULL,
	kind TEXT NOT NULL,
	item_id INTEGER REFERENCES media_items(id) ON DELETE CASCADE,
	library_id TEXT,
	show_title TEXT,
	created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY(user_id, watch_key)
);
CREATE TABLE IF NOT EXISTS user_ratings (
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	rate_key TEXT NOT NULL,
	kind TEXT NOT NULL,
	item_id INTEGER REFERENCES media_items(id) ON DELETE CASCADE,
	library_id TEXT,
	show_title TEXT,
	rating INTEGER NOT NULL,
	created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY(user_id, rate_key)
);
CREATE TABLE IF NOT EXISTS recommendation_exclusions (
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	exclusion_key TEXT NOT NULL,
	kind TEXT NOT NULL,
	item_id INTEGER REFERENCES media_items(id) ON DELETE CASCADE,
	library_id TEXT,
	show_title TEXT,
	created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY(user_id, exclusion_key)
);
CREATE TABLE IF NOT EXISTS imdb_ratings (
	imdb_id TEXT PRIMARY KEY,
	rating REAL,
	rotten_tomatoes INTEGER,
	metacritic INTEGER,
	fetched_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS trakt_live_cache (
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	name TEXT NOT NULL,
	payload_json TEXT NOT NULL,
	fetched_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY(user_id, name)
);
CREATE TABLE IF NOT EXISTS tmdb_titles (
	kind TEXT NOT NULL,
	tmdb_id INTEGER NOT NULL,
	title TEXT,
	overview TEXT,
	poster_path TEXT,
	backdrop_path TEXT,
	rating REAL,
	runtime INTEGER,
	genres TEXT,
	fetched_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY(kind, tmdb_id)
);
CREATE TABLE IF NOT EXISTS trakt_collection_entries (
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	entry_key TEXT NOT NULL,
	kind TEXT NOT NULL,
	payload_json TEXT NOT NULL,
	synced_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY(user_id, entry_key)
);
CREATE TABLE IF NOT EXISTS home_layouts (
	user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
	sections_json TEXT NOT NULL,
	created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS external_ratings_cache (
	item_id INTEGER PRIMARY KEY REFERENCES media_items(id) ON DELETE CASCADE,
	imdb_id TEXT,
	tmdb_id TEXT,
	imdb_rating REAL,
	tmdb_rating REAL,
	rotten_tomatoes_rating INTEGER,
	metacritic_rating INTEGER,
	source TEXT NOT NULL DEFAULT '',
	fetched_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS remote_devices (
	id TEXT PRIMARY KEY,
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	name TEXT NOT NULL,
	kind TEXT NOT NULL DEFAULT 'tv',
	created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	last_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS remote_pairing_codes (
	code TEXT PRIMARY KEY,
	device_id TEXT NOT NULL REFERENCES remote_devices(id) ON DELETE CASCADE,
	expires_at TEXT NOT NULL,
	created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS remote_commands (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	device_id TEXT NOT NULL REFERENCES remote_devices(id) ON DELETE CASCADE,
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	type TEXT NOT NULL,
	payload TEXT NOT NULL DEFAULT '{}',
	created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS remote_device_state (
	device_id TEXT PRIMARY KEY REFERENCES remote_devices(id) ON DELETE CASCADE,
	item_id INTEGER,
	title TEXT NOT NULL DEFAULT '',
	state TEXT NOT NULL DEFAULT 'idle',
	position_ms INTEGER NOT NULL DEFAULT 0,
	duration_ms INTEGER NOT NULL DEFAULT 0,
	updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	payload TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE IF NOT EXISTS app_updates (
	app TEXT PRIMARY KEY,
	apk_path TEXT NOT NULL,
	version_code INTEGER NOT NULL,
	version_name TEXT NOT NULL,
	release_notes TEXT NOT NULL DEFAULT '',
	created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
	updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);`)
	if err != nil {
		return err
	}
	if err := dropProfiledHomeLayouts(db); err != nil {
		return err
	}
	for _, stmt := range []string{
		`ALTER TABLE media_items ADD COLUMN show_title TEXT`,
		`ALTER TABLE media_items ADD COLUMN original_title TEXT`,
		`ALTER TABLE media_items ADD COLUMN imdb_id TEXT`,
		`ALTER TABLE media_items ADD COLUMN tmdb_id TEXT`,
		`ALTER TABLE media_items ADD COLUMN tvdb_id TEXT`,
		`ALTER TABLE media_items ADD COLUMN bit_rate INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE media_items ADD COLUMN nfo_mtime_unix INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE media_items ADD COLUMN poster_mtime_unix INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE media_items ADD COLUMN backdrop_mtime_unix INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE media_items ADD COLUMN overview TEXT`,
		`ALTER TABLE media_items ADD COLUMN tagline TEXT`,
		`ALTER TABLE media_items ADD COLUMN official_rating TEXT`,
		`ALTER TABLE media_items ADD COLUMN genres TEXT`,
		`ALTER TABLE media_items ADD COLUMN tags TEXT`,
		`ALTER TABLE media_items ADD COLUMN studios TEXT`,
		`ALTER TABLE media_items ADD COLUMN directors TEXT`,
		`ALTER TABLE media_items ADD COLUMN writers TEXT`,
		`ALTER TABLE media_items ADD COLUMN countries TEXT`,
		`ALTER TABLE media_items ADD COLUMN rating REAL`,
		`ALTER TABLE media_items ADD COLUMN premiered TEXT`,
		`ALTER TABLE media_items ADD COLUMN season_number INTEGER`,
		`ALTER TABLE media_items ADD COLUMN episode_number INTEGER`,
		`ALTER TABLE media_items ADD COLUMN episode_title TEXT`,
		// Thumbhash placeholders. Written only by the background artwork pass,
		// never by the scanner's upsert, so a rescan cannot wipe them. The
		// _src columns record the artwork mtime each hash was derived from,
		// which is what makes the pass notice replaced artwork and recompute.
		`ALTER TABLE media_items ADD COLUMN poster_thumbhash TEXT`,
		`ALTER TABLE media_items ADD COLUMN poster_thumbhash_src INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE media_items ADD COLUMN backdrop_thumbhash TEXT`,
		`ALTER TABLE media_items ADD COLUMN backdrop_thumbhash_src INTEGER NOT NULL DEFAULT 0`,
		// Partial index over just the rows the backfill still owes work for,
		// so its polling query stays cheap on a fully-hashed library.
		`CREATE INDEX IF NOT EXISTS idx_media_thumbhash_pending ON media_items(id)
			WHERE (poster_path IS NOT NULL AND poster_path != '' AND (poster_thumbhash IS NULL OR poster_thumbhash_src != poster_mtime_unix))
			   OR (backdrop_path IS NOT NULL AND backdrop_path != '' AND (backdrop_thumbhash IS NULL OR backdrop_thumbhash_src != backdrop_mtime_unix))`,
		`CREATE INDEX IF NOT EXISTS idx_media_show ON media_items(library_id, show_title, season_number, episode_number)`,
		`CREATE INDEX IF NOT EXISTS idx_media_library_kind_sort ON media_items(library_id, kind, sort_title, season_number, episode_number)`,
		`CREATE INDEX IF NOT EXISTS idx_media_library_kind_updated ON media_items(library_id, kind, updated_at)`,
		`CREATE INDEX IF NOT EXISTS idx_media_library_kind_mtime ON media_items(library_id, kind, mtime_unix)`,
		`CREATE INDEX IF NOT EXISTS idx_media_library_kind_rating ON media_items(library_id, kind, rating)`,
		`CREATE INDEX IF NOT EXISTS idx_media_path_library ON media_items(path, library_id)`,
		`CREATE TABLE IF NOT EXISTS media_streams (
			item_id INTEGER NOT NULL REFERENCES media_items(id) ON DELETE CASCADE,
			stream_index INTEGER NOT NULL,
			type TEXT NOT NULL,
			codec TEXT NOT NULL,
			codec_long_name TEXT,
			profile TEXT,
			level INTEGER,
			width INTEGER,
			height INTEGER,
			pix_fmt TEXT,
			color_range TEXT,
			color_space TEXT,
			color_transfer TEXT,
			color_primaries TEXT,
			hdr_format TEXT,
			bit_rate INTEGER,
			channels INTEGER,
			channel_layout TEXT,
			sample_rate INTEGER,
			language TEXT,
			title TEXT,
			is_default INTEGER NOT NULL DEFAULT 0,
			is_forced INTEGER NOT NULL DEFAULT 0,
			disposition_json TEXT,
			raw_json TEXT NOT NULL DEFAULT '{}',
			updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
			PRIMARY KEY(item_id, stream_index)
		)`,
		`CREATE INDEX IF NOT EXISTS idx_media_streams_item_type ON media_streams(item_id, type)`,
		`CREATE TABLE IF NOT EXISTS media_shows (
			library_id TEXT NOT NULL,
			show_title TEXT NOT NULL,
			sort_title TEXT NOT NULL,
			original_title TEXT,
			year INTEGER,
			nfo_path TEXT,
			nfo_mtime_unix INTEGER NOT NULL DEFAULT 0,
			overview TEXT,
			genres TEXT,
			rating REAL,
			premiered TEXT,
			updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
			PRIMARY KEY(library_id, show_title)
		)`,
		`CREATE INDEX IF NOT EXISTS idx_media_shows_sort ON media_shows(library_id, sort_title)`,
		`CREATE TABLE IF NOT EXISTS media_seasons (
			library_id TEXT NOT NULL,
			show_title TEXT NOT NULL,
			season_number INTEGER NOT NULL,
			title TEXT,
			nfo_path TEXT,
			nfo_mtime_unix INTEGER NOT NULL DEFAULT 0,
			poster_path TEXT,
			poster_mtime_unix INTEGER NOT NULL DEFAULT 0,
			overview TEXT,
			rating REAL,
			premiered TEXT,
			updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
			PRIMARY KEY(library_id, show_title, season_number)
		)`,
		`CREATE INDEX IF NOT EXISTS idx_media_seasons_show ON media_seasons(library_id, show_title, season_number)`,
		`CREATE TABLE IF NOT EXISTS media_actors (
			id INTEGER PRIMARY KEY,
			scope TEXT NOT NULL,
			item_id INTEGER REFERENCES media_items(id) ON DELETE CASCADE,
			library_id TEXT,
			show_title TEXT,
			season_number INTEGER,
			name TEXT NOT NULL,
			role TEXT,
			thumb TEXT,
			sort_order INTEGER NOT NULL DEFAULT 0
		)`,
		`CREATE INDEX IF NOT EXISTS idx_media_actors_item ON media_actors(scope, item_id, sort_order)`,
		`CREATE INDEX IF NOT EXISTS idx_media_actors_show ON media_actors(scope, library_id, show_title, sort_order)`,
		`CREATE INDEX IF NOT EXISTS idx_media_actors_season ON media_actors(scope, library_id, show_title, season_number, sort_order)`,
		`CREATE INDEX IF NOT EXISTS idx_media_actors_name_item ON media_actors(name, scope, item_id)`,
		`CREATE INDEX IF NOT EXISTS idx_media_actors_name_show ON media_actors(name, scope, library_id, show_title)`,
		`CREATE TABLE IF NOT EXISTS actor_metadata_cache (
			name TEXT PRIMARY KEY,
			tmdb_id TEXT,
			imdb_id TEXT,
			biography TEXT,
			birthday TEXT,
			deathday TEXT,
			place_of_birth TEXT,
			known_for_department TEXT,
			profile_path TEXT,
			source TEXT NOT NULL DEFAULT '',
			fetched_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE TABLE IF NOT EXISTS metadata_backfills (
			library_id TEXT NOT NULL,
			name TEXT NOT NULL,
			completed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
			PRIMARY KEY(library_id, name)
		)`,
		`ALTER TABLE scan_state ADD COLUMN files_seen INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE scan_state ADD COLUMN media_found INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE scan_state ADD COLUMN items_imported INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE scan_state ADD COLUMN files_skipped INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE scan_state ADD COLUMN errors INTEGER NOT NULL DEFAULT 0`,
		`CREATE INDEX IF NOT EXISTS idx_users_username ON users(username)`,
		`CREATE INDEX IF NOT EXISTS idx_auth_sessions_user ON auth_sessions(user_id)`,
		`CREATE INDEX IF NOT EXISTS idx_auth_sessions_expires ON auth_sessions(expires_at)`,
		`CREATE INDEX IF NOT EXISTS idx_auth_qr_expires ON auth_qr_codes(expires_at)`,
		`CREATE INDEX IF NOT EXISTS idx_playback_progress_user ON playback_progress(user_id, updated_at)`,
		`CREATE INDEX IF NOT EXISTS idx_user_watchlist_user ON user_watchlist(user_id, updated_at)`,
		`CREATE INDEX IF NOT EXISTS idx_user_ratings_user ON user_ratings(user_id, updated_at)`,
		`CREATE TABLE IF NOT EXISTS recommendation_exclusions (
			user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			exclusion_key TEXT NOT NULL,
			kind TEXT NOT NULL,
			item_id INTEGER REFERENCES media_items(id) ON DELETE CASCADE,
			library_id TEXT,
			show_title TEXT,
			created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
			updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
			PRIMARY KEY(user_id, exclusion_key)
		)`,
		`CREATE INDEX IF NOT EXISTS idx_recommendation_exclusions_user ON recommendation_exclusions(user_id, updated_at)`,
		`CREATE TABLE IF NOT EXISTS imdb_ratings (
			imdb_id TEXT PRIMARY KEY,
			rating REAL,
			rotten_tomatoes INTEGER,
			metacritic INTEGER,
			fetched_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE TABLE IF NOT EXISTS trakt_live_cache (
			user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			name TEXT NOT NULL,
			payload_json TEXT NOT NULL,
			fetched_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
			PRIMARY KEY(user_id, name)
		)`,
		`CREATE TABLE IF NOT EXISTS tmdb_titles (
			kind TEXT NOT NULL,
			tmdb_id INTEGER NOT NULL,
			title TEXT,
			overview TEXT,
			poster_path TEXT,
			backdrop_path TEXT,
			rating REAL,
			runtime INTEGER,
			genres TEXT,
			fetched_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
			PRIMARY KEY(kind, tmdb_id)
		)`,
		`CREATE TABLE IF NOT EXISTS trakt_collection_entries (
			user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			entry_key TEXT NOT NULL,
			kind TEXT NOT NULL,
			payload_json TEXT NOT NULL,
			synced_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
			PRIMARY KEY(user_id, entry_key)
		)`,
		`CREATE TABLE IF NOT EXISTS home_layouts (
			user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
			sections_json TEXT NOT NULL,
			created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
			updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
		)`,
		`ALTER TABLE external_ratings_cache ADD COLUMN tmdb_rating REAL`,
		`CREATE INDEX IF NOT EXISTS idx_remote_commands_device ON remote_commands(device_id, id)`,
		`CREATE INDEX IF NOT EXISTS idx_remote_pairing_expires ON remote_pairing_codes(expires_at)`,
		`CREATE INDEX IF NOT EXISTS idx_app_updates_updated ON app_updates(updated_at)`,
		`ALTER TABLE users ADD COLUMN avatar TEXT NOT NULL DEFAULT ''`,
	} {
		if _, err := db.Exec(stmt); err != nil && !isDuplicateColumn(err) {
			return err
		}
	}
	return nil
}

// dropProfiledHomeLayouts removes the home_layouts table from the short-lived
// per-client-profile design, so the single-layout table can be created in its
// place. Layouts are cheap to redo and this only fires once.
func dropProfiledHomeLayouts(db *sql.DB) error {
	rows, err := db.Query(`PRAGMA table_info(home_layouts)`)
	if err != nil {
		return err
	}
	defer rows.Close()
	profiled := false
	for rows.Next() {
		var (
			cid, notNull, primaryKey int
			name, columnType         string
			defaultValue             any
		)
		if err := rows.Scan(&cid, &name, &columnType, &notNull, &defaultValue, &primaryKey); err != nil {
			return err
		}
		if name == "profile" {
			profiled = true
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if !profiled {
		return nil
	}
	_, err = db.Exec(`DROP TABLE home_layouts`)
	return err
}

func isDuplicateColumn(err error) bool {
	return err != nil && (strings.Contains(err.Error(), "duplicate column name") || strings.Contains(err.Error(), "already exists"))
}
