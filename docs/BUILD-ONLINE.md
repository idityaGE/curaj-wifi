# Building the APK online

> **GitHub Actions is disabled for this repository.** The supported build and
> release path is local, using [LOCAL-BUILD.md](LOCAL-BUILD.md) and
> [RELEASE-BUILD.md](RELEASE-BUILD.md). This page is retained as a reference
> for the other CI services listed below.

The project is a native Kotlin/Gradle Android app, so **Expo EAS Build does
not apply** — EAS builds Expo/React Native projects, not an `android/` Gradle
project. The equivalent services for this app are:

| service | native Android | free tier | best for |
|---|---|---|---|
| **GitHub Actions** | yes | public repos free; private repos get a monthly minute allowance | one-click builds, artifact download, no extra account |
| **Codemagic** | yes | free minutes for small/private apps | GUI + `codemagic.yaml`, email the APK |
| **Bitrise** | yes | free plan for small projects | GUI workflow editor |
| **Appcircle** | yes | free starter plan | GUI, enterprise distribution |
| **GitLab CI** | yes | free minutes on GitLab.com | projects already on GitLab |
| **GitHub Codespaces / Gitpod** | yes | monthly free core-hours | a browser IDE where you run `./gradlew` yourself |
| **Expo EAS Build** | **no** | — | Expo / React Native only |
| random “online APK builder” sites | — | — | **avoid**; you cannot audit what they inject |

Recommendation for this repository: **build locally**. GitHub Actions is
disabled; the workflow files remain at `.github/workflows/` only as a
reference and will not run.

---

## 1. GitHub Actions (reference only; disabled here)

### 1.1 Put a fresh copy on GitHub

This project is already a git repository. The commands below are only for a
new copy; do not run `git init` in the existing checkout.

```bash
cd ~/curaj-wifi
git status --short
```

Create an empty repository on GitHub, then:

```bash
git remote add origin git@github.com:<you>/curaj-wifi.git
git branch -M main
git push -u origin main
```

Or use the GitHub CLI:

```bash
gh auth login
gh repo create curaj-wifi --private --source=. --remote=origin --push
```

Before pushing, confirm no credentials or keys are included:

```bash
git status --short
git grep -n "password" -- ':!README.md' ':!docs/*' || true
```

The real credentials live only in `~/.config/curaj-wifi/config.json` on the
Linux machine and are never copied into the repo. The Android app asks for
them at runtime and stores them in the Keystore.

### 1.2 Run the workflow

The included workflow runs on every push to `main`/`master`, on pull requests,
and manually:

1. Open the repository on GitHub.
2. **Actions** → **Build Android APK** → **Run workflow**.
3. Wait ~3–6 minutes for the first run (Gradle and the Android SDK are
   downloaded and cached).
4. Open the finished run → **Artifacts** → download
   **`curaj-autologin-debug`**.
5. Unzip and install:

```bash
unzip curaj-autologin-debug.zip
adb install -r app-debug.apk
```

The artifact is kept for 30 days.

### What the workflow does

`.github/workflows/android.yml`:

```yaml
- actions/checkout@v4
- actions/setup-java@v4          # Temurin JDK 17
- android-actions/setup-android@v4
    packages: platform-tools platforms;android-35 build-tools;35.0.0
- gradle/actions/setup-gradle@v4
- ./gradlew assembleDebug --no-daemon --stacktrace
- actions/upload-artifact@v4     # app-debug.apk
```

The project now includes the official **Gradle 8.14.6 wrapper**, so the same
`./gradlew assembleDebug` command works on GitHub Actions, Codemagic, Bitrise,
GitLab CI, or your own machine.

### Optional: attach the APK to a GitHub Release

If you want a permanent download link instead of a 30-day artifact, add a
second workflow triggered by a tag:

```yaml
name: Release APK
on:
  push:
    tags: [ 'v*' ]
permissions:
  contents: write
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: android-actions/setup-android@v4
        with: { packages: 'platform-tools platforms;android-35 build-tools;35.0.0' }
      - uses: gradle/actions/setup-gradle@v4
      - run: cd android && ./gradlew assembleDebug --no-daemon
      - uses: softprops/action-gh-release@v2
        with:
          files: android/app/build/outputs/apk/debug/app-debug.apk
```

Then `git tag v1.1 && git push --tags`.

> This is a **debug** APK. It is installable by sideloading, but it is not
> signed with a release key and cannot be uploaded to Google Play. That is fine
> for personal use. For a signed APK to share with other people, use
> [RELEASE-BUILD.md](RELEASE-BUILD.md) and the included
> `.github/workflows/android-release.yml`.

---

## 2. Codemagic

