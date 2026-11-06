# Android compatibility baseline

Decision date: 2026-11-05. This is a pinned implementation baseline, not a claim
that every dependency is the latest. No application build or physical-device SSH
test was performed in this research task. Task 2 must resolve and compile this
tuple; Task 4 must validate crypto and transport on Android before claiming runtime
compatibility.

## Dependency tuple

| Component | Exact version / setting | Primary compatibility evidence |
| --- | --- | --- |
| Kotlin, KMP, Android Kotlin plugin | 2.3.21 | [KGP table](https://kotlinlang.org/docs/gradle-configure-project.html#apply-the-plugin) and [KMP table](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html#version-compatibility): 2.3.20–2.3.21 support Gradle 7.6.3–9.3.0 and AGP 8.2.2–9.0.0 |
| Android Gradle plugin | 8.13.2 | [AGP release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes): Kotlin 2.3 support via R8 8.13.19, Gradle minimum 8.13, JDK minimum 17, maximum API 36.1 |
| Gradle Wrapper | 8.13 | [Gradle 8.13 compatibility](https://docs.gradle.org/8.13/userguide/compatibility.html#java): JDK 21 can run Gradle from 8.5 onward |
| Build JDK | 21 | Run Gradle with JDK 21; set Java and Kotlin bytecode targets to 17 consistently. Build JDK does not imply Java 21 Android APIs |
| Android SDK | min 26, compile 36, target 36; build tools 35.0.0 | API 36 fits AGP 8.13; 35.0.0 is its documented default. [Android Java API support](https://developer.android.com/studio/write/java8-support) explains runtime API/desugaring limits |
| Compose compiler plugin | 2.3.21 | Same version as Kotlin, using `org.jetbrains.kotlin.plugin.compose`; [official Compose/Kotlin guidance](https://developer.android.com/jetpack/androidx/releases/compose-kotlin) supports this for Kotlin 2.0+ |
| Compose BOM | 2025.10.01 | [Published Google Maven BOM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.10.01/compose-bom-2025.10.01.pom) pins UI, foundation, runtime, tooling, UI test to 1.9.4 and Material3 to 1.4.0 |
| Room runtime, compiler, test | 2.8.3 | [Room release notes](https://developer.android.com/jetpack/androidx/releases/room#2.8.3); 2.8 raises minSdk to 23 and Room Gradle plugin minimum AGP to 8.4, both below this baseline |
| KSP2 | 2.3.7 | [Release](https://github.com/google/ksp/releases/tag/2.3.7), [pinned build properties](https://github.com/google/ksp/blob/2.3.7/gradle.properties): compiler baseline 2.3.20. Use KSP2 with Kotlin 2.3.21; KSP1 is excluded. This patch-level combination still requires compilation in Task 2 |
| kotlinx-coroutines core, android, test | 1.10.2 | [Release](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.10.2); [1.10.0 compiler baseline](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.10.0) is Kotlin 2.1.0, readable by selected Kotlin |
| kotlinx-serialization JSON | 1.9.0 | [Release](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.9.0) requires Kotlin 2.2+ and includes kotlin.time.Instant serialization; compiler plugin stays 2.3.21 |
| Android SSH | com.github.mwiede:jsch:2.28.8 | [Maintainer release](https://github.com/mwiede/jsch/releases/tag/jsch-2.28.8), [published artifact POM](https://repo.maven.apache.org/maven2/com/github/mwiede/jsch/2.28.8/jsch-2.28.8.pom) |
| Supplemental SSH crypto | org.bouncycastle:bcprov-jdk18on:1.86 | [Published versions](https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/maven-metadata.xml); [JSch requirements](https://github.com/mwiede/jsch/blob/jsch-2.28.8/Readme.md#are-ssh-ed25519-ssh-ed448-curve25519-sha256-curve448-sha512--chacha20-poly1305opensshcom-supported) |

These tables establish supported ranges and compiler baselines. There is no
official table certifying the complete cross-product of app libraries. Successful
dependency resolution, Android compilation, Room generation and API 26/device
tests remain required evidence in the owning implementation tasks. Do not replace
pins with `+`, latest-release selectors, snapshots, alpha, beta or RC artifacts.

Use `org.jetbrains.kotlin.multiplatform` with `com.android.library` and
`androidTarget()` in `shared` for this AGP 8.x baseline. Keep Android app and
Android-only Room/SSH adapters in `androidApp`. A common JVM test target is allowed
for platform-free protocol tests; no iOS scaffolding is required. The
[KMP migration guidance](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html#migrate-to-google-s-plugin-for-android-targets)
describes the new Android KMP plugin; adopting it later is a separate migration,
not a reason to mix AGP 9 DSL into this baseline.

The single shared instant type is **kotlin.time.Instant**, stable in Kotlin 2.3.
Use epoch seconds plus nanoseconds for daemon Unix-nanosecond timestamps and
`fromEpochMilliseconds` for transcript entry `at`. Never treat Attention/Mail
nanoseconds as milliseconds. Use local receipt time for retention.
Source: [Kotlin Instant API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.time/-instant/).

## SSH choice and Android setup

Choose the maintained mwiede JSch fork, not the original com.jcraft artifact.
Its Java 8 base classes fit API 26; optional Java 11/15 algorithms in the
multi-release JAR must use the Bouncy Castle fallback on older Android. The
selected release includes bounds-check fixes in network parsers. The maintainer
[security advisory page](https://github.com/mwiede/jsch/security/advisories)
showed no published advisories on the decision date; this is not a security audit.
Keep strict KEX enabled and do not enable RSA/SHA1 or accept every host key.

P-256 phone keys can be generated with JCA `EC`/`secp256r1`, encoded as PKCS#8,
encrypted at rest using a separate Android Keystore AES-GCM key, and passed in
memory to JSch `addIdentity(name, privateBytes, publicBytes, passphrase)`.
The pinned `KeyPair.load` handles PKCS#8 and OpenSSH private keys;
`KeyPairECDSA` handles P-256. Use OpenSSH public-key wire serialization for the
pairing request. Do not depend on importing a non-exportable Android Keystore
private key directly into JSch. Clear temporary byte arrays when possible.

Implement `HostKeyRepository` against the exact pairing-pinned SSH public key
blob, host identity and port. Require `StrictHostKeyChecking=yes`; changed and
unknown keys fail closed. Configure the negotiated host-key type to match the
pinned key, including Ed25519 hosts even when the phone identity is P-256.
Use `ChannelExec` with PTY disabled and binary stdin/stdout for federation frames.
The SSH library does not provide the tuios multiplexer: implement framing in the
transport adapter using the [pinned contract](tuios-contract.md).

Bundle `bcprov-jdk18on:1.86` for JSch's optional lightweight EdDSA/XDH/ChaCha
implementations. Its `com.jcraft.jsch.bc` classes call Bouncy Castle's lightweight
crypto directly; registering or replacing Android's global `BC` provider is not
required for those paths. Keep JCA P-256, SHA256, HMAC and AES-GCM on Android's
normal providers. Do not install Spongy Castle. Disable logging of authentication,
framing payloads, pairing URIs and message bodies. Verify the API 26 fallback on
an emulator/device; a desktop JDK 21 test alone cannot prove it.

Primary API source: [JSch 2.28.8 source JAR](https://repo.maven.apache.org/maven2/com/github/mwiede/jsch/2.28.8/jsch-2.28.8-sources.jar)
(`JSch`, `KeyPair`, `KeyPairECDSA`, `HostKeyRepository`, `ChannelExec`,
`bc/SignatureEdDSA`).

SSHJ 0.40.0 was checked first. Its pinned
[AndroidConfig](https://github.com/hierynomus/sshj/blob/v0.40.0/src/main/java/net/schmizz/sshj/AndroidConfig.java)
registers Spongy Castle and explicitly lists Ed25519/RSA/DSA rather than ECDSA.
A custom configuration could change that, but it adds provider/configuration
work without an advantage for this app. Its upstream
[README](https://github.com/hierynomus/sshj) also requires 0.38+ for the Terrapin
fix. This decision does not claim SSHJ cannot be adapted to Android.

## Windows workflow

Use PowerShell and the checked-in Gradle Wrapper, not a system Gradle. Existing
JDK 21, Android SDK platform 36 and build tools 35.0.0 are available in this
workspace environment. Keep the machine-specific SDK location in ignored
`local.properties` or `ANDROID_HOME`. Do not persist a new global CLI default.

```powershell
.\gradlew.bat --version
.\gradlew.bat :shared:testDebugUnitTest :androidApp:testDebugUnitTest
.\gradlew.bat :androidApp:assembleDebug :androidApp:lintDebug
.\gradlew.bat :androidApp:connectedDebugAndroidTest
```

The task names assume the approved AGP 8 Android library target. Verify actual
tasks after scaffolding. The controller reported a headless Android 36 emulator
connected during research. API 26 crypto behavior and real Tailscale/SSH pairing
still need their own checks. No dependencies, SDK packages or host grants were
installed or changed by Task 1.
