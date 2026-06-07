package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/pelletier/go-toml/v2"
)

type config struct {
	Server   string `toml:"server"`
	Token    string `toml:"token"`
	Username string `toml:"username"`
}

type library struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Type string `json:"type"`
}

type item struct {
	ID            int64  `json:"id"`
	Kind          string `json:"kind"`
	Title         string `json:"title"`
	Year          int    `json:"year"`
	DurationMS    int64  `json:"durationMs"`
	ShowTitle     string `json:"showTitle"`
	SeasonNumber  int    `json:"seasonNumber"`
	EpisodeNumber int    `json:"episodeNumber"`
	EpisodeTitle  string `json:"episodeTitle"`
}

type show struct {
	LibraryID    string `json:"libraryId"`
	Title        string `json:"title"`
	SeasonCount  int    `json:"seasonCount"`
	EpisodeCount int    `json:"episodeCount"`
}

type season struct {
	SeasonNumber int    `json:"seasonNumber"`
	Title        string `json:"title"`
	EpisodeCount int    `json:"episodeCount"`
}

type progress struct {
	ItemID     int64 `json:"itemId"`
	PositionMS int64 `json:"positionMs"`
	DurationMS int64 `json:"durationMs"`
	Completed  bool  `json:"completed"`
}

type mpvUpdate struct {
	PositionMS int64
	DurationMS int64
	EndReason  string
}

type searchResponse struct {
	Items []item `json:"items"`
}

type app struct {
	cfgPath  string
	cfg      config
	client   *http.Client
	picker   picker
	columns  int
	mpvPath  string
	limit    int
	noResume bool
}

type picker interface {
	Pick(ctx context.Context, prompt string, choices []choice) (choice, bool, error)
	Input(ctx context.Context, prompt, initial string, password bool) (string, bool, error)
	Message(ctx context.Context, msg string)
}

type choice struct {
	ID    string
	Label string
}

func main() {
	os.Exit(run())
}

