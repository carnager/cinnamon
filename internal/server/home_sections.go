package server

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"popcorn/internal/config"
	"popcorn/internal/media"
)

// One layout per user, shared by every client they sign in from. Users who
// have never customised it get defaultHomeLayout.
const (
	homeLayoutMaxSections = 30
	homeSectionMaxLimit   = 60
	homeSectionLimit      = 24
)

// homeSectionBuilder fills one section. Returning a section with no content
// drops it from the response: clients hide empty shelves anyway, and a home
// payload is already large enough.
type homeSectionBuilder func(homeSectionScope, media.HomeLayoutSection) (media.HomeSection, error)

type homeSectionScope struct {
	ctx      context.Context
	app      *App
	userID   int64
	movieLib *config.Library
	tvLib    *config.Library
	payload  *homePayload
}

type homeSectionDef struct {
	media.HomeSectionType
	title string
	build homeSectionBuilder
}

func homeSectionDefs() map[string]homeSectionDef {
	limitParam := media.HomeSectionParam{
		Name: "limit", Label: "Items", Type: "int", Suffix: "items",
		Choices: []string{"10", "16", "24", "40", "60"},
		Default: strconv.Itoa(homeSectionLimit),
	}
	defs := []homeSectionDef{
		{
			HomeSectionType: media.HomeSectionType{
				Type: "recommendations", Label: "Recommended for you", Layout: "hero", Kind: "recommendation",
				Description: "The ranked feed: continue watching, because you watched, new arrivals, watchlist.",
				Params:      []media.HomeSectionParam{limitParam},
			},
			title: "Recommended for you",
			build: func(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
				entries := scope.payload.Recommendations
				return media.HomeSection{Entries: entries[:min(len(entries), sectionLimit(cfg))]}, nil
			},
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "continue_movies", Label: "Continue Movies", Layout: "progress", Kind: "movie",
				Params: []media.HomeSectionParam{limitParam},
			},
			title: "Continue Movies",
			build: func(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
				items := scope.payload.ContinueMovies
				return media.HomeSection{
					Items:    items[:min(len(items), sectionLimit(cfg))],
					Subtitle: countLabel(len(items), "in progress"),
					More:     "continue/movies",
				}, nil
			},
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "continue_tv", Label: "Continue TV", Layout: "progress", Kind: "episode",
				Params: []media.HomeSectionParam{limitParam},
			},
			title: "Continue TV",
			build: func(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
				items := scope.payload.ContinueEpisodes
				return media.HomeSection{
					Items:    items[:min(len(items), sectionLimit(cfg))],
					Subtitle: countLabel(len(items), "episodes"),
					More:     "continue/tv",
				}, nil
			},
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "recent_movies", Label: "Recently Added Movies", Layout: "poster", Kind: "movie",
				Params: []media.HomeSectionParam{limitParam},
			},
			title: "Recently Added Movies",
			build: func(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
				items := scope.payload.RecentMovies
				return media.HomeSection{
					Items:    items[:min(len(items), sectionLimit(cfg))],
					Subtitle: countLabel(len(items), "new"),
					More:     "recent/movies",
				}, nil
			},
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "recent_tv", Label: "Recently Added TV", Layout: "poster", Kind: "show",
				Params: []media.HomeSectionParam{limitParam},
			},
			title: "Recently Added TV",
			build: func(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
				shows := scope.payload.RecentShows
				return media.HomeSection{
					Shows:    shows[:min(len(shows), sectionLimit(cfg))],
					Subtitle: countLabel(len(shows), "shows"),
					More:     "recent/tv",
				}, nil
			},
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "watchlist_movies", Label: "Watchlist Movies", Layout: "poster", Kind: "movie",
				Params: []media.HomeSectionParam{limitParam},
			},
			title: "Your Watchlist",
			build: func(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
				items := make([]media.Item, 0, len(scope.payload.Watchlist.Items))
				for _, item := range scope.payload.Watchlist.Items {
					if item.Kind == "movie" {
						items = append(items, item)
					}
				}
				return media.HomeSection{
					Items:    items[:min(len(items), sectionLimit(cfg))],
					Subtitle: countLabel(len(items), "saved"),
					More:     "watchlist",
				}, nil
			},
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "watchlist_tv", Label: "Watchlist TV", Layout: "poster", Kind: "show",
				Params: []media.HomeSectionParam{limitParam},
			},
			title: "Your Watchlist · TV",
			build: func(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
				shows := scope.payload.Watchlist.Shows
				return media.HomeSection{
					Shows:    shows[:min(len(shows), sectionLimit(cfg))],
					Subtitle: countLabel(len(shows), "saved"),
					More:     "watchlist",
				}, nil
			},
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "top_rated_movies", Label: "Top Rated Movies", Layout: "poster", Kind: "movie",
				Description: "Highest rated movies you have not finished.",
				Params:      []media.HomeSectionParam{limitParam},
			},
			title: "Top Rated Movies",
			build: func(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
				if scope.movieLib == nil {
					return media.HomeSection{}, nil
				}
				items, err := scope.app.store.ListItemsForUser(scope.ctx, scope.movieLib.ID, "", "", "", "rating", "unseen", scope.userID, 0, sectionLimit(cfg), 0)
				if err != nil {
					return media.HomeSection{}, err
				}
				return media.HomeSection{Items: items}, nil
			},
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "top_rated_tv", Label: "Top Rated TV", Layout: "poster", Kind: "show",
				Description: "Highest rated shows with episodes left to watch.",
				Params:      []media.HomeSectionParam{limitParam},
			},
			title: "Top Rated TV",
			build: func(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
				if scope.tvLib == nil {
					return media.HomeSection{}, nil
				}
				shows, err := scope.app.store.ListShowsForUser(scope.ctx, scope.tvLib.ID, "", "", "", "rating", "unseen", scope.userID, 0, sectionLimit(cfg), 0)
				if err != nil {
					return media.HomeSection{}, err
				}
				return media.HomeSection{Shows: shows}, nil
			},
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "surprise", Label: "Surprise me", Layout: "poster", Kind: "movie",
				Description: "A different handful of unwatched titles on every visit.",
				Params: []media.HomeSectionParam{
					{Name: "kind", Label: "Media", Type: "enum", Options: []string{"movies", "tv"}, Default: "movies"},
					limitParam,
				},
			},
			title: "Surprise me",
			build: buildSurpriseSection,
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "new_episodes", Label: "New episodes for you", Layout: "poster", Kind: "episode",
				Description: "Recently added episodes of shows you have already started.",
				Params:      []media.HomeSectionParam{limitParam},
			},
			title: "New episodes for you",
			build: buildNewEpisodesSection,
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "because_you_watched", Label: "Because you watched…", Layout: "poster", Kind: "movie",
				Description: "Titles TMDb ranks alongside the last film you finished.",
				Params:      []media.HomeSectionParam{limitParam},
			},
			title: "Because you watched",
			build: buildBecauseYouWatchedSection,
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "gathering_dust", Label: "Gathering dust", Layout: "poster", Kind: "movie",
				Description: "The oldest files in your library that you have never watched.",
				Params: []media.HomeSectionParam{
					{Name: "kind", Label: "Media", Type: "enum", Options: []string{"movies", "tv"}, Default: "movies"},
					limitParam,
				},
			},
			title: "Gathering dust",
			build: buildGatheringDustSection,
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "watchlist_waiting", Label: "Longest on your watchlist", Layout: "poster", Kind: "movie",
				Description: "What you saved and never got round to, oldest first.",
				Params:      []media.HomeSectionParam{limitParam},
			},
			title: "Longest on your watchlist",
			build: buildWatchlistWaitingSection,
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "filter", Label: "Filter shelf", Layout: "poster", Kind: "movie", Repeatable: true,
				Description: "A saved search: any mix of genre, decade, country, studio, runtime and rating.",
				Params: []media.HomeSectionParam{
					{Name: "kind", Label: "Media", Type: "enum", Options: []string{"movies", "tv"}, Default: "movies"},
					{Name: "genre", Label: "Genre", Type: "string", Multi: true},
					{Name: "decades", Label: "Decade", Type: "string", Multi: true},
					{Name: "country", Label: "Country", Type: "string", Multi: true},
					{Name: "studio", Label: "Studio", Type: "string", Multi: true},
					{Name: "certificate", Label: "Rated", Type: "string", Multi: true},
					{Name: "maxMinutes", Label: "Max length", Type: "int", Suffix: "min", Choices: []string{"", "45", "60", "75", "90", "105", "120", "150"}},
					{Name: "minRating", Label: "Min rating", Type: "number", Suffix: "and up", Choices: []string{"", "5", "6", "6.5", "7", "7.5", "8", "8.5"}},
					{Name: "sort", Label: "Sort", Type: "enum", Options: []string{"rating", "mtime", "year_desc", "title", "random"}, Default: "rating"},
					{Name: "seen", Label: "Watched", Type: "enum", Options: []string{"any", "unseen", "seen"}, Default: "unseen"},
					limitParam,
				},
			},
			build: buildFilterSection,
		},
		{
			HomeSectionType: media.HomeSectionType{
				Type: "genre", Label: "Genre shelf", Layout: "poster", Kind: "movie", Repeatable: true,
				Description: "One shelf for a single genre, e.g. everything filed under Horror.",
				Params: []media.HomeSectionParam{
					{Name: "genre", Label: "Genre", Type: "string", Required: true},
					{Name: "kind", Label: "Media", Type: "enum", Options: []string{"movies", "tv"}, Default: "movies"},
					{Name: "sort", Label: "Sort", Type: "enum", Options: []string{"rating", "mtime", "year_desc", "title"}, Default: "rating"},
					{Name: "seen", Label: "Watched", Type: "enum", Options: []string{"any", "unseen"}, Default: "unseen"},
					limitParam,
				},
			},
			build: buildGenreSection,
		},
	}
	out := make(map[string]homeSectionDef, len(defs))
	for _, def := range defs {
		out[def.Type] = def
	}
	return out
}

