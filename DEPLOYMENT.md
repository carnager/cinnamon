# Deployment Notes

## Release Bundle

Build a release bundle from the repo root:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk scripts/release
```

Use Java 21 for Android builds. Java 26 currently trips Android's JDK image
transform during APK builds.

The script writes everything to:

```text
dist/<git-version>/
```

The Android version defaults are:

- `versionCode`: `date -u +%s / 60`
- `versionName`: `<git-version>`

Override them only when needed:

```sh
POPCORN_ANDROID_VERSION_CODE=29676778 \
POPCORN_ANDROID_VERSION_NAME=v0.1.0-39-g6ce8f7d-dirty \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk scripts/release
```

## Release File Names

The release script creates:

```text
dist/<git-version>/popcornd
dist/<git-version>/popcorn-tv-<git-version>.apk
dist/<git-version>/popcorn-companion-<git-version>.apk
dist/<git-version>/config.example.toml
dist/<git-version>/popcornd.service
dist/<git-version>/SHA256SUMS
```

Example:

```text
dist/v0.1.0-39-g6ce8f7d-dirty/popcorn-tv-v0.1.0-39-g6ce8f7d-dirty.apk
dist/v0.1.0-39-g6ce8f7d-dirty/popcorn-companion-v0.1.0-39-g6ce8f7d-dirty.apk
```

## APK Updates

Do not make `scripts/release` upload APKs to the server. It only builds them and prints the local paths.

Upload APKs manually in the web admin settings, App Updates section:

- Android TV: upload `dist/<git-version>/popcorn-tv-<git-version>.apk`
- Phone Companion: upload `dist/<git-version>/popcorn-companion-<git-version>.apk`

On the server, uploaded APKs are stored next to the database under:

```text
<database-dir>/updates/
```

For the `gemenon` deployment this is:

```text
/home/carnager/.local/share/popcorn/updates/
```

This directory is storage for the web admin upload handler only. Do not copy
APKs there manually as a release step. A copied file will not be offered to
clients unless an app update record/config entry also points at it.

Uploaded APKs are renamed by the server to:

```text
popcorn-tv-<versionCode>.apk
popcorn-companion-<versionCode>.apk
```

The upload handler also writes the active update metadata back into:

```text
/home/carnager/.config/popcorn/popcornd.toml
```

## Server Deployment On Gemenon

The user service runs:

```text
/home/carnager/.local/bin/popcornd -config /home/carnager/.config/popcorn/popcornd.toml
```

Runtime paths:

```text
config:   /home/carnager/.config/popcorn/popcornd.toml
database: /home/carnager/.local/share/popcorn/database.db
updates:  /home/carnager/.local/share/popcorn/updates/
service:  /home/carnager/.config/systemd/user/popcornd.service
binary:   /home/carnager/.local/bin/popcornd
```

Deploy a new server binary without racing the running executable:

```sh
scp dist/<git-version>/popcornd gemenon:/home/carnager/.local/bin/popcornd.new
ssh gemenon 'set -e
systemctl --user stop popcornd
mv /home/carnager/.local/bin/popcornd.new /home/carnager/.local/bin/popcornd
chmod +x /home/carnager/.local/bin/popcornd
systemctl --user start popcornd
sleep 1
systemctl --user --no-pager --full status popcornd | sed -n "1,12p"'
```

Check logs:

```sh
ssh gemenon 'journalctl --user -u popcornd -n 80 --no-pager'
```
