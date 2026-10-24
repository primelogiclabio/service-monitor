//go:build !windows

package agent

import "os"

// restrict makes sure the file is private to its owner (it holds the agent's token).
func restrict(path string) error {
	return os.Chmod(path, 0o600)
}
