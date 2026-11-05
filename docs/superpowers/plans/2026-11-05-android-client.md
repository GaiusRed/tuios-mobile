# tuios Android Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task after user approval. Steps use checkbox (`- [ ]`) syntax for tracking. User instructions override automatic commits and worktree creation in skills.

**Goal:** Build a personal Android app that connects to existing tuios sessions over Tailscale and SSH, presenting transcripts, Mail, and human responses in an iOS Messages-like interface without notifications or a new backend.

**Architecture:** A Kotlin Multiplatform core owns protocol models and synchronization rules. A native Android app supplies Compose screens, a Room database, secure credential storage, and an SSH adapter. Feature state depends on narrow interfaces rather than Android, Room, or SSH library types; iOS platform adapters and screens are deferred.

**Tech Stack:** Kotlin, Jetpack Compose, coroutines/Flow, Kotlin serialization, Room/SQLite, JUnit and coroutine tests, Compose UI tests. Android SSH library selection and exact dependency versions are the explicit first-task compatibility deliverable, not assumptions hidden in implementation tasks.

**Spec:** [tuios mobile design](../specs/2026-11-05-tuios-mobile-design.md).

**Status:** Approved by the user on 2026-11-05 for execution with subagents. The user authorized committing and pushing this plan before execution. No implementation tasks have started.

## Global Constraints

The following requirements are copied from the spec and bind every task:

- Personal tool, Android first, iOS later.
- Kotlin is the selected language.
- Use Tailscale for network access.
- Use existing tuios interfaces and SSH pairing rather than tuios-web credentials.
- Allow listing, Mail, and human responses.
- Store retained history locally on the phone. No cloud synchronization.
- Exclude notifications, push registration, push providers, and background notification connections.
- Agent compatibility belongs to tuios. Do not maintain a mobile-specific supported-agent list.
- Resemble the iOS Messages app as closely as practical on Android.
- Bottom navigation contains Sessions, Mail, Terminal, and Settings, in that order.
- Sessions opens by default. Terminal is visible but disabled for a future release.
- General prompt submission is outside the agreed initial permission scope.

No worktrees. Work in the existing checkout on `feature/android-client`. Do not move the existing project. The user authorized the initial design commit and then committing and pushing this plan before execution; implementers and reviewers must not create further commits, push implementation changes, merge, publish builds, or open a PR without fresh consent. A skill's automatic commit instruction does not grant consent. Plan approval authorizes execution with subagents, not implementation commits.

## Proposed implementation defaults

Approval of this plan also approves these concrete defaults, which were not previously user decisions:

- Native Compose Android UI and a shared Kotlin core; no iOS target scaffolding or SwiftUI project yet.
- Namespace and application ID `dev.gaiusred.tuiosmobile`; display name `tuios mobile`.
- Android minimum API 26. Use compile/target API 36 for the initial personal build, subject to the verified dependency tuple in Task 1.
- tuios 0.12.1 is the initial tested baseline, not a claim that older daemons are incompatible. Feature discovery controls graceful degradation.
- Cache received content for 30 days by default, measured from local receipt. Settings offers 7 days, 30 days, forever, and clear now. Drafts and connection settings are not age-pruned.
- No biometric lock in the first version. Keys are protected at rest with Android Keystore-backed encryption; private keys are briefly available to the selected SSH implementation in memory.
- One active host connection at a time; multiple saved hosts. This avoids unnecessary simultaneous connections while preserving host identity in all cached data.
- Use hand-written composition of dependencies. Do not add a dependency-injection framework, generic repository framework, or event bus.

## Review Focus

Each risk has a named test in its owning task:

1. Connection loss after a mutation may mean it succeeded: never replay automatically; report uncertainty and reconcile (Tasks 2, 7, 8).
2. Reused pane names, transcript resets, and daemon restarts must not join unrelated histories (Tasks 3 and 5).
3. Missing transcript support must leave Mail usable; unknown entries must not break the conversation (Tasks 2 and 6).
4. Font scaling, keyboard insets, and disabled Terminal accessibility must preserve usable navigation and responses (Tasks 6 and 8).
5. Pairing secrets, nonces, and downloaded content must not escape through logs, backup, fixtures, or cache deletion gaps (Tasks 1, 3, 4, 9).

## Clean code boundaries

