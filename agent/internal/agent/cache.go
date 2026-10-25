package agent

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"

	"svcmon/internal/hubapi"
)

// CacheFileName is where the agent keeps the check list the hub last gave it, beside agent.json. An agent that
// is started while the hub cannot be reached runs that list instead of waiting for the hub.
const CacheFileName = "assigned.json"

// CachePath is where the cache sits for an agent config at cfgPath.
func CachePath(cfgPath string) string {
	return filepath.Join(filepath.Dir(cfgPath), CacheFileName)
}

// SetCacheFile makes the agent keep the hub's check list in this file.
func (a *Agent) SetCacheFile(path string) { a.cachePath = path }

// saveCache keeps the list the hub just gave, unless the file already holds it. The hub's clock reading is
// left out, so a list that has not changed does not rewrite the file on every poll.
func (a *Agent) saveCache(cfg *hubapi.AgentConfig) {
	if a.cachePath == "" {
		return
	}
	keep := *cfg
	keep.HubTime = 0
	if keep.Checks == nil {
		keep.Checks = []hubapi.AgentCheck{}
	}
	data, err := json.MarshalIndent(keep, "", "  ")
	if err != nil {
		return
	}
	sum := sha256.Sum256(data)
	if sum == a.cachedSum {
		return
	}
	// The check settings can carry HTTP headers, so the file is private like agent.json.
	if err := writeFileAtomic(a.cachePath, append(data, '\n')); err != nil {
		a.logCacheProblem("cannot keep the hub's check list in %s: %v", a.cachePath, err)
		return
	}
	a.cachedSum = sum
	a.cacheErr = ""
}

// loadCache returns the list the hub last gave this server, or nil when there is none worth running: no file,
// a file that cannot be read, or a list for a server this agent no longer is (it was enrolled again).
func (a *Agent) loadCache() *hubapi.AgentConfig {
	if a.cachePath == "" {
		return nil
	}
	data, err := os.ReadFile(a.cachePath)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		a.logCacheProblem("cannot read %s: %v", a.cachePath, err)
		return nil
	}
	var cfg hubapi.AgentConfig
	if err := json.Unmarshal(bytes.TrimPrefix(data, []byte("\xef\xbb\xbf")), &cfg); err != nil {
		a.logCacheProblem("not using %s: %v", a.cachePath, err)
		return nil
	}
	if cfg.ServerID != a.cfg.ServerID {
		return nil
	}
	return &cfg
}

// cachedInstead is the cached list to run when fetching the config failed with err, or nil. When the hub
// answered and said no (a token it no longer knows, a server it deleted), running on would be wrong, so only a
// hub that cannot be reached, or fails on its side, falls back to the cache.
func (a *Agent) cachedInstead(err error) *hubapi.AgentConfig {
	var api *hubapi.APIError
	if errors.As(err, &api) && api.Status >= 400 && api.Status < 500 {
		return nil
	}
	return a.loadCache()
}

// logCacheProblem logs a problem once; the same one is not repeated on every config poll.
func (a *Agent) logCacheProblem(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	if msg != a.cacheErr {
		a.cacheErr = msg
		a.log.Print(msg)
	}
}
