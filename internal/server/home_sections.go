package server

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"popcorn/internal/config"
	"popcorn/internal/media"
)

// Home layouts are stored per user and per client kind, because a TV wants a
// handful of large shelves where a phone wants many small ones. A profile with
// no row of its own falls back to "default", and a user with no row at all gets
// defaultHomeLayout.
const defaultHomeProfile = "default"

var homeProfiles = []string{defaultHomeProfile, "tv", "phone", "web"}

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
	limitParam := media.HomeSectionParam{Name: "limit", Label: "Items", Type: "int", Default: strconv.Itoa(homeSectionLimit)}
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
				Type: "continue_movies", Label: "Continue Movies", Layout: "poster", Kind: "movie",
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
				Type: "continue_tv", Label: "Continue TV", Layout: "poster", Kind: "episode",
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
func defaultHomeLayout(profile string) media.HomeLayoutDoc {
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
	return media.HomeLayoutDoc{Profile: profile, Source: "default", Sections: sections}
}

func homeSectionCatalog() []media.HomeSectionType {
	defs := homeSectionDefs()
	out := make([]media.HomeSectionType, 0, len(defs))
	// Catalog order follows the default layout so the editor lists the familiar
	// shelves first, with the repeatable ones after them.
	for _, section := range defaultHomeLayout(defaultHomeProfile).Sections {
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

// resolveHomeLayout returns the layout for a profile: its own row, else the
// default profile's row, else the built-in default.
func (a *App) resolveHomeLayout(ctx context.Context, userID int64, profile string) (media.HomeLayoutDoc, error) {
	profile = normalizeHomeProfile(profile)
	for _, candidate := range []string{profile, defaultHomeProfile} {
		doc, err := a.store.HomeLayout(ctx, userID, candidate)
		if err != nil {
			return media.HomeLayoutDoc{}, err
		}
		if strings.TrimSpace(doc) == "" {
			continue
		}
		var parsed media.HomeLayoutDoc
		if err := json.Unmarshal([]byte(doc), &parsed); err != nil {
			// A layout we cannot read must not take home down with it.
			if a.log != nil {
				a.log.Warn("home layout unreadable, using default", "user", userID, "profile", candidate, "error", err)
			}
			continue
		}
		parsed.Profile = profile
		parsed.Source = candidate
		return parsed, nil
	}
	return defaultHomeLayout(profile), nil
}

func (a *App) buildHomeSections(ctx context.Context, userID int64, profile string, movieLib, tvLib *config.Library, payload *homePayload) ([]media.HomeSection, error) {
	layout, err := a.resolveHomeLayout(ctx, userID, profile)
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

func normalizeHomeProfile(profile string) string {
	profile = strings.ToLower(strings.TrimSpace(profile))
	if containsString(homeProfiles, profile) {
		return profile
	}
	return defaultHomeProfile
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
	writeJSON(w, http.StatusOK, map[string]any{
		"profiles": homeProfiles,
		"sections": homeSectionCatalog(),
	})
}

func (a *App) homeLayoutGet(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	layout, err := a.resolveHomeLayout(r.Context(), user.ID, r.URL.Query().Get("profile"))
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
	profile := normalizeHomeProfile(firstNonEmpty(r.URL.Query().Get("profile"), in.Profile))
	layout, err := validateHomeLayout(in)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	layout.Profile = profile
	layout.Source = profile
	body, err := json.Marshal(layout)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if err := a.store.SaveHomeLayout(r.Context(), user.ID, profile, string(body)); err != nil {
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
	profile := normalizeHomeProfile(r.URL.Query().Get("profile"))
	if err := a.store.DeleteHomeLayout(r.Context(), user.ID, profile); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	a.invalidateResponseCache()
	layout, err := a.resolveHomeLayout(r.Context(), user.ID, profile)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, layout)
}