// Shows the user has actually started, newest episodes first. The started set
// comes from the progress already loaded for this request.
func buildNewEpisodesSection(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
	started := make([]media.ShowProgress, 0, len(scope.payload.ShowProgress))
	for _, show := range scope.payload.ShowProgress {
		if show.CompletedCount > 0 {
			started = append(started, show)
		}
	}
	if len(started) == 0 {
		return media.HomeSection{}, nil
	}
	episodes, err := scope.app.store.RecentEpisodesForShows(scope.ctx, scope.userID, started, sectionLimit(cfg))
	if err != nil {
		return media.HomeSection{}, err
	}
	return media.HomeSection{Items: episodes, More: "continue/tv"}, nil
}

// The same warmed TMDb list the hero draws from, as a shelf of its own with the
// anchor in its title. Empty until the background worker has the answer — home
// never waits on TMDb.
func buildBecauseYouWatchedSection(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
	anchors := scope.app.anchorsFromProgress(scope.ctx, scope.payload.Progress, recommendationAnchorCandidates)
	anchor, candidates, ok := scope.app.similarFromAnchors(anchors)
	if !ok {
		return media.HomeSection{}, nil
	}
	completed := completedItemIDs(scope.payload.Progress)
	out := make([]media.Item, 0, sectionLimit(cfg))
	for _, candidate := range candidates {
		if completed[candidate.ID] {
			continue
		}
		out = append(out, candidate)
		if len(out) >= sectionLimit(cfg) {
			break
		}
	}
	if len(out) == 0 {
		return media.HomeSection{}, nil
	}
	return media.HomeSection{Title: fmt.Sprintf("Because you watched %s", anchor.Title), Items: out}, nil
}