func run() int {
	var (
		configPath = flag.String("config", defaultConfigPath(), "config file")
		serverFlag = flag.String("server", "", "Popcorn server URL")
		tokenFlag  = flag.String("token", "", "Popcorn auth token")
		userFlag   = flag.String("username", "", "Popcorn username")
		passFlag   = flag.String("password", "", "Popcorn password")
		selector   = flag.String("selector", "auto", "selector: auto, rofi, fzf")
		moviesOnly = flag.Bool("movies", false, "list movies only")
		tvOnly     = flag.Bool("tv", false, "list TV shows only")
		searchOnly = flag.Bool("search", false, "open global search directly")
		queryFlag  = flag.String("query", "", "search query")
		limit      = flag.Int("limit", 2000, "maximum items to request per list")
		columns    = flag.Int("columns", envInt("POPCORN_ROFI_COLUMNS", 100), "label width in columns")
		mpvPath    = flag.String("mpv", "mpv", "mpv executable")
		noResume   = flag.Bool("no-resume", false, "always start from beginning")
	)
	flag.Parse()

	if *moviesOnly && *tvOnly {
		fmt.Fprintln(os.Stderr, "-movies and -tv are mutually exclusive")
		return 2
	}

	cfg, err := loadConfig(*configPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	overrideFromEnv(&cfg)
	if *serverFlag != "" {
		cfg.Server = *serverFlag
	}
	if *tokenFlag != "" {
		cfg.Token = *tokenFlag
	}
	if *userFlag != "" {
		cfg.Username = *userFlag
	}
	if strings.TrimSpace(cfg.Server) == "" {
		cfg.Server = "http://localhost:8097"
	}
	cfg.Server = strings.TrimRight(strings.TrimSpace(cfg.Server), "/")
	if err := saveConfigFile(*configPath, cfg); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}

	p, err := newPicker(*selector)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 2
	}

	a := &app{
		cfgPath:  *configPath,
		cfg:      cfg,
		client:   &http.Client{Timeout: 30 * time.Second},
		picker:   p,
		columns:  max(54, *columns),
		mpvPath:  *mpvPath,
		limit:    *limit,
		noResume: *noResume,
	}

	ctx := context.Background()
	if err := a.ensureLogin(ctx, *passFlag); err != nil {
		p.Message(ctx, err.Error())
		fmt.Fprintln(os.Stderr, err)
		return 1
	}

	id, ok, err := a.choose(ctx, *moviesOnly, *tvOnly, *searchOnly, strings.TrimSpace(*queryFlag))
	if err != nil {
		p.Message(ctx, err.Error())
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if !ok {
		return 0
	}
	if err := a.play(ctx, id); err != nil {
		p.Message(ctx, err.Error())
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	return 0
}

func (a *app) choose(ctx context.Context, moviesOnly, tvOnly, searchOnly bool, query string) (int64, bool, error) {
	switch {
	case query != "":
		return a.chooseSearch(ctx, query)
	case searchOnly:
		q, ok, err := a.picker.Input(ctx, "Search", "", false)
		if err != nil || !ok || strings.TrimSpace(q) == "" {
			return 0, false, err
		}
		return a.chooseSearch(ctx, q)
	case moviesOnly:
		return a.chooseMovie(ctx)
	case tvOnly:
		return a.chooseEpisode(ctx)
	default:
		chosen, ok, err := a.picker.Pick(ctx, "Popcorn", []choice{
			{ID: "movies", Label: "Movies"},
			{ID: "tv", Label: "TV Shows"},
			{ID: "search", Label: "Search"},
		})
		if err != nil || !ok {
			return 0, false, err
		}
		switch chosen.ID {
		case "movies":
			return a.chooseMovie(ctx)
		case "tv":
			return a.chooseEpisode(ctx)
		case "search":
			q, ok, err := a.picker.Input(ctx, "Search", "", false)
			if err != nil || !ok || strings.TrimSpace(q) == "" {
				return 0, false, err
			}
			return a.chooseSearch(ctx, q)
		default:
			return 0, false, nil
		}
	}
}

func (a *app) chooseMovie(ctx context.Context) (int64, bool, error) {
	lib, err := a.library(ctx, "movies")
	if err != nil {
		return 0, false, err
	}
	var items []item
	if err := a.get(ctx, "/api/items?libraryId="+url.QueryEscape(lib.ID)+"&limit="+strconv.Itoa(a.limit)+"&sort=title", &items); err != nil {
		return 0, false, err
	}
	choices := make([]choice, 0, len(items))
	for _, it := range items {
		choices = append(choices, choice{ID: strconv.FormatInt(it.ID, 10), Label: a.movieLabel(it)})
	}
	chosen, ok, err := a.picker.Pick(ctx, "Movie", choices)
	if err != nil || !ok {
		return 0, false, err
	}
	return parseItemID(chosen.ID)
}

func (a *app) chooseEpisode(ctx context.Context) (int64, bool, error) {
	lib, err := a.library(ctx, "tv")
	if err != nil {
		return 0, false, err
	}
	var shows []show
	if err := a.get(ctx, "/api/tv/shows?libraryId="+url.QueryEscape(lib.ID)+"&limit="+strconv.Itoa(a.limit)+"&sort=title", &shows); err != nil {
		return 0, false, err
	}
	showChoices := make([]choice, 0, len(shows))
	for _, s := range shows {
		showChoices = append(showChoices, choice{
			ID:    s.LibraryID + "\x1f" + s.Title,
			Label: a.fit(s.Title, fmt.Sprintf("[%02ds/%02de]", s.SeasonCount, s.EpisodeCount)),
		})
	}
	chosenShow, ok, err := a.picker.Pick(ctx, "Show", showChoices)
	if err != nil || !ok {
		return 0, false, err
	}
	parts := strings.SplitN(chosenShow.ID, "\x1f", 2)
	if len(parts) != 2 {
		return 0, false, errors.New("invalid show selection")
	}
	libraryID, showTitle := parts[0], parts[1]

	var seasons []season
	if err := a.get(ctx, "/api/tv/seasons?libraryId="+url.QueryEscape(libraryID)+"&showTitle="+url.QueryEscape(showTitle), &seasons); err != nil {
		return 0, false, err
	}
	seasonChoices := make([]choice, 0, len(seasons))
	for _, s := range seasons {
		title := s.Title
		if title == "" {
			title = fmt.Sprintf("Season %d", s.SeasonNumber)
		}
		seasonChoices = append(seasonChoices, choice{
			ID:    strconv.Itoa(s.SeasonNumber),
			Label: a.fit(fmt.Sprintf("S%02d  %s", s.SeasonNumber, title), fmt.Sprintf("[%02de]", s.EpisodeCount)),
		})
	}
	chosenSeason, ok, err := a.picker.Pick(ctx, "Season", seasonChoices)
	if err != nil || !ok {
		return 0, false, err
	}

	var episodes []item
	if err := a.get(ctx, "/api/tv/episodes?libraryId="+url.QueryEscape(libraryID)+"&showTitle="+url.QueryEscape(showTitle)+"&season="+url.QueryEscape(chosenSeason.ID), &episodes); err != nil {
		return 0, false, err
	}
	episodeChoices := make([]choice, 0, len(episodes))
	for _, ep := range episodes {
		episodeChoices = append(episodeChoices, choice{ID: strconv.FormatInt(ep.ID, 10), Label: a.episodeLabel(ep)})
	}
	chosenEpisode, ok, err := a.picker.Pick(ctx, "Episode", episodeChoices)
	if err != nil || !ok {
		return 0, false, err
	}
	return parseItemID(chosenEpisode.ID)
}

func (a *app) chooseSearch(ctx context.Context, query string) (int64, bool, error) {
	var res searchResponse
	if err := a.get(ctx, "/api/search?limit=100&q="+url.QueryEscape(strings.TrimSpace(query)), &res); err != nil {
		return 0, false, err
	}
	choices := make([]choice, 0, len(res.Items))
	for _, it := range res.Items {
		switch it.Kind {
		case "movie":
			choices = append(choices, choice{ID: strconv.FormatInt(it.ID, 10), Label: a.fit("MOVIE  "+it.Title, movieMeta(it))})
		case "episode":
			labelTitle := fmt.Sprintf("TV     %s  S%02dE%02d  %s", firstNonEmpty(it.ShowTitle, it.Title), it.SeasonNumber, it.EpisodeNumber, firstNonEmpty(it.EpisodeTitle, it.Title))
			choices = append(choices, choice{ID: strconv.FormatInt(it.ID, 10), Label: a.fit(labelTitle, duration(it.DurationMS))})
		}
	}
	if len(choices) == 0 {
		return 0, false, fmt.Errorf("no results for %q", query)
	}
	chosen, ok, err := a.picker.Pick(ctx, "Play", choices)
	if err != nil || !ok {
		return 0, false, err
	}
	return parseItemID(chosen.ID)
}

func parseItemID(value string) (int64, bool, error) {
	id, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return 0, false, err
	}
	return id, true, nil
}

