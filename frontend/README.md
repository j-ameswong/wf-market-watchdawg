# Operator console development

The production UI is browser-native JavaScript and CSS in
`market/src/main/resources/static/`. Spring serves it from the same origin as
`/api/operator`; Gradle packages it directly into the jar. There is no frontend
production build or separate web server.

Run the existing service with `nix develop --command mrun` and open
`http://localhost:8080`. See [operations](../deploy/OPERATIONS.md) for homelab and
the hostname allowlist. Runtime configuration saves supersede startup YAML and
persist in the database; use a development database when editing locally.

## Browser checks

```sh
cd frontend
npm ci
npx playwright install chromium
npm run format:check
cd ..
nix develop --command bash -c 'cd market && ./gradlew build'
nix develop --command bash frontend/run-tests.sh
```

The script starts a disposable TimescaleDB and the packaged Java application on
random loopback ports, with all collectors disabled. Synthetic catalog/statistics
rows test UI mechanics only. Playwright exercises actual API persistence and forms;
mocked responses cover network failures and delayed responses. The script then
restarts the process and verifies saved configuration survives. Its trap removes
the test process, container, volume and temporary files.

Screenshots/traces land in ignored `frontend/test-results/`. Tests run serially
because they share one disposable database. `npm test` alone targets
`WATCHDAWG_TEST_URL` (default `http://127.0.0.1:18082`); **never point the mutation
suite at an operational service**. Prefer the disposable script above.

Run `npm run format` to format assets and tests. Node dependencies here are
development tooling only and do not change the Gradle/Nix dependency cache.
