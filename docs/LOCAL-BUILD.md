# Building the APK locally

Yes — this is a small native Android app and it builds locally without root.
The only requirements are **JDK 17** and the **Android SDK**; both can live in
your home directory.

This machine now has:

| component | location |
|---|---|
| JDK 17 (Temurin 17.0.20.1) | `~/.local/share/mise/installs/java/temurin-17.0.20+101` |
| Android SDK | `~/Android/Sdk` |
| SDK platform | `platforms;android-35` |
| Build tools | `build-tools;35.0.0` (AGP also pulled 34.0.0) |
| Gradle | downloaded by the wrapper to `~/.gradle/wrapper/dists/gradle-8.9-bin` |
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `android/app/build/outputs/apk/release/app-release.apk` |

Build output:

```
app-release.apk 5.4 MB
package         in.ac.curaj.autologin
versionName     1.5  (versionCode 6)
minSdk          26
targetSdk       35
signature       APK Signature Scheme v2 (release key)
sha256          30ec1785b17482e66fce9ed78dda92afd695633759b6a6ea187b7b62eb367e1b
```

---

## 1. Install JDK 17 without root

The machine had only JDK 26, which AGP 8.7 rejects. `mise` can install a
private JDK:

```bash
mise install java@temurin-17.0.20+101
export JAVA_HOME="$(mise where java@temurin-17.0.20+101)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version          # openjdk 17.0.20.1
```

If you prefer not to use `mise`, download any Temurin 17 tarball from
<https://adoptium.net/> and set `JAVA_HOME` to the extracted directory.

## 2. Install the Android command-line tools

No Android Studio is needed. The command-line tools are enough for Gradle.

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
cd /tmp
curl -fL -o cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
unzip -q cmdline-tools.zip -d /tmp/android-tools
mv /tmp/android-tools/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses
sdkmanager --sdk_root="$ANDROID_SDK_ROOT" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

`sdkmanager` prints a deprecation warning about a new `android` CLI. It still
works and is what Gradle expects.

## 3. Point Gradle at the SDK

`local.properties` is machine-specific and ignored by git:

```bash
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" \
  > ~/curaj-wifi/android/local.properties
```

## 4. Build

```bash
export JAVA_HOME="$(mise where java@temurin-17.0.20+101)"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$PATH"
cd ~/curaj-wifi/android
./gradlew assembleDebug
```

First run downloads Gradle 8.9 and the AndroidX/Kotlin dependencies, so it
takes several minutes. Subsequent builds are ~15 seconds.

The debug APK appears at:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

For a signed APK to share with other people, use `./gradlew assembleRelease`
instead. The signing setup and distribution checklist are in
[RELEASE-BUILD.md](RELEASE-BUILD.md).

## 5. Install

Enable **Developer options → USB debugging** on the phone, connect it, then:

```bash
"$HOME/Android/Sdk/platform-tools/adb" devices
"$HOME/Android/Sdk/platform-tools/adb" install -r \
  ~/curaj-wifi/android/app/build/outputs/apk/debug/app-debug.apk
```

No phone was connected for the first build. The APK was later installed and
tested on a OnePlus CPH2793 running Android 16; see
[ANDROID-TEST.md](ANDROID-TEST.md).

---

## What the builds fixed

The Android source had never been compiled before the first local build. Three
build-time issues appeared and are now fixed:

1. **`in` is a Kotlin keyword.** The Java-style package
   `in.ac.curaj.autologin` is valid Java but not valid Kotlin. All Kotlin files
   now start with:

   ```kotlin
   package `in`.ac.curaj.autologin
   ```

   The escaped form compiles to the same JVM package, so the Android
   `namespace`, `applicationId`, manifest, and `R` class are unchanged.

2. **Private framework icons.** The notification used
   `android.R.drawable.stat_sys_wifi_signal_3` and
   `android.R.drawable.stat_notify_sync`, which are hidden framework
   resources and are not in the public SDK. The app now ships three small
   vector drawables:

   * `res/drawable/ic_stat_wifi.xml` — notification small icon
   * `res/drawable/ic_sync.xml` — “Login now” action
   * `res/drawable/ic_pause.xml` — “Pause” action

3. **The connectivity probe failed before login.** Android resolved
   `connectivitycheck.gstatic.com` through the captive Wi-Fi, which returned
   NXDOMAIN until after authentication:

   ```
   probe failed: Unable to resolve host "connectivitycheck.gstatic.com":
   No address associated with hostname
   ```

   The probe now uses four fixed Google `generate_204` IP addresses over the
   bound Wi-Fi network, so it needs no DNS. The portal intercepts the HTTP
   request and returns its meta-refresh splash page exactly as before. The
   IPs are also in `network_security_config.xml`, because otherwise Android
   blocks the cleartext request.

The build also prints two deprecation warnings for
`ConnectivityManager.allNetworks`. They are harmless; the app still uses it to
find a Wi-Fi network. A future cleanup can replace it with
`registerNetworkCallback` / `getActiveNetwork`.

### Runtime fixes after the first live test

The first APK compiled but exposed two runtime issues that are now fixed in
1.3:

* Android’s captive-portal activity could race the app, producing
  `Authentication already in progress for IP …`. The app no longer sends a
  duplicate `newlogin.do`; it polls the probe and retries transiently.
* The notification used to publish every transition. It now exposes only
  **Connected**, **Sign-in failed**, and **Disconnected**, with duplicate
  updates suppressed.

1.4 adds the two-session limit flow:

* `newlogin.do` can return `Maximum session allowed reached`. The app lists the
  sessions, asks which one to close, and retries only after a verified close.
* The close request needs a **splash-context** `JSESSIONID`. A direct
  self-care cookie gets `302 http://1.254.254.254?N=...` and leaves the session
  alive. The app now obtains the context cookie first and verifies against a
  second list; it no longer treats the `302` as success.

1.5 is the UI/network release:

* Material 3 dashboard with live SSID (optional permission), IP, band, signal
  percentage/RSSI, HTTP latency/loss, app throughput, and link speed.
* Credentials save automatically; the switch is **Auto connect**; the main
  button changes from **Connect now** to **Log out** when online.
* The session list is a bottom sheet with a loading spinner, per-row close
  spinner, and automatic refresh.
* A shared `NetworkMonitor` supplies one fresh Wi-Fi `Network`; `EPERM` bind
  failures are retried with a freshly resolved network instead of falling back
  to mobile data.

The full engineering narrative is in
[ANDROID-ARCHITECTURE.md](ANDROID-ARCHITECTURE.md).

---

## Troubleshooting

* **`Unsupported class file major version` or AGP/JDK error** — you are on
  JDK 21/26. Use JDK 17 for AGP 8.7.3.
* **`SDK location not found`** — create `android/local.properties` as above,
  or export `ANDROID_HOME`.
* **`Failed to install ... platform 35`** — accept licenses with
  `sdkmanager --licenses`, then rerun.
* **Build works but install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`** —
  an older build with a different signing key is installed. Uninstall it
  first: `adb uninstall in.ac.curaj.autologin`.
* **No device in `adb devices`** — unlock the phone and accept the USB
  debugging prompt; try `adb kill-server && adb devices`.
