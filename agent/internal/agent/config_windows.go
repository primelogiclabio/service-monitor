//go:build windows

package agent

import (
	"fmt"
	"os/exec"
	"os/user"
	"strings"
)

// restrict removes inherited permissions and leaves the config accessible only to SYSTEM (which runs the
// service), Administrators, and the account that wrote it, because the file holds the agent's token.
// Windows ignores the 0600 file mode.
func restrict(path string) error {
	grants := []string{"*S-1-5-18:F", "*S-1-5-32-544:F"}
	if u, err := user.Current(); err == nil && strings.HasPrefix(u.Uid, "S-1-") {
		grants = append(grants, "*"+u.Uid+":F")
	}
	args := append([]string{path, "/inheritance:r", "/grant:r"}, grants...)
	out, err := exec.Command("icacls", args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("restricting access to %s: %v: %s", path, err, out)
	}
	return nil
}