- Keep protocol encoding, transport framing, synchronization, persistence, and rendering separate. Each file has one responsibility.
- Put files that change together under a feature directory. Keep shared contracts small; do not put UI state in wire DTOs.
- Depend on interfaces only at actual platform or side-effect boundaries: connection, storage, clock, and key storage. Use plain functions for deterministic transformations.
- Centralize request schemas, identity construction, and retry classification. Do not duplicate these in ViewModels.
- Keep known wire models typed, while preserving unknown JSON fields/types for forward compatibility. Do not route arbitrary verbs from UI code.
- Expose immutable state and explicit user actions. Use exhaustive typed outcomes instead of booleans that lose failure context.
- Add tests for observable failure modes and behavior. Do not test getters, trivial wrappers, colors by implementation detail, or skill compliance through source-text matching.

## File and module map

Root files: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, Gradle Wrapper files, `.gitignore`, `.gitattributes`, and `README.md`.

Shared source root: `shared/src/commonMain/kotlin/dev/gaiusred/tuiosmobile/`.
Shared test root: `shared/src/commonTest/kotlin/dev/gaiusred/tuiosmobile/`.
Android source root: `androidApp/src/main/kotlin/dev/gaiusred/tuiosmobile/`.
Android unit-test root: `androidApp/src/test/kotlin/dev/gaiusred/tuiosmobile/`.
Android device-test root: `androidApp/src/androidTest/kotlin/dev/gaiusred/tuiosmobile/`.

Paths below are relative to these roots when prefixed `shared:`, `shared-test:`, `android:`, `android-test:`, or `device-test:`. Build-module files always use repository-relative paths.

| Area | Files and responsibility |
| --- | --- |
| Protocol | `shared:protocol/{DaemonClient,ProtocolCodec,ProtocolModels,ProtocolFailure}.kt`: requests, DTOs, decoding, errors |
| Identity | `shared:identity/RecordKey.kt`: host, session, pane, boot, and transcript identity |
| Storage port | `shared:storage/HistoryStore.kt`: transactional snapshots, history, drafts, cursors, retention |
| Host port | `shared:connection/{HostProfile,HostConnection,ConnectionState}.kt`: saved host descriptors and foreground connection lifecycle |
| Sync | `shared:sync/ForegroundSync.kt`: listings, subscriptions, transcript cursors, reconciliation |
| Features | `shared:sessions/SessionRepository.kt`, `shared:mail/MailRepository.kt`, `shared:attention/AttentionRepository.kt`: feature behavior |
| Pairing and SSH | `android:connection/{PairingClient,SshProxyTransport,HostKeyStore,DeviceKeyStore}.kt`: Android adapters |
| Database | `android:storage/{AppDatabase,HistoryDao,HistoryEntities,RoomHistoryStore}.kt`: local persistence |
| App composition | `android:{MainActivity,AppContainer}.kt`, `android:ui/AppRoot.kt`: lifecycle and wiring |
| Design system | `android:ui/design/{MessagesTheme,BottomDestinations,MessageBubble,ConnectionBanner}.kt`: reusable appearance |
| Screens | `android:ui/{sessions,conversation,mail,attention,settings}/`: screen and ViewModel per feature |

Use a separate file per public type when the grouped descriptions above would create an oversized file. No new module per screen or per interface.

## Shared contracts

Define these in their owning tasks, then preserve their names and semantics across delegates:

```kotlin
// Task 2; protocol models also define these referenced types.
interface DaemonClient {
    suspend fun request(request: DaemonRequest): DaemonReply
    fun subscribe(request: SubscriptionRequest): Flow<DaemonEvent>
}
sealed interface MutationOutcome {
    data class Accepted(val receipt: MutationReceipt) : MutationOutcome
    data class Rejected(val failure: ProtocolFailure) : MutationOutcome
    data object Uncertain : MutationOutcome
}

// Task 3; all parameters are typed models, not arbitrary JSON or SQL.
interface HistoryStore {
    fun observe(query: HistoryQuery): Flow<HistorySnapshot>
    suspend fun apply(batch: HistoryBatch)
    suspend fun saveDraft(draft: MailDraft)
    suspend fun prune(policy: RetentionPolicy, now: Instant)
    suspend fun clearHost(hostId: HostId)
}

// Task 4; an opened channel preserves byte boundaries only as a stream.
interface HostConnection {
    val state: StateFlow<ConnectionState>
    suspend fun connect(host: HostProfile)
    suspend fun openChannel(kind: ChannelKind): ProxyChannel
    suspend fun disconnect()
}

// Task 5
interface SessionRepository {
    val state: StateFlow<SessionSnapshot>
    suspend fun refresh()
    fun conversation(target: AgentTarget): Flow<ConversationSnapshot>
    suspend fun loadOlder(target: AgentTarget)
}
```

