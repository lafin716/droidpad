# PROGRESS

Single source of truth for task state. `DONE` requires evidence. Real-device Bluetooth behaviour
is tracked separately (T13) and is **not** verified.

| ID | Task | Status | Evidence |
|---|---|---|---|
| T1 | Scaffold (Gradle KTS, AGP 8.13, Kotlin 2.2, Compose M3, minSdk 31) | DONE | `./gradlew assembleDebug` succeeds |
| T2 | HID descriptor/reports (keyboard ID 1 + LED out, mouse ID 2 with wheel/pan) | DONE | `HidDescriptorTest` passes |
| T3 | Keyboard encoding (ASCII, 두벌식 Hangul, 한/영 toggle, modifiers) | DONE | `TextEncoder/TextMirror/Hangul/AsciiKeyMap` tests pass |
| T4 | Touchpad gesture engine (move, tap, double tap, 2-finger tap/scroll, long-press drag) | DONE | `TouchpadGestureEngineTest` passes |
| T5 | Connection manager (register, pair, connect, disconnect, auto-reconnect, errors) | DONE (code) | Builds; `ReconnectPolicyTest` passes; runtime on real hardware → T13 |
| T6 | Settings (DataStore) + ViewModel (StateFlow) | DONE | Settings persist across restart on emulator |
| T7 | Compose UI (control/devices/settings, folded/unfolded, light/dark) | DONE | `docs/screenshots/*.png` (1080x1920, 1768x2208) |
| T8 | Unit tests + Gradle build | DONE | `lintDebug testDebugUnitTest assembleDebug`: 76/76 tests pass, lint 0 errors |
| T9 | Independent review + fixes | DONE | Review 1: 1 HIGH + 4 MEDIUM + 3 LOW fixed (drag modifier, permission split, shutdown race, concurrent holds, auto-connect scope, NOT_EXPORTED receiver, surrogate count). Re-review: duplicate-proxy race and surrogate prefix bug fixed; host-language reset on reconnect reverted (host keeps its IME state) |
| T10 | Emulator smoke test | DONE | Signed release APK installs and launches on emulator-5554, no crash in logcat |
| T11 | Docs, CI, signing | DONE | README, LICENSE (MIT), `.github/workflows/{ci,release}.yml`, keystore via env/Secrets only |
| T12 | GitHub repo + Release v1.0.0 | TODO | |
| T13 | Real-device Bluetooth HID verification (Fold → Galaxy Tab) | BLOCKED | No physical devices available to the agent; emulator has no Bluetooth HID Device profile |