func buildGatheringDustSection(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
	limit := sectionLimit(cfg)
	if paramOrDefault(cfg, "kind", "movies") == "tv" {
		if scope.tvLib == nil {
			return media.HomeSection{}, nil
		}
		shows, err := scope.app.store.ListShowsForUser(scope.ctx, scope.tvLib.ID, "", "", "", "mtime_asc", "unseen", scope.userID, 0, limit, 0)
		if err != nil {
			return media.HomeSection{}, err
		}
		return media.HomeSection{Kind: "show", Shows: shows}, nil
	}
	if scope.movieLib == nil {
		return media.HomeSection{}, nil
	}
	items, err := scope.app.store.ListItemsForUser(scope.ctx, scope.movieLib.ID, "", "", "", "mtime_asc", "unseen", scope.userID, 0, limit, 0)
	if err != nil {
		return media.HomeSection{}, err
	}
	return media.HomeSection{Items: items}, nil
}

func buildWatchlistWaitingSection(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
	items, err := scope.app.store.ListWatchlistItemsByAge(scope.ctx, scope.userID, sectionLimit(cfg))
	if err != nil {
		return media.HomeSection{}, err
	}
	return media.HomeSection{Items: items, More: "watchlist"}, nil
}

// One section type behind every "everything that is X" shelf. Genre shelves are
// the same query with one parameter set; this one exposes the rest.
func buildFilterSection(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
	sortMode := paramOrDefault(cfg, "sort", "rating")
	seen := paramOrDefault(cfg, "seen", "unseen")
	if seen == "any" {
		seen = ""
	}
	minRating, _ := strconv.ParseFloat(strings.TrimSpace(cfg.Params["minRating"]), 64)
	maxMinutes, _ := strconv.Atoi(strings.TrimSpace(cfg.Params["maxMinutes"]))
	opts := media.SearchOptions{
		Genre:          strings.TrimSpace(cfg.Params["genre"]),
		Decades:        strings.TrimSpace(cfg.Params["decades"]),
		Studio:         strings.TrimSpace(cfg.Params["studio"]),
		Country:        strings.TrimSpace(cfg.Params["country"]),
		ContentRatings: strings.TrimSpace(cfg.Params["certificate"]),
		MaxDurationMS:  int64(maxMinutes) * 60_000,
		Sort:           sortMode,
		SeenStatus:     seen,
		UserID:         scope.userID,
		MinRating:      minRating,
		Limit:          sectionLimit(cfg),
	}
	section := media.HomeSection{Title: filterSectionTitle(cfg)}
	if paramOrDefault(cfg, "kind", "movies") == "tv" {
		if scope.tvLib == nil {
			return media.HomeSection{}, nil
		}
		// A show has no studio or certificate of its own; these match the
		// episodes it is made of, which is where that metadata lives.
		shows, err := scope.app.store.SearchShows(scope.ctx, media.ShowOptions{
			LibraryID:      scope.tvLib.ID,
			Genre:          opts.Genre,
			Decades:        opts.Decades,
			Studio:         opts.Studio,
			Country:        opts.Country,
			ContentRatings: opts.ContentRatings,
			MaxDurationMS:  opts.MaxDurationMS,
			Sort:           sortMode,
			SeenStatus:     seen,
			UserID:         scope.userID,
			MinRating:      minRating,
			Limit:          opts.Limit,
		})
		if err != nil {
			return media.HomeSection{}, err
		}
		section.Kind = "show"
		section.Shows = shows
		return section, nil
	}
	if scope.movieLib == nil {
		return media.HomeSection{}, nil
	}
	opts.LibraryID = scope.movieLib.ID
	opts.Kind = "movie"
	items, err := scope.app.store.SearchItems(scope.ctx, opts)
	if err != nil {
		return media.HomeSection{}, err
	}
	section.Items = items
	return section, nil
}

