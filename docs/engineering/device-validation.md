# Android device validation

Validation dates: 2026-11-05–06 (Asia/Manila). This record covers the local debug build and
fabricated test data. Real Tailscale/SSH pairing, physical-device behavior, and
TalkBack have not been validated. Automated success does not complete those
acceptance checks.

## Build environment

| Component | Observed version or setting |
| --- | --- |
| Build OS | Windows 11, amd64 |
| Gradle Wrapper | 8.13 |
| Launcher/build JDK | Eclipse Adoptium 21.0.12.1+1-LTS |
| Java/Kotlin bytecode | 17 |
| Kotlin plugins | 2.3.21 |
| Android Gradle plugin | 8.13.2 |
| SDK min / compile / target | 26 / 36 / 36 |
| Build tools | 35.0.0 |
| Compose BOM / UI / Material3 | 2025.10.01 / 1.9.4 / 1.4.0 |
| Activity Compose | 1.11.0 |
| Room / KSP | 2.8.3 / 2.3.7 |
| Coroutines / serialization JSON | 1.10.2 / 1.9.0 |
| JSch / Bouncy Castle | 2.28.8 / 1.86 |
| JUnit / Android test runner | 4.13.2 / 1.7.0 |
| Android emulator | 37.1.11.0, build 15917651, Windows Hypervisor Platform |
| Daemon protocol baseline | tuios 0.12.1, pinned source contract; no live daemon tested |

Gradle's own embedded Kotlin is 2.0.21; the project compiler plugins use 2.3.21.
The [compatibility research](compatibility.md) records the original selection and
primary sources. The results below provide the later build/runtime evidence.

## Post-review validation, 2026-11-06

The review found five integration gaps that the earlier matrix did not cover:
foreground reconnection, automatic Mail refresh, startup capability checks,
transcript presence stream retention, and downloaded Mail subjects. The fixes
have new regressions through the coordinator and foreground synchronizer.

The final local command is the same build/unit/lint command shown below. It
passed 98 shared tests on each of JVM, Android debug, and Android release, plus
46 app unit tests: 340 passing executions, zero failures or skips. Build and lint
succeeded with the same two reviewed `UseKtx` warnings. Kotlin reported an
incremental compilation cache inconsistency and completed its full-compilation
fallback successfully; no source change or test suppression was needed.

The full post-fix device suites passed on both APIs. Final counts come from the
JUnit XML, not the runner's progress display:

| Check | Tests | Passed | Failed/errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Shared JVM | 98 | 98 | 0 | 0 |
| Shared Android debug unit | 98 | 98 | 0 | 0 |
| Shared Android release unit | 98 | 98 | 0 | 0 |
| Android app debug unit | 46 | 46 | 0 | 0 |
| API 26 full instrumentation | 72 | 70 | 0 | 2 |
| API 36 full instrumentation | 72 | 70 | 0 | 2 |

Total: 480 passing executions and four optional screenshot skips. The final API
26 command completed in 2m10s; API 36 in 3m. Both used
`.\gradlew.bat :androidApp:connectedDebugAndroidTest --console=plain`. The two
skipped methods are the same capture methods named in the baseline below.

`ForegroundIntegrationTest` uses real Room storage, the production synchronizer,
Mail and Attention repositories, and the production federation multiplexer with
its unchanged 16-stream limit. Its four cases check an open thread receiving new
message IDs and gap recovery, a subject displayed after Room reopens offline,
20 transcript sessions followed by Mail and human-question reads, and absent
transcript/send verbs leaving Mail readable with Send disabled. The fabricated
peer rejects feature requests before `hello` and `list-verbs`, checks passive
peek parameters, and checks presence ownership on the exact request stream.

The first integration runs stalled in test cleanup: the test joined a scope
before closing its blocking peer pipe. Thread stacks identified that order;
closing the peer before joining fixed the harness. The affected runs were
stopped and are not counted as passes. A new coordinator timeout test also
needed to end its intentionally perpetual retry fixture before test cleanup.
No app workaround was added for either harness issue.

