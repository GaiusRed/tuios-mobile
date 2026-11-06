# tuios mobile protocol baseline

Verified on 2026-11-05 against the installed **tuios 0.12.1**, commit
`850335c0c08bb8b802406fcc7f254355c89af04a`, protocol 1, minimum protocol 1.
The installed daemon answered `hello` and `list-verbs --json`; no real Mail,
transcripts, pairing invitations, or human nonces were collected. Runtime identity
and load values are deliberately absent here.

All source references below use [this immutable source tree](https://github.com/Gaurav-Gosain/tuios/tree/850335c0c08bb8b802406fcc7f254355c89af04a).
The focused request/return field inventory at the end is derived from installed
discovery, with response fields completed from handler source. Discovery is not
a complete JSON Schema: handlers and Go JSON tags decide omitted fields.

## SSH transport

SSH opens an **exec channel without a PTY**, using public-key authentication and
the host key pinned during pairing. Request `tuios stdio-proxy`; the paired key's
forced command supplies the absolute binary path and `--as DEVICE`. Keep SSH
stderr separate from stdout. Do not interpolate the returned command into a shell
script. Proxy startup does not start a missing daemon.

The proxy first writes `TUIOS-LINK 1\n`. It then speaks binary federation frames,
not JSON directly on stdout. A bounded banner scan may precede the preamble (the
upstream limit is 8 KiB; preserve bytes after the newline). Every frame has:

| Offset | Encoding |
| --- | --- |
| 0 | Type byte: OPEN=1, DATA=2, CLOSE=3, CREDIT=4 |
| 1–4 | Unsigned stream ID, big endian |
| 5–8 | Unsigned payload byte length, big endian |
| 9 onward | Exactly that many payload bytes; maximum 1 MiB |

The phone uses odd stream IDs starting at 1, incrementing by 2. The answering
proxy allocates even IDs. An OPEN payload is empty or a JSON object. For every
phone channel that will hold or use human presence send `{"human":true}`.
Optional `from` is only a claimed device name; the host's forced `--as` name wins.
Do not set `bulk`: CREDIT is for file streams, outside this release. Unknown frame
types, oversize lengths, and incomplete headers/payloads terminate the link.
Serialize writes so two frames cannot interleave. DATA payloads are arbitrary
pieces of a logical byte stream: a UTF-8 character or JSON line can span frames,
and one frame may hold several lines. CLOSE has an empty payload and ends one
stream. Closing SSH ends every stream.

Sources: [frame.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/federation/frame.go),
[mux.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/federation/mux.go),
[stream_open.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/federation/stream_open.go),
[proxy.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/federation/proxy.go),
[host_commands.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/cmd/tuios/host_commands.go#L447).

## Pairing

The person runs `tuios pair --name phone --allow list,mail,respond` on the host.
Default pairing grants only list and mail, so default grants cannot read
transcripts or answer Attention. The app must explain the required grants;
it cannot add grants through the pairing request.

The QR URI is `tuios://pair?v=1&m=MACHINE&u=USER&s=HOST:PORT,...&p=HOST:PORT,...&fp=SHA256:...&t=TOKEN`.
The parameters are machine name, SSH user, SSH candidates, pairing HTTP
candidates, SSH host fingerprint, and 16 random bytes encoded as unpadded
base64url. IPv6 uses bracketed host/port form. There is **no expiry timestamp or
check code in the URI**. The host enforces expiry (default 5 minutes, maximum 1
hour). Do not invent an `expires` query parameter or trust a phone clock to prove
the invitation is current.

Client validation: bound input size (app policy: 8 KiB), require scheme `tuios`,
authority `pair`, no fragment/userinfo/path, version 1, exactly one of every
required parameter, valid percent encoding, nonempty user/machine, valid bounded
host/port lists, a 32-byte SHA256 fingerprint and a token that decodes to 16 bytes.
Reject control characters, invalid ports, duplicate security parameters and
arbitrary URL schemes. These checks are client policy around the documented
producer format, not extra fields the daemon requires. Try the advertised
tailnet candidates first. The app does not install Tailscale.

Generate ECDSA P-256 for the phone. The server accepts a single OpenSSH public-key
line: ECDSA P-256, Ed25519, or RSA of at least 3072 bits. Other EC curves and
security-key variants are refused. POST plain HTTP `/v1/pair` to one `p` endpoint,
with a fixed Content-Length, JSON body at most 8192 bytes, and fields `device`,
`key`, `mac`. Chunked requests fail with HTTP 411; one request uses one connection.
Avoid racing POSTs across candidates: pairing is a mutation.

Let `T` be the decoded token and `K` the exact public-key string sent:

```text
mac = base64url_no_padding(HMAC_SHA256(T, "tuios-pair-v1\n" + device + "\n" + K))
check = decimal_6_digits(uint32_be(HMAC_SHA256(T, "tuios-pair-v1-check\n" + K)[0:4]) % 1000000)
```

Display the check and phone public-key SHA256 fingerprint while waiting. The
person compares both on the host and types yes. Never bypass this with `--yes`.
The host rejects requests from itself and configured linked machines; do not
silently use `--accept-local`. The approved key is installed only after its
device-specific host policy has been saved and read back.

Success JSON fields: `ok:true`, `device`, `user`, `command`, `host_key`, `mac`,
`check`. Verify its MAC using
`"tuios-pair-v1-ok\n" + returned_device + "\n" + returned_host_key`.
Verify the returned check, parse the host-key line, compute its SSH SHA256
fingerprint, and compare with QR `fp`. Pin the full public host key before SSH
authentication; a mismatch stops connection. The success MAC does not cover
`user` or `command`: use the invitation's user and a fixed proxy exec request,
never treat returned command text as trusted instructions. Errors use
`ok:false,error:string`, HTTP statuses including 400, 403, 409, 410, 411, 413,
429 and 500. A lost HTTP acknowledgment can still mean the key was installed;
show an uncertain pairing result and let the person reconcile on the host.

Source: [pair_command.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/cmd/tuios/pair_command.go)
(URI at 850, MAC/check at 872, request handling at 1155, accepted keys at 1406).

## JSON and human presence

Within each logical stream send UTF-8 JSON objects terminated by LF:

```json
{"id":1,"verb":"hello","params":{"client":"tuios-mobile","version":"0.1.0","protocol":1}}
```

Replies are `{"id":1,"result":{"type":"...",...}}` or
`{"id":1,"error":{"code":"...","message":"...","hint":{...}}}`.
`id` is echoed, not an idempotency token. Keep unknown error codes and hint fields.
Do not confuse the `tuios call` CLI's unwrapped result with this envelope.
Bound line buffers separately from the federation payload bound. Treat EOF with
a partial line as transport failure. Unknown JSON fields are additive; unknown
event or transcript kinds must remain data that can be preserved and displayed.

Open a human stream and call `attach-presence` with explicit `session` to limit
the nonce to that session. Result type is `presence` with `human_nonce`,
`client_id`, and `session` only if supplied. Omitting session gives presence
authority across sessions; use it only for a deliberate all-session Attention
flow. Presence changes no size, focus, or viewed state and receives no broadcasts.
It survives other requests on that stream but ends with stream closure or
connection restriction. Reacquire on reconnect; retain the nonce only in memory.

Nonce verification also checks local-vs-link and human-vs-plain stream kind, and
the peer process when available. Sibling logical streams from the same proxy
can use a live nonce under these checks; the app should keep presence and its
requests on the same logical stream to avoid lifetime mistakes. A session-scoped
nonce cannot answer an item in another session or a host-level item. The proxy
itself performs `link-peer`; the phone must not send a forged handshake.

Required link capabilities:

| Operation | Capability | Human proof |
| --- | --- | --- |
| hello / list-verbs | Discovery | None |
| sessions, agents, Attention, peek-prompt, subscribe, attach-presence | list | Presence issuance requires human stream |
| Mail read/send | mail | Valid live nonce for verified `from:human` send |
| agent-transcript | respond | Live nonce covering target session |
| respond / reply-approval / answer-ask / dismiss-attention | respond | Live nonce covering the action |

Sources: [verb_presence.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/verb_presence.go),
[human_sender.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/human_sender.go),
[human_origin.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/human_origin.go),
[link_policy.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/link_policy.go).

## Mail, transcripts, and responses

**Both directions of retained Mail can be read without consuming unread state.**
Use `read-agent-messages` with explicit session, `thread` and `peek:true`, omitting
`to` and `unread`. The session-wide query returns every retained matching message,
including sender and recipient directions, and marks neither read nor seen.
`thread` accepts any retained message ID in that thread. An inbox query (`to`)
filters on recipient, so it does not return the sender's outgoing side.
An own-inbox peek can set `seen_at` while leaving `read_at` zero. A passive phone
thread should omit `to`; it must never issue a consuming recipient read.
Sources: [verb_agents.go:813](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/verb_agents.go#L813),
[verb_mailbox.go:488](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/verb_mailbox.go#L488).

Send and reply both use `send-agent-message`; a reply adds `reply_to`, not a
different reply verb. Always send explicit session, resolved recipient window ID,
`from:"human"`, current `human_nonce`, and text. A reply joins its parent's
`thread_id`; if the parent was evicted `reply_to_missing` can be true. A send from
human without valid proof can succeed as `claimed_human`, so validate
`verified_human` in the receipt instead of equating success with verified origin.
No client mutation key/deduplication field exists. A disconnect or timeout after
bytes may have left the phone is **uncertain**; never replay automatically.
Refresh retained messages, show evidence, and require a fresh deliberate send if
the user chooses. Matching body text cannot prove whether a send succeeded.

Mail is a bounded newest-first retention ring returned oldest-to-newest per page:
`limit` returns the newest matching rows, not an offset or before cursor. The
default is 20. The ring holds at most 256 messages and 512 KiB of text per session.
`total` and `evicted` expose incompleteness; 30-day local retention
cannot recover Mail already evicted before the phone received it. Missing threads
return an empty list. There is no Mail older-page cursor. Attachments are host
path references, not downloadable bytes under list/mail/respond permissions.

Transcripts use opaque `cursor` for `after` and `older` for `before`, never both.
Default limit 200; explicit limit is 1–1000. `more` requests another incremental
page. `reset:true` means replace this transcript generation, not append. Entry
IDs replace earlier entries with the same ID. Treat cursor contents as opaque.
Use host identity + session ID + window ID + boot/transcript generation for local
keys; names alone are unsafe. Missing `agent-transcript`, `unsupported_harness`,
or `no_transcript` leaves Mail available. Do not maintain a mobile harness list.
Source: [verb_agent_transcript.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/verb_agent_transcript.go).

For screen prompts, refresh `peek-prompt`, enable only offered actions, and always
send its `prompt_id` in `respond`. Stale/already answered prompts return
`prompt_changed` before keys are pressed. `settled_by:"timeout"` is a successful
write whose resulting state is not yet settled, not permission to retry.
For held approvals use `reply-approval` with `request_id`, the displayed `summary`,
offered `decision`, and exact `risk_ack` for an allow. Plan approvals also require
the displayed `plan_sha`. For ask-human questions use `answer-ask` with
`request_id`, exact displayed `question`, and one offered answer. An
`applied:false` result is not an accepted new answer. Sources:
[verb_respond.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/verb_respond.go),
[protocol response docs](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/docs/protocol.md#respond).

## Subscriptions and cancellation

Use a separate logical stream for `subscribe`. Its enveloped `subscribed` reply
contains `seq`, `boot_id`, optionally `replayed`. Then unwrapped event JSON lines
carry `type`, usually `seq`, `boot_id`, `time` and type-specific fields. Agent
messages contain an invalidation signal, not the body; transcript events contain
a cursor signal, not transcript entries. Opt in to `transcript` in `types`.
Keep the cursor from the last **applied event**, not merely the subscription ack:
replay events have sequence values at or below the acknowledgment baseline.
Gaps in numeric sequences can be filtered events, not necessarily loss.

Resume with both `after_seq` and `boot_id`. A gap marker has no `seq` and reasons
`overflow`, `evicted`, `boot_changed`, or `not_retained`; unknown/missing reasons
also require refreshing authoritative listings and histories. The replay ring
holds 4096 retained events. Output is not replayed. Do not request output or
notification events for this app. Unknown events must not crash decoding.

`unsubscribe` returns type `unsubscribed`; closing the logical stream also ends
the subscription. Cancellation closes that stream and clears its partial line
buffer. Cancel presence by closing its stream, and release all channels and SSH
when leaving the foreground. Lost mutation acknowledgments remain uncertain
despite closing the stream. Sources:
[verb_subscribe.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/verb_subscribe.go),
[daemon_events.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/daemon_events.go).

## Known scope limits

No backend change is needed for a directly paired host's listings, retained
transcripts, retained Mail, or current human answers with the agreed grants.
Complete historical Mail recovery, automatic exactly-once resend, and arbitrary
attachment download are unavailable under this contract. Linked-host Attention
may be listed, but answering on another machine requires connecting directly to
that saved host and acquiring its presence: a nonce is not portable across
daemons. Do not silently add relay/write/files permissions.

## Exact discovery field inventory

Names and primitive types below come from the installed schema. `!` marks a required request field. Optional means omitted is allowed, not that every combination is valid. Response `type` discriminators and handler additions follow below.

| Verb | Request params | Discovered result fields |
| --- | --- | --- |
| `hello` | `client:string`, `version:string`, `protocol:int`, `term:string`, `color_term:string` | None declared |
| `list-verbs` | `verb:string` | None declared |
| `list-sessions` | None declared | None declared |
| `list-agents` | `session:string`, `all:bool`, `all_sessions:bool`, `select:string` | `agents:[]object`, `total:int`, `select:string`, `confirm:string` |
| `agent-transcript` | `session:string`, `window!:string`, `human_nonce!:string`, `after:string`, `before:string`, `limit:int` | `session:string`, `window:string`, `harness:string`, `cursor:string`, `reset:bool`, `more:bool`, `older:string`, `entries:[]object`, `untrusted:bool` |
| `read-agent-messages` | `session:string`, `to:string`, `unread:bool`, `notices:bool`, `peek:bool`, `thread:int`, `limit:int` | `messages:[]object`, `thread:int`, `untrusted:bool`, `unread:int`, `seen:int`, `marked_read:int`, `marked_seen:int`, `total:int`, `evicted:int`, `peek_forced:bool` |
| `send-agent-message` | `session:string`, `to:string`, `from:string`, `from_host:string`, `subject:string`, `text:string`, `parts:[]object`, `reply_to:int`, `attachments:[]string`, `human_nonce:string`, `host:string`, `select:string`, `confirm:string` | `results:[]object`, `sent:int`, `failed:int`, `message_id:int`, `queued:bool`, `queue_id:int`, `waiting:int`, `kind:string`, `to:string`, `to_name:string`, `from:string`, `sent_at:int`, `reply_to:int`, `thread_id:int`, `reply_to_missing:bool`, `origin:string`, `origin_host:string`, `verified_human:bool`, `claimed_human:bool`, `held:bool`, `held_for:string` |
| `list-attention` | `session:string`, `kinds:[]string`, `host:string`, `select:string`, `include_snoozed:bool` | `items:[]object`, `counts:object`, `total:int`, `seq:int`, `boot_id:string` |
| `peek-prompt` | `session:string`, `window!:string` | `window:string`, `name:string`, `harness:string`, `state:string`, `state_at:int`, `waiting_ms:int`, `blocked:bool`, `found:bool`, `answerable:bool`, `reason:string`, `source:string`, `kind:string`, `message:string`, `prompt_id:string`, `lines:[]string`, `options:[]object`, `actions:[]string`, `untrusted:bool` |
| `get-approval` | `request_id!:string`, `session:string` | `request_id:string`, `kind:string`, `session:string`, `window:string`, `summary:string`, `tool:string`, `target:string`, `options:[]string`, `always_scope:[]string`, `plan:string`, `plan_sha:string`, `risk:[]object`, `deny_message:bool`, `untrusted:bool` |
| `attach-presence` | `session:string` | `human_nonce:string`, `client_id:string`, `session:string` |
| `respond` | `session:string`, `window!:string`, `action!:string`, `value:string`, `prompt_id:string`, `human_nonce:string`, `timeout:int`, `risk_ack:[]string` | `window:string`, `action:string`, `sent:string`, `prompt_id:string`, `settled_by:string`, `state:string`, `message:string`, `by_pane:string` |
| `reply-approval` | `request_id:string`, `session:string`, `window:string`, `decision!:string`, `message:string`, `human_nonce!:string`, `summary:string`, `risk_ack:[]string`, `plan_sha:string` | `request_id:string`, `decision:string`, `applied:bool`, `reason:string`, `summary:string`, `answered_by:string`, `session:string`, `window:string` |
| `answer-ask` | `request_id!:string`, `answer!:string`, `human_nonce!:string`, `question:string` | `request_id:string`, `answer:string`, `applied:bool`, `reason:string`, `answered_by:string` |
| `dismiss-attention` | `id!:string`, `human_nonce!:string` | `id:string`, `kind:string`, `session:string`, `host:string`, `dismissed:bool` |
| `subscribe` | `session:string`, `window:string`, `types:[]string`, `queue:int`, `after_seq:int`, `boot_id:string`, `hosts:bool` | `seq:int`, `boot_id:string`, `replayed:int` |
| `unsubscribe` | None declared | None declared |

### Response fields completed from source

The following objects supplement discovery; preserve further additive fields rather than rejecting them. All integer timestamps must use 64-bit values. Mail IDs and sequences are unsigned 64-bit in Go; preserve their numeric values without floating-point conversion.

- `hello`: `type:hello`, `protocol`, `min_protocol`, `daemon_version`, `pid`, `instance`, `os`, `sessions`, `link_policy`, `pane_grants`, `load`. The app must not persist process IDs or load as host identity.
- `list-verbs`: `type:verb_list`, `version`, `min_version`, `daemon_version`, `envelope`, `error_codes`, `verbs`. Each verb has `verb`, `description`, `params`, optional `returns`, `examples`; a param/return can carry `name`, `type`, `required`, `default`, `accepted`, `description`.
- `list-sessions`: `type:session_list`, `sessions:[]SessionInfo`.
- `list-agents`: `type:agent_list`, `session:string` for a single session, `agents:[]AgentRow`, `total:int`, `human_inbox:string` (the literal `human`), `human_unread:int`; selector results may add `select:string`, `confirm:string`.
- `agent-transcript`: `type:agent_transcript` plus the discovered fields.
- `read-agent-messages`: `type:agent_messages`, `session`, `inbox` (empty for a session query) plus the discovered fields.
- `send-agent-message`: `type:agent_message_sent` for one local-session recipient, with the discovered single-send fields; selector sends use `agent_messages_sent`. This app uses neither selectors nor host relay sends.
- `list-attention`: `type:attention_list`, `items`, `counts`, `total`, `seq`, `boot_id`; optional `select`. `counts` maps kinds to integer counts.
- `peek-prompt`: `type:prompt_peek`, `session`, the discovered fields, and `rule`. Read risk rules from the current Attention item or `get-approval`; peek-prompt has no risk field in this baseline. Use current actions/options, not a locally invented action set.
- `attach-presence`: `type:presence` plus the discovered fields.
- `get-approval`: `type:approval` plus the discovered fields. It reads a running hold with the `list` capability and `scopeRead`; no nonce is required for this read. Always supply the current session name and match its returned session, full window, request ID and kind. A closed hold returns an error. `risk` contains objects with `rule:string` and `why:string`; this differs from Attention's array of rule IDs. `plan` is the complete held plan, at most 32 KiB. Display the complete detail, including `always_scope`, before offering an answer. Allow decisions require exactly the rule names in `risk_ack`; plans also require the displayed `plan_sha`. `deny_message:true` enables an optional denial reason in `reply-approval.message`. Sources: [detail handler](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/verb_approval_ext.go), [link policy](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/link_policy.go#L299), and [approval guards](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/approvals.go#L444).
- `respond`: `type:prompt_response`, `session` plus discovered fields.
- `reply-approval`: `type:approval_replied` plus discovered fields; `answer-ask`: `type:ask_answered` plus discovered fields. Success must be based on `applied`. Sources: [approvals.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/approvals.go#L801), [ask_human.go](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/ask_human.go#L568).
- `dismiss-attention`: `type:attention_dismissed` plus discovered fields.
- `subscribe`: `type:subscribed`; `unsubscribe`: `type:unsubscribed`. Events are unwrapped, not result objects.


**SessionInfo** ? `name:string`, `id:string`, `created:int64`, `last_active:int64`, `window_count:int`, `attached:bool`, `width:int`, `height:int`, `windows:[]WindowSummary (omitted when zero)`, `display_name:string (omitted when zero)`, `accent:string (omitted when zero)`, `note:string (omitted when zero)`, `current_workspace:int (omitted when zero)`, `dir:string (omitted when zero)`, `branch:string (omitted when zero)`, `restored:bool (omitted when zero)`, `global:bool (omitted when zero)`, `space:string (omitted when zero)`, `rank:int (omitted when zero)`, `worktree:*WorktreeInfo (omitted when zero)`.

**Transcript entry** ? `id:string`, `at:int64 (omitted when zero)`, `role:string`, `kind:string`, `text:string (omitted when zero)`, `truncated:bool (omitted when zero)`, `tool:string (omitted when zero)`, `target:string (omitted when zero)`, `status:string (omitted when zero)`, `tool_id:string (omitted when zero)`, `diff:*Diff (omitted when zero)`, `plan:string (omitted when zero)`, `todos:[]Todo (omitted when zero)`.

**Transcript diff** ? `file:string`, `added:int`, `removed:int`, `hunks:[]Hunk`, `truncated:bool (omitted when zero)`, `plain:bool (omitted when zero)`, `whole_replace:bool (omitted when zero)`.

**Transcript hunk** ? `old_start:int`, `new_start:int`, `lines:[]DiffLine`.

**Transcript diff line** ? `op:string`, `text:string`, `spans:[]Span (omitted when zero)`, `words:[]Range (omitted when zero)`.

**Transcript todo** ? `text:string`, `status:string`.

**Mail message** ? `id:uint64`, `kind:string`, `session:string`, `from:string (omitted when zero)`, `from_label:string (omitted when zero)`, `to:string (omitted when zero)`, `to_label:string (omitted when zero)`, `subject:string (omitted when zero)`, `text:string`, `reply_to:uint64 (omitted when zero)`, `thread_id:uint64`, `reply_to_missing:bool (omitted when zero)`, `attachments:[]AgentAttachment (omitted when zero)`, `sent_at:int64`, `read_at:int64 (omitted when zero)`, `seen_at:int64 (omitted when zero)`, `settled_by:string (omitted when zero)`, `origin:string (omitted when zero)`, `origin_host:string (omitted when zero)`, `verified_human:bool (omitted when zero)`, `claimed_human:bool (omitted when zero)`, `undeliverable:bool (omitted when zero)`, `was_unread:bool (omitted when zero)`, `was_seen:bool (omitted when zero)`, `held:bool (omitted when zero)`, `held_for:string (omitted when zero)`, `held_for_label:string (omitted when zero)`, `released:bool (omitted when zero)`, `released_from:uint64 (omitted when zero)`.

**Mail attachment** ? `kind:string`, `path:string`, `media_type:string (omitted when zero)`, `bytes:int64 (omitted when zero)`, `missing:bool (omitted when zero)`, `stashed:bool (omitted when zero)`.

**Attention item** ? `id:string`, `kind:string`, `host:string (omitted when zero)`, `session:string`, `window:string (omitted when zero)`, `workspace:int (omitted when zero)`, `harness:string (omitted when zero)`, `name:string (omitted when zero)`, `summary:string (omitted when zero)`, `risky_flags:[]string (omitted when zero)`, `options:[]string (omitted when zero)`, `request_id:string (omitted when zero)`, `always_scope:[]string (omitted when zero)`, `expires:int64 (omitted when zero)`, `answer:string (omitted when zero)`, `answered_by:string (omitted when zero)`, `since:int64`, `seq:uint64`, `thread:uint64 (omitted when zero)`, `held_id:uint64 (omitted when zero)`, `held_for:string (omitted when zero)`, `for_host:string (omitted when zero)`, `count:int (omitted when zero)`, `seen:int (omitted when zero)`, `completion_seq:uint64 (omitted when zero)`, `closed:string (omitted when zero)`, `stale:bool (omitted when zero)`, `seen_at:int64 (omitted when zero)`, `snoozed_until:int64 (omitted when zero)`, `marked_unread:bool (omitted when zero)`, `risk:[]string (omitted when zero)`, `deny_message:bool (omitted when zero)`, `plan_lines:int (omitted when zero)`, `plan_sha:string (omitted when zero)`.

**Event** ? `seq:uint64 (omitted when zero)`, `type:string`, `session:string (omitted when zero)`, `window:string (omitted when zero)`, `pty_id:string (omitted when zero)`, `title:string (omitted when zero)`, `client_id:string (omitted when zero)`, `pid:int (omitted when zero)`, `attached:*bool (omitted when zero)`, `body:string (omitted when zero)`, `bytes:int (omitted when zero)`, `mode:string (omitted when zero)`, `enabled:bool (omitted when zero)`, `state:string (omitted when zero)`, `workspace:int (omitted when zero)`, `dropped:uint64 (omitted when zero)`, `reason:string (omitted when zero)`, `boot_id:string (omitted when zero)`, `time:int64 (omitted when zero)`, `action:string (omitted when zero)`, `attention:*AttentionItem (omitted when zero)`, `host:string (omitted when zero)`, `status:string (omitted when zero)`, `cmdline:string (omitted when zero)`, `exit_code:*int (omitted when zero)`, `duration_ms:int64 (omitted when zero)`, `command_seq:uint64 (omitted when zero)`, `entry:*AgentActivityEntry (omitted when zero)`, `cursor:string (omitted when zero)`, `transfer:*TransferRow (omitted when zero)`, `file:*TransferFile (omitted when zero)`, `tml:*TMLEvent (omitted when zero)`, `task:*TaskRow (omitted when zero)`.

### Prompt options and choose values

`peek-prompt.options` is an array of objects with these exact fields:

| Field | Type | Meaning |
| --- | --- | --- |
| `n` | int | Number displayed beside the option; use this value, not the array index. |
| `label` | string | Displayed option label, sanitized by the daemon. |

For `respond` with `action:"choose"`, `value` is the displayed option number
encoded as a **string**: an option with `n:2` maps to `value:"2"`. Send the current
`prompt_id`, and offer this control only while `actions` contains `choose`.
For `action:"text"`, the same `value:string` carries the answer text. Numbered
screen options differ from Attention's `options:[]string`, which contains offered
decisions or ask-human answers rather than objects with `n` and `label`.
Sources: [pinned prompt response handler](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/verb_respond.go#L333),
[pinned harness Option and answer selection](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/harness/answers.go#L177),
and the installed `respond` parameter schema (`value:string`).

### Session window summaries

`SessionInfo.windows` is an optional array of `WindowSummary`, not an array of
AgentRow. Its exact fields from the pinned `protocol.go` declaration follow.
Fields marked optional are omitted when empty, zero or false, according to their
Go JSON tags. The app uses **list-agents** as its authoritative typed agent
listing. These summaries can be preserved without substituting their differently
named fields for that listing.

| Field | Wire type | Omission |
| --- | --- | --- |
| `id` | string | Always present |
| `title` | string | Always present |
| `agent_state` | string | Optional |
| `agent_reason` | string | Optional |
| `agent_state_at` | int64 | Optional |
| `agent_harness` | string | Optional |
| `agent_message` | string | Optional |
| `agent_kind` | string | Optional |
| `completion_seq` | uint64 | Optional |
| `agent_meta` | array of opaque objects | Optional |
| `agent_queued` | int | Optional |
| `agent_draft_held` | bool | Optional |
| `agent_settled` | bool | Optional |
| `agent_subagents` | int | Optional |
| `agent_subagent_list` | array of opaque objects | Optional |
| `program_status` | array of opaque objects | Optional |
| `foreground_cmd` | string | Optional |
| `workspace` | int | Optional |
| `scratch` | bool | Optional |
| `note` | string | Optional |

`agent_meta`, `agent_subagent_list` and `program_status` are intentionally opaque
and unused by the initial screens; retain their JSON values for forward
compatibility. Do not invent nested DTO fields for them. Source:
[WindowSummary](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/protocol.go#L540).

### Agent rows

Every row emitted by `list-agents` has the primitive fields below, including
empty strings, zero counters and false flags. `evidence_age_ms` is present but
can be **null** when no state evidence exists. Use 64-bit integer storage for
nanoseconds and sequences; retain unsigned values without floating-point
conversion. An unknown state, source, confidence or identity remains a string.

| Fields | Exact wire type |
| --- | --- |
| `session`, `window_id`, `name`, `state`, `reason`, `message` | string |
| `source`, `harness_id`, `foreground`, `cwd`, `blocked_by` | string |
| `confidence`, `identity`, `agent_session_id`, `group`, `protocol` | string |
| `agent_state_at` | int64 (Unix nanoseconds) |
| `workspace`, `unread`, `seen`, `queued`, `subagents` | int |
| `evidence_age_ms` | int64 or null (milliseconds) |
| `completion_seq` | uint64 |
| `focused`, `ready`, `needs_you`, `finished_unread`, `draft_held`, `settled` | bool |
| `meta` | object, preserved as opaque JSON |
| `subagent_list`, `program_status` | array of objects, preserved as opaque JSON |
| `started_by` | object, optional; preserved as opaque JSON |

The initial screens consume the primitive fields. `meta`, `subagent_list`,
`program_status`, and `started_by` have no initial-screen interpretation; retaining
these objects does not authorize inferring their shape from WindowSummary.
`confidence` currently offers `certain`, `strong`, `none`; `identity` offers
`report`, `manifest`, `list`, `hint`, or an empty string. Source:
[agentRows](https://github.com/Gaurav-Gosain/tuios/blob/850335c0c08bb8b802406fcc7f254355c89af04a/internal/session/verb_agents.go#L213)
and installed `get-agent-state` discovery for confidence/identity and nullable
evidence age.
