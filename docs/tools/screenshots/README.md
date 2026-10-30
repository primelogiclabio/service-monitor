# How the screenshots in `docs/assets` were made

They show a **throwaway demo hub** filled with made-up servers, so they contain no real system, name or secret. To make them again after the dashboard changes:

1. Build the dashboard (`npm run build` in the dashboard repository) and the hub jar, and copy the jar somewhere (a running jar is locked on Windows).
2. Start a hub with a new, empty data folder on a spare port, for example:
   `java -jar svcmon-hub.jar --server.port=8091 --svcmon.data-dir=<new folder> --svcmon.bootstrap-admin-token=<a throwaway token of 32+ characters> --svcmon.ui-dir=<dashboard>/dist --svcmon.agent-stale-seconds=25 --svcmon.version=0.1.0`
   (`svcmon.version` is the release the footer shows, instead of the jar's `-SNAPSHOT` version.)
3. Fill it with servers and keep them reporting (leave both running):
   `node <dashboard>/scripts/seed-demo.mjs --hub http://127.0.0.1:8091 --token <token> --hours 6 --live`
   `node seed-containers.mjs --hub http://127.0.0.1:8091 --token <token>` (this folder; adds the `docker01` server whose container checks are declared by its own agent)
4. Start Edge or Chrome headless with a debugging port and a fresh profile:
   `msedge --headless=new --disable-gpu --hide-scrollbars --remote-debugging-port=9223 --user-data-dir=<new folder> about:blank`
5. Take the pictures, with the server ids from `GET /api/v1/servers`:
   `node cdp-shots.mjs 9223 <out folder> http://127.0.0.1:8091 <token> <docker01 id> <web02 id>`
   The dashboard signs people in with a user name and password, so the script uses the token to make an administrator named `demo` (or to reset its password on a later run), signs the browser in with the temporary password and changes it to a random one it does not keep.
6. Copy the ones you want into `docs/assets`, dropping the `-light` ending (`check-chart-light.png` becomes `check-chart.png`; only the dashboard keeps both `dashboard-light.png` and `dashboard-dark.png`), and stop the demo hub, the seeders and the browser.

Never point the seed scripts at a hub that monitors real systems, and do not publish a screenshot of a real hub.
