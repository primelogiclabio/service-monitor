// Package agent is the long-running part of svcmon: it fetches its check list from the hub, runs the
// checks on their intervals, and reports results (with a bounded buffer for hub outages).
package agent

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
)

// FileConfig is what enrollment leaves on disk.
type FileConfig struct {
	Hub      string `json:"hub"`
	Name     string `json:"name"`
	ServerID string `json:"serverId"`
	Token    string `json:"token"`
}

// DefaultConfigPath is where an installed agent keeps its config.
func DefaultConfigPath() string {
	switch runtime.GOOS {
	case "windows":
		base := os.Getenv("ProgramData")
		if base == "" {
			base = `C:\ProgramData`
		}
		return filepath.Join(base, "svcmon", "agent.json")
	case "darwin":
		return "/usr/local/etc/svcmon/agent.json"
	default:
		return "/etc/svcmon/agent.json"
	}
}

// LoadConfig reads and validates the config file.
func LoadConfig(path string) (*FileConfig, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var c FileConfig
	if err := json.Unmarshal(data, &c); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	if c.Hub == "" || c.Token == "" {
		return nil, fmt.Errorf("%s: hub and token are required; run 'svcmon agent enroll'", path)
	}
	return &c, nil
}

// Save writes the config so only the owner (and on Windows, SYSTEM and Administrators) can read the token.
func (c *FileConfig) Save(path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return err
	}
	return restrict(path)
}
