# BTMicFix W622 / Android Auto changes

## 0.5.2-aa-generic-device

- Removed hard-coded `Cardo` wording from the diagnostic UI and test results.
- Test summaries now use the selected/requested Bluetooth device name (for example `soundcore AeroClip`, `Spirit Tracer`, or any future headset).
- The live test prompt now says `PARLA NEL MICROFONO DI <device>`.
- The force-routing button is now `Forza <device> ora`.
- When no priority name is configured, the UI falls back to the currently active Bluetooth communication device name.
- Technical behavior from 0.5.1 is unchanged: TEST remains passive and does not renegotiate SCO.

## 0.5.1-aa-safe-test

- Fixed a regression where pressing a microphone TEST could re-assert/renegotiate the global SCO route. Diagnostic tests are now passive and never call `setCommunicationDevice()` or Shizuku force routing.
- The current system `AudioManager.communicationDevice` is now the routing source of truth; the per-instance cached route is no longer trusted across Activity/CompanionDeviceService lifecycles.
- A preferred-device match now falls back safely to the unique Bluetooth product name when HyperOS does not expose the SCO MAC address through `AudioDeviceInfo.address`.
- Selecting a priority Companion Device no longer auto-deletes other associations. They remain removable manually.
- `BTCompanionService.onDestroy()` no longer clears active global audio routing; actual disconnect cleanup remains in `onDeviceDisappeared()`.
- Live level, adaptive threshold, RMS/peak and the three source tests remain unchanged.


Modified files:

- `app/src/main/aidl/com/btmicfix/IPrivilegedService.aidl`
- `app/src/main/java/com/btmicfix/MainActivity.kt`
- `app/src/main/java/com/btmicfix/audio/AudioRoutingManager.kt`
- `app/src/main/java/com/btmicfix/companion/BTCompanionService.kt`
- `app/src/main/java/com/btmicfix/companion/DeviceCompanionManager.kt`
- `app/src/main/java/com/btmicfix/shizuku/PrivilegedServiceImpl.kt`
- `app/src/main/java/com/btmicfix/shizuku/ShizukuManager.kt`
- `app/src/main/java/com/btmicfix/ui/components/ShizukuStatusCard.kt`
- `app/src/main/java/com/btmicfix/ui/screens/HomeScreen.kt`
- `app/src/main/java/com/btmicfix/ui/screens/SetupScreen.kt`
- `app/src/main/java/com/btmicfix/util/Preferences.kt`
- `app/build.gradle.kts`
- `build.gradle.kts`
- `README.md`


Key behavioral difference: Shizuku is no longer only displayed as "ready". The app now binds the
privileged UserService and exposes a real force-SCO action for COMMUNICATION + RECORD, plus a
30-second reassert loop for Android Auto routing races.

## 0.3.0-aa-diag
- Added a real Bluetooth microphone diagnostic using `AudioRecord` + `VOICE_COMMUNICATION`.
- The diagnostic explicitly calls `AudioRecord.setPreferredDevice()` on the selected Bluetooth SCO/BLE input.
- Reports the actual `AudioRecord.routedDevice`, PCM peak/RMS, and a PASS/NO_AUDIO/WRONG_DEVICE verdict.
- Added runtime `RECORD_AUDIO` permission request; audio is analyzed in memory only and never saved.
- Moved raw green Shizuku/policy output to a dedicated **Dettagli tecnici** screen.
- Home screen keeps compact Android Auto controls plus a short result summary.
- Added optional full `dumpsys audio` / `media.audio_policy` capture in Details.

## 0.4.0-aa-multisource
- Replaced the single microphone diagnostic with three independent tests:
  - `VOICE_COMMUNICATION`
  - `VOICE_RECOGNITION`
  - `MIC`
- Each source now has its own TEST button and compact result on the home screen.
- Results are stored independently so all three can be compared side by side.
- Technical details screen now keeps a separate diagnostic block for each audio source.
- Existing Shizuku force-SCO and 30-second lock behavior are unchanged.
## 0.5.0-aa-level-priority
- Added live microphone level meter for each diagnostic source.
- Each 6-second test now has two phases: ~1.2 s noise calibration, then speech measurement.
- PASS now requires the actual routed input to match the requested Bluetooth input and the measured voice to exceed a dynamic minimum threshold.
- The UI shows volume in dBFS, RMS and the calculated minimum threshold.
- Technical diagnostics include baseline noise, voice level, threshold and route-match verification.
- Companion-device setup now supports one explicit priority device.
- Choosing a new priority device removes old app associations automatically.
- Added manual removal of individual legacy associations and a cleanup path for duplicate Carplay associations.
- Automatic routing, background companion callbacks and Android Auto force actions now honor the priority device instead of falling back to the first Bluetooth device.

