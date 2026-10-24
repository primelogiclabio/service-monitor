// Package hubapi is a small client for the hub's REST API, shared by the agent and the CLI.
package hubapi

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Client talks to one hub with one bearer token.
type Client struct {
	Base  string
	Token string
	HTTP  *http.Client
}

// New returns a client for a hub base URL such as "http://hub:8080".
func New(base, token string) *Client {
	return &Client{
		Base:  strings.TrimRight(strings.TrimSpace(base), "/"),
		Token: token,
		HTTP:  &http.Client{Timeout: 30 * time.Second},
	}
}

// APIError is a non-2xx answer from the hub.
type APIError struct {
	Status  int
	Message string
}

func (e *APIError) Error() string {
	return fmt.Sprintf("hub returned %d: %s", e.Status, e.Message)
}

// Do sends a JSON request to /api/v1<path> and decodes a JSON answer into out (when not nil).
func (c *Client) Do(ctx context.Context, method, path string, in, out any) error {
	var body io.Reader
	if in != nil {
		b, err := json.Marshal(in)
		if err != nil {
			return err
		}
		body = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.Base+"/api/v1"+path, body)
	if err != nil {
		return err
	}
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "svcmon")

	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode >= 300 {
		var e struct {
			Message string `json:"message"`
		}
		msg := strings.TrimSpace(string(data))
		if json.Unmarshal(data, &e) == nil && e.Message != "" {
			msg = e.Message
		}
		if msg == "" {
			msg = http.StatusText(resp.StatusCode)
		}
		return &APIError{Status: resp.StatusCode, Message: msg}
	}
	if out != nil && len(data) > 0 {
		return json.Unmarshal(data, out)
	}
	return nil
}