// A filter shelf names itself after whatever it was narrowed by, so the user
// does not have to title every one by hand.
func filterSectionTitle(cfg media.HomeLayoutSection) string {
	parts := make([]string, 0, 4)
	for _, name := range []string{"decades", "country", "studio", "genre", "certificate"} {
		value := strings.TrimSpace(cfg.Params[name])
		if value == "" {
			continue
		}
		values := strings.Split(value, ",")
		for i, entry := range values {
			entry = strings.TrimSpace(entry)
			if name == "decades" {
				entry += "s"
			}
			values[i] = entry
		}
		parts = append(parts, strings.Join(values, ", "))
	}
	if len(parts) > 0 {
		return strings.Join(parts, " · ")
	}
	if minutes := strings.TrimSpace(cfg.Params["maxMinutes"]); minutes != "" {
		return "Under " + minutes + " minutes"
	}
	if rating := strings.TrimSpace(cfg.Params["minRating"]); rating != "" {
		return "Rated " + rating + " and up"
	}
	// An unnarrowed filter shelf is just the library by whatever it sorts on.
	if paramOrDefault(cfg, "seen", "unseen") == "unseen" {
		return "Still unwatched"
	}
	return "From your library"
}

func completedItemIDs(progress []media.PlaybackProgress) map[int64]bool {
	out := make(map[int64]bool, len(progress))
	for _, entry := range progress {
		if entry.Completed || isFinished(entry.PositionMS, entry.DurationMS) {
			out[entry.ItemID] = true
		}
	}
	return out
}

