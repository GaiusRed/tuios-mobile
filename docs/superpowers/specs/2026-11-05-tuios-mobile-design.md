# tuios mobile design

Date: 2026-11-05
Status: Design accepted as the basis for implementation planning. Implementation requires approval of the implementation plan.
Project type: Personal.

## Intent

Build an Android app for following and responding to agents running in existing tuios sessions. Provide a messenger-style interface for conversations and Mail, with human questions and approvals accessible from the phone. Plan for iOS without implementing it in the first release.

The phone connects directly to the user's existing host through Tailscale and SSH. There is no new application backend and no change to tuios backend code.

## Agreed decisions

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

## Goals

- Browse accessible hosts, sessions, and agents.
- Read an agent's conversation as structured messages.
- Read and send Mail, including replies, and inspect available agent-to-agent exchanges.
- Read questions and approvals and submit deliberate human responses.
- Update visible data while connected in the foreground.
- Keep downloaded history readable offline.
- Recover from interrupted connections without silently repeating user actions.

## Non-goals

- Push or local system notifications, periodic background polling, or an always-on service.
- A cloud account, hosted gateway, server-side archive, or cross-device history sync.
- Launching agents, creating sessions, running shell commands, or general terminal input.
- An interactive terminal in the first release.
- Reading harness transcript files directly or implementing harness-specific parsers.
- Automatically approving requests or automatically replying to agents.

An ordinary new agent prompt is distinct from Mail or a response to an existing question. General prompt submission is outside the agreed initial permission scope.

## Approaches considered

| Approach | Benefits | Costs | Decision |
| --- | --- | --- | --- |
| Kotlin, native Android UI, shared Kotlin core | Direct Android integration; reusable protocol and synchronization logic for iOS | Separate iOS UI and platform adapters | Recommended architecture within the selected Kotlin direction |
| Flutter | Shared Android and iOS screens | Different language; platform integrations still required | Not selected after the user preferred Kotlin |
| Separate native Android and iOS applications | Independent native implementations | Duplicate protocol and synchronization logic | Not recommended |

Jetpack Compose for Android and Kotlin Multiplatform for shared logic are proposed implementation choices. The user has selected Kotlin, but has not separately approved these framework choices. SwiftUI is the proposed future iOS UI; sharing the UI is not a requirement.

## Product structure

The user-selected top-level destinations form a persistent bottom tab/button group:

1. Sessions: the default destination, listing sessions and their agents with current state and cached conversation previews.
2. Mail: available messages, sender and recipient, reply relationships, and a Mail composer.
3. Terminal: visible, disabled, and identified as a future feature. It cannot be selected and does not open a placeholder terminal or request additional permissions.
4. Settings: host setup, connection state, permissions, local retention controls, and connection removal.

Cold launch opens Sessions. Returning from a temporary background transition preserves the current screen. Explicit navigation to an item may open its owning destination. Mail remains directly accessible and is not hidden behind notifications.

Questions and approvals appear within Sessions and the relevant agent conversation. They do not introduce a fifth bottom destination. Connection management lives under Settings.

### Visual direction

Use iOS Messages as the primary visual reference for navigation, lists, and conversations. Apply that direction on Android rather than defaulting the app's appearance to Material components. Preserve Android system back behavior, accessibility, text scaling, keyboard handling, and system insets.

Use large, clear screen titles; restrained separators; rounded search fields; circular identity marks; readable message previews; and rounded conversation bubbles. Distinguish the person's messages from agent messages through alignment and color. Keep sender identity visible for agent-to-agent Mail. Use blue for primary actions and the selected destination, with subdued treatment for the disabled Terminal control. Support light and dark appearance.

Keep icons and labels in the bottom group. The selected state must be recognizable without relying only on color. Expose Terminal as disabled to accessibility services and describe it as a future feature. Respect navigation and keyboard insets so the group and composer do not overlap system controls.

List views should carry the Messages-like appearance while retaining tuios's host, session, and agent identities. Avoid showing terminal frames around conversations. Expandable tool details and human approval controls must remain distinct from ordinary text messages.

Exact icon choices, typography, spacing, translucency, and whether the bottom group is floating remain visual design details to review in a mockup. The user has specified iOS Messages generally, without specifying an iOS version.

