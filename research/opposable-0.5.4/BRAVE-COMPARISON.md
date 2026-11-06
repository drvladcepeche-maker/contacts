# Opposable 0.5.4 vs. current Brave Accessibility implementation

Date: 2026-11-06  
Source APK: Opposable 0.5.4, SHA-256 `7132e9ebfc5f5bdc7b89287ec50b32a42f302d6c54a9dad4ad23e348b888578b`  
Code branch inspected: `feature/brave-accessibility-control`

## Executive finding

The most relevant implementation difference is foreground-window discovery. Opposable does not rely solely on `rootInActiveWindow`. It enumerates `AccessibilityService.getWindows()`, filters application windows, prefers the focused window, then the highest-layer window, then an active window, and inspects the selected window's root package. Its `launchApp` also polls the available accessibility windows after launching an app.

Our current `BraveAccessibilityService`:
- starts Brave with `getLaunchIntentForPackage`;
- uses `NEW_TASK | REORDER_TO_FRONT`;
- immediately checks only `rootInActiveWindow`;
- processes a command only if that one root has package `com.brave.browser`;
- relies on a subsequent event from Brave to retry;
- has no periodic polling loop for pending commands;
- on timeout, reports the package from `rootInActiveWindow`.

The last recorded test timed out with active package `com.termux`. This is consistent with the above gap: our code did not establish whether a Brave application window existed among the available windows; it only inspected the active root.

## Side-by-side

| Capability | Opposable 0.5.4 | Current Brave branch |
|---|---|---|
| Foreground window | Scans `getWindows()`; selects focused application window, then highest layer, then active | `rootInActiveWindow` only |
| App launch | `getLaunchIntentForPackage`, flags `0x10200000` (NEW_TASK + RESET_TASK_IF_NEEDED) | `NEW_TASK | REORDER_TO_FRONT` |
| Wait for app window | Polls window list for about 4 seconds after launch | No polling; relies on enqueue call and later accessibility events |
| Accessibility event scope | Broad event types; no package allowlist in service XML | Brave-only package allowlist |
| Interactive windows | Enabled | Enabled in config, but current service code does not enumerate windows |
| Screenshot | `canTakeScreenshot=true`; calls `takeScreenshot` asynchronously | Not enabled in service XML; snapshot is tree-only |
| UI stability | Fingerprint + settleAfter + event recency | No post-action settle/verification |
| Text entry | Dedicated type/paste paths; OwlAgent checks if text appeared | `ACTION_SET_TEXT` only |
| Safety guard | Payment-screen and own-screen checks; owner approval | Package restriction to Brave; no payment-specific guard |
| MCP / remote control | Local HTTP/MCP plus optional remote relay | Shell broadcast + JSON result file |

## Priority fixes for our Brave service

### P0 — identify Brave window through getWindows()

Implement a helper that:
1. enumerates `windows`;
2. filters `AccessibilityWindowInfo.TYPE_APPLICATION`;
3. obtains each root and checks `root.packageName == com.brave.browser`;
4. prefers a focused matching window;
5. otherwise prefers the highest-layer matching window, then an active matching window;
6. reports both the selected window package and the active/root package in diagnostics.

This mirrors the part of Opposable that is directly relevant to the current timeout.

### P0 — poll while waiting for Brave

Use a bounded Handler poll (for example every 200–300 ms) while the command is pending. Stop polling once the command is completed or times out. This avoids depending exclusively on a new event from Brave. Keep the existing request UUID and bounded timeout.

### P1 — test launch flags

A controlled A/B test should compare the current `NEW_TASK | REORDER_TO_FRONT` with Opposable's `NEW_TASK | RESET_TASK_IF_NEEDED`. Do not assume one is universally better; verify the actual focused window and package on the user's Android build.

### P1 — verify after each action

After click/input/scroll:
- wait for a relevant accessibility event or a short stable UI fingerprint;
- take a fresh tree snapshot;
- return the observed post-action state, not only the boolean from `performAction`.

This is essential because Android may return `true` for dispatching an action even when the intended navigation or text change did not occur.

### P2 — screenshot and input fallback

Enable `canTakeScreenshot` only if screenshot diagnostics are needed. For text fields that reject `ACTION_SET_TEXT`, add a carefully scoped paste fallback after focus is confirmed; do not silently paste into an unverified field.

## Proposed minimal implementation sequence

1. Add focus-aware window lookup and diagnostics.
2. Add bounded polling of windows for the pending request.
3. A/B test the two launch flag combinations.
4. Test snapshot on Brave start.
5. Test click and input with post-action snapshot verification.
6. Only then consider broader model/MCP features.

## Safety boundaries

Keep the Brave service package allowlist. Do not copy Opposable's broad all-app control into this app. Do not implement its remote relay or general MCP surface unless separately requested. Never store entered passwords or typed text in logs. Avoid logging bearer tokens or URL query credentials.

## Current test evidence

The last observed response from our branch was:

```json
{
  "ok": false,
  "error": "Timed out waiting for Brave to become the active accessibility window (active package: com.termux)."
}
```

This is not evidence that Brave is absent or that its launch Activity is invalid. It proves only that, at timeout, `rootInActiveWindow` belonged to Termux.
