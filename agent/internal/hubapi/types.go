package hubapi

import "svcmon/internal/checks"

// --- agent protocol ---

type RegisterRequest struct {
	Name         string `json:"name"`
	Cluster      string `json:"cluster,omitempty"`
	OS           string `json:"os,omitempty"`
	AgentVersion string `json:"agentVersion,omitempty"`
}

type RegisterResponse struct {
	ServerID         string `json:"serverId"`
	Token            string `json:"token"`
	HeartbeatSeconds int    `json:"heartbeatSeconds"`
}

type AgentCheck struct {
	ID              string         `json:"id"`
	Type            string         `json:"type"`
	Target          string         `json:"target"`
	Config          map[string]any `json:"config"`
	IntervalSeconds int            `json:"intervalSeconds"`
	TimeoutMs       int            `json:"timeoutMs"`
}

type AgentConfig struct {
	ServerID         string       `json:"serverId"`
	ServerName       string       `json:"serverName"`
	Cluster          string       `json:"cluster"`
	HeartbeatSeconds int          `json:"heartbeatSeconds"`
	HubTime          int64        `json:"hubTime"`
	Checks           []AgentCheck `json:"checks"`
}

// DeclaredCheck is one entry of the checks file an agent declares to its hub. The agent's definition wins
// over the hub's for a check it declares.
type DeclaredCheck struct {
	ID                 string         `json:"id,omitempty"`
	Name               string         `json:"name,omitempty"`
	Type               string         `json:"type"`
	Target             string         `json:"target,omitempty"`
	Config             map[string]any `json:"config,omitempty"`
	IntervalSeconds    int            `json:"intervalSeconds,omitempty"`
	TimeoutMs          int            `json:"timeoutMs,omitempty"`
	FailuresBeforeDown int            `json:"failuresBeforeDown,omitempty"`
}

// DeclareResult is what the hub did with one entry: created, updated, unchanged, taken over or rejected.
type DeclareResult struct {
	Index   int    `json:"index"`
	CheckID string `json:"checkId"`
	Result  string `json:"result"`
	Error   string `json:"error"`
}

type DeclareResponse struct {
	Results []DeclareResult `json:"results"`
	Removed []string        `json:"removed"`
}

type ReportRequest struct {
	OS           string          `json:"os,omitempty"`
	AgentVersion string          `json:"agentVersion,omitempty"`
	Results      []checks.Result `json:"results"`
}

type ReportResponse struct {
	Accepted int   `json:"accepted"`
	Rejected int   `json:"rejected"`
	HubTime  int64 `json:"hubTime"`
}

// --- read models used by the CLI ---

type Counts struct {
	Up      int `json:"up"`
	Warn    int `json:"warn"`
	Down    int `json:"down"`
	Unknown int `json:"unknown"`
}

type Summary struct {
	Overall string `json:"overall"`
	Servers Counts `json:"servers"`
	Checks  Counts `json:"checks"`
}

type CheckView struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	Type         string   `json:"type"`
	Target       string   `json:"target"`
	RunFrom      string   `json:"runFrom"`
	Enabled      bool     `json:"enabled"`
	Status       string   `json:"status"`
	LatencyMs    *float64 `json:"latencyMs,omitempty"`
	Value        *float64 `json:"value,omitempty"`
	Message      string   `json:"message,omitempty"`
	LastResultAt *int64   `json:"lastResultAt,omitempty"`
	LastChange   *int64   `json:"lastChange,omitempty"`
}

type ServerView struct {
	ID           string      `json:"id"`
	Name         string      `json:"name"`
	Cluster      string      `json:"cluster,omitempty"`
	OS           string      `json:"os,omitempty"`
	AgentVersion string      `json:"agentVersion,omitempty"`
	LastSeen     *int64      `json:"lastSeen,omitempty"`
	AgentOnline  bool        `json:"agentOnline"`
	Status       string      `json:"status"`
	Checks       []CheckView `json:"checks"`
}

type ClusterView struct {
	Name    string       `json:"name"`
	Status  string       `json:"status"`
	Servers []ServerView `json:"servers"`
}

type Status struct {
	GeneratedAt int64         `json:"generatedAt"`
	Summary     Summary       `json:"summary"`
	Clusters    []ClusterView `json:"clusters"`
	HubChecks   []CheckView   `json:"hubChecks"`
}

// CheckDef is a check as stored on the hub.
type CheckDef struct {
	ID                 string         `json:"id"`
	Name               string         `json:"name"`
	Type               string         `json:"type"`
	Target             string         `json:"target,omitempty"`
	Config             map[string]any `json:"config"`
	IntervalSeconds    int            `json:"intervalSeconds"`
	TimeoutMs          int            `json:"timeoutMs"`
	FailuresBeforeDown int            `json:"failuresBeforeDown"`
	RunFrom            string         `json:"runFrom"`
	ServerID           string         `json:"serverId,omitempty"`
	Enabled            bool           `json:"enabled"`
}

type ServerInfo struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	Cluster      string `json:"cluster,omitempty"`
	OS           string `json:"os,omitempty"`
	AgentVersion string `json:"agentVersion,omitempty"`
	LastSeen     *int64 `json:"lastSeen,omitempty"`
}

type IssuedToken struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Scope     string `json:"scope"`
	Token     string `json:"token"`
	ExpiresAt *int64 `json:"expiresAt,omitempty"`
	Cluster   string `json:"cluster,omitempty"`
}

// AlertChannel is an alert destination as the hub returns it: secrets in Config are masked.
type AlertChannel struct {
	ID            string         `json:"id"`
	Name          string         `json:"name"`
	Type          string         `json:"type"`
	Enabled       bool           `json:"enabled"`
	Statuses      []string       `json:"statuses"`
	Config        map[string]any `json:"config"`
	LastAttemptAt *int64         `json:"lastAttemptAt,omitempty"`
	LastSuccessAt *int64         `json:"lastSuccessAt,omitempty"`
	LastError     string         `json:"lastError,omitempty"`
	CreatedAt     int64          `json:"createdAt"`
}

type AlertTestResult struct {
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
}

type TokenView struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Scope     string `json:"scope"`
	ServerID  string `json:"serverId,omitempty"`
	CreatedAt int64  `json:"createdAt"`
	ExpiresAt *int64 `json:"expiresAt,omitempty"`
	UsedAt    *int64 `json:"usedAt,omitempty"`
	Cluster   string `json:"cluster,omitempty"`
	UseCount  int    `json:"useCount,omitempty"` // join keys: servers registered with it
}
