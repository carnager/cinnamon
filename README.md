# Cinnamon

Cinnamon is a media server for your own movies and TV shows. It runs on a
machine with access to your media, with clients for the browser, Android TV,
Android phones, and mpv on the desktop.

Each account has its own watch history, resume points, watchlist, and home
layout. You can arrange shelves around what you watch: recent additions,
unfinished shows, recommendations, or filters such as genre, decade, and rating.
The web, TV, and phone apps share that layout.

The server is written in Go, stores its data in SQLite, and includes the web
client in the binary. Playback uses the original file when the client supports
it; otherwise, ffmpeg remuxes or transcodes it.

Cinnamon was previously called Popcorn. The commands, config paths, and
`POPCORN_*` environment variables still use the old name.

## Getting started

You need Go 1.26.3 or later, plus `ffmpeg` and `ffprobe` on your `PATH`.

```sh
git clone https://github.com/carnager/cinnamon.git
cd cinnamon
cp config.example.toml config.toml
$EDITOR config.toml
```

Set the library paths to your movie and TV folders. The example config uses
VAAPI; change `hwAccel` to `"none"` if you want CPU transcoding. Set
`scanOnStart = true` to scan your libraries when the server starts.

```sh
go run ./cmd/popcornd -config config.toml
```

Open <http://localhost:8097>. On an empty database, the server creates an admin
account. The username defaults to `admin`; set `POPCORN_ADMIN_USER` to change
it. Set `POPCORN_ADMIN_PASSWORD` before starting the server to choose the
password, or use the generated password printed once in the startup log.

To build and run the server binary:

```sh
go build -o popcornd ./cmd/popcornd
./popcornd -config config.toml
```

You can also run with environment variables alone:

```sh
POPCORN_LIBRARY=/media/movies POPCORN_LISTEN=:8097 go run ./cmd/popcornd
```

Add `POPCORN_LIBRARY_TYPE=tv` for a TV library. Set `POPCORN_LOG_LEVEL=debug`
when you need more detail about scans or playback.

## Libraries and metadata

Cinnamon reads local `.nfo` files and artwork in the layout used by
TinyMediaManager. Keep posters and backdrops beside the videos. Each library
has a type:

```toml
[[libraries]]
id = "movies"
name = "Movies"
type = "movies"
path = "/media/movies"

[[libraries]]
id = "tv"
name = "TV Shows"
type = "tv"
path = "/media/tv"
```

For TV, episode `.nfo` files supply the show title, season, episode number, and
episode title. If the show title is missing, the scanner checks `tvshow.nfo`,
then falls back to the first folder below the library root.

Use **Update Libraries** from an admin account to scan manually. Scans notice
removed files and reuse probe results for files that have not changed.
`autoScan = true` enables filesystem watching and periodic scans. A separate
reconciliation scan runs every hour by default, even with `autoScan` off;
set `reconcileInterval = "0s"` to disable it.

For media on a NAS, [popcorn-watch](packaging/popcorn-watch.md) can run on the
storage host and notify the server when a folder changes.

## Clients

The web client is served at the server's root URL. The Android TV and phone
apps live in `android-tv/`; both connect to the same server and support QR
sign-in. The phone app can also act as a TV remote.

On the desktop, `popcorn-mpv` uses mpv for playback and rofi or fzf to choose
what to watch:

```sh
go run ./cmd/popcorn-mpv -server http://localhost:8097 -selector rofi
```

Use `-selector fzf` for a terminal picker. The launcher saves its login token
in `$XDG_CONFIG_HOME/popcorn/mpv.toml`, or `~/.config/popcorn/mpv.toml`.

## Playback

The server chooses direct playback, an HLS remux, an audio transcode, or a full
transcode based on the client's supported formats and bandwidth limit. Direct
playback supports HTTP range requests; transcoded playback can restart at the
requested position when you seek.

Set `hwAccel` in the server config to choose how ffmpeg transcodes video:

| Value | Behaviour |
| --- | --- |
| `none` | CPU encoding with libx264 |
| `auto` | Automatic hardware decoding, CPU encoding |
| `nvenc` | NVIDIA H.264 encoding |
| `qsv` | Intel Quick Sync H.264 encoding |
| `vaapi` | Software decoding, VAAPI H.264 encoding |

For VAAPI, set `hwDevice` to your render node, usually `/dev/dri/renderD128`.

Embedded text subtitles in Matroska files can be read directly from the file's
index. Files without a usable subtitle index fall back to ffmpeg, which can be
slow on large files. PGS and VobSub image subtitles need conversion to text for
the apps. [SUBTITLES.md](SUBTITLES.md) covers extraction and the repair and OCR
scripts.

## Trakt and discovery

Connect Trakt from your account settings to scrobble playback and access your
Trakt watchlist, history, and recommendations. You can import watched status,
watchlists, ratings, and hidden recommendations. Collection sync records which
titles are in your libraries and removes entries Cinnamon added when those
files are gone.

The phone app's Discover screen shows titles outside your library and upcoming
episodes of shows you own. Configure `tmdbApiKey` or `tmdbReadAccessToken` for
TMDb artwork and details. An optional `omdbApiKey` adds ratings from OMDb.

Trakt app credentials are included for device sign-in. To use your own Trakt
app, set `traktClientId` and `traktClientSecret`, or the matching
`POPCORN_TRAKT_CLIENT_ID` and `POPCORN_TRAKT_CLIENT_SECRET` environment variables.
Each Cinnamon user connects their Trakt account separately.

## Installing on Arch Linux

From a checkout:

```sh
cd packaging/arch
makepkg -si
sudoedit /etc/popcorn/config.toml
sudo systemctl enable --now popcornd
popcorn-mpv -selector rofi
```

The package installs the server, desktop launcher, and systemd integration.
See [the packaging notes](packaging/arch/README.md) for details.

## Development and releases

Run the Go tests and build both Android debug APKs:

```sh
./scripts/check
```

Android builds need Java 21 and Android SDK platform 36. The script selects
`/usr/lib/jvm/java-21-openjdk` when it exists and `JAVA_HOME` is unset.
Set `POPCORN_CHECK_ANDROID=0` to run only the Go tests, or
`POPCORN_CHECK_GO=0` to build only the Android apps.

Build a release bundle with:

```sh
./scripts/release
```

Use `--server` for the server and desktop launcher, `--phone` for the phone
app, or `--androidtv` for the TV app. Flags can be combined. Bundles go into
`dist/<git-version>/` with SHA-256 checksums.

Android releases require signing credentials. By default, the build reads
`~/.local/android/release-keys/popcorn.properties`. The web admin's **App
Updates** screen accepts release APKs for clients to download.
[DEPLOYMENT.md](DEPLOYMENT.md) covers signing, release options, uploads, and
systemd setup.

## API

The clients use a JSON API under `/api/`. Authenticate with
`Authorization: Bearer <token>` or the `popcorn_token` login cookie. Health,
login, and the QR start/poll/claim routes are public; other API routes require
a login, and administrative actions require an admin account.

Useful starting points are `GET /api/libraries`, `GET /api/items`,
`GET /api/home`, and `POST /api/playback/plan`. The route list is in
[internal/server/server.go](internal/server/server.go).
