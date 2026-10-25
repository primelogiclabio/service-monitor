package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync"
	"testing"

	"svcmon/internal/hubapi"
)

type recorded struct {
	Method string
	Path   string
	Body   map[string]any
}

// fakeHub is just enough of the hub's alert-channel API to see what the CLI sends.
type fakeHub struct {
	mu         sync.Mutex
	channels   []hubapi.AlertChannel
	requests   []recorded
	testResult hubapi.AlertTestResult
}

func (h *fakeHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") != "Bearer tok" {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	var body map[string]any
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&body)
	}
	h.mu.Lock()
	h.requests = append(h.requests, recorded{r.Method, r.URL.Path, body})
	channels := append([]hubapi.AlertChannel(nil), h.channels...)
	h.mu.Unlock()

	const base = "/api/v1/alert-channels"
	w.Header().Set("Content-Type", "application/json")
	switch {
	case r.Method == http.MethodGet && r.URL.Path == base:
		_ = json.NewEncoder(w).Encode(channels)
	case r.Method == http.MethodPost && r.URL.Path == base:
		created := hubapi.AlertChannel{ID: "new-id-1234567", Name: body["name"].(string), Type: body["type"].(string),
			Enabled: body["enabled"] != false, Statuses: []string{"DOWN", "WARN", "UP"}}
		if s, ok := body["statuses"].([]any); ok {
			created.Statuses = nil
			for _, v := range s {
				created.Statuses = append(created.Statuses, v.(string))
			}
		}
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(created)
	case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/test"):
		_ = json.NewEncoder(w).Encode(h.testResult)
	case r.Method == http.MethodPut && strings.HasPrefix(r.URL.Path, base+"/"):
		id := strings.TrimPrefix(r.URL.Path, base+"/")
		for _, c := range channels {
			if c.ID == id {
				if n, ok := body["name"].(string); ok {
					c.Name = n
				}
				if s, ok := body["statuses"].([]any); ok {
					c.Statuses = nil
					for _, v := range s {
						c.Statuses = append(c.Statuses, v.(string))
					}
				}
				_ = json.NewEncoder(w).Encode(c)
				return
			}
		}
		w.WriteHeader(http.StatusNotFound)
	case r.Method == http.MethodDelete:
		w.WriteHeader(http.StatusNoContent)
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

func (h *fakeHub) last() recorded {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.requests[len(h.requests)-1]
}

func (h *fakeHub) count() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.requests)
}

func newFakeHub(t *testing.T, channels ...hubapi.AlertChannel) (*fakeHub, func(args ...string) (int, string, string)) {
	t.Helper()
	hub := &fakeHub{channels: channels, testResult: hubapi.AlertTestResult{OK: true}}
	srv := httptest.NewServer(hub)
	t.Cleanup(srv.Close)
	run := func(args ...string) (int, string, string) {
		var out, errOut bytes.Buffer
		code := Run(append([]string{"--hub", srv.URL, "--token", "tok"}, args...), &out, &errOut)
		return code, out.String(), errOut.String()
	}
	return hub, run
}

func slackChannel(id, name string) hubapi.AlertChannel {
	return hubapi.AlertChannel{ID: id, Name: name, Type: "SLACK", Enabled: true, Statuses: []string{"DOWN", "WARN", "UP"},
		Config: map[string]any{"url": "https://hooks.slack.com/********"}}
}

func TestAlertAddWebhook(t *testing.T) {
	hub, run := newFakeHub(t)
	code, out, errOut := run("alert", "add", "webhook", "--name", "ops", "--url", "https://x.example/hook",
		"--header", "X-Team: ops", "--header", "Authorization=Bearer abc", "--signing-secret", "s3", "--on", "all")
	if code != 0 {
		t.Fatalf("exit %d: %s%s", code, out, errOut)
	}
	body := hub.last().Body
	if hub.last().Method != "POST" || hub.last().Path != "/api/v1/alert-channels" {
		t.Fatalf("unexpected request %+v", hub.last())
	}
	if body["name"] != "ops" || body["type"] != "WEBHOOK" {
		t.Fatalf("body: %v", body)
	}
	cfg := body["config"].(map[string]any)
	if cfg["url"] != "https://x.example/hook" || cfg["signingSecret"] != "s3" {
		t.Fatalf("config: %v", cfg)
	}
	if !reflect.DeepEqual(cfg["headers"], map[string]any{"X-Team": "ops", "Authorization": "Bearer abc"}) {
		t.Fatalf("headers: %v", cfg["headers"])
	}
	if !reflect.DeepEqual(body["statuses"], []any{"DOWN", "WARN", "UP", "UNKNOWN"}) {
		t.Fatalf("statuses: %v", body["statuses"])
	}
	if _, present := body["enabled"]; present {
		t.Fatal("enabled should be left to the hub's default")
	}
	if !strings.Contains(out, "Created webhook channel") {
		t.Fatalf("output: %s", out)
	}
}

