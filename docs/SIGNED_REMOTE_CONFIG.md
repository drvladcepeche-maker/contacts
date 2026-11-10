# Signed remote configuration protocol

## Envelope

The transport envelope is a JSON object with exactly two string fields:

- `payload`: base64url-encoded UTF-8 JSON bytes;
- `signature`: base64url-encoded ASN.1 DER ECDSA signature.

The signature algorithm is `SHA256withECDSA` over the exact decoded payload bytes. The verifier does not parse and re-serialize JSON before signature verification, so whitespace and property order are covered by the signature. The server must sign the exact byte sequence it sends.

## Payload schema version 1

The payload has exactly these required top-level fields:

- `schema_version`: integer, must be 1;
- `sequence`: positive integer, strictly greater than the device's last successfully applied sequence;
- `issued_at`: Unix epoch seconds;
- `expires_at`: Unix epoch seconds;
- `config`: object containing exactly `features` and `sync`.

Allowed configuration keys:

- `config.features.telegram_match_enabled`: optional boolean;
- `config.features.brave_automation_enabled`: optional boolean;
- `config.sync.poll_interval_seconds`: optional integer from 15 through 300.

Unknown keys, wrong types, missing required containers, stale sequence numbers, expired payloads, issue times more than five minutes in the future, and validity periods longer than 30 days are rejected. Payload bytes are limited to 64 KiB. No strings, expressions, URLs, package names, paths, or commands are accepted as configuration values.

## Key management and rollout boundary

The host application must pass a public EC key pinned in its own signed APK to `SignedRemoteConfig.verify`. The private signing key belongs in a controlled signing environment, never in the app, phone, Supabase client, or repository.

This implementation intentionally does not embed a placeholder key, fetch remote configuration, persist values, or apply feature flags. Before enabling delivery, provision a real keypair securely, embed the corresponding public key in a reviewed host build, and implement atomic persistence with last-known-good rollback. Persist the sequence only after a successful atomic apply.

## Verification API

`SignedRemoteConfig.verify(envelopeJson, pinnedPublicKey, nowEpochSeconds, lastAppliedSequence)` returns an immutable validated snapshot or throws on any validation failure. The caller is responsible for obtaining a trustworthy current time and persisting the sequence only after successful application.
