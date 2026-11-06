# Opposable DEX method inventory (selected classes)

Source: `opposable-0.5.4.apk`, package `com.getopposable.app`, version `0.5.4`, versionCode `19`.  
This is a method-name inventory from DEX class_data, not a full decompiled source listing.

## PhoneControlService

Lifecycle and window discovery:
- `onServiceConnected`, `onAccessibilityEvent`, `onInterrupt`, `onDestroy`
- `frontAppWindow`, `frontPackage`, `frontAppName`, `getForegroundPackage`, `appRootFallback`, `windowPackageAt`
- `activeWindowId`, `activeWindowAgeMs`, `activeWindowQuiet`, `lastEventInActiveWindow`

App launch and UI:
- `apps`, `launchApp`, `openUrl`
- `captureBitmap`, `screenshot`, `screenBounds`, `contentRect`
- `elements`, `settledElements`, `findNodes`, `nodesWithText`, `readText`, `readAppTexts`, `windowTexts`, `screenTexts`
- `clickByText`, `findAndTap`, `tapElement`, `tap`, `swipe`, `swipeDirection`, `gesture`, `typeText`, `paste`, `pressKey`
- `fingerprint`, `settleAfter`, `waitForIdle`, `waitForPageContent`, `waitForText`
- `focusedText`, `focusedIsPassword`, `focusedInput`, `inputForTyping`, `editableFields`, `getKeyboardOpen`
- `recentNotifications`, `paymentTexts`, `paymentShown`
- `restartMcp`, `mcpStatus`, `startWatchdog`

Safety and state:
- `ownCheck`, `ownerOnly`, `ownerOnlyAt`, `markPayment`, `notePayment`, `coverCheck`

## RelayClient

- `register`
- `pairStart`, `pairStatus`
- `connect`, `disconnectClient`, `getState`, `status`
- `listClients`, `rotateClientToken`, `unregister`
- `event`, `sendFeedback`
- `fetchRecipes`, `backupRecipes`, `syncAccountRecipes`
- `scheduleReconnect`, `start`, `stop`

## McpHttpServer

- `start`, `stop`, `isListening`, `status`
- `handle`, `parse`, `readLine`, `respond`, `authorized`

## PhoneApi

- `apiHelp`, `rest`, `dispatch`, `dispatch$ok`, `dispatch$err`
- `firstToolCall`

The API help strings advertise:
- `GET /api/health`
- `GET /api/screen`
- `POST /api/do`
- `POST /api/tasks`, `GET /api/tasks`, `GET /api/tasks/<id>`, `DELETE /api/tasks/<id>`
- `GET /api/recipes`, `POST /api/recipes/<name>/run`
- `GET /api/audit`
- `GET /api/code`
- `GET/POST /api/schedules`, `DELETE /api/schedules/<id>`

## Guard

- `isPayment`, `paymentShown`, `payment`
- `assess`, `check`
- `ownScreen`, `cover`, `coverRefusal`

## OwlAgent

- `run`, `execute`, `ground`, `scout`, `parseCall`
- `prefetch`, `reached`, `achieved`
- `typedCheck`, `unverified`, `ungrounded`, `words`

## OwlBrain / OwlNative

- `OwlBrain.ensureLoaded`, `OwlBrain.warmUp`
- `OwlNative.scout`

## Interpretation

The code is organized into separate layers:
1. Android Accessibility executor (`PhoneControlService`).
2. UI reasoning/grounding loop (`OwlAgent` + `OwlBrain` + `OwlNative`).
3. Safety/approval gate (`Guard` + `Handoff`).
4. Local HTTP/MCP transport (`McpHttpServer` + `PhoneApi`).
5. Remote relay/pairing (`RelayClient`).

This separation is useful for future work: Brave needs only a narrow slice of layer 1 and a small part of layer 3, not Opposable's full local model, MCP API, or remote relay.
