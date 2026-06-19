# popcorn-watch

`popcorn-watch` runs on the storage host (e.g. TrueNAS SCALE) and watches the
media datasets with local filesystem events (`inotify`). When something changes
it tells the popcorn server to incrementally scan only the directory that
changed — no polling of a network mount, so new files appear within seconds
regardless of library size.

```
storage host (local fs events)              popcorn server
  popcorn-watch ──debounce──► POST /api/scan/path ──► scan just that folder
```

## Configuration

Flags (or the matching environment variables):

| flag         | env                       | description                                                        |
|--------------|---------------------------|--------------------------------------------------------------------|
| `-server`    | `POPCORN_WATCH_SERVER`    | popcorn base URL, e.g. `http://gemenon:8097`                        |
| `-user`      | `POPCORN_WATCH_USER`      | popcorn **admin** username                                          |
| `-password`  | `POPCORN_WATCH_PASSWORD`  | popcorn admin password                                             |
| `-watch`     | `POPCORN_WATCH_DIRS`      | comma-separated **local** directories to watch                     |
| `-map`       | `POPCORN_WATCH_MAP`       | comma-separated `local=popcorn` path-prefix maps                   |
| `-debounce`  | `POPCORN_WATCH_DEBOUNCE`  | coalesce window (default `2s`)                                      |

`-map` translates the local path the watcher sees into the path the popcorn
server uses for its libraries. Example: the dataset is `/mnt/tank/media/movies`
on TrueNAS but popcorn's library `path` is `/nas/movies`, so:

```
-map "/mnt/tank/media/movies=/nas/movies,/mnt/tank/media/tv=/nas/tv"
```

If the watcher and server see identical paths, omit `-map`.

## Running on TrueNAS SCALE (Docker / custom app)

> TrueNAS SCALE has a **read-only root filesystem**: you cannot place the binary
> in `/usr/local/bin` or install a persistent host systemd unit. Keep the binary
> on a dataset (e.g. `/mnt/<pool>/.../popcorn-watch`, `chmod +x` it) and run it
> from a container/app (durable) or a Post-Init script. The static build means
> it runs as-is from a `scratch`/`alpine` image too.

A minimal compose service (bind-mount the datasets **read-only**). The watch
dirs use the in-container paths, and `-map` translates those to popcorn's paths:

```yaml
services:
  popcorn-watch:
    image: golang:1-alpine        # or any image containing the static binary
    command: /popcorn-watch
    restart: unless-stopped
    environment:
      POPCORN_WATCH_SERVER: http://gemenon:8097
      POPCORN_WATCH_USER: admin
      POPCORN_WATCH_PASSWORD: "..."
      POPCORN_WATCH_DIRS: /media/movies,/media/tv
      POPCORN_WATCH_MAP: /media/movies=/nas/movies,/media/tv=/nas/tv
    volumes:
      - /host/popcorn-watch:/popcorn-watch:ro   # the built binary
      - /mnt/tank/media/movies:/media/movies:ro
      - /mnt/tank/media/tv:/media/tv:ro
```

`inotify` events propagate through Linux bind mounts, so watching `/media/...`
inside the container sees host writes. Note the `-map` here translates the
in-container path to the popcorn library path.

## Running as a systemd service (generic Linux host — NOT TrueNAS SCALE)

```ini
[Unit]
Description=popcorn filesystem watcher
After=network-online.target

[Service]
ExecStart=/usr/local/bin/popcorn-watch
Environment=POPCORN_WATCH_SERVER=http://gemenon:8097
Environment=POPCORN_WATCH_USER=admin
Environment=POPCORN_WATCH_PASSWORD=...
Environment=POPCORN_WATCH_DIRS=/mnt/tank/media/movies,/mnt/tank/media/tv
Environment=POPCORN_WATCH_MAP=/mnt/tank/media/movies=/nas/movies,/mnt/tank/media/tv=/nas/tv
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

## Note on inotify watch limits

One watch is used per directory. Large libraries can exceed the kernel default
(`fs.inotify.max_user_watches`, often 8192). Raise it if the watcher logs
"no space left on device" when adding watches:

```
sysctl -w fs.inotify.max_user_watches=524288
```
