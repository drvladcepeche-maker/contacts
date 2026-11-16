# Termux Bridge: Android control

The mobile worker exposes narrowly scoped Android operations in addition to its ordinary Termux workspace commands.

## Read-only diagnostics

| Job type | Purpose | Access |
| --- | --- | --- |
| `device_info` | Android manufacturer, model, release, SDK, security patch | Termux app UID |
| `android_package_inventory` | Installed package IDs, APK paths, installer source | User 0: Termux app UID; user 999: Shizuku |
| `android_package_info` | APK path, launcher activity, installer for one package | User 0: Termux app UID; user 999: Shizuku |
| `shizuku_status` | Verify that `rish` returns Android shell UID 2000 | Shizuku |
| `android_appops_get` | Read AppOps for one validated package ID | Shizuku |
| `android_vpn_status` | Read VPN-related Connectivity state | Shizuku |

The supported MIUI profile IDs are 0 (main profile) and 999 (XSpace). Cross-user inspection of user 999 requires Shizuku authorization.

## UI automation

| Job type | Purpose |
| --- | --- |
| `screen_snapshot` | Dump the current Android accessibility/UI hierarchy as XML |
| `screen_action` | Perform one validated UI action |

Supported `screen_action` actions:

- `launch`: launch a package by validated package ID.
- `tap`: integer screen coordinates.
- `swipe`: integer start/end coordinates and a bounded duration.
- `text`: up to 120 printable characters; control characters are rejected.
- `back`, `home`, `enter`, `tab`, `up`, `down`, `left`, `right`, `center`, `menu`, `escape`.

All UI actions require Shizuku. The worker verifies Android shell UID 2000 before every privileged operation. There is no generic remote Shizuku shell command and no arbitrary package-manager command.

## Security and limits

- Ordinary `terminal_run` remains restricted to the configured Termux workspace and ordinary Termux app UID.
- Shizuku is used only by explicit allowlisted Android operations.
- Package names are validated; coordinates, swipe duration, and text length are bounded.
- The bridge cannot silently bypass Android system permission dialogs, biometric checks, device-unlock requirements, or app-level authentication. A protected prompt may still require user confirmation.
- Android VPN service approval remains controlled by Android. The bridge can inspect and interact with the UI only while Shizuku is running and authorized.
