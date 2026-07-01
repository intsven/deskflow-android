# Deskflow Android - Lessons Learned

## Mouse Click Fix (SYSTEM_ALERT_WINDOW stale state)

### Root Cause
On Android 15, `SYSTEM_ALERT_WINDOW` package-level grant can become stale: `appops=allow` but `granted=false`. This causes `dispatchGesture()` to return `true` and fire `onCompleted`, but injected touches are silently dropped by InputDispatcher. A reboot forces system to re-evaluate and set `granted=true`.

### Diagnostic
Check permission health on service connect:
- `android.provider.Settings.canDrawOverlays(context)` → package-level grant
- `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_SYSTEM_ALERT_WINDOW, ...)` → appops state
- If mismatch: `canDrawOverlays=true` but `appOpsAllowed=false` → mouse clicks silently fail

### Fix: Speculative Hold + Click Path
- **Speculative hold** starts on Mouse Down with `willContinue=true` (immediate gesture, fingers stay down)
- On Mouse Up: if no movement occurred (`initialHoldDuration == 0`), dispatch a fresh `tapGesture()` instead of `endDragGesture()`/`continueStroke()`
- `continueStroke` causes `ACTION_MOVE touching pointers don't match` errors in InputDispatcher on Android 15
- `tapGesture()` creates a clean new gesture that avoids pointer ID conflicts

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