// The surprise shelf reshuffles on every home build — the response cache keeps
// that to once every 15 seconds rather than once per keypress.
func buildSurpriseSection(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
	limit := sectionLimit(cfg)
	tv := paramOrDefault(cfg, "kind", "movies") == "tv"
	if tv && scope.tvLib == nil {
		return media.HomeSection{}, nil
	}
	if !tv && scope.movieLib == nil {
		return media.HomeSection{}, nil
	}
	// Well-rated picks first, then anything unwatched, so a library of unrated
	// files still fills the shelf.
	for _, minRating := range []float64{surpriseMinRating, 0} {
		if tv {
			shows, err := scope.app.store.ListShowsForUser(scope.ctx, scope.tvLib.ID, "", "", "", "random", "unseen", scope.userID, minRating, limit, 0)
			if err != nil {
				return media.HomeSection{}, err
			}
			if len(shows) > 0 {
				return media.HomeSection{Kind: "show", Shows: shows}, nil
			}
			continue
		}
		items, err := scope.app.store.ListItemsForUser(scope.ctx, scope.movieLib.ID, "", "", "", "random", "unseen", scope.userID, minRating, limit, 0)
		if err != nil {
			return media.HomeSection{}, err
		}
		if len(items) > 0 {
			return media.HomeSection{Items: items}, nil
		}
	}
	return media.HomeSection{}, nil
}

func buildGenreSection(scope homeSectionScope, cfg media.HomeLayoutSection) (media.HomeSection, error) {
	genre := strings.TrimSpace(cfg.Params["genre"])
	if genre == "" {
		return media.HomeSection{}, nil
	}
	sort := paramOrDefault(cfg, "sort", "rating")
	seen := paramOrDefault(cfg, "seen", "unseen")
	if seen == "any" {
		seen = ""
	}
	limit := sectionLimit(cfg)
	section := media.HomeSection{Title: genre}
	if paramOrDefault(cfg, "kind", "movies") == "tv" {
		if scope.tvLib == nil {
			return media.HomeSection{}, nil
		}
		shows, err := scope.app.store.ListShowsForUser(scope.ctx, scope.tvLib.ID, "", genre, "", sort, seen, scope.userID, 0, limit, 0)
		if err != nil {
			return media.HomeSection{}, err
		}
		section.Kind = "show"
		section.Shows = shows
		return section, nil
	}
	if scope.movieLib == nil {
		return media.HomeSection{}, nil
	}
	items, err := scope.app.store.ListItemsForUser(scope.ctx, scope.movieLib.ID, "", genre, "", sort, seen, scope.userID, 0, limit, 0)
	if err != nil {
		return media.HomeSection{}, err
	}
	section.Items = items
	return section, nil
}

// defaultHomeLayout is what every client rendered before layouts existed, so an
// untouched account sees no change.
func defaultHomeLayout() media.HomeLayoutDoc {
	types := []string{
		"recommendations",
		"continue_movies",
		"continue_tv",
		"recent_movies",
		"recent_tv",
		"watchlist_movies",
		"watchlist_tv",
		"top_rated_movies",
		"top_rated_tv",
	}
	sections := make([]media.HomeLayoutSection, 0, len(types))
	for _, name := range types {
		sections = append(sections, media.HomeLayoutSection{ID: name, Type: name, Enabled: true})
	}
	return media.HomeLayoutDoc{Source: "default", Sections: sections}
}

func homeSectionCatalog() []media.HomeSectionType {
	defs := homeSectionDefs()
	out := make([]media.HomeSectionType, 0, len(defs))
	// Catalog order follows the default layout so the editor lists the familiar
	// shelves first, with the repeatable ones after them.
	for _, section := range defaultHomeLayout().Sections {
		if def, ok := defs[section.Type]; ok {
			out = append(out, def.HomeSectionType)
			delete(defs, section.Type)
		}
	}
	for _, name := range sortedKeys(defs) {
		out = append(out, defs[name].HomeSectionType)
	}
	return out
}

// resolveHomeLayout returns the user's stored layout, or the built-in default
// when they have never saved one.
func (a *App) resolveHomeLayout(ctx context.Context, userID int64) (media.HomeLayoutDoc, error) {
	doc, err := a.store.HomeLayout(ctx, userID)
	if err != nil {
		return media.HomeLayoutDoc{}, err
	}
	if strings.TrimSpace(doc) == "" {
		return defaultHomeLayout(), nil
	}
	var parsed media.HomeLayoutDoc
	if err := json.Unmarshal([]byte(doc), &parsed); err != nil {
		// A layout we cannot read must not take home down with it.
		if a.log != nil {
			a.log.Warn("home layout unreadable, using default", "user", userID, "error", err)
		}
		return defaultHomeLayout(), nil
	}
	parsed.Source = "user"
	return parsed, nil
}

