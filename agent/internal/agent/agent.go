package agent

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"log"
	"math/rand/v2"
	"runtime"
	"sync"
	"time"

	"github.com/shirou/gopsutil/v4/host"

	"svcmon/internal/checks"
	"svcmon/internal/hubapi"
)

const (
	maxBuffer      = 5000 // results kept while the hub is unreachable; oldest dropped first
	reportChunk    = 500  // the hub accepts at most 1000 per report
	configInterval = 60 * time.Second
)

type queued struct {
	seq uint64
	res checks.Result
}

// Agent runs the checks the hub assigns to this server.
type Agent struct {
	cfg     *FileConfig
	client  *hubapi.Client
	log     *log.Logger
	version string
	osDesc  string

	mu   sync.Mutex
	buf  []queued
	seq  uint64
	down bool              // last report failed; used to log only on transitions
	last map[string]string // last status seen per check, to report changes promptly
	kick chan struct{}     // signalled when a result is worth sending before the next heartbeat

	checksPath  string // the checks file to declare to the hub, if any
	declaredSum string // checksum of the file the hub last took
	declareErr  string // the last problem logged about it, so a repeating one is logged once

	cachePath string   // where the hub's last check list is kept, if anywhere
	cachedSum [32]byte // checksum of what that file holds, so an unchanged list is not written again
	cacheErr  string   // the last problem logged about the cache, logged once
}

// New prepares an agent. It does not contact the hub until Run or RunOnce.
func New(cfg *FileConfig, logger *log.Logger, version string) *Agent {
	return &Agent{
		cfg:     cfg,
		client:  hubapi.New(cfg.Hub, cfg.Token),
		log:     logger,
		version: version,
		osDesc:  DescribeOS(),
		last:    map[string]string{},
		kick:    make(chan struct{}, 1),
	}
}

// DescribeOS returns something like "Microsoft Windows 11 Home 10.0.26300 (amd64)".
func DescribeOS() string {
	info, err := host.Info()
	if err != nil || info.Platform == "" {
		return runtime.GOOS + "/" + runtime.GOARCH
	}
	return fmt.Sprintf("%s %s (%s)", info.Platform, info.PlatformVersion, runtime.GOARCH)
}

// Run works until ctx is cancelled: fetch config, run checks, report on the hub's heartbeat interval.
func (a *Agent) Run(ctx context.Context) error {
	a.log.Printf("svcmon agent %s starting as %q, hub %s", a.version, a.cfg.Name, a.cfg.Hub)
	cfg, fromCache, err := a.fetchConfigRetry(ctx)
	if err != nil {
		return nil // cancelled before the hub ever answered
	}
	// Started from the cache, the hub is not answering, so declaring would only fail; the next config poll does it.
	if !fromCache && a.syncDeclared(ctx) { // declared checks are in the list the hub hands out, so ask again
		if next, err := a.fetchConfig(ctx); err == nil {
			cfg = next
		}
	}
	r := &runner{agent: a, ctx: ctx, cancels: map[string]context.CancelFunc{}}
	r.apply(cfg)

	heartbeat := heartbeatOf(cfg)
	reportTick := time.NewTicker(heartbeat)
	configTick := time.NewTicker(configInterval)
	defer reportTick.Stop()
	defer configTick.Stop()

	a.report(ctx)
	for {
		select {
		case <-ctx.Done():
			r.stop()
			flushCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			a.report(flushCtx)
			cancel()
			a.log.Printf("agent stopped")
			return nil
		case <-reportTick.C:
			a.report(ctx)
		case <-a.kick:
			select { // let results from checks that finish together share one report
			case <-time.After(500 * time.Millisecond):
			case <-ctx.Done():
				continue
			}
			a.report(ctx)
		case <-configTick.C:
			a.syncDeclared(ctx)
			next, err := a.fetchConfig(ctx)
			if err != nil {
				a.log.Printf("could not refresh config: %v", err)
				continue
			}
			r.apply(next)
			if hb := heartbeatOf(next); hb != heartbeat {
				heartbeat = hb
				reportTick.Reset(hb)
			}
		}
	}
}

// RunOnce fetches the config, runs every check one time, reports, and returns the results.
func (a *Agent) RunOnce(ctx context.Context) ([]checks.Result, error) {
	a.syncDeclared(ctx)
	cfg, err := a.fetchConfig(ctx)
	if err != nil {
		return nil, err
	}
	results := make([]checks.Result, len(cfg.Checks))
	var wg sync.WaitGroup
	for i, c := range cfg.Checks {
		wg.Add(1)
		go func() {
			defer wg.Done()
			results[i] = checks.Run(ctx, toCheck(c))
		}()
	}
	wg.Wait()
	for _, res := range results {
		a.enqueue(res)
	}
	if err := a.flush(ctx); err != nil {
		return results, err
	}
	return results, nil
}

func heartbeatOf(cfg *hubapi.AgentConfig) time.Duration {
	if cfg.HeartbeatSeconds < 1 {
		return 30 * time.Second
	}
	return time.Duration(cfg.HeartbeatSeconds) * time.Second
}

func toCheck(c hubapi.AgentCheck) checks.Check {
	return checks.Check{
		ID:      c.ID,
		Type:    c.Type,
		Target:  c.Target,
		Config:  c.Config,
		Timeout: time.Duration(c.TimeoutMs) * time.Millisecond,
	}
}

