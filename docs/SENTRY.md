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
- Source context and ProGuard/R8 mapping upload on release builds when a Sentry
  auth token is available.

Session Replay, screenshots, view hierarchies, profiling, and default PII are
disabled.

## Credential scrubbing

The portal password must never leave the device through Sentry. `CurajApp.kt`
sets:

- `sendDefaultPii = false`
- `attachScreenshot = false`
- `attachViewHierarchy = false`
- a `beforeSend` callback that removes the request object, the user object, and
  any extra whose key looks like a password, credential, cookie, authorization
  header, or username
- a `beforeBreadcrumb` callback that removes HTTP query strings and the same
  sensitive keys

Portal exception messages and transaction names do not include credentials.

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

Verify:

1. The test event appears in Sentry under the `curaj-wifi` project.
2. The event does not contain the portal password, username, request body,
   cookies, or Authorization headers.
3. A portal login produces a `portal.login` transaction.
4. A release event shows readable Kotlin frames and source context.
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
