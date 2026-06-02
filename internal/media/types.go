package media

type Item struct {
	ID                int64   `json:"id"`
	LibraryID         string  `json:"libraryId"`
	Path              string  `json:"-"`
	Kind              string  `json:"kind"`
	Title             string  `json:"title"`
	SortTitle         string  `json:"sortTitle"`
	OriginalTitle     string  `json:"originalTitle,omitempty"`
	Year              int     `json:"year,omitempty"`
	DurationMS        int64   `json:"durationMs,omitempty"`
	Container         string  `json:"container,omitempty"`
	VideoCodec        string  `json:"videoCodec,omitempty"`
	AudioCodec        string  `json:"audioCodec,omitempty"`
	IMDbID            string  `json:"imdbId,omitempty"`
	TMDbID            string  `json:"tmdbId,omitempty"`
	TVDbID            string  `json:"tvdbId,omitempty"`
	Width             int     `json:"width,omitempty"`
	Height            int     `json:"height,omitempty"`
	SizeBytes         int64   `json:"sizeBytes"`
	MTimeUnix         int64   `json:"mtimeUnix"`
	NFOPath           string  `json:"-"`
	NFOMTimeUnix      int64   `json:"nfoMtimeUnix,omitempty"`
	PosterPath        string  `json:"posterPath,omitempty"`
	PosterMTimeUnix   int64   `json:"posterMtimeUnix,omitempty"`
	BackdropPath      string  `json:"backdropPath,omitempty"`
	BackdropMTimeUnix int64   `json:"backdropMtimeUnix,omitempty"`
	Overview          string  `json:"overview,omitempty"`
	Tagline           string  `json:"tagline,omitempty"`
	Genres            string  `json:"genres,omitempty"`
	Rating            float64 `json:"rating,omitempty"`
	Premiered         string  `json:"premiered,omitempty"`
	ShowTitle         string  `json:"showTitle,omitempty"`
	SeasonNumber      int     `json:"seasonNumber,omitempty"`
	EpisodeNumber     int     `json:"episodeNumber,omitempty"`
	EpisodeTitle      string  `json:"episodeTitle,omitempty"`
}

type ScanStatus struct {
	LibraryID     string `json:"libraryId"`
	StartedAt     string `json:"startedAt,omitempty"`
	FinishedAt    string `json:"finishedAt,omitempty"`
	Status        string `json:"status"`
	Message       string `json:"message,omitempty"`
	FilesSeen     int64  `json:"filesSeen"`
	MediaFound    int64  `json:"mediaFound"`
	ItemsImported int64  `json:"itemsImported"`
	FilesSkipped  int64  `json:"filesSkipped"`
	Errors        int64  `json:"errors"`
}

type ShowSummary struct {
	LibraryID         string  `json:"libraryId"`
	Title             string  `json:"title"`
	SortTitle         string  `json:"sortTitle"`
	OriginalTitle     string  `json:"originalTitle,omitempty"`
	Year              int     `json:"year,omitempty"`
	EpisodeCount      int     `json:"episodeCount"`
	SeasonCount       int     `json:"seasonCount"`
	PosterItemID      int64   `json:"posterItemId,omitempty"`
	PosterMTimeUnix   int64   `json:"posterMtimeUnix,omitempty"`
	BackdropItemID    int64   `json:"backdropItemId,omitempty"`
	BackdropMTimeUnix int64   `json:"backdropMtimeUnix,omitempty"`
	Overview          string  `json:"overview,omitempty"`
	Genres            string  `json:"genres,omitempty"`
	Rating            float64 `json:"rating,omitempty"`
	Premiered         string  `json:"premiered,omitempty"`
}

type SeasonSummary struct {
	LibraryID       string  `json:"libraryId"`
	ShowTitle       string  `json:"showTitle"`
	SeasonNumber    int     `json:"seasonNumber"`
	Title           string  `json:"title,omitempty"`
	EpisodeCount    int     `json:"episodeCount"`
	DurationMS      int64   `json:"durationMs,omitempty"`
	PosterItemID    int64   `json:"posterItemId,omitempty"`
	PosterMTimeUnix int64   `json:"posterMtimeUnix,omitempty"`
	BackdropItemID  int64   `json:"backdropItemId,omitempty"`
	Overview        string  `json:"overview,omitempty"`
	Rating          float64 `json:"rating,omitempty"`
	Premiered       string  `json:"premiered,omitempty"`
}

type PlaybackProgress struct {
	ItemID     int64  `json:"itemId"`
	PositionMS int64  `json:"positionMs"`
	DurationMS int64  `json:"durationMs"`
	Completed  bool   `json:"completed"`
	UpdatedAt  string `json:"updatedAt,omitempty"`
}

type ShowProgress struct {
	LibraryID        string `json:"libraryId"`
	ShowTitle        string `json:"showTitle"`
	EpisodeCount     int    `json:"episodeCount"`
	CompletedCount   int    `json:"completedCount"`
	Completed        bool   `json:"completed"`
	HasAnyCompletion bool   `json:"hasAnyCompletion"`
}

type TraktAccount struct {
	UserID       int64  `json:"-"`
	AccessToken  string `json:"-"`
	RefreshToken string `json:"-"`
	ExpiresAt    string `json:"expiresAt,omitempty"`
	CreatedAt    string `json:"createdAt,omitempty"`
	UpdatedAt    string `json:"updatedAt,omitempty"`
}

type Watchlist struct {
	Items []Item        `json:"items"`
	Shows []ShowSummary `json:"shows"`
}

type SourceRatings struct {
	IMDb           float64
	TMDb           float64
	RottenTomatoes int
	Metacritic     int
}