func (a *Agent) fetchConfig(ctx context.Context) (*hubapi.AgentConfig, error) {
	var cfg hubapi.AgentConfig
	if err := a.client.Do(ctx, "GET", "/agent/config", nil, &cfg); err != nil {
		return nil, err
	}
	if skew := time.Since(time.UnixMilli(cfg.HubTime)); skew > 30*time.Second || skew < -30*time.Second {
		a.log.Printf("warning: clock differs from the hub by %s; fix time sync", skew.Round(time.Second))
	}
	a.saveCache(&cfg)
	return &cfg, nil
}

// fetchConfigRetry keeps trying with backoff so an agent started before the hub is reachable still comes up.
// When the hub does not answer and the agent has the list the hub last gave it, it starts on that list at once
// (the bool) instead of waiting; the config poll swaps in the hub's list as soon as the hub is back.
func (a *Agent) fetchConfigRetry(ctx context.Context) (*hubapi.AgentConfig, bool, error) {
	delay := 2 * time.Second
	for {
		cfg, err := a.fetchConfig(ctx)
		if err == nil {
			a.log.Printf("config received: %d check(s) assigned", len(cfg.Checks))
			return cfg, false, nil
		}
		if cached := a.cachedInstead(err); cached != nil {
			a.log.Printf("cannot fetch config from hub: %v; running the %d check(s) it last assigned until it answers",
				err, len(cached.Checks))
			return cached, true, nil
		}
		a.log.Printf("cannot fetch config from hub: %v (retrying in %s)", err, delay)
		select {
		case <-ctx.Done():
			return nil, false, ctx.Err()
		case <-time.After(delay):
		}
		if delay < time.Minute {
			delay *= 2
		}
	}
}

// enqueue buffers a result. A first result, or one whose status differs from the last, also asks for an
// early report so a failure reaches the hub in about a second rather than at the next heartbeat.
func (a *Agent) enqueue(r checks.Result) {
	a.mu.Lock()
	a.seq++
	a.buf = append(a.buf, queued{seq: a.seq, res: r})
	if len(a.buf) > maxBuffer {
		a.buf = append([]queued(nil), a.buf[len(a.buf)-maxBuffer:]...)
	}
	prev, seen := a.last[r.CheckID]
	a.last[r.CheckID] = r.Status
	notify := !seen || prev != r.Status
	a.mu.Unlock()
	if notify {
		select {
		case a.kick <- struct{}{}:
		default:
		}
	}
}

// report flushes the buffer (or sends a bare heartbeat) and logs only when reachability changes.
func (a *Agent) report(ctx context.Context) {
	err := a.flush(ctx)
	a.mu.Lock()
	was := a.down
	a.down = err != nil
	pending := len(a.buf)
	a.mu.Unlock()
	switch {
	case err != nil && !was:
		a.log.Printf("report to hub failed: %v (buffering %d result(s))", err, pending)
	case err == nil && was:
		a.log.Printf("hub reachable again; buffered results sent")
	}
}

func (a *Agent) flush(ctx context.Context) error {
	for {
		a.mu.Lock()
		n := min(len(a.buf), reportChunk)
		batch := make([]queued, n)
		copy(batch, a.buf[:n])
		a.mu.Unlock()

		results := make([]checks.Result, n)
		for i, q := range batch {
			results[i] = q.res
		}
		var resp hubapi.ReportResponse
		req := hubapi.ReportRequest{OS: a.osDesc, AgentVersion: a.version, Results: results}
		if err := a.client.Do(ctx, "POST", "/agent/report", req, &resp); err != nil {
			return err
		}
		if resp.Rejected > 0 {
			a.log.Printf("hub rejected %d of %d result(s); the check may have been removed or disabled", resp.Rejected, n)
		}
		if n > 0 {
			last := batch[n-1].seq
			a.mu.Lock()
			i := 0
			for i < len(a.buf) && a.buf[i].seq <= last {
				i++
			}
			a.buf = append([]queued(nil), a.buf[i:]...)
			a.mu.Unlock()
		}
		if n < reportChunk {
			return nil
		}
	}
}

// runner keeps one goroutine per assigned check and restarts them when the hub's list changes.
type runner struct {
	agent   *Agent
	ctx     context.Context
	cancels map[string]context.CancelFunc
	hash    [32]byte
	started bool
}

func (r *runner) apply(cfg *hubapi.AgentConfig) {
	data, _ := json.Marshal(cfg.Checks)
	sum := sha256.Sum256(data)
	if r.started && sum == r.hash {
		return
	}
	r.stop()
	r.hash, r.started = sum, true
	for _, c := range cfg.Checks {
		ctx, cancel := context.WithCancel(r.ctx)
		r.cancels[c.ID] = cancel
		go r.agent.loop(ctx, c)
	}
	r.agent.log.Printf("running %d check(s)", len(cfg.Checks))
}

func (r *runner) stop() {
	for id, cancel := range r.cancels {
		cancel()
		delete(r.cancels, id)
	}
}

func (a *Agent) loop(ctx context.Context, c hubapi.AgentCheck) {
	interval := time.Duration(c.IntervalSeconds) * time.Second
	if interval < time.Second {
		interval = 30 * time.Second
	}
	jitter := time.Duration(rand.Int64N(int64(min(interval, 2*time.Second)) + 1))
	select {
	case <-time.After(jitter):
	case <-ctx.Done():
		return
	}
	tick := time.NewTicker(interval)
	defer tick.Stop()
	for {
		res := checks.Run(ctx, toCheck(c))
		if ctx.Err() != nil {
			return // stopped mid-check: the outcome would be a false failure
		}
		a.enqueue(res)
		select {
		case <-tick.C:
		case <-ctx.Done():
			return
		}
	}
}
