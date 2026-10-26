# Making a release

A release is a tag. The **Release** workflow (`.github/workflows/release.yml`) builds every file from it and leaves them in a **draft** GitHub release; nothing is public until you publish that draft.

What it builds, for version `X.Y.Z`:

| File | From |
|---|---|
| `svcmon-hub-X.Y.Z.jar` | the hub, built with the dashboard inside (`hub/src/main/resources/ui`), tested, and started once by `.github/release/smoke-hub-jar.sh` |
| `svcmon-hub-X.Y.Z-windows-x64.zip` | `hub/service/windows/build-package.ps1` (jar, WinSW, installer) |
| `svcmon-hub-X.Y.Z-linux.tar.gz` | the jar and `hub/service/linux/` |
| `svcmon-agent-X.Y.Z-<os>-<cpu>[.exe]` | the agent and CLI for windows amd64/arm64, linux amd64/arm64, darwin amd64/arm64, freebsd amd64 |
| `service-monitor-ui-X.Y.Z-dist.zip` | the dashboard alone |
| `SHA256SUMS` | a checksum of each file above |

The version comes only from the tag: the workflow sets the hub's version with Maven and the agent's with `-ldflags -X`, so `GET /api/v1/hub` and `svcmon version` report `X.Y.Z`. `pom.xml` stays `0.1.0-SNAPSHOT` in the repository.

## Steps

1. `main` is green in both repositories and the docs match the code (README, reference, design document, trust policy).
2. **Rehearse** (optional but cheap): Actions, **Release**, **Run workflow**, version `X.Y.Z-test`, dashboard ref `main`. It builds everything the same way, publishes nothing and keeps the files as a workflow artifact for 7 days.
3. **Tag the dashboard repository first**, since its build goes inside the hub jar:

   ```bash
   cd service-monitor-ui
   git switch main && git pull
   git tag vX.Y.Z && git push origin vX.Y.Z
   ```

4. **Tag this repository**: the same, in `service-monitor`. The workflow starts (it stops at once, naming the problem, if the dashboard tag is missing).
5. Open the draft under **Releases**. Download a file or two, check them against `SHA256SUMS`, run the hub jar and an agent from them.
6. Publish the draft. A version with a `-` (for example `0.2.0-rc.1`) is published as a pre-release.

## Mistakes

- A tag whose release is wrong: delete the draft release and the tag (`git push origin :refs/tags/vX.Y.Z`), fix, tag again. Do not move a tag that was published.
- Nothing is signed. If that changes, the trust policy (section 6) changes with it.
