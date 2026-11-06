# Opposable research and Brave implementation status

Updated: 2026-11-06

## Research source

- APK: `opposable-0.5.4.apk`
- Package: `com.getopposable.app`
- Version: `0.5.4` / code 19
- SHA-256: `7132e9ebfc5f5bdc7b89287ec50b32a42f302d6c54a9dad4ad23e348b888578b`
- Detailed analysis: [ANALYSIS.md](ANALYSIS.md)
- DEX inventory: [DEX-METHOD-INVENTORY.md](DEX-METHOD-INVENTORY.md)
- Accessibility XML: [accessibility-service-config.xml](accessibility-service-config.xml)
- Brave comparison: [BRAVE-COMPARISON.md](BRAVE-COMPARISON.md)

## Findings that matter for Brave

1. Opposable chooses a foreground app window by scanning `getWindows()`, not by relying exclusively on `rootInActiveWindow`.
2. It prefers a focused application window, then the highest-layer application window, then an active application window.
3. Its app-launch method polls accessibility windows for roughly 4 seconds after `startActivity`.
4. It uses `NEW_TASK | RESET_TASK_IF_NEEDED` when launching the app.
5. It settles UI state using a fingerprint and Accessibility event timing before trusting an action.
6. It can take screenshots through the Accessibility screenshot API.
7. It keeps payment confirmation and the app's own approval screen owner-only.

## Our implementation experiment

Branch: `feature/brave-accessibility-opposable-window-detection`  
Draft PR: https://github.com/drvladcepeche-maker/contacts/pull/67  
Base branch: `feature/brave-accessibility-control`

Changes in the experiment:
- enumerate application windows and locate a root belonging to `com.brave.browser`;
- prefer focused matching window, then highest layer, then active;
- bounded polling every 250 ms while a request waits for Brave;
- include both active package and application-window packages in timeout diagnostics;
- switch launch flags to `NEW_TASK | RESET_TASK_IF_NEEDED`;
- listen for view-focus, view-scroll and windows-changed events;
- retain the Brave-only package allowlist.

The branch is isolated. It does not modify the existing working branch unless the PR is merged.

## Last known CI run

- Workflow: Build APK
- Run ID: `42223646794`
- Run number: `350`
- Status: success (all build steps completed)
- Artifact ID: `13503798957`
- APK size: 93,049,169 bytes
- APK SHA-256: `8173fc5aef06c18bee2f7f1882c6ef3fcca549060f9454f3683d2e54e20145c4`
- Stable Android signing secrets: configured in CI
- Commit: `1b16b628970af8cf5b2523ad3462593c656c448a`

Do not treat the APK as ready until the workflow completes successfully and the artifact is downloaded and verified.

## Next test on the phone

After successful build and installation:
1. Confirm the accessibility service is still enabled.
2. From Termux, issue a fresh `snapshot` request with a new UUID.
3. Inspect the response. If it times out, note both `active package` and `application windows`.
4. If the snapshot succeeds, verify that the returned `package` is `com.brave.browser` and the tree contains Brave UI.
5. Only then test click/input actions.

Do not uninstall the previous working APK before the replacement is verified.