Codemagic is the closest thing to Expo EAS in terms of UX: connect a repo, get
a GUI, receive the APK by email.

1. Sign in at <https://codemagic.io> with GitHub/GitLab/Bitbucket.
2. Add the `curaj-wifi` repository.
3. Choose **Android** and let it detect the project.
4. Use this `codemagic.yaml` at the repo root (or paste the equivalent in the
   workflow editor):

```yaml
workflows:
  android-debug:
    name: CURAJ Auto Login (debug)
    max_build_duration: 30
    instance_type: linux_x2
    environment:
      java: 17
    scripts:
      - name: Build debug APK
        script: |
          cd android
          ./gradlew assembleDebug --no-daemon --stacktrace
    artifacts:
      - android/app/build/outputs/apk/debug/*.apk
    publishing:
      email:
        recipients:
          - you@example.com
        notify:
          success: true
          failure: true
```

5. Start the build. Download the APK from the build page or the email.

Codemagic’s free tier has a monthly minute allowance; a debug build of this
small app is typically a few minutes after the first cache warm-up.

---

## 3. Bitrise

1. Sign in at <https://bitrise.io>.
2. **Add new app** → connect the repository.
3. Bitrise scans the project and suggests an Android workflow.
4. Make sure the workflow runs:
   ```bash
   cd android
   ./gradlew assembleDebug
   ```
5. Add an **Android Build** step with variant `debug`, or keep the generated
   `:app:assembleDebug` step.
6. Add the artifact path:
   ```
   android/app/build/outputs/apk/debug/app-debug.apk
   ```
7. Run the build and download the artifact from the build page.

Bitrise’s free plan is fine for occasional personal builds; check the current
minute allowance.

---

## 4. Appcircle

1. Sign in at <https://appcircle.io>.
2. Connect the repository and create an **Android** build profile.
3. Select the `app` module and the `debug` variant.
4. Start the build; download the APK from the build output.

Appcircle’s free starter plan works for a single personal app.

---

## 5. GitLab CI

If the repo is on GitLab, add `.gitlab-ci.yml`:

```yaml
image: mingc/android-build-box:latest

build-debug:
  stage: build
  script:
    - cd android
    - chmod +x gradlew
    - ./gradlew assembleDebug --no-daemon --stacktrace
  artifacts:
    paths:
      - android/app/build/outputs/apk/debug/app-debug.apk
    expire_in: 30 days
```

`mingc/android-build-box` includes the Android SDK and JDK. Pin a specific tag
if you want reproducible builds.

---

## 6. Browser IDE instead of a build service

If you want to compile it yourself in a browser:

* **GitHub Codespaces** — open the repo → **Code** → **Codespaces** → create.
  Install JDK 17 and the Android command-line tools, then run
  `cd android && ./gradlew assembleDebug`. The APK appears in the file
  explorer; right-click → download.
* **Gitpod** — same idea with a `.gitpod.yml` that installs JDK 17 and the
  Android SDK.

This is more setup than GitHub Actions, but useful if you want to run
`./gradlew` and see the full output interactively.

A minimal Codespaces setup:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk unzip
export ANDROID_SDK_ROOT="$HOME/android-sdk"
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
curl -fsSLo /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q /tmp/tools.zip -d "$ANDROID_SDK_ROOT/cmdline-tools"
mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
yes | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"
cd android && ./gradlew assembleDebug
```

---

## 7. Why not Expo EAS?

EAS Build expects an Expo/React Native project: `package.json`, an Expo config,
and a JavaScript bundle. It does run Gradle internally for bare React Native,
but it is not a general “upload any Android project” service.

To use EAS you would have to rewrite the UI and service in React Native — a lot
of work for a four-request HTTP login. The native Kotlin app is smaller and has
direct access to `ConnectivityManager` and `Network.openConnection`, which is
exactly what this task needs.

---

## 8. Security notes for any online builder

* The repository contains **no** portal username/password. They are entered on
  the phone and stored with the Android Keystore.
* Never commit `~/.config/curaj-wifi/config.json`, a `.jks`/keystore, or a
  `keystore.properties` file. The included `.gitignore` covers the usual
  Android build outputs and keys.
* Prefer a private repository. A public repo is fine for the code, but it
  reveals the portal host and your automation; the credentials are still not
  in it.
* Avoid anonymous “build my APK” websites. A malicious builder can add code to
  the APK that exfiltrates the password when you type it. GitHub Actions,
  Codemagic, Bitrise, Appcircle and GitLab are auditable, reputable services.
* A debug APK is signed with a debug key. If you later publish a release, keep
  the release keystore in CI secrets, never in the repo.