Task 2 owns DTOs (`DaemonRequest`, `DaemonReply`, `SubscriptionRequest`, `DaemonEvent`, `MutationReceipt`) and failure classification. Task 3 owns stored-domain models (`HistoryQuery`, `HistorySnapshot`, `HistoryBatch`, `MailDraft`, `RetentionPolicy`) and `HostId`/record keys. Task 4 owns connection models (`HostProfile`, `ChannelKind`, `ProxyChannel`, `ConnectionState`). Task 5 owns `AgentTarget`, `SessionSnapshot`, and `ConversationSnapshot`. Use `kotlin.time.Instant` if supported by the verified Kotlin tuple; otherwise select and document a single shared instant type in Task 1 before Task 3 starts.

Do not guess a wire field from these app-facing types: map them to the exact Task 1 protocol evidence.

## Subagent execution and review

Execute only after the user approves this plan. The controller reads the spec and plan, creates the skill's local progress ledger, and assigns a fresh implementer to each task. Give each worker the relevant task, shared contracts, global constraints, file ownership, and completed dependency reports.

Use sequential tasks by default. All agents share this checkout; do not run concurrent writers against the same files. After each task, a fresh reviewer checks spec compliance and code quality. Fix review findings before moving to dependent work, following the skill's review loop. Finish with a whole-branch review.

No worker commits. Record task boundaries with the set of changed files and a copy of each task's patch in the local, ignored `.superpowers/sdd/` workspace. Since `HEAD` does not move, a `BASE..HEAD` diff would be empty: generate the review package from working-tree snapshots captured before and after the task, including newly created files. Record file hashes and verification results. The final review sees the full tracked and untracked implementation diff against the initial design commit.

Every report includes files changed, commands and actual results, unresolved concerns, and both the base and resulting task snapshot identity. Do not claim tests passed from reviewer intuition. Keep the plan and ledger current across context compaction. Do not delete the approved plan during cleanup.

## Task 1: Establish the protocol and toolchain compatibility baseline

**Depends on:** Plan approval.
**Files:** Create `docs/engineering/compatibility.md`, `docs/engineering/tuios-contract.md`, `docs/engineering/fixtures/README.md`.
**Produces:** Exact dependency tuple and pinned upstream protocol reference for all later tasks.

- [ ] Inspect the installed daemon's `hello`, `list-verbs`, and focused verb schemas. Record tuios 0.12.1 and its source commit as the tested baseline; capture only schema and fabricated examples, never real Mail, transcript, nonce, hostname, or credentials.
- [ ] Read the matching pairing source/CLI protocol and `stdio-proxy` framing. Document QR validation, supported key formats, SSH host verification, opening human-authorized streams, stream framing, and cancellation. Include the session scope and capabilities for transcript and response verbs.
- [ ] Record exact request and response fields for session/agent listings, `agent-transcript`, Mail reads with `peek`, Mail send/reply, Attention listing, `peek-prompt`, human presence, response verbs, and `subscribe`. Explicitly confirm whether both sides of a Mail exchange can be queried without consuming recipient unread state.
- [ ] Select one Android SSH library after checking Android API 26 compatibility, maintained releases/security advisories, P-256 key import, host-key verification, and the proxy transport. SSHJ is the first candidate, not a preapproved dependency. Record the decision and required Android crypto provider setup if any.
- [ ] Choose and record an exact stable Kotlin/KMP, AGP, Gradle, Compose, Room, coroutines, serialization, and SSH version tuple using official compatibility tables. No dynamic versions, snapshots, or invented version combinations. Confirm JDK 21 build compatibility, API 26 floor, API 36 target, the shared instant type, and the Windows CLI workflow.
- [ ] Self-check the contract for partial lines, unknown entry types, structured errors, human nonce scope, Mail peek behavior, and lost mutation acknowledgments. A reviewer must reject invented wire fields or an unresolved core transport. If the current daemon cannot meet a required feature without backend changes, report the concrete limitation before proceeding with that feature.

