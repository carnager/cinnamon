package media

// The home screen is a list of sections the server composes for the client.
// Clients switch on Layout, never on Type: a client that can draw the four
// layouts can render section types that did not exist when it was built, and
// skips the ones it does not recognise. That is what lets new shelves ship
// without an app release.
type HomeSection struct {
	ID       string            `json:"id"`
	Type     string            `json:"type"`
	Layout   string            `json:"layout"`
	Kind     string            `json:"kind"`
	Title    string            `json:"title"`
	Subtitle string            `json:"subtitle,omitempty"`
	More     string            `json:"more,omitempty"`
	Params   map[string]string `json:"params,omitempty"`
	Items    []Item            `json:"items,omitempty"`
	Shows    []ShowSummary     `json:"shows,omitempty"`
	Entries  []Recommendation  `json:"entries,omitempty"`
}

// HomeLayoutDoc is what a user stores and what PUT /api/home/layout accepts.
// Source is "user" once they have saved one, "default" while they are still on
// the built-in layout.
type HomeLayoutDoc struct {
	Source   string              `json:"source,omitempty"`
	Sections []HomeLayoutSection `json:"sections"`
}

type HomeLayoutSection struct {
	ID      string            `json:"id"`
	Type    string            `json:"type"`
	Title   string            `json:"title,omitempty"`
	Enabled bool              `json:"enabled"`
	Params  map[string]string `json:"params,omitempty"`
}

// HomeSectionType describes one entry of the catalog a client renders its
// layout editor from, so a new section type needs no client change.
type HomeSectionType struct {
	Type        string             `json:"type"`
	Label       string             `json:"label"`
	Description string             `json:"description,omitempty"`
	Layout      string             `json:"layout"`
	Kind        string             `json:"kind"`
	Repeatable  bool               `json:"repeatable"`
	Params      []HomeSectionParam `json:"params,omitempty"`
}

type HomeSectionParam struct {
	Name    string   `json:"name"`
	Label   string   `json:"label"`
	Type    string   `json:"type"`
	Options []string `json:"options,omitempty"`
	// Choices are the values a picker should offer for a number, and Suffix is
	// what they are measured in — without them a client has to guess that
	// "limit" means items and "maxMinutes" does not. An empty choice clears the
	// parameter. Unlike Options they are suggestions, not a constraint.
	Choices  []string `json:"choices,omitempty"`
	Suffix   string   `json:"suffix,omitempty"`
	Default  string   `json:"default,omitempty"`
	Required bool     `json:"required,omitempty"`
}
