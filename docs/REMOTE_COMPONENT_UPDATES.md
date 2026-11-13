# Remote component updates through Shizuku

## Status

Architecture decision and implementation plan for the Android app. The existing whole-APK updater remains the recovery and base-runtime update channel. Component updates are a separate, staged capability and must not be implemented by loading arbitrary DEX/JAR files into the app process.

## Goals

- Update a narrowly scoped feature without replacing the main APK.
- Perform approved package installation through the already integrated Shizuku UserService when Shizuku is running and its permission has already been granted.
- Keep a signed, auditable update manifest and immutable component artifacts.
- Verify publisher identity, artifact digest, package identity, version monotonicity, compatibility, and an explicit component allowlist before installation.
- Keep user data and Android contacts untouched by component rollback or update failures.

## Android deployment model

Use separate Android application packages for independently deployable components. Each component exposes a small, versioned Binder/AIDL or bound-Service contract. The host app communicates with the component through explicit intents and signature-level permissions; components do not receive broad access to host-private data.

The first candidate modules are:

1. Telegram matching worker.
2. Diagnostics/health reporting worker.
3. Brave automation integration, only if its lifecycle and accessibility-service ownership can be separated safely.

Do not split code that relies on in-process private state until its interface and data ownership are defined. In particular, ContactRepo and raw contact access remain in the host app; a worker receives only the minimum input required for its task.

## Signed update manifest

The update service should return a signed manifest with, at minimum:

- manifest schema version and monotonically increasing manifest sequence;
- component ID from a fixed allowlist;
- Android package name;
- component version code and semantic version;
- artifact URL and SHA-256;
- signing-certificate SHA-256;
- minimum host version and supported Android SDK range;
- rollout channel and optional minimum Shizuku API version;
- expiry/issued-at values and rollback policy.

The app must verify the manifest signature against a public key embedded in the host APK, then verify the artifact digest and APK signing certificate independently. HTTPS alone is not an authenticity mechanism. The private signing key must never be stored in the APK, Supabase client, or phone.

## Installation sequence

1. Poll for an update manifest over the authenticated device bridge.
2. Validate schema, signature, expiry, sequence, component allowlist, compatibility, and rollout channel.
3. Download the artifact to an app-controlled staging directory.
4. Verify SHA-256, APK package name, version code, and signer certificate.
5. Check Shizuku availability and the already-granted permission. Do not attempt to bypass a denied permission.
6. Copy the verified artifact to a shell-readable location and ask the Shizuku UserService to install only the allowlisted package with Package Manager.
7. Verify the installed package version and signer after installation.
8. Run a component health check and mark the manifest sequence as applied only after success.
9. If health checks fail, disable the component and reinstall the last known-good signed artifact where Android permits it. If rollback is unavailable, leave the host app functional and report the failure.

## Security boundaries

- Never accept arbitrary shell commands from the server.
- Never accept arbitrary package names, file paths, or install flags from a remote payload.
- Never install an APK solely because its SHA-256 was supplied by the same unauthenticated response; verify a manifest signature rooted in a key embedded in the app.
- Never weaken the existing host APK package/signature/hash checks.
- Never load remote executable DEX/JAR/native libraries into the host process.
- Never use Shizuku to grant permissions, change secure settings, or access unrelated packages as part of the updater.
- All contact read/write operations continue to obey the existing account-level permissions and explicit confirmation rules.
- If Shizuku is unavailable, defer package updates; signed configuration/resource updates may continue independently.

## Non-code updates

Use a separate signed configuration channel for feature flags, sync parameters, text resources, and lookup tables. Configuration must have a strict schema, bounded sizes, expiry, and rollback to the last known-good version. Configuration must not contain executable code or arbitrary expressions.

## Rollout stages

### Stage 0 — reliability and diagnostics

- Fix ownership of the existing get_diagnostics command: the foreground service is the sole processor, including while the Activity is visible.
- Confirm diagnostic payload excludes contact contents, device tokens, and Telegram API credentials.
- Preserve the current full-APK updater as the fallback.

### Stage 1 — signed configuration

- Define and publish the manifest/config schema.
- Add a pinned public verification key to the host app and a signing process in CI.
- Add staged download, validation, atomic apply, and rollback for non-executable configuration.

### Stage 2 — component package registry

- Define component IDs, package names, signer certificate pins, host/component API compatibility, and health-check contracts.
- Add read-only inventory and update planning before enabling remote installation.

### Stage 3 — first independently deployable module

- Extract the diagnostics worker first because it has the smallest data surface.
- Test install, upgrade, failure, and rollback on a physical Android device.
- Only then consider Telegram matching and other components.

### Stage 4 — controlled automation

- Enable unattended installation only for pre-authorized, allowlisted packages and only when Shizuku is connected with an existing grant.
- Keep a server-side kill switch and per-device rollout controls.
- Retain full APK update as a recovery path.

## Current implementation note

The current app already contains a Shizuku UserService that runs `pm install -r --user 0` for the host APK after package, signer, version, and SHA-256 checks. This implementation is host-APK-specific. It must not be generalized to arbitrary package paths or remote-supplied shell commands; component installation needs its own fixed allowlist and pinned signer map.