**Verification:** All protocol examples match a pinned schema/source reference; all chosen dependency versions have a primary-source compatibility reference. Do not pair a real phone, change host grants, or send test Mail to running agents in this documentation task.

## Task 2: Build the project foundation and tested protocol client

**Depends on:** Task 1.
**Files:** Root Gradle and ignore files; `shared/build.gradle.kts`; `androidApp/build.gradle.kts`; `androidApp/src/main/AndroidManifest.xml`; protocol files in the map; `shared-test:protocol/ProtocolClientTest.kt`; `shared-test:protocol/ProtocolCodecTest.kt`; fabricated fixtures under `shared/src/commonTest/resources/protocol/`.
**Consumes:** Task 1 pinned versions and exact wire schemas.
**Produces:** `DaemonClient`, typed protocol outcomes, a minimal installable Android activity, and repeatable CLI builds.

- [ ] Create the minimum Gradle Wrapper, version catalog, KMP shared module with an Android target, and Android Compose app needed to run tests. Disable cloud backup and omit notification permissions/services. Ignore `.superpowers/`, local SDK paths, keys, build outputs, and machine-local files.
- [ ] Add failing tests: `fragmentedJsonLinesDecodeOnce`, `structuredFailurePreservesCode`, `unknownTranscriptEntryIsRetained`, `missingTranscriptDoesNotDisableMail`, and `lostReplyAfterWriteReturnsUncertain`. Assert fragmented UTF-8 is preserved; unknown entry content remains safe data; unavailable transcript support is feature-local; a possibly written mutation is not retried.
- [ ] Run `./gradlew.bat :shared:testDebugUnitTest` and confirm these tests fail for missing behavior. Implement request IDs, response routing, bounded line decoding according to the daemon contract, explicit subscription streams, cancellation, and read-versus-mutation retry classification.
- [ ] Run `./gradlew.bat :shared:testDebugUnitTest :androidApp:assembleDebug`. Expect all tests green and a debug APK. Verify unknown JSON fields do not fail known records and malformed or oversized input produces a typed recoverable failure.
- [ ] Record the actual compatible dependency tuple and commands in `README.md`. No standalone Kotlin/Gradle installation and no project relocation.

## Task 3: Implement durable local history, identity, and retention

**Depends on:** Task 2.
**Files:** Identity/storage ports and Room adapter files in the map; `shared-test:identity/RecordKeyTest.kt`; `device-test:storage/RoomHistoryStoreTest.kt`.
**Consumes:** Protocol models and the Task 1 instant type.
**Produces:** `HistoryStore`, typed storage models, transactionally consistent history and cursor storage.

- [ ] Define `RecordKey` around stable host/session/pane identifiers and daemon/transcript identity. Define `HistoryBatch` so updating entries and their cursor is one transaction. Separate local receipt time from displayed server time.
- [ ] Add failing tests: `reusedPaneDoesNotJoinOldTranscript`, `batchAndCursorCommitTogether`, `duplicateEntryUpsertsOnce`, `pruneKeepsDraftsAndConnections`, and `clearHostRemovesHistoryCursorsAndDrafts`. Test 7/30-day boundaries with a fixed clock and forever retention.
- [ ] Run `./gradlew.bat :shared:testDebugUnitTest :androidApp:connectedDebugAndroidTest` with an Android device/emulator available; confirm the new behavior tests fail before implementation.
- [ ] Implement Room entities/DAO and `RoomHistoryStore`. Keep SQL and Android types outside shared code. Cache only sanitized daemon data; enforce host separation and preserve drafts across process restarts.
- [ ] Re-run the commands. Expect transaction, identity, pruning, and deletion tests to pass. Inspect backup configuration to confirm both history and credentials are excluded.

## Task 4: Implement pairing, secure SSH, and foreground connection lifecycle

**Depends on:** Tasks 1–3.
**Files:** Connection port and Android adapters in the map; `shared-test:connection/ConnectionLifecycleTest.kt`; `android-test:connection/PairingClientTest.kt`; `device-test:connection/DeviceKeyStoreTest.kt`; fabricated connection fixtures.
**Consumes:** Task 1 pairing/proxy contract; Task 2 client framing; persisted host descriptors from Task 3.
**Produces:** `HostConnection`, pairing outcome, saved host profiles, and live human-authorized proxy streams.

