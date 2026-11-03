# Trove for Quest

A native Meta Quest app (sideloaded APK) for browsing and playing a self-hosted
[Stash](https://stashapp.cc) library in VR. It is the headset companion to the
[Trove server](https://github.com/driftsyncone/trove) and only talks to that server, never to Stash directly.
The Stash API key stays on the server.

**18+ only.** Meant for adults and for libraries of adult content. Use it only with a Trove server you
run yourself on a network you control.

## Feature overview

**Library**
- Scenes, Performers, Studios and Tags sections, like Stash: search, sort, favourites, saved-filter
  tabs, paging, and a page per performer/studio/tag with *Play random*.
- Scene details with markers; resume where you stopped; plays and resume points are reported back to
  Stash, so they show up in the Stash UI and the web player too.
- VR keyboard on panels (type searches, server address, passwords).

**Watching**
- Up to six screens at once, like monitors around you: grab with the grip, push/pull and resize with
  the stick, tidy and posture presets, "face me", overlap avoidance, restore your last layout.
- The focused screen has sound and plays the original stream; the others use lighter streams so a
  headset can keep several going.
- 2D, 3D side-by-side and over-under, 180/360 domes and fisheye 190/200/220, detected per scene and
  overridable. Passthrough "room" view behind the picture.
- Look-to-focus (gaze picks the screen), drag a card off the wall to open it as a new screen.
- **Storm**: a preview wall of many scenes at once, with a Focus layout (centre scrub bar, scene info,
  tap a performer/studio/tag to re-Storm with just that).

**Sessions and safety**
- Pairing without typing: the headset shows a code and QR, you approve it on your phone.
- Phone partner console on the server: STOP ALL, Black Out, Director (send a scene to the headset),
  session timer, headset status.
- **Black Out** hides everything and mutes; a wearer escape (both grips held for 2 s, or both open
  hands raised) always works.
- Optional device sync through the server (funscripts to a supported device). **Off unless you arm it
  on the server**; it stops on STOP ALL, headset or console silence, and the wearer escape.

**Maintenance**
- In-app update from your own Trove server (no store, no cable after the first install).
- Controls are documented in [docs/control-scheme.md](docs/control-scheme.md); everything else in
  [docs/reference.md](docs/reference.md).

## Before you start

| You need | Notes |
|---|---|
| A running **Trove server** on HTTPS | See the [server README](https://github.com/driftsyncone/trove). The app refuses plain HTTP except for `localhost` (see [Plain-HTTP test servers](#plain-http-test-servers)). |
| A **Meta Quest 3** (or Quest 3S / Pro) | Horizon OS (Android 14). The app targets API 34 and arm64 only. |
| **Developer Mode** on the headset | Needs a Meta developer account: create one at developers.meta.com and verify it. Then in the Meta Horizon phone app: Devices > your headset > Headset settings > Developer Mode > on. |
| A computer with a USB-C cable | For the first install. Windows, macOS or Linux. |
| **Android platform-tools** (`adb`) | https://developer.android.com/tools/releasepage |

To build the APK yourself you also need **JDK 17 or newer** (21 is what the author uses) and the
**Android SDK** with platform `android-36.1` (`sdkmanager "platforms;android-36.1"`, then
`sdkmanager --licenses`). Gradle itself is downloaded by the wrapper on first run.

## Install

### 1. Build the APK

```sh
git clone https://github.com/driftsyncone/trove-quest.git
cd trove-quest
export JAVA_HOME=/path/to/jdk                 # if your default java is not 17+
export ANDROID_HOME=$HOME/Android/Sdk         # your SDK folder
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk` (about 94 MB). The first build downloads
dependencies and takes several minutes. Run the unit tests with `./gradlew testDebugUnitTest`.

### 2. Connect the headset

1. Put the headset on, plug it into the computer with USB-C.
2. Accept **Allow USB debugging** inside the headset (tick "Always allow from this computer").
3. On the computer: `adb devices` must list the headset as `device` (not `unauthorized`).

### 3. Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

In the headset: App Library, filter **Unknown sources**, open **Trove**.

### 4. Log in

On first launch the login screen shows a **Server** field. Type your Trove address
(`https://trove.example.com`; a missing `https://` is added for you).

- **With your phone (recommended):** the screen shows a 6-character code and a QR code. Scan the QR
  with a phone, or open `<your server>/pair` and type the code. Log in to Trove there, check the
  device, tap **Approve**. The headset logs in within a couple of seconds. Codes last 5 minutes; use
  **New code** for another.
- **With a password:** use the name and password fields on the right of the same screen.

The session is kept, so you only do this once (and again after logging out).

### Wireless adb (optional)

Horizon OS does not offer Android's "Wireless debugging" toggle, but classic adb over Wi-Fi works.
With the headset plugged in once:

```sh
adb tcpip 5555
adb connect <headset-ip>:5555        # the headset's Wi-Fi address, then unplug
```

Keep working without the cable (`adb install -r ...`). The setting resets when the headset reboots;
plug it in and run `adb tcpip 5555` again. If a connection goes stale after the headset slept, run
`adb kill-server` and `adb connect` again.

### Plain-HTTP test servers

The network security config allows cleartext only for `localhost`. To try a plain-HTTP server on your
LAN, add a `<domain includeSubdomains="false">your-host</domain>` line inside the `domain-config`
block of `app/src/main/res/xml/network_security_config.xml` and rebuild. Use HTTPS for real use: the
login cookie travels with every request.

## Updating the app

**By cable or wireless adb:** rebuild and run `adb install -r ...` again. Your login and layouts are
kept as long as the app is signed with the same key (see below).

**From your Trove server (no cable):** `scripts/publish-apk.sh` builds the APK and uploads it,
with release notes from `CHANGELOG.md`, into the server's `<DATA_DIR>/apps/quest/` folder. The app
then shows an *Update available* card; tap **Install** and confirm the Android dialog. The script
needs `ssh`/`scp` access to the server, `jq` and the Android build tools, and reads these variables:
`TROVE_SERVER_HOST` (for example `user@your-server`), `TROVE_SERVER_SSH_KEY`, `TROVE_SERVER_APPS_DIR` (the server's
apps folder). Run it with `--dry-run` first. Details in [docs/reference.md](docs/reference.md#updates-self-update-from-the-trove-server).

### Signing

Android installs an update only if it is signed with the **same key** as the installed app. Debug
builds use the debug key in `~/.android/debug.keystore` on the computer that built them, so builds
from another computer (or after losing that file) cannot update an existing install. For anything
beyond a trial, make a release keystore once and keep it backed up:

```sh
keytool -genkeypair -v -keystore ~/keys/trove-release.jks -alias trove \
  -keyalg RSA -keysize 4096 -validity 36500
```

Give it to Gradle with the environment variables `TROVE_KEYSTORE`, `TROVE_KEYSTORE_PASSWORD`,
`TROVE_KEY_ALIAS`, `TROVE_KEY_PASSWORD`, or a git-ignored `keystore.properties` in the repo root
(`storeFile=`, `storePassword=`, `keyAlias=`, `keyPassword=`), then build with
`./gradlew assembleRelease` or publish with `scripts/publish-apk.sh --release`. Switching from a
debug-signed install to a release-signed one needs one `adb uninstall app.trove.quest` first
(you will log in again).

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `adb devices` shows nothing or `unauthorized` | Developer Mode off, cable is charge-only, or the USB debugging prompt in the headset was not accepted. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / signature mismatch | The installed copy was signed with another key. `adb uninstall app.trove.quest`, then install. |
| Login screen cannot reach the server | The headset is not on the network that reaches your server, the address is wrong, or the server has no valid HTTPS certificate. |
| Pairing code never approves | Approve on `<server>/pair` while logged in; codes expire after 5 minutes. |
| App not in the library | App Library, filter **Unknown sources**. |
| Keyboard types nothing | Close and reopen the keyboard by clicking the field again; logs: `adb logcat -s TroveIme:*`. |

Logs: `adb logcat -s PlayerSession:* ExoPlayerImpl:* MediaCodec:* AndroidRuntime:* TroveUpdate:*`.

## License

[MIT](LICENSE). Not affiliated with Meta, Stash or any device maker; product names belong to their owners.