func (a *app) play(ctx context.Context, itemID int64) error {
	var it item
	if err := a.get(ctx, fmt.Sprintf("/api/items/%d", itemID), &it); err != nil {
		return err
	}
	prog := progress{ItemID: itemID, DurationMS: it.DurationMS}
	_ = a.get(ctx, fmt.Sprintf("/api/items/%d/progress", itemID), &prog)

	startSeconds := int64(0)
	if !a.noResume && prog.PositionMS > 60_000 && !prog.Completed {
		answer, ok, err := a.picker.Pick(ctx, "Resume?", []choice{
			{ID: "resume", Label: "Resume at " + clock(prog.PositionMS)},
			{ID: "start", Label: "Start from beginning"},
		})
		if err != nil {
			return err
		}
		if ok && answer.ID == "resume" {
			startSeconds = prog.PositionMS / 1000
		}
	}

	streamURL := fmt.Sprintf("%s/api/items/%d/stream", strings.TrimRight(a.cfg.Server, "/"), itemID)
	socketDir, err := os.MkdirTemp("", fmt.Sprintf("popcorn-mpv-%d-*", itemID))
	if err != nil {
		return err
	}
	defer os.RemoveAll(socketDir)
	socketPath := filepath.Join(socketDir, "mpv.sock")

	durationMS := firstPositive(prog.DurationMS, it.DurationMS)
	positionMS := prog.PositionMS
	_ = a.saveProgress(ctx, itemID, positionMS, durationMS, "start", false)

	args := []string{
		"--force-media-title=" + displayTitle(it),
		"--input-ipc-server=" + socketPath,
		"--http-header-fields=Authorization: Bearer " + a.cfg.Token,
	}
	if startSeconds > 0 {
		args = append(args, "--start="+strconv.FormatInt(startSeconds, 10))
	}
	args = append(args, streamURL)

	cmd := exec.CommandContext(ctx, a.mpvPath, args...)
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return err
	}

	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	mpvUpdates := make(chan mpvUpdate, 32)
	monitorCtx, cancelMonitor := context.WithCancel(ctx)
	defer cancelMonitor()
	go monitorMPV(monitorCtx, socketPath, mpvUpdates)

	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	endReason := ""
	applyUpdate := func(update mpvUpdate) {
		if update.PositionMS > 0 {
			positionMS = update.PositionMS
		}
		if update.DurationMS > 0 {
			durationMS = update.DurationMS
		}
		if update.EndReason != "" {
			endReason = update.EndReason
		}
	}
	for {
		select {
		case err := <-done:
			deadline := time.After(500 * time.Millisecond)
		drain:
			for {
				select {
				case update := <-mpvUpdates:
					applyUpdate(update)
				case <-deadline:
					break drain
				}
			}
			completed := endReason == "eof" || isFinished(positionMS, durationMS)
			if completed && durationMS > 0 && endReason == "eof" {
				positionMS = durationMS
			}
			_ = a.saveProgress(ctx, itemID, positionMS, durationMS, "stop", completed)
			return err
		case update := <-mpvUpdates:
			applyUpdate(update)
		case <-ticker.C:
			pos, ok := mpvNumber(socketPath, "playback-time")
			if ok {
				positionMS = int64(pos * 1000)
			}
			dur, ok := mpvNumber(socketPath, "duration")
			if ok && dur > 0 {
				durationMS = int64(dur * 1000)
			}
			if positionMS > 0 {
				_ = a.saveProgress(ctx, itemID, positionMS, durationMS, "play", false)
			}
		}
	}
}