- [ ] Define `PairingClient.pair(invitation: PairInvitation): PairingOutcome` and validate invitation length, scheme, expiry, host candidates, and supplied key/check information against the actual pairing contract. Present fingerprint/check information for deliberate comparison; do not silently pair.
- [ ] Add failing tests: `malformedInvitationNeverConnects`, `changedHostKeyRequiresExplicitReview`, `backgroundClosesAllChannels`, `reconnectAcquiresFreshPresence`, and `decryptFailureRequiresRepairWithoutLoggingKey`. Use scripted SSH peers for framing and connection-loss tests.
- [ ] Run `./gradlew.bat :shared:testDebugUnitTest :androidApp:testDebugUnitTest` to confirm failures. Implement the selected SSH adapter, encrypted private-key storage, bounded timeouts, stream closure, and explicit host verification. Do not use permissive host-key verification.
- [ ] Connect only through the user's existing Tailscale network. Put connection management in Settings; display clear disconnected, connecting, connected, authentication, and permission states. Do not start Tailscale, logins, foreground services, or a notification provider.
- [ ] Re-run unit tests and `./gradlew.bat :androidApp:connectedDebugAndroidTest` for encryption/decryption round trips and key deletion. Prove lifecycle cancellation releases sockets, channel readers, and human presence without retaining nonces.

## Task 5: Synchronize sessions and transcripts with recovery

**Depends on:** Tasks 2–4.
**Files:** Session and sync files in the map; `shared-test:sync/ForegroundSyncTest.kt`; `shared-test:sessions/SessionRepositoryTest.kt`.
**Consumes:** `DaemonClient`, `HostConnection`, `HistoryStore`.
**Produces:** `SessionRepository`, foreground synchronization, and typed `AgentTarget`/snapshot models.

- [ ] Add failing tests: `listingSubscriptionRaceReconciles`, `eventGapRefreshesState`, `newBootDoesNotReplayOldIdentity`, `transcriptResetReplacesCurrentBranch`, `loadOlderPreservesOrder`, and `switchHostIgnoresLateOldEvents`. Use a scripted daemon and controlled dispatcher/clock.
- [ ] Run `./gradlew.bat :shared:testDebugUnitTest` to confirm failures. Implement listings and event subscriptions using sequence/boot identifiers wherever provided; explicitly subscribe to transcript changes and fetch with the last stored cursor.
- [ ] Implement refresh/reconciliation for events without replay, gaps, unsupported resume, session removal, agent restart, and cursor reset. Read from the local store for UI snapshots; keep network decoding out of UI code.
- [ ] Re-run `./gradlew.bat :shared:testDebugUnitTest`. Expect deterministic passing tests and no duplicate visible entries after reconnect. Verify no synchronization job remains active after backgrounding or host switch.

## Task 6: Build the Messages-like app shell, Sessions, and conversations

**Depends on:** Tasks 3 and 5.
**Files:** App composition/design files in the map; `android:ui/sessions/{SessionsScreen,SessionsViewModel}.kt`; `android:ui/conversation/{ConversationScreen,ConversationViewModel}.kt`; `device-test:ui/NavigationTest.kt`; `device-test:ui/ConversationTest.kt`.
**Consumes:** `SessionRepository`, immutable conversation snapshots, saved host connection state.
**Produces:** Default Sessions screen, accessible bottom navigation, agent transcript presentation.

- [ ] Add failing UI tests: `coldLaunchSelectsSessions`, `bottomOrderIsSessionsMailTerminalSettings`, `terminalIsDisabledForTouchAndAccessibility`, `temporaryBackgroundPreservesRoute`, and `unsupportedTranscriptStillLinksToMail`. Run `./gradlew.bat :androidApp:connectedDebugAndroidTest` to confirm missing behavior.
- [ ] Implement the agreed bottom group, Sessions list, agent detail navigation, and paginated conversation screen. Render clear Messages-like large titles, rounded search, identity marks, restrained separators, blue primary controls, and text bubbles in light/dark modes. Do not import Apple's proprietary fonts or icons.
- [ ] Render expandable tool/result/diff/plan entries and a safe unknown-entry fallback. Maintain host/session/sender context. Search filters downloaded/listed records only; do not imply server-wide historical search.
- [ ] Re-run device tests. Add and pass `largeTextKeepsBottomControlsUsable` at 200% font scale; inspect compact-screen and keyboard/inset layouts. Capture screenshots for user review; a visual match requires human review, not a claimed pixel comparison to an unspecified iOS version.

