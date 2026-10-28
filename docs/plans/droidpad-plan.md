# DroidPad — Master Plan

## Goal
Use a Galaxy Fold as a Bluetooth Classic HID keyboard + touchpad for a Galaxy Tab (or any HID host)
with no app on the host, no internet, no root. Ship a signed APK through GitHub Releases (v1.0.0).

## Key decisions
| Topic | Decision | Reason |
|---|---|---|
| HID API | `android.bluetooth.BluetoothHidDevice` (API 28+) | Only official, root-free HID device API |
| minSdk | 31 | All Fold models run Android 12+; single BT permission model (CONNECT/SCAN/ADVERTISE) |
| Report map | Report ID 1 = boot-style keyboard (8B in, LED out), ID 2 = mouse (5 buttons, X/Y, wheel, AC Pan) | Broad host compatibility, horizontal scroll |
| Text input | Custom View `InputConnection` mirrors IME editable → diff → HID keystrokes | Works with Samsung Keyboard composition (Hangul) |
| Hangul | Decompose syllables → 2-beolsik (두벌식) key positions; host IME recomposes | HID only sends physical keys; host layout decides glyphs |
| 한/영 | Tracked host language state + configurable toggle (Shift+Space default, LANG1, Right Alt, Ctrl+Space) | Host state is not observable over HID |
| DI / persistence | Manual container, SharedPreferences + StateFlow | Avoid unnecessary dependencies |
| Layout | Width-based adaptive (<600dp folded: mode tabs, ≥600dp unfolded: dual pane) | No extra window library needed |
| Signing | Keystore generated locally (outside repo), injected through GitHub Secrets | Never commit keys |

## Architecture
```
ui (Compose screens) → MainViewModel (StateFlow) → AppContainer
   ├─ hid/HidConnectionManager  (profile proxy, register, connect, auto-reconnect)
   ├─ hid/HidReportSender       (single thread, mouse coalescing)
   ├─ hid/HidDescriptor, reports (pure)
   ├─ input/keyboard: KeyStroke, AsciiKeyMap, Hangul, TextEncoder, TextMirror (pure, unit-tested)
   ├─ input/touchpad: TouchpadGestureEngine, PointerMath (pure, unit-tested)
   └─ data/SettingsRepository
```

## Task graph
| ID | Task | Depends | Difficulty |
|---|---|---|---|
| T1 | Gradle scaffold, wrapper, manifest | – | LOW |
| T2 | HID descriptor + report encoders | T1 | MEDIUM |
| T3 | Keyboard encoding (ASCII, Hangul 2-beolsik, lang toggle, mirror diff) | T2 | HIGH |
| T4 | Touchpad gesture engine | T2 | HIGH |
| T5 | HID connection manager + sender | T2 | HIGH |
| T6 | Settings repo + ViewModel | T3,T4,T5 | MEDIUM |
| T7 | Compose UI (control, devices, settings, theme, adaptive) | T6 | MEDIUM |
| T8 | Unit tests + Gradle build | T3,T4 | MEDIUM |
| T9 | Independent review | T7,T8 | HIGH |
| T10 | Emulator smoke test (UI only; BT HID not available in emulator) | T7 | LOW |
| T11 | README, LICENSE, CI workflow, signing secrets | T7 | LOW |
| T12 | Repo create, push, tag v1.0.0, release APK verify | T11 | LOW |

## Model routing
Leader (current Claude model) implements core for consistency; independent reviewer subagent (general-purpose) reviews the diff.
No per-agent model override needed.

## Verification limits
Real-device Bluetooth HID (Fold → Tab) cannot be exercised from this environment. Those features stay **unverified on hardware**.