func (a *app) ensureLogin(ctx context.Context, passwordFlag string) error {
	if a.cfg.Server == "" {
		server, ok, err := a.picker.Input(ctx, "Popcorn server", "http://localhost:8097", false)
		if err != nil || !ok {
			return err
		}
		a.cfg.Server = server
	}
	if a.cfg.Token != "" {
		var me map[string]any
		if err := a.get(ctx, "/api/auth/me", &me); err == nil {
			return a.saveConfig()
		}
		a.cfg.Token = ""
	}
	if a.cfg.Username == "" {
		username, ok, err := a.picker.Input(ctx, "Popcorn user", "", false)
		if err != nil || !ok {
			return err
		}
		a.cfg.Username = username
	}
	password := passwordFlag
	if password == "" {
		var ok bool
		var err error
		password, ok, err = a.picker.Input(ctx, "Popcorn password", "", true)
		if err != nil || !ok {
			return err
		}
	}
	var out struct {
		Token string `json:"token"`
	}
	if err := a.post(ctx, "/api/auth/login", map[string]string{"username": a.cfg.Username, "password": password}, &out); err != nil {
		return err
	}
	if out.Token == "" {
		return errors.New("login returned no token")
	}
	a.cfg.Token = out.Token
	return a.saveConfig()
}

func (a *app) library(ctx context.Context, typ string) (library, error) {
	var libs []library
	if err := a.get(ctx, "/api/libraries", &libs); err != nil {
		return library{}, err
	}
	for _, lib := range libs {
		if lib.Type == typ {
			return lib, nil
		}
	}
	return library{}, fmt.Errorf("no %s library configured", typ)
}