func (a *App) buildHomeSections(ctx context.Context, userID int64, movieLib, tvLib *config.Library, payload *homePayload) ([]media.HomeSection, error) {
	layout, err := a.resolveHomeLayout(ctx, userID)
	if err != nil {
		return nil, err
	}
	defs := homeSectionDefs()
	scope := homeSectionScope{ctx: ctx, app: a, userID: userID, movieLib: movieLib, tvLib: tvLib, payload: payload}
	sections := make([]media.HomeSection, 0, len(layout.Sections))
	for _, cfg := range layout.Sections {
		if !cfg.Enabled {
			continue
		}
		def, ok := defs[cfg.Type]
		if !ok {
			// Written by a newer server, or a type that has since been removed.
			continue
		}
		section, err := def.build(scope, cfg)
		if err != nil {
			return nil, err
		}
		if len(section.Items) == 0 && len(section.Shows) == 0 && len(section.Entries) == 0 {
			continue
		}
		section.ID = strings.TrimSpace(cfg.ID)
		if section.ID == "" {
			section.ID = cfg.Type
		}
		section.Type = cfg.Type
		section.Layout = def.Layout
		if section.Kind == "" {
			section.Kind = def.Kind
		}
		if title := strings.TrimSpace(cfg.Title); title != "" {
			section.Title = title
		} else if section.Title == "" {
			section.Title = def.title
		}
		section.Params = cfg.Params
		sections = append(sections, section)
	}
	return sections, nil
}

// validateHomeLayout normalises a submitted layout and rejects anything the
// server cannot render, so a bad editor request fails loudly instead of
// silently dropping shelves later.
func validateHomeLayout(doc media.HomeLayoutDoc) (media.HomeLayoutDoc, error) {
	if len(doc.Sections) > homeLayoutMaxSections {
		return doc, fmt.Errorf("layout has %d sections, at most %d are allowed", len(doc.Sections), homeLayoutMaxSections)
	}
	defs := homeSectionDefs()
	seenIDs := map[string]bool{}
	seenTypes := map[string]bool{}
	out := make([]media.HomeLayoutSection, 0, len(doc.Sections))
	for index, section := range doc.Sections {
		def, ok := defs[section.Type]
		if !ok {
			return doc, fmt.Errorf("unknown section type %q", section.Type)
		}
		if !def.Repeatable && seenTypes[section.Type] {
			return doc, fmt.Errorf("section type %q cannot be used more than once", section.Type)
		}
		seenTypes[section.Type] = true

		section.ID = strings.TrimSpace(section.ID)
		if section.ID == "" {
			section.ID = fmt.Sprintf("%s-%d", section.Type, index+1)
		}
		if seenIDs[section.ID] {
			return doc, fmt.Errorf("duplicate section id %q", section.ID)
		}
		seenIDs[section.ID] = true

		params, err := validateHomeSectionParams(def, section.Params)
		if err != nil {
			return doc, fmt.Errorf("section %q: %w", section.ID, err)
		}
		section.Params = params
		section.Title = strings.TrimSpace(section.Title)
		out = append(out, section)
	}
	doc.Sections = out
	return doc, nil
}

func validateHomeSectionParams(def homeSectionDef, params map[string]string) (map[string]string, error) {
	if len(params) == 0 && !hasRequiredParam(def) {
		return nil, nil
	}
	allowed := make(map[string]media.HomeSectionParam, len(def.Params))
	for _, param := range def.Params {
		allowed[param.Name] = param
	}
	out := make(map[string]string, len(params))
	for name, value := range params {
		param, ok := allowed[name]
		if !ok {
			return nil, fmt.Errorf("unknown parameter %q", name)
		}
		value = strings.TrimSpace(value)
		switch param.Type {
		case "int":
			number, err := strconv.Atoi(value)
			if err != nil || number <= 0 {
				return nil, fmt.Errorf("parameter %q must be a positive number", name)
			}
		case "number":
			number, err := strconv.ParseFloat(value, 64)
			if err != nil || number < 0 {
				return nil, fmt.Errorf("parameter %q must be a number", name)
			}
		case "enum":
			if !containsString(param.Options, value) {
				return nil, fmt.Errorf("parameter %q must be one of %s", name, strings.Join(param.Options, ", "))
			}
		}
		if value == "" {
			continue
		}
		out[name] = value
	}
	for _, param := range def.Params {
		if param.Required && strings.TrimSpace(out[param.Name]) == "" {
			return nil, fmt.Errorf("parameter %q is required", param.Name)
		}
	}
	if len(out) == 0 {
		return nil, nil
	}
	return out, nil
}

