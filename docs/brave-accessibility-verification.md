# Brave accessibility action verification

The Brave-only AccessibilityService accepts commands through the Termux bridge. Android's `performAction()` return value means that the action request was accepted; it does not prove that the UI changed.

## Response fields

For `click`, `input`, `scroll`, and `back`, the service waits for the accessibility tree to stabilize and adds:

- `verification`: `state_changed`, `no_change_detected`, `changed_but_not_settled`, or `window_unavailable`.
- `state_changed`: whether the observed tree fingerprint differs from the pre-action fingerprint.
- `settled`: whether three consecutive samples matched.
- `verification_elapsed_ms`: elapsed verification time.
- `verified`: whether the expected outcome was observed. For input, this means the requested value is present in the original editable field; for other actions, it means a stable tree change was observed.
- `value_present`: input-only exact value check.

The verification loop polls every 150 ms for at most 1.8 seconds. It runs asynchronously on the service handler; it does not sleep or block the main thread.

A response with `ok=true` and `verified=false` means Android accepted the command but the expected UI outcome was not confirmed. Callers must not treat that as a confirmed success.

## Manual device test checklist

1. Enable the **Мои контакты — привязка** AccessibilityService.
2. Run a Brave snapshot and confirm the response package is `com.brave.browser`.
3. Click a visible navigation element; confirm `verification=state_changed` and `verified=true` when the UI tree changes.
4. Enter a harmless test string into a visible editable field; confirm `value_present=true`.
5. Scroll a page and confirm the tree/bounds change is observed.
6. Trigger an action that Android accepts but that causes no visible change; confirm it is not reported as verified.
7. Switch away from Brave during a command and confirm the service does not operate on another package.

The service remains allowlisted to Brave. Do not add broad package access, arbitrary shell execution, or logging of entered text as part of this feature.