func (a *app) get(ctx context.Context, path string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, a.cfg.Server+path, nil)
	if err != nil {
		return err
	}
	if a.cfg.Token != "" {
		req.Header.Set("Authorization", "Bearer "+a.cfg.Token)
	}
	resp, err := a.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return decodeResponse(resp, out)
}

func (a *app) post(ctx context.Context, path string, in, out any) error {
	body, err := json.Marshal(in)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, a.cfg.Server+path, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if a.cfg.Token != "" {
		req.Header.Set("Authorization", "Bearer "+a.cfg.Token)
	}
	resp, err := a.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return decodeResponse(resp, out)
}

func (a *app) put(ctx context.Context, path string, in, out any) error {
	body, err := json.Marshal(in)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, a.cfg.Server+path, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if a.cfg.Token != "" {
		req.Header.Set("Authorization", "Bearer "+a.cfg.Token)
	}
	resp, err := a.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return decodeResponse(resp, out)
}

func (a *app) saveProgress(ctx context.Context, itemID, positionMS, durationMS int64, state string, completed bool) error {
	body := map[string]any{
		"positionMs": positionMS,
		"durationMs": durationMS,
		"completed":  completed,
		"state":      state,
	}
	var out progress
	return a.put(ctx, fmt.Sprintf("/api/items/%d/progress", itemID), body, &out)
}

func decodeResponse(resp *http.Response, out any) error {
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		msg := strings.TrimSpace(string(data))
		if msg == "" {
			msg = resp.Status
		}
		return fmt.Errorf("%s: %s", resp.Request.URL.Path, msg)
	}
	if out == nil || len(data) == 0 {
		return nil
	}
	if err := json.Unmarshal(data, out); err != nil {
		return fmt.Errorf("%s: %w", resp.Request.URL.Path, err)
	}
	return nil
}

func loadConfig(path string) (config, error) {
	var cfg config
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return cfg, nil
		}
		return cfg, err
	}
	if err := toml.Unmarshal(data, &cfg); err != nil {
		return cfg, err
	}
	return cfg, nil
}

func (a *app) saveConfig() error {
	return saveConfigFile(a.cfgPath, a.cfg)
}

func saveConfigFile(path string, cfg config) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := toml.Marshal(cfg)
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}

func overrideFromEnv(cfg *config) {
	if v := os.Getenv("POPCORN_SERVER"); v != "" {
		cfg.Server = v
	}
	if v := os.Getenv("POPCORN_TOKEN"); v != "" {
		cfg.Token = v
	}
	if v := os.Getenv("POPCORN_USERNAME"); v != "" {
		cfg.Username = v
	}
}

func defaultConfigPath() string {
	if v := os.Getenv("POPCORN_MPV_CONFIG"); v != "" {
		return v
	}
	base := os.Getenv("XDG_CONFIG_HOME")
	if base == "" {
		home, _ := os.UserHomeDir()
		base = filepath.Join(home, ".config")
	}
	return filepath.Join(base, "popcorn", "mpv.toml")
}

func newPicker(name string) (picker, error) {
	switch name {
	case "auto", "":
		if _, err := exec.LookPath("rofi"); err == nil && os.Getenv("DISPLAY") != "" {
			return rofiPicker{}, nil
		}
		if _, err := exec.LookPath("fzf"); err == nil {
			return fzfPicker{}, nil
		}
		return nil, errors.New("missing selector: install rofi or fzf")
	case "rofi":
		if _, err := exec.LookPath("rofi"); err != nil {
			return nil, err
		}
		return rofiPicker{}, nil
	case "fzf":
		if _, err := exec.LookPath("fzf"); err != nil {
			return nil, err
		}
		return fzfPicker{}, nil
	default:
		return nil, fmt.Errorf("unknown selector %q", name)
	}
}

type rofiPicker struct{}