func TestAlertAddReadsSecretsFromTheEnvironment(t *testing.T) {
	t.Setenv("SVCMON_ALERT_URL", "https://hooks.slack.com/services/T/B/X")
	hub, run := newFakeHub(t)
	code, _, errOut := run("alert", "add", "slack", "--name", "chat", "--disabled", "--on", "down,up")
	if code != 0 {
		t.Fatalf("exit %d: %s", code, errOut)
	}
	body := hub.last().Body
	if body["config"].(map[string]any)["url"] != "https://hooks.slack.com/services/T/B/X" {
		t.Fatalf("url not taken from the environment: %v", body["config"])
	}
	if body["enabled"] != false || !reflect.DeepEqual(body["statuses"], []any{"DOWN", "UP"}) {
		t.Fatalf("body: %v", body)
	}

	// An explicit flag wins over the environment.
	if code, _, _ := run("alert", "add", "discord", "--name", "d", "--url", "https://discord.com/api/webhooks/1/x"); code != 0 {
		t.Fatal("add discord failed")
	}
	if hub.last().Body["config"].(map[string]any)["url"] != "https://discord.com/api/webhooks/1/x" {
		t.Fatalf("flag should win: %v", hub.last().Body["config"])
	}
}

func TestAlertAddEmail(t *testing.T) {
	t.Setenv("SVCMON_SMTP_PASSWORD", "hunter2")
	hub, run := newFakeHub(t)
	code, _, errOut := run("alert", "add", "email", "--name", "mail", "--smtp-host", "smtp.example.test", "--smtp-port", "2525",
		"--security", "none", "--smtp-user", "svc", "--from", "svcmon@example.test",
		"--to", "ops@example.test,oncall@example.test", "--to", "boss@example.test")
	if code != 0 {
		t.Fatalf("exit %d: %s", code, errOut)
	}
	cfg := hub.last().Body["config"].(map[string]any)
	want := map[string]any{"smtpHost": "smtp.example.test", "smtpPort": float64(2525), "security": "none",
		"username": "svc", "password": "hunter2", "from": "svcmon@example.test",
		"to": []any{"ops@example.test", "oncall@example.test", "boss@example.test"}}
	if !reflect.DeepEqual(cfg, want) {
		t.Fatalf("config:\n got %v\nwant %v", cfg, want)
	}
}

func TestAlertEmailFormatFlag(t *testing.T) {
	hub, run := newFakeHub(t)
	code, _, errOut := run("alert", "add", "email", "--name", "pager", "--smtp-host", "smtp.example.test",
		"--from", "svcmon@example.test", "--to", "5551234@sms.example.test", "--format", "text")
	if code != 0 {
		t.Fatalf("exit %d: %s", code, errOut)
	}
	if got := hub.last().Body["config"].(map[string]any)["format"]; got != "text" {
		t.Fatalf("format = %v, want text", got)
	}

	// Without the flag nothing is sent, so the hub applies its default (HTML).
	if code, _, errOut := run("alert", "add", "email", "--name", "mail", "--smtp-host", "smtp.example.test",
		"--from", "svcmon@example.test", "--to", "ops@example.test"); code != 0 {
		t.Fatalf("exit %d: %s", code, errOut)
	}
	if _, has := hub.last().Body["config"].(map[string]any)["format"]; has {
		t.Fatalf("format should not be sent when the flag is not given: %v", hub.last().Body["config"])
	}

	// The flag belongs to email channels only.
	code, _, errOut = run("alert", "add", "slack", "--name", "x", "--url", "https://x.example/a", "--format", "text")
	if code != exitUnknown || !strings.Contains(errOut, "--format does not apply to slack channels") {
		t.Fatalf("exit %d, stderr %q", code, errOut)
	}
}