## Task 7: Implement Mail viewing, drafts, send, and reply

**Depends on:** Tasks 2–6.
**Files:** `shared:mail/MailRepository.kt`; `shared-test:mail/MailRepositoryTest.kt`; `android:ui/mail/{MailScreen,MailThreadScreen,MailViewModel,MailComposer}.kt`; `device-test:ui/MailTest.kt`.
**Consumes:** Protocol Mail schemas, `HistoryStore`, current human presence, and explicit target session/recipient identities.
**Produces:** `MailRepository.observe(target: MailTarget): Flow<MailSnapshot>`, `suspend refresh(target: MailTarget)`, and `suspend send(draft: MailDraft): MutationOutcome`. Define `MailTarget` and `MailSnapshot` in this task.

- [ ] Add failing tests: `peekDoesNotConsumeRecipientUnreadState`, `replyUsesExistingMessageId`, `offlineDraftSurvivesRestart`, `lostSendReceiptShowsUncertainWithoutRetry`, `reconnectDoesNotSubmitDraft`, and `mailEvictionShowsHistoryGap`. Run `./gradlew.bat :shared:testDebugUnitTest` to confirm failures.
- [ ] Implement available session Mail reads with peek semantics and local unread positions. Preserve exact sender/recipient and reply relationships without inventing delivery/read acknowledgments. Handle missing reply parents and bounded-ring eviction explicitly.
- [ ] Implement Mail list, thread, composer, recipient picker within the selected session, and sent/rejected/uncertain UI states. Verify human Mail carries valid current presence. Show no general agent-prompt composer. Treat unsupported human-send capability as a visible permission error.
- [ ] Run shared tests and `./gradlew.bat :androidApp:connectedDebugAndroidTest`. Verify Mail remains available when transcripts are absent, no notification API is used, and offline drafting never causes automatic sends.

## Task 8: Implement human questions and approval responses

**Depends on:** Tasks 2, 4–6.
**Files:** `shared:attention/AttentionRepository.kt`; `shared-test:attention/AttentionRepositoryTest.kt`; `android:ui/attention/{AttentionCard,AttentionViewModel,ResponseEditor}.kt`; `device-test:ui/AttentionTest.kt`.
**Consumes:** Exact Attention and response contracts, live human presence, target identity, and session snapshots.
**Produces:** `AttentionRepository.observe(target: AgentTarget): Flow<List<AttentionItem>>` and `suspend respond(response: HumanResponse): MutationOutcome`. Define `AttentionItem` and `HumanResponse` in this task, preserving protocol-specific identity/risk fields.

- [ ] Add failing tests: `stalePromptNeverReceivesOldApproval`, `alreadyAnsweredItemRefreshes`, `riskAcknowledgmentIsExplicit`, `lostApprovalReceiptIsNeverRetried`, `nonceScopeCannotCrossSessions`, and `onlyDaemonOfferedActionsAreEnabled`. Run `./gradlew.bat :shared:testDebugUnitTest` and confirm failures.
- [ ] Implement item-specific dispatch to `reply-approval`, `answer-ask`, or `respond`; revalidate the prompt and identity first and include all supported risk/plan checks. Keep arbitrary terminal input and `send-keys` unavailable.
- [ ] Place pending questions/approvals in Sessions and agent conversations, not a fifth destination. Provide action-specific confirmation, question text entry, current-state refresh, and explicit uncertain result handling.
- [ ] Run shared/device tests. Pass `keyboardDoesNotCoverResponseActions` and verify TalkBack labels, font scaling, disabled offline actions, and stale-item replacement. Do not automatically approve a real request during validation.

## Task 9: Complete Settings, retention, and privacy controls