func (rofiPicker) Pick(ctx context.Context, prompt string, choices []choice) (choice, bool, error) {
	if len(choices) == 0 {
		return choice{}, false, nil
	}
	labels := make([]string, len(choices))
	for i, c := range choices {
		labels[i] = c.Label
	}
	out, ok, err := runMenu(ctx, "rofi", []string{"-dmenu", "-i", "-p", prompt}, strings.Join(labels, "\n")+"\n")
	if err != nil || !ok {
		return choice{}, false, err
	}
	return matchChoice(choices, out)
}

func (rofiPicker) Input(ctx context.Context, prompt, initial string, password bool) (string, bool, error) {
	args := []string{"-dmenu", "-p", prompt}
	if password {
		args = append(args, "-password")
	}
	input := ""
	if initial != "" {
		input = initial + "\n"
	}
	return runMenu(ctx, "rofi", args, input)
}

func (rofiPicker) Message(ctx context.Context, msg string) {
	_ = exec.CommandContext(ctx, "rofi", "-e", msg).Run()
}

type fzfPicker struct{}

func (fzfPicker) Pick(ctx context.Context, prompt string, choices []choice) (choice, bool, error) {
	if len(choices) == 0 {
		return choice{}, false, nil
	}
	labels := make([]string, len(choices))
	for i, c := range choices {
		labels[i] = c.Label
	}
	out, ok, err := runMenu(ctx, "fzf", []string{"--prompt", prompt + "> "}, strings.Join(labels, "\n")+"\n")
	if err != nil || !ok {
		return choice{}, false, err
	}
	return matchChoice(choices, out)
}

func (fzfPicker) Input(_ context.Context, prompt, initial string, password bool) (string, bool, error) {
	if initial != "" {
		fmt.Fprintf(os.Stderr, "%s [%s]: ", prompt, initial)
	} else {
		fmt.Fprintf(os.Stderr, "%s: ", prompt)
	}
	if password {
		fmt.Fprint(os.Stderr, "")
	}
	reader := bufio.NewReader(os.Stdin)
	line, err := reader.ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return "", false, err
	}
	line = strings.TrimSpace(line)
	if line == "" {
		line = initial
	}
	return line, line != "", nil
}

func (fzfPicker) Message(_ context.Context, msg string) {
	fmt.Fprintln(os.Stderr, msg)
}

func runMenu(ctx context.Context, name string, args []string, input string) (string, bool, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Stdin = strings.NewReader(input)
	var out bytes.Buffer
	var stderr bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &stderr
	err := cmd.Run()
	if err != nil {
		if exit, ok := err.(*exec.ExitError); ok && exit.ExitCode() == 1 {
			return "", false, nil
		}
		msg := strings.TrimSpace(stderr.String())
		if msg != "" {
			return "", false, fmt.Errorf("%s: %s", name, msg)
		}
		return "", false, err
	}
	return strings.TrimRight(out.String(), "\r\n"), true, nil
}

func matchChoice(choices []choice, label string) (choice, bool, error) {
	for _, c := range choices {
		if c.Label == label {
			return c, true, nil
		}
	}
	return choice{}, false, nil
}

func mpvNumber(socketPath, property string) (float64, bool) {
	conn, err := net.DialTimeout("unix", socketPath, 500*time.Millisecond)
	if err != nil {
		return 0, false
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(800 * time.Millisecond))
	req := map[string]any{"command": []string{"get_property", property}}
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return 0, false
	}
	var resp struct {
		Data any `json:"data"`
	}
	if err := json.NewDecoder(conn).Decode(&resp); err != nil {
		return 0, false
	}
	switch v := resp.Data.(type) {
	case float64:
		return v, true
	case int:
		return float64(v), true
	default:
		return 0, false
	}
}

