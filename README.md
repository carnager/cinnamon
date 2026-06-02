# Popcorn

Popcorn is a small Jellyfin-adjacent media daemon. The first version focuses on a clean API, fast local scans, direct playback, ffmpeg transcoding, and a built-in web UI.

## Run

```sh
cp config.example.json config.json
$EDITOR config.json
go run ./cmd/popcornd -config config.json
```

Open `http://localhost:8097`.

For scanner diagnostics, run with debug logging:

```sh
POPCORN_LOG_LEVEL=debug go run ./cmd/popcornd -config config.json
```

Build a daemon binary with:

```sh
go build -o popcornd ./cmd/popcornd
```

A starter systemd unit is in `packaging/popcornd.service`.

You can also configure it with environment variables:

```sh
POPCORN_LIBRARY=/media/movies POPCORN_LISTEN=:8097 go run ./cmd/popcornd
```

For a single TV library through env vars, add `POPCORN_LIBRARY_TYPE=tv`.

## API

- `GET /api/health`
- `GET /api/libraries`
- `POST /api/scan`
- `GET /api/scan`
- `GET /api/items?libraryId=movies&q=alien&genre=Sci-Fi&limit=100&offset=0`
- `GET /api/genres?libraryId=movies`
- `GET /api/items/{id}`
- `GET /api/progress`
- `GET /api/progress/tv`
- `GET /api/items/{id}/progress`
- `PUT /api/items/{id}/progress`
- `GET /api/watchlist`
- `PUT /api/items/{id}/watchlist`
- `DELETE /api/items/{id}/watchlist`
- `PUT /api/watchlist/tv?libraryId=tv_shows&showTitle=Example`
- `DELETE /api/watchlist/tv?libraryId=tv_shows&showTitle=Example`
- `GET /api/items/{id}/stream`
- `GET /api/items/{id}/transcode?bandwidth=6000`
- `GET /api/items/{id}/image/poster`
- `GET /api/items/{id}/image/backdrop`
- `GET /api/trakt/status`
- `POST /api/trakt/device`
- `POST /api/trakt/device/token`
- `POST /api/trakt/import-watched`
- `POST /api/trakt/import-watchlist`
- `DELETE /api/trakt`

Progress, watchlist, and Trakt routes are per authenticated user.

## Transcoding

Transcoding streams fragmented MP4 from ffmpeg. Set `hwAccel` to:

- `none` for CPU encode with `libx264`
- `auto` for automatic hardware decode and CPU encode
- `nvenc` for NVIDIA H.264 encode
- `qsv` for Intel Quick Sync H.264 encode
- `vaapi` for VAAPI H.264 encode. Popcorn uses software decode plus VAAPI encode for this mode because it is more reliable across mixed source codecs.

For VAAPI, set `hwDevice` to the render node, usually `/dev/dri/renderD128`.

The web UI lets you pick fixed target bandwidths before playback.

## Metadata

The scanner expects TinyMediaManager-style local assets. It reads title/year from sidecar `.nfo` files and looks for common poster/backdrop names next to each video.

Libraries must declare a type:

```json
{ "id": "movies", "name": "Movies", "type": "movies", "path": "/media/movies" }
{ "id": "tv", "name": "TV Shows", "type": "tv", "path": "/media/tv" }
```

TV libraries scan videos as episodes. Popcorn reads `showtitle`, `season`, `episode`, and episode `title` from episode `.nfo` files, falls back to `tvshow.nfo` for the show title, and then falls back to the first folder below the library root.

Scans are incremental. Popcorn still walks the library so deletes are noticed, but it reuses stored ffprobe data for unchanged files and probes new or changed files in parallel.

## Seeking

Direct playback uses normal HTTP range requests. Transcoded playback accepts `start` in seconds:

```sh
GET /api/items/{id}/transcode?bandwidth=6000&start=3600
```

The web UI uses that to seek outside the currently buffered transcode output by restarting ffmpeg at the selected movie timestamp.

## Trakt

Popcorn ships with Trakt app credentials for the device-auth flow. You can override them with `traktClientId` and `traktClientSecret` in `config.json` or with `POPCORN_TRAKT_CLIENT_ID` and `POPCORN_TRAKT_CLIENT_SECRET`.

Each Popcorn user connects Trakt separately:

1. `POST /api/trakt/device` returns the user code and verification URL.
2. After the user approves it on Trakt, poll `POST /api/trakt/device/token` with `{"deviceCode":"..."}`.
3. Playback progress updates will save local resume state. Start, pause, and finished events are also sent to Trakt when that user has connected an account.

To import existing Trakt watched status for a linked Popcorn user:

```sh
./scripts/import-trakt-watched.sh
```

To import an existing Trakt watchlist:

```sh
./scripts/import-trakt-watchlist.sh
```