API 36's first post-fix full run passed 69 tests, skipped the two capture methods,
and failed the unchanged response-keyboard test. A System UI ANR dialog again
owned window focus. Screenshot, focus, and log evidence were preserved. Selecting
Wait restored focus; the unchanged isolated test then passed with the real IME
visible. The full confirmation then passed with the same app and test source. An earlier
invocation started before the emulator finished booting and ran no tests.

## Pre-fix automated matrix, 2026-11-05

These earlier 434 passing executions and four opt-in skips establish the
pre-review baseline. They do not validate the five subsequent fixes.

Commands ran from PowerShell with the checked-in wrapper. Gradle had 1536 MiB
heap and two workers. One emulator ran at a time, with two cores, 1536 MiB RAM,
SwiftShader, no window, no audio, and no snapshots. No real host credentials or
conversation data were used.

```powershell
.\gradlew.bat :shared:allTests :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug --console=plain
.\gradlew.bat :androidApp:connectedDebugAndroidTest --console=plain
```

The device command runs separately on each API. Counts come from JUnit XML,
including named skips, rather than the progress display.

| Check | Tests | Passed | Failed/errors | Skipped | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Shared JVM | 87 | 87 | 0 | 0 | Success |
| Shared Android debug unit | 87 | 87 | 0 | 0 | Success |
| Shared Android release unit | 87 | 87 | 0 | 0 | Success |
| Android app debug unit | 41 | 41 | 0 | 0 | Success |
| API 36 full instrumentation | 68 | 66 | 0 | 2 | Success |
| API 26 full instrumentation | 68 | 66 | 0 | 2 | Success |
| Android lint debug | n/a | n/a | 0 errors | n/a | 2 reviewed style warnings |
| Debug APK assembly | n/a | n/a | 0 | n/a | Success |

API 36 uses the Google Play x86_64 Android 16 image (`google_apis_playstore`), build
`BE2A.250530.026.D1`, image build 13818094. API 26 uses the Google APIs x86_64
Android 8.0.0 image, build `OSR1.180418.026`, image build 6741039.

The two skips are opt-in display capture methods:
`MailTest.captureMailScreens` and `VisualCaptureTest.capturePopulatedScreens`.
Ordinary Mail, navigation, large-text, and keyboard tests still run. The API 36
runner printed a final progress count of 70/68; its XML contains 68 testcases,
66 passed and two skipped. This record uses the XML count.

Lint warnings are both `UseKtx` in `AppContainer`: retention persistence and the
pending-pairing flag deliberately check the synchronous `commit()` result. The
suggested KTX helper would discard that result. No lint errors remain. The
emulator-console warning about port 5554 did not prevent device installation or
test execution.

The first API 26 full run had three failures: the saved-conversation and
unknown-entry checks in `ConversationTest`, and the removed-host reading check
in `SettingsTest`. All three reproduced unchanged in a focused run. They tried
to call `performScrollTo()` on a lazy-list child that was not composed on the
1080 by 1920, 420 dpi display. The tests now scroll the parent list with
`performScrollToNode()` before selecting the row. The focused rerun passed all
three, including the original read-only, body, Mail-access, and expandable-detail
assertions. No production behavior changed in that task. The pre-fix full-device results above
include these corrected tests.

After restarting API 36, a full rerun and an isolated response-keyboard test
failed waiting for IME visibility. `dumpsys window` showed that a System UI
"isn't responding" dialog held focus above the test activity; a screenshot and
IME logs confirmed the obstruction. Selecting Wait in that emulator dialog
restored focus. The same isolated test then passed unchanged with the real IME
visible. No app workaround, timeout increase, or suppression was added. The full
suite was repeated after restoring the emulator's UI state.

## Behavior exercised

These are automated checks using scripted protocol peers, real temporary Room
storage, and Android crypto where indicated. They do not prove live tailnet or
host interoperability.

