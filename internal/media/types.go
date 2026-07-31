package media

type Item struct {
	ID                int64           `json:"id"`
	LibraryID         string          `json:"libraryId"`
	Path              string          `json:"-"`
	Kind              string          `json:"kind"`
	Title             string          `json:"title"`
	SortTitle         string          `json:"sortTitle"`
	OriginalTitle     string          `json:"originalTitle,omitempty"`
	Year              int             `json:"year,omitempty"`
	DurationMS        int64           `json:"durationMs,omitempty"`
	Container         string          `json:"container,omitempty"`
	VideoCodec        string          `json:"videoCodec,omitempty"`
	AudioCodec        string          `json:"audioCodec,omitempty"`
	IMDbID            string          `json:"imdbId,omitempty"`
	TMDbID            string          `json:"tmdbId,omitempty"`
	TVDbID            string          `json:"tvdbId,omitempty"`
	Width             int             `json:"width,omitempty"`
	Height            int             `json:"height,omitempty"`
	BitRate           int64           `json:"bitRate,omitempty"`
	SizeBytes         int64           `json:"sizeBytes"`
	MTimeUnix         int64           `json:"mtimeUnix"`
	NFOPath           string          `json:"-"`
	NFOMTimeUnix      int64           `json:"nfoMtimeUnix,omitempty"`
	PosterPath        string          `json:"posterPath,omitempty"`
	PosterMTimeUnix   int64           `json:"posterMtimeUnix,omitempty"`
	BackdropPath      string          `json:"backdropPath,omitempty"`
	BackdropMTimeUnix int64           `json:"backdropMtimeUnix,omitempty"`
	Overview          string          `json:"overview,omitempty"`
	Tagline           string          `json:"tagline,omitempty"`
	OfficialRating    string          `json:"officialRating,omitempty"`
	Genres            string          `json:"genres,omitempty"`
	Tags              string          `json:"tags,omitempty"`
	Studios           string          `json:"studios,omitempty"`
	Directors         string          `json:"directors,omitempty"`
	Writers           string          `json:"writers,omitempty"`
	Countries         string          `json:"countries,omitempty"`
	Rating            float64         `json:"rating,omitempty"`
	Premiered         string          `json:"premiered,omitempty"`
	ShowTitle         string          `json:"showTitle,omitempty"`
	SeasonNumber      int             `json:"seasonNumber,omitempty"`
	EpisodeNumber     int             `json:"episodeNumber,omitempty"`
	EpisodeTitle      string          `json:"episodeTitle,omitempty"`
	Actors            []Actor         `json:"actors,omitempty"`
	Streams           []MediaStream   `json:"-"`
	StreamsKnown      bool            `json:"-"`
	ShowMetadata      *ShowMetadata   `json:"-"`
	SeasonMetadata    *SeasonMetadata `json:"-"`
}

type MediaProbe struct {
	DurationMS int64
	BitRate    int64
	Streams    []MediaStream
}

type MediaStream struct {
	ItemID          int64  `json:"-"`
	Index           int    `json:"index"`
	Type            string `json:"type"`
	Codec           string `json:"codec,omitempty"`
	CodecLongName   string `json:"codecLongName,omitempty"`
	Profile         string `json:"profile,omitempty"`
	Level           int    `json:"level,omitempty"`
	Width           int    `json:"width,omitempty"`
	Height          int    `json:"height,omitempty"`
	PixelFormat     string `json:"pixFmt,omitempty"`
	ColorRange      string `json:"colorRange,omitempty"`
	ColorSpace      string `json:"colorSpace,omitempty"`
	ColorTransfer   string `json:"colorTransfer,omitempty"`
	ColorPrimaries  string `json:"colorPrimaries,omitempty"`
	HDRFormat       string `json:"hdrFormat,omitempty"`
	BitRate         int64  `json:"bitRate,omitempty"`
	Channels        int    `json:"channels,omitempty"`
	ChannelLayout   string `json:"channelLayout,omitempty"`
	SampleRate      int    `json:"sampleRate,omitempty"`
	Language        string `json:"language,omitempty"`
	Title           string `json:"title,omitempty"`
	Default         bool   `json:"default,omitempty"`
	Forced          bool   `json:"forced,omitempty"`
	DispositionJSON string `json:"-"`
	RawJSON         string `json:"-"`
}

