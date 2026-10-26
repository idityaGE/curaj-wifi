# Sentry

The Android app reports crashes, handled portal errors, and performance
transactions to Sentry project `idityage/curaj-wifi`.

## What is enabled

- Crash and exception monitoring.
- App-start and Activity tracing.
- Portal transactions:
  - `portal.login`
  - `portal.logout`
  - `portal.sessions.list`
  - `portal.sessions.close`
- A warning-level `network-bind-error` event for the intermittent Android
  `EPERM` / `EACCES` Wi-Fi bind failure.
- Source context upload on release builds when a Sentry auth token is
  available (plus ProGuard/R8 mapping if minification is enabled).

Session Replay, screenshots, view hierarchies, profiling, and default PII are
disabled.

## Credential scrubbing

The portal password must never leave the device through Sentry. `CurajApp.kt`
sets:

- `sendDefaultPii = false`
- `attachScreenshot = false`
- `attachViewHierarchy = false`
- `debug = false`
- a `beforeSend` callback that removes the request object, the user object, and
  any extra whose key looks like a password, credential, cookie, authorization
  header, or username; it also re-sanitizes breadcrumbs as a second pass
- a `beforeBreadcrumb` callback that removes HTTP query strings, fragments,
  request/response bodies, cookies, headers, and the same sensitive keys, and
  strips query strings and fragments from `http.url`

The key comparison uses `Locale.ROOT`, so the filter behaves the same on
Turkish and other non-English device locales. Portal exception messages and
transaction names do not include credentials.

Sentry's manifest auto-init provider is disabled
(`io.sentry.auto-init=false`). `CurajApp` is the only initializer, which avoids
a second initialization with an empty DSN.

## Local configuration

The DSN is public, but it lives outside the repository with the signing
material:

```text
~/.config/curaj-wifi/sentry.properties
```

```properties
dsn=https://<public-key>@o<org>.ingest.sentry.io/<project-id>
org=idityage
project=curaj-wifi
```

Override the path with `CURAJ_SENTRY_PROPERTIES`, or set `SENTRY_DSN` in the
environment.

The build-time auth token is secret and is **never** written to the repository
or the APK. Put it in the environment when building a release with source
context:

```bash
export SENTRY_AUTH_TOKEN="..."
```

Alternatively add `authToken=...` to the same mode-600
`~/.config/curaj-wifi/sentry.properties` file. Do not commit that file.

If the token is absent, the build still works and source-context upload is
skipped.

The source bundle contains the app's Kotlin source files, not runtime
credentials. It is uploaded to Sentry so stack traces can show the matching
lines. Source context is only applied to events captured after the upload.

The project uses the Sentry Android Gradle plugin `6.24.0` with the matching
Sentry Android SDK `8.61.1`. The plugin's source-bundle task requires Gradle
8.14.x, so the wrapper is pinned to `8.14.6`; do not downgrade the wrapper to
8.9.

## Build and verify

Debug build:

```bash
cd android
JAVA_HOME="$(mise where java)" ./gradlew :app:assembleDebug
```

Debug builds add a **Send test event** item to the toolbar overflow menu. It
sends an `IllegalStateException` to Sentry and is not included in release
builds.

Release build with source context:

```bash
cd android
export SENTRY_AUTH_TOKEN="..."
JAVA_HOME="$(mise where java)" ./gradlew :app:assembleRelease
```

With a token, the build runs `sentryCollectSourcesRelease`,
`sentryBundleSourcesRelease`, and `sentryUploadSourceBundleRelease`. The last
task should end with `UPLOADED <debug-id> (<debug-id>.zip; sources)`.

Verify:

1. The test event appears in Sentry under the `curaj-wifi` project.
2. The event does not contain the portal password, username, request body,
   cookies, or Authorization headers.
3. A portal login produces a `portal.login` transaction.
4. The source bundle appears under **Project Settings → Debug Files** with
   symbol type `sourcebundle`, and a release event shows readable Kotlin frames
   and source context.
5. The release APK still verifies with `apksigner`.

## OpenCode

The Sentry MCP server is configured in the global OpenCode config:

```bash
opencode mcp add sentry --global --url https://mcp.sentry.dev/mcp
```

Authenticate once through OpenCode:

```text
/mcps → sentry → sign in
```

The MCP is for reading issues, events, and traces from Sentry. It is not needed
for the app to send telemetry.
