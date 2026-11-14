# Repository Guidelines

## Project Structure & Module Organization

- `android/` contains the Kotlin Android app. Production code lives under `app/src/main/java/com/example/remoteapplock/`, while manifests and XML resources are in `app/src/main/`.
- `cloudflare/` contains the Cloudflare Worker (`src/index.js`), D1 schema (`schema.sql`), Wrangler configuration, and npm scripts.
- `dashboard/index.html` is a self-contained HTML/CSS/JavaScript control panel with no build step.
- `tests/` contains backend integration tests. Keep cross-component setup guidance in `README.md` and quick commands in `QUICK_START.txt`.

## Build, Test, and Development Commands

From `cloudflare/`:

- `npm install` installs Wrangler.
- `npm run dev` starts the Worker locally.
- `npm test` runs backend integration tests with Node.js 22+ and in-memory SQLite.
- `npm run db:local` applies `schema.sql` to local D1 state.
- `npm run deploy` deploys the Worker; configure secrets and the D1 ID first.

From `android/`, bootstrap the wrapper once with `powershell -ExecutionPolicy Bypass -File .\bootstrap-wrapper.ps1`, then run `gradlew.bat :app:assembleDebug`. Use JDK 17 and Android SDK 36. Open `dashboard/index.html` directly in a browser for dashboard work.

## Coding Style & Naming Conventions

Follow the existing style in each component. Kotlin uses four-space indentation, `PascalCase` classes, `camelCase` members, and package name `com.example.remoteapplock`. JavaScript uses two-space indentation, single quotes, semicolons, ESM syntax, and `camelCase` identifiers. Keep the dashboard dependency-free and preserve its compact inline CSS/JS organization unless a broader refactor is intentional. No formatter or linter is configured, so keep diffs focused and match adjacent code.

## Testing Guidelines

Use Node's built-in `node:test` framework and strict assertions. Name backend files `*.test.mjs` and describe behavior in test names. Add coverage for successful flows and security boundaries such as device authentication and OTP expiry/attempt limits. Run `npm test` before every PR. Android accessibility and overlay changes also require manual validation on an emulator or physical Android 8.0+ device.

## Commit & Pull Request Guidelines

Git history is unavailable in this checkout. Use short, imperative commits, preferably Conventional Commit style, for example `fix(worker): reject invalid device tokens`. Keep commits scoped to one concern. PRs should explain user-visible behavior, list verification commands, note schema/configuration changes, and link related issues. Include screenshots for dashboard or Android UI changes and never commit tokens, `.dev.vars`, `local.properties`, APKs, or the downloaded Gradle wrapper JAR.

## Security & Configuration

Treat `ADMIN_TOKEN`, device tokens, and `OTP_SECRET` as secrets. Keep local values in ignored environment files or Wrangler secrets. Use only devices you own or administer with informed consent.
