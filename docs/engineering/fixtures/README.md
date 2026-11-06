# Protocol fixtures

The baseline is tuios 0.12.1, commit
`850335c0c08bb8b802406fcc7f254355c89af04a`, protocol 1. See
[the contract](../tuios-contract.md) and [compatibility decisions](../compatibility.md).

Only schema metadata and deliberately fabricated examples belong here. Never
capture real Mail, transcript content, host/user names, tokens, nonces, SSH keys,
pairing invitations, or device fingerprints. A string such as
`FABRICATED_NONCE_DO_NOT_USE` is a placeholder, not a valid credential.

Keep raw federation framing fixtures separate from newline-delimited daemon JSON
fixtures. Future tests must split headers, payloads, JSON lines and multibyte
UTF-8 across reads, and combine several lines in one read. Include unknown entry
kinds, additive fields, omitted fields, structured errors, gap markers, expired
presence, transcript reset, thread eviction, stale response guards, and lost
mutation receipts. Check field names against pinned source or installed discovery;
do not add app-local identity fields to wire examples.

The local ignored task evidence contains the fetched upstream source and installed
schema. It contains no copied runtime Mail or transcripts and is not an app fixture.