func hasRequiredParam(def homeSectionDef) bool {
	for _, param := range def.Params {
		if param.Required {
			return true
		}
	}
	return false
}

func sectionLimit(cfg media.HomeLayoutSection) int {
	limit, err := strconv.Atoi(strings.TrimSpace(cfg.Params["limit"]))
	if err != nil || limit <= 0 {
		return homeSectionLimit
	}
	if limit > homeSectionMaxLimit {
		return homeSectionMaxLimit
	}
	return limit
}

func paramOrDefault(cfg media.HomeLayoutSection, name, fallback string) string {
	if value := strings.TrimSpace(cfg.Params[name]); value != "" {
		return value
	}
	return fallback
}

func countLabel(count int, noun string) string {
	if count == 0 {
		return ""
	}
	return fmt.Sprintf("%d %s", count, noun)
}

func containsString(values []string, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}

func sortedKeys(defs map[string]homeSectionDef) []string {
	out := make([]string, 0, len(defs))
	for name := range defs {
		out = append(out, name)
	}
	for i := 1; i < len(out); i++ {
		for j := i; j > 0 && out[j] < out[j-1]; j-- {
			out[j], out[j-1] = out[j-1], out[j]
		}
	}
	return out
}

func (a *App) homeSectionCatalogGet(w http.ResponseWriter, r *http.Request) {
	if _, ok := a.requireUser(w, r); !ok {
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"sections": homeSectionCatalog()})
}

// homeFacets lists the values a filter shelf's text parameters can take, so a
// client with no keyboard can offer them as a list.
func (a *App) homeFacets(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	movieLib := firstLibraryOfType(a.cfg.Libraries, "movies", "movie")
	tvLib := firstLibraryOfType(a.cfg.Libraries, "tv")
	libraryID := ""
	if movieLib != nil {
		libraryID = movieLib.ID
	}
	if r.URL.Query().Get("kind") == "tv" && tvLib != nil {
		libraryID = tvLib.ID
	}
	a.writeCachedJSON(w, r, cacheKey(r, "home-facets", user.ID), 5*time.Minute, func() (any, error) {
		out := map[string]any{}
		genres, err := a.store.ListGenres(r.Context(), libraryID)
		if err != nil {
			return nil, err
		}
		out["genre"] = genres
		kind := "movies"
		if r.URL.Query().Get("kind") == "tv" {
			kind = "tv"
		}
		decades, err := a.store.ListDecades(r.Context(), libraryID, kind)
		if err != nil {
			return nil, err
		}
		decadeValues := make([]string, 0, len(decades))
		for i := len(decades) - 1; i >= 0; i-- {
			decadeValues = append(decadeValues, strconv.Itoa(decades[i]))
		}
		out["decades"] = decadeValues
		for _, facet := range []string{"studio", "country", "certificate"} {
			values, err := a.store.ListFacetValues(r.Context(), libraryID, facet, 120)
			if err != nil {
				return nil, err
			}
			out[facet] = values
		}
		return out, nil
	})
}

func (a *App) homeLayoutGet(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	layout, err := a.resolveHomeLayout(r.Context(), user.ID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, layout)
}

func (a *App) homeLayoutSave(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	var in media.HomeLayoutDoc
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid layout body", http.StatusBadRequest)
		return
	}
	layout, err := validateHomeLayout(in)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	layout.Source = "user"
	body, err := json.Marshal(layout)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if err := a.store.SaveHomeLayout(r.Context(), user.ID, string(body)); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	a.invalidateResponseCache()
	writeJSON(w, http.StatusOK, layout)
}

func (a *App) homeLayoutDelete(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	if err := a.store.DeleteHomeLayout(r.Context(), user.ID); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	a.invalidateResponseCache()
	layout, err := a.resolveHomeLayout(r.Context(), user.ID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, layout)
}

// surpriseMinRating keeps the surprise shelf away from the bottom of the
// library while anything decent is still unwatched.
const surpriseMinRating = 6.5
