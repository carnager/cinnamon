package web

import "embed"

//go:embed index.html app.js views.js playback.js styles.css theater.css hls.min.js remote.html remote.js fonts
var Files embed.FS