Conceptual structure, not a pixel specification:

```text
Sessions
[ Search sessions and agents             ]

Host / Session
  Agent name                  Working
  Latest conversation preview

[ Sessions ] [ Mail ] [ Terminal ] [ Settings ]
  selected               disabled
```

### Conversations

Selecting an agent opens a paginated transcript. Render user and assistant text as messages. Show tools, results, plans, and diffs as expandable entries when the daemon supplies them. Preserve ordering and identify the source host, session, and pane.

Agent activity, Mail, and questions are different records. A conversation may link to related Mail or Attention items, but must not invent a single chronology or reply relationship that the daemon does not provide.

If no transcript is available, explain the daemon's response and leave Mail accessible. Preserve cached entries with a stale indicator. Unknown transcript entry types get a safe fallback rather than breaking the view.

### Mail

List messages available within the selected session. Show sender, recipient, body, and any subject, timestamp, or reply identifier the daemon provides. Do not assume Mail crosses session boundaries.

Allow a person to select an agent recipient, write Mail, and reply using an existing message identifier. Sending Mail must not imply that the agent started another turn or read the message.

Use app-local read positions for unread badges where possible. Verify the daemon's read and peek semantics before choosing an operation that could consume or mark shared mail read.

Drafts survive navigation and process restarts. Offline drafts are not sent automatically on reconnect.

### Attention and human responses

Show the current question or approval, target agent, and the actions actually offered by tuios. Use the appropriate human-response verb rather than typing into a terminal.

Revalidate an item before responding. Include its prompt or item identity where the protocol supports it. If it changed, refresh and ask the person to review the current request. Never apply a previous approval to a replacement prompt.

Do not automatically resend an approval or response after an ambiguous disconnect. Display that the result is uncertain and reconcile current state.

### Offline behavior

Cached records remain readable and clearly show the last synchronization time. Sending and approval controls require a current connection. Drafting Mail remains available offline.

Reopening the app fetches current data and resumes supported cursors. This is a foreground operation, not a background delivery promise.

## Connection and authorization

1. The user makes the host reachable through their existing Tailscale setup.
2. The app generates a device SSH key and uses tuios's existing pairing protocol.
3. The user verifies and approves pairing on the host.
4. SSH runs the restricted `tuios stdio-proxy` command.
5. The app checks the daemon protocol and available verbs.
6. A human-authorized link stream obtains `attach-presence` for human operations.
7. The app reads current state and subscribes to relevant foreground events.

Use `list`, `mail`, and `respond` capabilities for the intended scope. Do not silently request `open`, `write`, or broader administrative authority. Transcript reads also require human presence and `respond` over a link, despite being read operations.

Pairing changes existing host configuration and SSH authorization through tuios's supported setup. This is allowed configuration, not a backend code change. The app does not install or manage Tailscale.

Verify SSH host identity. Never silently accept a changed host key. Keep private keys in app-private storage protected with platform security facilities. Do not assume a chosen SSH library can directly use non-exportable Android Keystore keys; verify this before selecting key storage.

## Application architecture

Proposed modules:

- Android app: Compose screens, navigation, lifecycle, and platform storage integration.
- Shared core: protocol models, request handling, synchronization, conversation state, and retention rules.
- Platform SSH adapter: pairing credentials, host verification, SSH connection, and proxy streams.
- Local persistence: transcripts, downloaded Mail, Attention snapshots, cursors, local read positions, and drafts.

Use coroutines and Flow for asynchronous state and Kotlin serialization for protocol data. Room/SQLite is the proposed local database. Select concrete versions and the SSH library during implementation planning after compatibility checks.

Keep SSH and platform storage behind small interfaces so an iOS adapter can replace Android-specific code without rewriting protocol logic. iOS builds will require macOS and Xcode.

## Synchronization and recovery

