# Building and distributing the production APK

This is the release path for sharing the app with friends at the university.
It is **not** a Google Play release: it is a signed APK that people install
directly (sideload). The important difference from the debug APK is the signing
key and `debuggable=false`, not the app’s features.

---

## 1. What “production” means here

| | debug APK | production APK |
|---|---|---|
| build type | `debug` | `release` |
| `debuggable` | true | false |
| signing key | Android debug keystore | your release keystore |
| updates | works over other debug builds | works over other builds signed with the same release key |
| Play Store | no | no |

Both are installable by sideloading. The release APK is the one to give to
other people, because it is not debuggable and has a stable, private signing
identity.

Do **not** share the keystore or its passwords. The APK itself is safe to
share; it contains no campus credentials.

---

## 2. Current build

The release APK has been built on this machine:

```
android/app/build/outputs/apk/release/app-release.apk
```

A copy prepared for sharing:

```
dist/CURAJ-AutoLogin-1.5.apk
dist/SHA256SUMS.txt
dist/INSTALL.txt
```

| item | value |
|---|---|
| package | `in.ac.curaj.autologin` |
| version | 1.5 (versionCode 6) |
| size | 5.4 MB |
| SHA-256 | `f13c37387bcca49602e066c6c7855907b25c0284234a8b64194967c5120914a9` |
| APK signature | APK Signature Scheme v2, verified |
| certificate SHA-256 | `55da02f424dbf6a4dfd9521b236c1bba4f598fe23db477afceea82fa894ac092` |

The code is the tested 1.3 login/logout code plus the 1.4 session-management
changes and the 1.5 Material 3 dashboard, network monitor, and `EPERM` retry.
Only the build type and signing key differ from a debug build.

---

## 3. The release key

The release key was generated once and stored outside the repository:

```
~/.config/curaj-wifi/curaj-release.jks
~/.config/curaj-wifi/release-signing.properties   # mode 600
```

`release-signing.properties` contains:

```
storeFile=/home/idityage/.config/curaj-wifi/curaj-release.jks
storePassword=<generated>
keyAlias=curaj
keyPassword=<generated>
```

The password was generated randomly and is not printed in the documentation.
Read it from the properties file when you need it.

Certificate details:

```
Alias:   curaj
Key:     RSA 4096-bit
Valid:   2026-10-21 to 2054-03-08 (10000 days)
SHA-256: 55:DA:02:F4:24:DB:F6:A4:DF:D9:52:1B:23:6C:1B:BA:4F:59:8F:E2:3D:B4:77:AF:CE:EA:82:FA:89:4A:C0:92
```

### Back this up now

Copy **both** files to an encrypted backup (password manager attachment,
encrypted USB drive, or a private vault):

```
~/.config/curaj-wifi/curaj-release.jks
~/.config/curaj-wifi/release-signing.properties
```

Why it matters:

* **Lost key** → you cannot publish an update that installs over the existing
  app. Friends must uninstall and reinstall, losing the app’s stored settings.
* **Leaked key** → someone else can sign a malicious APK that Android accepts
  as an update to your app.

Do not commit either file. Do not put them in a public GitHub repository.

---

## 4. How Gradle finds the key

`android/app/build.gradle.kts` reads signing material from:

1. `$CURAJ_SIGNING_PROPERTIES`, if set; otherwise
2. `~/.config/curaj-wifi/release-signing.properties`.

If that file exists, the `release` build type is signed with it. If it does not
exist, `assembleRelease` produces an **unsigned** APK; `assembleDebug` still
works normally.

This means the repository contains no passwords or keystore files.

---

## 5. Build the release APK

```bash
export JAVA_HOME="$HOME/.local/share/mise/installs/java/temurin-17.0.20+101"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$PATH"

cd ~/curaj-wifi/android
./gradlew clean assembleRelease --no-daemon
```

The APK appears at:

```
app/build/outputs/apk/release/app-release.apk
```

If Gradle says it cannot find `storeFile`, check that the path inside
`release-signing.properties` is absolute and that the `.jks` file exists.

---

## 6. Verify before sharing

```bash
APK=app/build/outputs/apk/release/app-release.apk

"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify \
  --verbose --print-certs "$APK"

"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$APK" \
  | grep -E '^package:|^application-label:|^sdkVersion:|^targetSdkVersion:'

sha256sum "$APK"
```

Expected:

```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
package: name='in.ac.curaj.autologin' versionCode='6' versionName='1.5'
application-label:'CURAJ Auto Login'
```

The release build must **not** contain `debuggable`. `aapt dump badging` not
showing a `debuggable` line is the expected result.

Then prepare the distribution copy:

```bash
mkdir -p ~/curaj-wifi/dist
cp "$APK" ~/curaj-wifi/dist/CURAJ-AutoLogin-1.5.apk
cd ~/curaj-wifi/dist
sha256sum CURAJ-AutoLogin-1.5.apk > SHA256SUMS.txt
```

---

## 7. How friends install it

Send them `CURAJ-AutoLogin-1.5.apk` (and optionally `SHA256SUMS.txt` and
`INSTALL.txt`).

On the phone:

1. Save the APK to Files/Downloads.
2. Tap it.
3. Android asks to allow installs from the app opening the APK (Files, Chrome,
   Telegram, etc.). Allow that source.
