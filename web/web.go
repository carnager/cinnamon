package web

import "embed"

//go:embed index.html app.js views.js styles.css hls.min.js remote.html remote.js
var Files embed.FS