func monitorMPV(ctx context.Context, socketPath string, updates chan<- mpvUpdate) {
	deadline := time.Now().Add(5 * time.Second)
	var conn net.Conn
	for {
		if ctx.Err() != nil {
			return
		}
		c, err := net.DialTimeout("unix", socketPath, 250*time.Millisecond)
		if err == nil {
			conn = c
			break
		}
		if time.Now().After(deadline) {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	defer conn.Close()

	encoder := json.NewEncoder(conn)
	_ = encoder.Encode(map[string]any{"command": []any{"observe_property", 1, "playback-time"}})
	_ = encoder.Encode(map[string]any{"command": []any{"observe_property", 2, "duration"}})

	decoder := json.NewDecoder(conn)
	for {
		if ctx.Err() != nil {
			return
		}
		var event struct {
			Event  string `json:"event"`
			ID     int    `json:"id"`
			Name   string `json:"name"`
			Reason string `json:"reason"`
			Data   any    `json:"data"`
		}
		if err := decoder.Decode(&event); err != nil {
			return
		}
		update := mpvUpdate{}
		switch event.Event {
		case "property-change":
			value, ok := jsonNumber(event.Data)
			if !ok || value <= 0 {
				continue
			}
			switch event.Name {
			case "playback-time":
				update.PositionMS = int64(value * 1000)
			case "duration":
				update.DurationMS = int64(value * 1000)
			default:
				continue
			}
		case "end-file":
			update.EndReason = event.Reason
		default:
			continue
		}
		select {
		case updates <- update:
		case <-ctx.Done():
			return
		}
	}
}

func jsonNumber(value any) (float64, bool) {
	switch v := value.(type) {
	case float64:
		return v, true
	case int:
		return float64(v), true
	case int64:
		return float64(v), true
	case json.Number:
		n, err := v.Float64()
		return n, err == nil
	default:
		return 0, false
	}
}

func (a *app) movieLabel(it item) string {
	return a.fit(it.Title, movieMeta(it))
}

func (a *app) episodeLabel(it item) string {
	title := firstNonEmpty(it.EpisodeTitle, it.Title)
	return a.fit(fmt.Sprintf("S%02dE%02d  %s", it.SeasonNumber, it.EpisodeNumber, title), duration(it.DurationMS))
}

func (a *app) fit(title, meta string) string {
	title = strings.TrimSpace(title)
	meta = strings.TrimSpace(meta)
	cols := max(24, a.columns)
	metaLen := textLen(meta)
	titleWidth := cols - metaLen - 2
	if meta == "" {
		titleWidth = cols
	}
	if titleWidth < 12 {
		titleWidth = 12
	}
	title = truncate(title, titleWidth)
	if meta == "" {
		return title
	}
	gap := cols - textLen(title) - metaLen
	if gap < 2 {
		gap = 2
	}
	return title + strings.Repeat(" ", gap) + meta
}

func movieMeta(it item) string {
	year := "----"
	if it.Year > 0 {
		year = strconv.Itoa(it.Year)
	}
	dur := duration(it.DurationMS)
	if dur == "" {
		return "[" + year + "]"
	}
	return "[" + year + "]  " + dur
}

func duration(ms int64) string {
	if ms <= 0 {
		return ""
	}
	minutes := ms / 60000
	return fmt.Sprintf("%02d:%02d", minutes/60, minutes%60)
}

func clock(ms int64) string {
	seconds := ms / 1000
	return fmt.Sprintf("%02d:%02d:%02d", seconds/3600, (seconds%3600)/60, seconds%60)
}

func displayTitle(it item) string {
	if it.Kind == "episode" {
		return firstNonEmpty(it.ShowTitle, it.Title) + " - " + firstNonEmpty(it.EpisodeTitle, it.Title)
	}
	return it.Title
}

func truncate(s string, cols int) string {
	if textLen(s) <= cols {
		return s
	}
	if cols <= 3 {
		return strings.Repeat(".", cols)
	}
	out := make([]rune, 0, cols)
	width := 0
	for _, r := range s {
		if width+1 > cols-3 {
			break
		}
		out = append(out, r)
		width++
	}
	return string(out) + "..."
}

func textLen(s string) int {
	return utf8.RuneCountInString(s)
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func firstPositive(values ...int64) int64 {
	for _, v := range values {
		if v > 0 {
			return v
		}
	}
	return 0
}

func isFinished(positionMS, durationMS int64) bool {
	if durationMS <= 0 || positionMS <= 0 {
		return false
	}
	return durationMS-positionMS <= 90_000 || positionMS*100 >= durationMS*92
}

func envInt(name string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	n, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return n
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}