func TestAlertAddRejectsMistakesBeforeContactingTheHub(t *testing.T) {
	cases := []struct {
		args []string
		want string
	}{
		{[]string{"alert", "add", "slack", "--url", "https://x.example/a"}, "--name is required"},
		{[]string{"alert", "add", "slack", "--name", "x", "--smtp-host", "h"}, "--smtp-host does not apply to slack"},
		{[]string{"alert", "add", "email", "--name", "x", "--url", "https://x.example/a"}, "--url does not apply to email"},
		{[]string{"alert", "add", "pager", "--name", "x"}, "unknown channel type"},
		{[]string{"alert", "add", "slack", "--name", "x", "--on", "bogus"}, "not a status"},
		{[]string{"alert", "add", "webhook", "--name", "x", "--url", "https://x.example/a", "--header", "nocolon"}, "use \"Name: value\""},
		{[]string{"alert", "add"}, "usage: svcmon alert add"},
	}
	hub, run := newFakeHub(t)
	for _, tc := range cases {
		code, _, errOut := run(tc.args...)
		if code != exitUnknown || !strings.Contains(errOut, tc.want) {
			t.Errorf("%v: exit %d, stderr %q, want %q", tc.args, code, errOut, tc.want)
		}
	}
	if hub.count() != 0 {
		t.Fatalf("the hub should not have been contacted, got %d request(s)", hub.count())
	}
}

func TestAlertListNeverShowsSecrets(t *testing.T) {
	failing := slackChannel("aaaa1111-0000", "ops")
	failing.LastError = "HTTP 500: receiver says no"
	failing.Config = map[string]any{"url": "https://hooks.slack.com/********"}
	ok := slackChannel("bbbb2222-0000", "chat")
	ok.Enabled = false
	now := int64(1_761_312_067_000)
	ok.LastSuccessAt = &now

	_, run := newFakeHub(t, failing, ok)
	code, out, _ := run("alert", "list")
	if code != 0 {
		t.Fatalf("exit %d", code)
	}
	for _, want := range []string{"ops", "chat", "SLACK", "disabled", "enabled", "HTTP 500: receiver says no", "never", "aaaa1111", "DOWN,WARN,UP"} {
		if !strings.Contains(out, want) {
			t.Errorf("list output missing %q:\n%s", want, out)
		}
	}
	if strings.Contains(out, "hooks.slack.com") {
		t.Errorf("list output should not include URLs:\n%s", out)
	}

	_, runEmpty := newFakeHub(t)
	if code, out, _ := runEmpty("alert", "list"); code != 0 || !strings.Contains(out, "No alert channels yet") {
		t.Errorf("empty list: exit %d, %q", code, out)
	}
}

func TestAlertSetChangesOnlyWhatIsGiven(t *testing.T) {
	hub, run := newFakeHub(t, slackChannel("aaaa1111-0000", "ops"))
	code, out, errOut := run("alert", "set", "ops", "--on", "down")
	if code != 0 {
		t.Fatalf("exit %d: %s%s", code, out, errOut)
	}
	req := hub.last()
	if req.Method != "PUT" || req.Path != "/api/v1/alert-channels/aaaa1111-0000" {
		t.Fatalf("request: %+v", req)
	}
	if !reflect.DeepEqual(req.Body["statuses"], []any{"DOWN"}) {
		t.Fatalf("statuses: %v", req.Body["statuses"])
	}
	if _, renamed := req.Body["name"]; renamed {
		t.Fatal("the name was not changed and must not be sent")
	}
	// The masked URL goes back as-is, which tells the hub to keep the stored secret.
	if req.Body["config"].(map[string]any)["url"] != "https://hooks.slack.com/********" {
		t.Fatalf("config: %v", req.Body["config"])
	}

	if _, _, e := run("alert", "set", "ops", "--name", "ops-2"); !strings.Contains(e, "") || hub.last().Body["name"] != "ops-2" {
		t.Fatalf("rename not sent: %v", hub.last().Body)
	}
	if c, _, e := run("alert", "set", "ops"); c != exitUnknown || !strings.Contains(e, "nothing to change") {
		t.Errorf("no flags: exit %d %q", c, e)
	}
	if c, _, e := run("alert", "set", "ops", "--smtp-host", "h"); c != exitUnknown || !strings.Contains(e, "does not apply to slack") {
		t.Errorf("wrong-type flag: exit %d %q", c, e)
	}
}

