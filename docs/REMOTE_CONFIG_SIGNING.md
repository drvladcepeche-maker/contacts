# Remote configuration signing endpoint

## Request

The Android client will call the Supabase Edge Function `remote-config` with:

- HTTP method: `POST`;
- body: empty JSON object `{}`;
- header: `Authorization: Bearer <64-character device token>`.

The endpoint authenticates the token against `public.contact_bridge_devices` using its SHA-256 hash, and rejects disabled or revoked devices. It does not accept an anonymous configuration request.

## Signing

The function reads `REMOTE_CONFIG_PRIVATE_KEY_PKCS8_B64` from Supabase Edge Function secrets. The value is standard Base64 of a DER-encoded PKCS#8 P-256 private key. It must never be committed to Git, shipped in the APK, or placed in a client-side environment variable.

The function signs the exact UTF-8 bytes of the JSON payload using ECDSA with SHA-256. WebCrypto returns the ECDSA signature in IEEE P1363 form; the function converts it to ASN.1 DER before returning the envelope expected by Android JCA `SHA256withECDSA`.

Response:

```json
{
  "payload": "<base64url-encoded exact UTF-8 payload bytes>",
  "signature": "<base64url-encoded ASN.1 DER ECDSA signature>"
}
```

The payload schema and allowlisted values are defined in [SIGNED_REMOTE_CONFIG.md](SIGNED_REMOTE_CONFIG.md).

## Database access

The source row is `public.remote_app_config`. RLS is enabled, and privileges are revoked from `PUBLIC`, `anon`, and `authenticated`; only `service_role` receives SELECT. The Edge Function reads the single row after device authentication. It validates the schema, safe integer bounds, sequence, issue time, expiry, and configuration keys before signing.

The migration seeds both feature flags as disabled and a conservative 60-second polling interval. Changing configuration requires an authorized server-side database operation that increments `sequence`, sets a fresh `issued_at`, and keeps `expires_at` within 30 days.

## Provisioning boundary

The function returns HTTP 503 until the signing private key is provisioned. This is intentional fail-closed behavior. A matching DER SubjectPublicKeyInfo public key must be pinned in a reviewed Android host build before the client starts trusting this endpoint. Key rotation requires a coordinated app release; do not replace the server private key alone.
