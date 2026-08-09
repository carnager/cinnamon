package web

import "embed"

//go:embed index.html app.js views.js playback.js thumbhash.js styles.css theater.css hls.min.js remote.html remote.js fonts jassub
var Files embed.FS