4. Tap **Install**. Play Protect may warn because the APK is not from the Play
   Store; that is expected for a sideloaded app.
5. Open **CURAJ Auto Login**.
6. Each person enters **their own** mobile number and portal password. The
   fields save automatically.
7. Allow notifications.
8. Turn on **Auto connect**.
9. Optionally tap **Enable Wi-Fi details** to show the SSID and signal
   percentage; login works without it.
10. Open the three-dot menu → **Battery optimisation** and set the app to
    **Unrestricted / Don’t optimise**.
11. On Xiaomi/Oppo/Vivo, also allow Autostart/background activity.
12. Tap **Connect now** or wait for the service; when connected, the main
    button becomes **Log out**.

The app has no credentials baked in. Everyone uses their own account.

### If they already have the debug APK

A release-signed APK cannot update a debug-signed installation. Android will
show **App not installed** or a signature mismatch. Uninstall the old
**CURAJ Auto Login**, then install the release APK. This also clears the old
app’s saved credentials.

---

## 8. Publishing updates

For every new release:

1. Increment `versionCode` and `versionName` in
   `android/app/build.gradle.kts`.
2. Keep the **same** `curaj-release.jks` and alias.
3. Rebuild, verify, and share the new APK.

Friends can install the new APK over the old one without uninstalling, as long
as the package name and signing key are the same. Android requires the
`versionCode` to increase for an in-place update.

Do not change `applicationId` (`in.ac.curaj.autologin`) between releases unless
you intentionally want a separate app.

---

## 9. Building the release in GitHub Actions

The repository includes `.github/workflows/android-release.yml`. It builds a
signed release when you push a tag such as `v1.5`, and attaches the APK to the
GitHub Release.

Configure these repository secrets:

| secret | value |
|---|---|
| `CURAJ_KEYSTORE_BASE64` | base64 of `curaj-release.jks` |
| `CURAJ_STORE_PASSWORD` | `storePassword` from the properties file |
| `CURAJ_KEY_ALIAS` | `curaj` |
| `CURAJ_KEY_PASSWORD` | `keyPassword` from the properties file |

Create the base64 value locally:

```bash
base64 -w0 ~/.config/curaj-wifi/curaj-release.jks
```

Paste the output into the `CURAJ_KEYSTORE_BASE64` secret. Never paste the
keystore or passwords into a workflow file or a commit.

Then:

```bash
git tag v1.5
git push origin v1.5
```

The workflow writes the secrets to a temporary properties file, sets
`CURAJ_SIGNING_PROPERTIES`, builds `assembleRelease`, checks that
`app-release.apk` exists, and uploads the APK plus its SHA-256.

If the signing secrets are missing, the workflow fails instead of silently
publishing an unsigned APK.

---

## 10. Creating your own key instead

If you do not want to use the key generated here, create your own:

```bash
mkdir -p ~/.config/curaj-wifi
umask 077
keytool -genkeypair -v \
  -keystore ~/.config/curaj-wifi/curaj-release.jks \
  -alias curaj \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass 'YOUR-STRONG-PASSWORD' \
  -keypass 'YOUR-STRONG-PASSWORD' \
  -dname "CN=CURAJ Auto Login, OU=Android, O=CURAJ, L=Ajmer, ST=Rajasthan, C=IN"
```

Then write `~/.config/curaj-wifi/release-signing.properties`:

```properties
storeFile=/home/YOU/.config/curaj-wifi/curaj-release.jks
storePassword=YOUR-STRONG-PASSWORD
keyAlias=curaj
keyPassword=YOUR-STRONG-PASSWORD
```

Use a password manager-generated password. Keep the keystore and password
together in a secure backup, but not in the repository.

---

## 11. Troubleshooting

| symptom | cause | fix |
|---|---|---|
| `App not installed` / signature mismatch | debug build or a different release key is already installed | uninstall the old app, then install; future updates must use the same key |
| `storeFile missing` / `Keystore was tampered with` | wrong path or wrong password | check `release-signing.properties` and the `.jks` path |
| `app-release-unsigned.apk` is produced | signing properties file was not found | set `CURAJ_SIGNING_PROPERTIES` or create the default file |
| Play Protect warning | APK is sideloaded, not from Play | expected; install only from a source you trust |
| App installs but does not log in | wrong portal password, or not on the campus Wi-Fi | test the portal in a browser; check `adb logcat -s AutoLoginService PortalClient` |
| Notification disappears after a while | OEM battery manager killed the service | set battery to Unrestricted and enable Autostart |

---

## 12. Release checklist

- [ ] `versionCode` increased for an update
- [ ] `./gradlew assembleRelease` succeeds
- [ ] `apksigner verify --print-certs` says `Verifies`
- [ ] `aapt dump badging` shows the correct version and no `debuggable`
- [ ] SHA-256 recorded and shared
- [ ] APK installed on a clean phone or after uninstalling the old copy
- [ ] Login, logout, notification, and reboot behavior tested
- [ ] keystore + properties backed up securely
- [ ] secrets not committed

---

See also: [ANDROID-ARCHITECTURE.md](ANDROID-ARCHITECTURE.md),
[LOCAL-BUILD.md](LOCAL-BUILD.md), [ANDROID-TEST.md](ANDROID-TEST.md).