| Scenario | Covering checks and observed scope |
| --- | --- |
| Reachability loss and reconnection | `ConnectionLifecycleTest` checks lost links, fresh presence, background cancellation, and competing connection attempts. `HostSessionCoordinatorTest` also restores initial/dropped connections without navigation, checks backoff cancellation and deliberate retry, and keeps authentication/key-review failures paused. |
| Changed SSH host key | `HostKeyStoreTest.changedHostKeyRequiresExplicitReview` rejects mismatched and unknown keys. Pairing security tests reject forged proofs/checks and lost acknowledgments without replay. |
| Daemon restart, gaps, pane reuse | `ForegroundSyncTest` checks boot changes, transcript resets, event gaps, incremental/older paging, deduplication, and stale host events. Room/checkpoint tests reopen persisted generations and verify transactional rollback. |
| Foreground/background transitions | Shared lifecycle tests close channels and subscriptions; coordinator tests cover cancelled pending connects. `NavigationTest` preserves the route across a temporary background transition and recreation. |
| Cache-only reading | Retained-draft device tests reopen Room without a saved SSH profile; Settings tests read retained Mail without a profile. Conversation tests check the saved generation and read-only controls. Full draft text and uncertain attempts remain accessible without network channels. |
| Mail send/reply safety | `MailRepositoryTest` checks non-consuming session-wide peek, exact reply IDs, absent automatic sends, eviction gaps, late receipts, host/generation guards, and uncertain sends. `MailConnectionGenerationTest` blocks mutation before current-connection listing verification. |
| Current human responses | `AttentionRepositoryTest` checks stale prompts, exact offered answers, risk acknowledgment, full-plan revalidation, session-scoped presence, changed connections, late receipts, and absent automatic replay. `AttentionTest` exercises confirmation, stale replacement, plan/risk controls, verbatim labels, keyboard and large text. |
| Startup compatibility and unsupported features | `ForegroundSyncTest` checks protocol ranges, missing/rejected subscriptions, capability invalidation on reconnect, and separate transcript/send fallback. Device integration keeps Mail readable when transcript/send support is absent. Unknown transcript records render safely. |
| Four destinations and disabled Terminal | `NavigationTest` checks cold Sessions, exact tab order, disabled touch/accessibility semantics, back behavior, and restoration. No fifth destination is added. |
| Retention and deletion | Retention, Settings, Room, and key-store tests check receipt-time boundaries, preserved drafts/attempts, per-host/all-host clear, removed-host recovery, detached test-key removal, and reset confirmation. |
| Minimum-version crypto/transport | Device tests generate/encrypt/decrypt/sign P-256 keys, reject corrupt ciphertext, exercise Ed25519/X25519 fallbacks without replacing the global provider, close an actual loopback SSH socket on cancellation, and check federation framing. They do not complete an authenticated SSH exchange. |

Fabricated screenshots from earlier feature tasks cover light/dark Sessions and
conversations, 320 dp at 200% text, Mail with the IME, plan/risk responses, and
Settings/pairing/removal. Those captures were reviewed during implementation;
the final matrix does not recapture them or claim physical-device visual
approval. Automated semantics checks do not replace a TalkBack reading-order and
focus review.

## APK and packaged privacy inspection

Artifact: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Application ID: `dev.gaiusred.tuiosmobile`, version 0.1.0 (code 1).
Size: 15,195,262 bytes. Post-fix SHA-256:

```text
276dac2c1a0e1a0459f9799de1844e7d1177c6cc475b1e3d54b5b9255c95fd88
```

The post-fix APK installed with `adb install -r` and cold-launched on API 26 and 36 with
`am start -W`; installation returned `Success` and launch returned `Status: ok`.
API 36 explicitly reported `LaunchState: COLD` after stopping the app process.
This is a local debug artifact, not a published or release-signed build.

