# Arch Package

Build and install from a checkout:

```sh
cd packaging/arch
makepkg -si
```

By default the PKGBUILD packages the repository two directories above this file.
Set `POPCORN_SRC=/path/to/popcorn` to package a different checkout.

After install:

```sh
sudoedit /etc/popcorn/config.toml
sudo systemctl enable --now popcornd
popcorn-mpv -selector rofi
```

The `popcorn-mpv` client also supports `-selector auto` and `-selector fzf`.