**Depends on:** Tasks 3, 4, 6–8.
**Files:** `android:ui/settings/{SettingsScreen,SettingsViewModel}.kt`; `shared-test:settings/RetentionSettingsTest.kt`; `device-test:ui/SettingsTest.kt`; backup rules if required by the chosen SDK.
**Consumes:** Host connection state, encrypted key storage, `HistoryStore`, retention policy.
**Produces:** Saved-host management and predictable local data deletion.

- [ ] Add failing tests: `retentionDefaultsToThirtyDays`, `clearHistoryKeepsSavedConnection`, `removeHostDeletesSelectedCredentialsAndCache`, and `retentionChangeNeverSendsNetworkMutation`. Run shared/device tests as appropriate and confirm failures.
- [ ] Implement retention choices, clear-history action, saved-host selection, pairing/repair, capability display, and connection removal with explicit credential/cache choices. Do not display secrets or offer broader capabilities silently.
- [ ] Run pruning on foreground data maintenance and policy changes. Keep drafts independent of age retention. Wire no background scheduler, push subscription, notification permission, or automatic cloud backup.
- [ ] Re-run the test suite. Inspect an app backup manifest/configuration and a sanitized diagnostics example; verify credentials, nonces, and message bodies are excluded. Report privacy limitations honestly rather than claiming deletion from another application's storage.

## Task 10: Validate the integrated app and document operation

**Depends on:** All previous tasks.
**Files:** `README.md`; `docs/engineering/device-validation.md`; integrated tests under the existing test roots; small fixes only in the owning feature files.
**Consumes:** All reviewed task outputs and the approved spec.
**Produces:** Installable debug APK, recorded verification, and a final review package.

- [ ] Run `./gradlew.bat :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug`. Expect successful tasks and zero unresolved lint errors. Record actual versions and results; do not write expected results as observed results.
- [ ] Run `./gradlew.bat :androidApp:connectedDebugAndroidTest` on API 26 and the target API when available. If no device is available, identify the exact manual prerequisite and leave device acceptance incomplete.
- [ ] With the person's explicitly approved test host/session, validate real pairing over Tailscale, human presence, listing, transcript paging, Mail peek/send/reply, and response behavior. Use a disposable test recipient and benign question; never send to arbitrary running agents or answer a production approval. Host pairing/grant changes are concrete manual setup steps.
- [ ] Exercise host reachability loss, SSH key mismatch, daemon restart, background/foreground transitions, cache-only access, uncertain Mail sends, stale approvals, and unsupported transcripts. Verify Terminal never opens, no fifth tab appears, and no system notifications are requested or generated.
- [ ] Document installation via `adb`, host setup/removal, foreground-only synchronization, ephemeral daemon Mail limitations, local retention, and the requirement for macOS/Xcode for future iOS builds. Keep usernames, network addresses, credentials, and actual conversation content out of tracked evidence.
- [ ] Obtain the subagent workflow's whole-branch review for spec coverage, clean code boundaries, privacy, and mutation safety. Fix substantive findings and re-run only affected checks. Report remaining device/visual validation gaps without claiming full acceptance.

Stop after reporting the reviewed result. Do not commit, publish an APK, push, merge, or create a PR unless the user separately authorizes that action.

## Dependency order and review gates

Execution order is 1 through 10. Task 1's contract review gates the entire implementation. Task 2 freezes protocol interfaces; Task 3 freezes storage identities; Task 4 freezes platform connection interfaces. Later delegates receive these definitions instead of recreating them. Any interface change requires updating its consumers and tests before the task passes review.

There is no benefit to concurrent file writers for this first implementation. The subagents provide fresh implementation and review contexts, not competing implementations or background worktrees.

## Plan self-review

- Spec coverage: connection, transcript paging/fallback, Mail, human responses, foreground lifecycle, privacy, local retention, Messages-like navigation, and disabled Terminal all have tasks and acceptance evidence.
- Type consistency: the Shared contracts block names owning tasks and the feature methods used by later tasks; no platform library type enters the core ports.
- Failure coverage: the five Review Focus risks map to named tests above.
- Remaining choices: exact dependency versions and SSH selection are deliberate Task 1 evidence gates. They must be settled before dependent implementation. Visual screenshots remain subject to user assessment because the user has not specified a pixel reference or iOS version.
- Consent: the initial design commit and the plan commit/push were authorized. No implementation commits are part of this plan's execution. The user has approved this document; execute after its commit and push are verified.
