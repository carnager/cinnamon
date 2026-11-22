package web

import "embed"

// app is the web client; the rest are files it (or /remote) loads by fixed URL.
//
//go:embed app hls.min.js remote.html remote.js fonts jassub
var Files embed.FS