func TestAlertEnableDisableAndRemove(t *testing.T) {
	hub, run := newFakeHub(t, slackChannel("aaaa1111-0000", "ops"), slackChannel("bbbb2222-0000", "chat"))

	if code, _, _ := run("alert", "disable", "OPS"); code != 0 { // names match case-insensitively
		t.Fatal("disable failed")
	}
	if r := hub.last(); r.Method != "PUT" || r.Body["enabled"] != false || !strings.HasSuffix(r.Path, "aaaa1111-0000") {
		t.Fatalf("disable request: %+v", r)
	}
	if code, _, _ := run("alert", "enable", "ops"); code != 0 || hub.last().Body["enabled"] != true {
		t.Fatalf("enable request: %+v", hub.last())
	}
	if code, out, _ := run("alert", "rm", "bbbb"); code != 0 || !strings.Contains(out, `Deleted channel "chat"`) { // by ID prefix
		t.Fatalf("rm: exit %d %q", code, out)
	}
	if r := hub.last(); r.Method != "DELETE" || !strings.HasSuffix(r.Path, "bbbb2222-0000") {
		t.Fatalf("rm request: %+v", r)
	}
}

func TestAlertTestReportsSuccessAndFailure(t *testing.T) {
	hub, run := newFakeHub(t, slackChannel("aaaa1111-0000", "ops"))
	code, out, _ := run("alert", "test", "ops")
	if code != 0 || !strings.Contains(out, `Delivered a test alert to "ops"`) {
		t.Fatalf("success: exit %d %q", code, out)
	}
	if r := hub.last(); r.Method != "POST" || !strings.HasSuffix(r.Path, "/aaaa1111-0000/test") {
		t.Fatalf("request: %+v", r)
	}

	hub.testResult = hubapi.AlertTestResult{OK: false, Error: "HTTP 500: nope"}
	code, _, errOut := run("alert", "test", "ops")
	if code != exitUnknown || !strings.Contains(errOut, "FAILED: HTTP 500: nope") {
		t.Fatalf("failure: exit %d %q", code, errOut)
	}
}

func TestAlertChannelLookupErrors(t *testing.T) {
	_, run := newFakeHub(t, slackChannel("abcd1111-0000", "ops"), slackChannel("abcd2222-0000", "chat"))
	if code, _, e := run("alert", "rm", "nothing"); code != exitUnknown || !strings.Contains(e, "no alert channel matches") {
		t.Errorf("unknown: exit %d %q", code, e)
	}
	if code, _, e := run("alert", "rm", "abcd"); code != exitUnknown || !strings.Contains(e, "matches 2 channels") {
		t.Errorf("ambiguous: exit %d %q", code, e)
	}
	if code, _, e := run("alert", "frobnicate"); code != exitUnknown || !strings.Contains(e, "unknown alert command") {
		t.Errorf("bad subcommand: exit %d %q", code, e)
	}
}

func TestParseStatusesAndHeaders(t *testing.T) {
	if got, err := parseStatuses(" down, WARN ,down"); err != nil || !reflect.DeepEqual(got, []string{"DOWN", "WARN"}) {
		t.Errorf("statuses: %v %v", got, err)
	}
	if got, _ := parseStatuses("ALL"); len(got) != 4 {
		t.Errorf("all: %v", got)
	}
	for _, bad := range []string{"", " , ", "red"} {
		if _, err := parseStatuses(bad); err == nil {
			t.Errorf("%q should be rejected", bad)
		}
	}
	if n, v, err := parseHeader("Authorization: Bearer a:b"); err != nil || n != "Authorization" || v != "Bearer a:b" {
		t.Errorf("header: %q %q %v", n, v, err)
	}
	if n, v, err := parseHeader("X-A=1"); err != nil || n != "X-A" || v != "1" {
		t.Errorf("header with =: %q %q %v", n, v, err)
	}
	for _, bad := range []string{"", "novalue", ": v", "k:", "k:  "} {
		if _, _, err := parseHeader(bad); err == nil {
			t.Errorf("header %q should be rejected", bad)
		}
	}
}
