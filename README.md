# tuios mobile

A personal Android client for existing tuios sessions, with a Kotlin Multiplatform
core and native Compose screens. Connect through your existing Tailscale network
and tuios SSH pairing. Downloaded conversations, Mail, drafts, and response
records stay on the phone.

Sessions opens by default. The four bottom destinations are Sessions, Mail,
Terminal, and Settings. Terminal is disabled. Questions and approvals appear
inside Sessions and conversations. The app has no general prompt input, system
notifications, push registration, or background synchronization.

The debug app builds and has automated coverage on Android API 26 and 36. Real
Tailscale pairing and physical-device operation still need validation; see the
[device validation record](docs/engineering/device-validation.md) for exact results
and remaining checks. The pinned daemon contract is tuios 0.12.1. Other daemon
versions depend on the verbs they expose; this is not a blanket compatibility
claim.

Post-review validation on November 6, 2026 passed 480 test executions across
shared/app unit tests and both emulators, with four optional screenshot skips.
Build, lint, installation, and cold launch passed. The validation record lists
the APK hash and distinguishes these results from the earlier baseline.

## Build and install

Use JDK 21 and the checked-in Gradle Wrapper. Install Android SDK platform 36 and
build tools 35.0.0. Set `ANDROID_HOME`, or put your SDK location in the ignored
`local.properties` file:

```properties
sdk.dir=C:/path/to/android-sdk
```

Run in PowerShell from this checkout:

```powershell
.\gradlew.bat --version
.\gradlew.bat :shared:allTests :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug
```

Connect an Android 8.0/API 26 or newer device with USB debugging enabled, accept
its debugging prompt, and select its serial from `adb devices`:

```powershell
adb devices
adb -s DEVICE_SERIAL install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s DEVICE_SERIAL shell am start -n dev.gaiusred.tuiosmobile/.MainActivity
Get-FileHash androidApp/build/outputs/apk/debug/androidApp-debug.apk -Algorithm SHA256
```

`DEVICE_SERIAL` is a placeholder. The APK is a local debug build, signed with the
build machine's debug key. It has not been published. To run the device suite
with one emulator or device connected:

```powershell
.\gradlew.bat :androidApp:connectedDebugAndroidTest
```

Reports are under `shared/build/reports/tests/`,
`androidApp/build/reports/tests/`, `androidApp/build/reports/androidTests/`, and
`androidApp/build/reports/lint-results-debug.html`.

## Pair a host

Pairing changes the host's SSH authorization. Do this on a host and session you
intend to use. Keep Tailscale connected on the host and phone; the app does not
install or manage Tailscale.

1. On the host, start the supported tuios pairing flow:

   ```text
   tuios pair --name phone --allow list,mail,respond
   ```

2. In the app, open Settings, then Pair a host. Paste the invitation and enter a
   device name. Invitations contain a secret; do not put them in screenshots,
   issue reports, or logs.
3. Start pairing. Compare the check code and phone/computer fingerprints on both
   devices. Approve on the host only when they match.
4. Select the paired host in Settings. Each connection checks protocol support
   and available verbs before reading sessions. Check current host support in
   Settings shows the daemon's advertised features. A listed verb does not
   override host permissions.

The app uses a restricted `tuios stdio-proxy` SSH channel and pins the host key
from pairing. The agreed grants are `list`, `mail`, and `respond`; transcript
reads also require `respond` and current human presence. No terminal, general
write, or administrative grant is needed.

If a host key changes, verify the host independently and use Re-pair this host
deliberately. If pairing reports an uncertain result, check authorization on the
host before using a fresh invitation. The app retains the possibly installed
local key and does not replay the pairing attempt. Re-pairing replaces the local
credential after success; revoke the old authorization on the host separately.

## Use and recovery

The app keeps one selected host connected while it is in the foreground. Moving
it to the background closes the SSH connection, presence, and subscriptions.
Returning reconnects and reconciles current state. Mail and approval delivery are
not promised while the app is closed.

Temporary connection failures retry while the app stays open, with delays from
one second up to 30 seconds. Retry connection in Sessions starts a fresh attempt
immediately. Authentication failures and changed host keys still require review
in Settings. An unsupported protocol or missing subscription support leaves
saved content readable but cannot verify live sessions.

Cached conversations remain readable offline, including older transcript
generations after a daemon restart or pane reuse. Unsupported transcripts leave
Mail accessible. Drafts survive navigation and reopening; reconnecting never
sends them automatically. Drafts from an older daemon generation stay separate.
When offered, adopting a legacy draft requires an explicit current recipient.

Mail uses a session-wide peek that does not consume shared unread state. Unread
positions in this app are local. Incoming events, gaps, and reconnection refresh
Mail without leaving an open thread. Downloaded subjects remain visible offline.
Missing send support disables sending while available Mail remains readable.
tuios Mail is ephemeral and bounded: the pinned
baseline retains at most 256 messages and 512 KiB of text per session. There is
no older-page Mail cursor. The phone cannot recover messages evicted before it
downloaded them. Attachment paths are references on the host; this app does not
download their bytes. A successful send does not prove an agent read it or began
another turn.

Human responses require current presence and a freshly checked question or
approval. Review the displayed request and offered action before confirming. A
lost acknowledgment can leave a Mail send or response marked possibly sent.
The app preserves that warning and never retries automatically. Check current
state on the host before choosing any further action; matching message text is
not proof of delivery.

## Local data and removal

Settings offers 7 days, 30 days, and Forever retention. The default is 30 days,
measured from receipt on this phone. Maintenance runs on foreground entry and
when changing retention. Drafts, saved send/response attempts, and connection
settings are not deleted by age.

Clear downloaded history deletes the selected host's cached records, read
positions, drafts, and saved attempts while keeping its connection and
credential. Clear all downloaded history includes hosts whose connections were
removed. Remote records still available may be downloaded again.

Remove connection offers separate choices to delete its local credential and
history. Kept history remains readable in Sessions and Mail without a saved
connection. A credential used by another saved connection is kept. Remove all
local connections and content also removes detached pairing keys and resets
retention to 30 days. Review the confirmation before deleting saved uncertain
attempts.

These actions do not revoke authorized SSH keys or delete data on the host. End
remote access by removing the phone's authorization on the host separately.
Deletion removes app-visible records; it does not guarantee forensic erasure of
SQLite or flash storage.

Private SSH keys are encrypted with Android Keystore and stored in app-private
storage. They are briefly available in memory for SSH. Automatic cloud backup
and device transfer are excluded. Production code does not log invitations,
keys, nonces, downloaded payloads, or message bodies.

## Engineering

Dependencies are pinned in `gradle/libs.versions.toml`: Kotlin 2.3.21, AGP 8.13.2,
Gradle 8.13, Compose BOM 2025.10.01, Room 2.8.3, KSP 2.3.7, JSch 2.28.8, and
Bouncy Castle 1.86. Build bytecode targets Java 17. Shared time values use
`kotlin.time.Instant`; wire timestamps retain their original units.

See the [compatibility research](docs/engineering/compatibility.md),
[wire contract](docs/engineering/tuios-contract.md), and
[validation record](docs/engineering/device-validation.md). `shared` owns the
protocol, identities, synchronization, and feature rules. `androidApp` owns Room,
Keystore, SSH, lifecycle, and Compose. Tests use fabricated data and local peers.

There is no iOS app or iOS build target yet. Future iOS work needs platform
adapters and UI, plus macOS and Xcode to build and test it.