- Maintain independent request and subscription streams as required by tuios's transport.
- Refresh listings and subscribe using supported sequence and boot identifiers.
- Subscribe explicitly to transcript, agent state, Mail, and Attention changes needed by the visible views.
- Use transcript cursors to fetch new or older entries. Honor reset and pagination indicators.
- After an event gap or daemon restart, reconcile current state rather than assuming complete replay.
- Deduplicate records using daemon identifiers within the proper host/session/boot scope.
- Do not treat a pane reused for a new agent conversation as the old conversation.
- Close connections when the app leaves the foreground; reconnect and reconcile when it returns. Preserve drafts and read positions.

Messages that tuios discarded while the app was disconnected cannot be recovered from events alone. Do not claim complete Mail history or exactly-once delivery.

## Data retention and privacy

Retain only data received through authorized daemon interfaces. Store it locally; provide controls to clear a host's cached history and all local data. Removing a connection must offer deletion of its stored credentials and cached content.

Retention duration is configurable; the initial default remains an open decision. Exclude credentials and conversation data from automatic cloud backup. Avoid message bodies, keys, human nonces, and tokens in diagnostic logs.

Human presence expires with the connection. Reacquire it on reconnect and do not persist the nonce as a durable credential.

## Toolchain

Use the existing Scoop-managed JDK, Android command-line tools, and optional Android Studio. Android SDK tools manage SDK packages. The project Gradle Wrapper pins Gradle; project configuration pins Kotlin and Compose dependencies. A standalone Scoop Kotlin compiler is unnecessary.

The inspected machine already has Scoop-managed `temurin21-jdk`, `android-clt`, and `android-studio`. This establishes tool availability, not a verified application build environment.

## Acceptance criteria

- Pair and connect to an existing tuios host over Tailscale without backend code changes.
- List sessions and agents under the agreed capabilities.
- Open Sessions on cold launch and show the bottom destinations in the agreed order: Sessions, Mail, Terminal, Settings.
- Keep Terminal disabled and inaccessible as a functional destination.
- Apply the iOS Messages visual direction to lists and conversations while preserving Android accessibility and navigation behavior.
- Read a daemon-provided transcript, including pagination and incremental updates.
- Read available Mail, send a message, and reply with the proper reply identifier.
- Display Mail independently of transcript availability.
- Answer a current human question or approval; reject stale targets safely.
- Reconnect after network loss without duplicating visible history or silently resending mutations.
- Read downloaded history and edit unsent drafts offline.
- Show a recoverable unsupported-feature state when a daemon lacks a needed verb.
- Operate without registering push subscriptions, showing system notifications, or maintaining background connections.

## Validation required before implementation decisions are final

Check the target daemon's `hello` and `list-verbs` contracts against pairing, human link streams, transcript reads, Mail read/peek semantics, replies, and Attention responses. Select an SSH implementation that supports the required proxy transport and key handling.

Meaningful implementation tests must cover reconnect gaps, transcript resets, duplicate events, ambiguous Mail submission, stale approvals, unknown transcript entries, and retention. Validate the actual SSH pairing and foreground lifecycle on a physical Android device connected through Tailscale.

## Remaining decisions

- Confirm native Compose Android UI with a shared Kotlin core.
- Choose local retention defaults and whether to require biometric unlock.
- Review a mockup for the iOS Messages visual direction; bottom destination names, order, default, and disabled Terminal state are already decided.
- Select the SSH library after transport and credential-storage validation.
- Define the minimum supported Android and tuios versions from compatibility evidence.

The user has authorized creating a public GitHub repository with this design as the initial commit on main, followed by an implementation plan on a feature branch. Subagents are authorized for later execution after plan approval. No application implementation or dependency installation is authorized yet. Do not create worktrees. Consent for the initial design commit does not authorize additional commits; follow the user's separate consent rule.

## Evidence

- https://tuios.dev/docs/phones
- https://tuios.dev/docs/control-protocol
- https://raw.githubusercontent.com/Gaurav-Gosain/tuios/main/docs/protocol.md
- https://tuios.dev/docs/agent-messaging
- https://developer.android.com/kotlin/multiplatform
- https://developer.android.com/tools
- https://kotlinlang.org/docs/multiplatform-mobile-setup.html

## Review

Self-reviewed against the brainstorming builder-check guidance. No subagents were used to prepare the design. User decisions and proposed implementation choices are separated. Remaining protocol and library uncertainties are explicit and must be resolved before implementation depends on them.