type Actor struct {
	Name  string `json:"name"`
	Role  string `json:"role,omitempty"`
	Thumb string `json:"thumb,omitempty"`
	Order int    `json:"order,omitempty"`
}

type ActorInfo struct {
	Name               string `json:"name"`
	TMDbID             string `json:"tmdbId,omitempty"`
	IMDbID             string `json:"imdbId,omitempty"`
	Biography          string `json:"biography,omitempty"`
	Birthday           string `json:"birthday,omitempty"`
	Deathday           string `json:"deathday,omitempty"`
	PlaceOfBirth       string `json:"placeOfBirth,omitempty"`
	KnownForDepartment string `json:"knownForDepartment,omitempty"`
	ProfilePath        string `json:"profilePath,omitempty"`
	Source             string `json:"source,omitempty"`
	FetchedAt          string `json:"fetchedAt,omitempty"`
}

type NFOExtras struct {
	Tagline        string
	OfficialRating string
	Tags           []string
	Studios        []string
	Directors      []string
	Writers        []string
	Countries      []string
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
	EndYear           int     `json:"endYear,omitempty"`
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
	Actors            []Actor `json:"actors,omitempty"`
}

// Recommendation is a server-ranked home suggestion. Exactly one of Item or
// Show is populated.
type Recommendation struct {
	Key    string       `json:"key"`
	Reason string       `json:"reason"`
	Source string       `json:"source"`
	Item   *Item        `json:"item,omitempty"`
	Show   *ShowSummary `json:"show,omitempty"`
}

// RecommendationExclusion is a reversible, per-user "Not interested" entry.
// Path and SizeBytes are informational only; media deletion is intentionally
// not exposed by the server.
type RecommendationExclusion struct {
	Key       string       `json:"key"`
	Kind      string       `json:"kind"`
	Item      *Item        `json:"item,omitempty"`
	Show      *ShowSummary `json:"show,omitempty"`
	Path      string       `json:"path,omitempty"`
	SizeBytes int64        `json:"sizeBytes"`
	CreatedAt string       `json:"createdAt"`
}

type SeasonSummary struct {
	LibraryID       string  `json:"libraryId"`
	ShowTitle       string  `json:"showTitle"`
	SeasonNumber    int     `json:"seasonNumber"`
	Title           string  `json:"title,omitempty"`
	EpisodeCount    int     `json:"episodeCount"`
	DurationMS      int64   `json:"durationMs,omitempty"`
	PosterItemID    int64   `json:"posterItemId,omitempty"`
	PosterPath      string  `json:"posterPath,omitempty"`
	PosterMTimeUnix int64   `json:"posterMtimeUnix,omitempty"`
	BackdropItemID  int64   `json:"backdropItemId,omitempty"`
	Overview        string  `json:"overview,omitempty"`
	Rating          float64 `json:"rating,omitempty"`
	Premiered       string  `json:"premiered,omitempty"`
	Actors          []Actor `json:"actors,omitempty"`
}

type ShowMetadata struct {
	LibraryID     string
	Title         string
	SortTitle     string
	OriginalTitle string
	Year          int
	NFOPath       string
	NFOMTimeUnix  int64
	Overview      string
	Genres        string
	Rating        float64
	Premiered     string
	Actors        []Actor
}

type SeasonMetadata struct {
	LibraryID       string
	ShowTitle       string
	SeasonNumber    int
	Title           string
	NFOPath         string
	NFOMTimeUnix    int64
	PosterPath      string
	PosterMTimeUnix int64
	Overview        string
	Rating          float64
	Premiered       string
	Actors          []Actor
}

type AlphabetEntry struct {
	Letter string `json:"letter"`
	Offset int    `json:"offset"`
	Count  int    `json:"count"`
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
	LastWatched      string `json:"lastWatched,omitempty"`
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

// UserRating is one personal rating (1-10, Trakt scale): either an item
// (movie/episode, ItemID set) or a show (LibraryID+ShowTitle set).
type UserRating struct {
	Kind      string `json:"kind"`
	ItemID    int64  `json:"itemId,omitempty"`
	LibraryID string `json:"libraryId,omitempty"`
	ShowTitle string `json:"showTitle,omitempty"`
	Rating    int    `json:"rating"`
}

type SourceRatings struct {
	IMDb           float64
	TMDb           float64
	RottenTomatoes int
	Metacritic     int
}