The pre-fix APK was 15,466,375 bytes with SHA-256
`70fb9c4439d8a289358503e9ffc2529b86a564eb31bbb6be1b09c1dc4d93a729`.
Its install and cold-launch checks passed on both APIs before the fixes.

Inspection was repeated on the post-fix APK with `apkanalyzer manifest print` and `aapt2 dump xmltree`
on its packaged `res/xml/data_extraction_rules.xml`. Observed:

- `allowBackup=false`, `fullBackupContent=false`, and extraction rules exclude
  all declared root, file, database, shared-preference, external, and
  device-protected domains for cloud backup and device transfer.
- Requested permissions are `INTERNET` and the AndroidX-generated app-specific
  signature permission for non-exported receivers. No notification permission,
  push component, boot polling receiver, or foreground sync service is present.
- Room contributes its non-exported `MultiInstanceInvalidationService`; this is
  an internal database service, not a background network sync connection.
  AndroidX startup/profile components and debug preview/test activities are also
  present in this debug package.
- `usesCleartextTraffic=true` supports tuios's pairing HTTP protocol over the
  tailnet. Pairing checks its proof and pins the returned SSH key; subsequent
  proxy streams use SSH. No broad trust-on-first-use fallback is enabled.

Production-source inspection found no logging calls or network payload logger.
Connection and parser exceptions expose fixed categories rather than downloaded
payloads. A permissible diagnostic example, shown only to define the limit, is:

```json
{"screen":"settings","connection":"offline","retention":"ThirtyDays","pairingReviewRequired":true}
```

There is no diagnostic upload feature. Keep usernames, host addresses, tokens,
nonces, pairing invitations, key material, message bodies, and exception text
out of reports. This tracked record contains none of the test machine's account
or network identity. Deletion tests prove app-visible removal, not forensic
erasure of SQLite pages or flash media, remote key revocation, or remote history
deletion.

## Manual acceptance still required

No approved real host/session or physical Android device was supplied for these
checks. The optional host-setup request had no verified reply. No pairing grant,
real Mail action, production approval, remote configuration, or real key deletion
was performed.

Use a disposable host/session and recipient approved by the person before
starting this checklist. The [README](../../README.md#pair-a-host) describes
installation and pairing. Keep test evidence limited to versions, status,
counts, and fabricated/redacted UI; do not save invitation secrets or real
conversation content.

1. Connect the physical phone and host to Tailscale. Deliberately start pairing
   with `list,mail,respond`, compare both devices' checks/fingerprints, and
   approve on the host. Confirm actual SSH host identity and current human
   presence. Verify list/hello/capability behavior on the selected daemon.
2. In the disposable session, read transcript older/incremental pages and safe
   unknown/unsupported states. Peek Mail without changing another recipient's
   unread state. Send one benign message and a reply, then verify the exact
   reply relationship and human origin on the host.
3. Create a benign human question/approval in that session. Confirm the current
   request and offered action, replace it before responding to verify stale
   rejection, then deliberately answer a fresh test request. Never use a
   production approval for this exercise.
4. Disconnect and restore reachability, background and foreground the app, and
   restart only the disposable daemon. Verify offline cache/drafts, fresh
   presence, separated transcript generations, and no automatic mutation replay.
   Exercise an intentionally lost acknowledgment with controlled test tooling;
   record uncertainty and reconcile on the host before any further send.
5. On the physical phone, check light/dark appearance, real IME/system insets,
   large text, rotation/recreation, and TalkBack reading order, labels, focus,
   disabled Terminal, and confirmation dialogs. Confirm four destinations and
   no notification request or generated system notification.
6. Test local clear/removal with disposable credentials and content only. Verify
   selected deletion choices and remaining cached access. Revoke the disposable
   authorization on the host separately when finished.

Record each result and actual versions after execution. Until then, live
integration and physical-device acceptance remain incomplete. Future iOS builds
also require an iOS implementation, macOS, and Xcode; none was validated here.
