# Deskflow Android - Lessons Learned

## Mouse Click Fix (SYSTEM_ALERT_WINDOW stale state)

### Root Cause
On Android 15, `SYSTEM_ALERT_WINDOW` package-level grant can become stale: `appops=allow` but `granted=false`. This causes `dispatchGesture()` to return `true` and fire `onCompleted`, but injected touches are silently dropped by InputDispatcher. A reboot forces system to re-evaluate and set `granted=true`.

### Diagnostic
Check permission health on service connect:
- `android.provider.Settings.canDrawOverlays(context)` → package-level grant
- `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_SYSTEM_ALERT_WINDOW, ...)` → appops state
- If mismatch: `canDrawOverlays=true` but `appOpsAllowed=false` → mouse clicks get CANCELLED by system (movement still works)
- Note: `appops get <pkg> SYSTEM_ALERT_WINDOW` showing "No operations" means the appops entry is missing → check reports `appOps=false`

### Fix (no reboot, verified 2026-10-06)
The stale state can also occur in mirror form: `granted=true` but appops entry missing.
`pm grant` alone does NOT recreate the missing appops entry. Repair sequence:
1. `su -c 'pm grant org.tfv.deskflow android.permission.SYSTEM_ALERT_WINDOW'` (keeps `granted=true`)
2. `su -c 'appops set org.tfv.deskflow SYSTEM_ALERT_WINDOW allow'` (recreates entry; safe here because grant is already true — verify `granted=true` still holds after)
3. Rebind the accessibility service (toggle it off/on in `enabled_accessibility_services`) so `onServiceConnected` re-runs the permission check
4. Confirm log shows `Overlay permission check: canDrawOverlays=true, appOps=true` with no `OVERLAY PERMISSION ISSUE` error
(Caution: `appops set allow` WITHOUT the package grant produces the reverse stale state `appops=allow/granted=false`; always verify both sides.)

### Fix: Speculative Hold + Click Path
- **Speculative hold** starts on Mouse Down with `willContinue=true` (immediate gesture, fingers stay down)
- On Mouse Up: if no movement occurred (`initialHoldDuration == 0`), dispatch a fresh `tapGesture()` instead of `endDragGesture()`/`continueStroke()`
- `continueStroke` causes `ACTION_MOVE touching pointers don't match` errors in InputDispatcher on Android 15
- `tapGesture()` creates a clean new gesture that avoids pointer ID conflicts

### WARNING: Abandoned willContinue Holds Leak Phantom Pointers (found 2026-10-06)
The click path above NULLS `activeDragState` and abandons the speculative-hold stroke
instead of releasing it with `continueStroke(..., willContinue=false)`. The finger stays
down system-side. Symptoms when phantoms accumulate:
- EVERY tap (even isolated slow clicks) gets `Gesture CANCELLED by system`, movement still works
- `dumpsys input` → `TouchStatesByDisplay` shows `touchingPointers=[Pointer(id=0, UNKNOWN), ...]`
  with garbage `downTimeInTarget` (days old); `TouchStates: <no displays touched>` may still claim idle
- Phantom state lives in `system_server`: survives app force-stop AND accessibility-service rebind;
  only a reboot clears it. (Observed: pointers stuck ~4.3 days, survived process restart.)
- Diagnose with: `su -c 'dumpsys input' | grep -A2 TouchStatesByDisplay`
- Fix (implemented 2026-10-06, deployed same day): on Mouse Up with no movement, release the hold via
  `dispatchFinalStroke()` (`continueStroke(path, 0, 10, false)` from==to, so no mismatch)
  instead of abandoning it + fresh tap. Deferred via `isEnding` if the hold is still in flight.
  Note: this stops NEW leaks; phantoms already stuck in `system_server` (proven: identical
  `downTimeInTarget` across reinstall + process death) still require a reboot to clear.

### Fallback: Node-Based Click
If `dispatchGesture()` returns `false`, try `AccessibilityNodeInfo.performAction(ACTION_CLICK)` on:
1. Focused node (`findFocus(FOCUS_INPUT)`)
2. Node at position (`findNodeAtPosition()` recursive traversal)

### Log Level Guidelines
- **debug**: High-frequency events (mouse moves, gesture callbacks, keyboard events)
- **info**: One-time/rare events (service connect, display selection, permission health check)
- **warn/error**: Failure conditions (permission mismatch, gesture cancelled, fallback triggered)

### Key Files
- `GlobalInputService.kt`: Gesture dispatch, mouse handling, permission checks
- `VirtualKeyboardService.kt`: Keyboard input via `InputConnection.commitText()` (works independently)
- `Client.kt`: Connection management
- `FullDuplexSocket.kt`: Socket I/O with selector-based OP_WRITE management

### Android 15 Specifics
- `SYSTEM_ALERT_WINDOW` is managed via `AppOpsManager`, but `dumpsys package` shows `granted` field that can become stale
- `dispatchGesture()` can return `true` and fire `onCompleted` while silently failing
- `InputConnection.commitText()` (VirtualKeyboardService) works because it doesn't require `SYSTEM_ALERT_WINDOW`
- Background process freezing can break Tailscale connectivity; whitelist with `dumpsys deviceidle whitelist +com.tailscale.ipn`
