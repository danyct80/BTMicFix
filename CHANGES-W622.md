# BTMicFix W622 / Android Auto changes

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
- The diagnostic explicitly calls `AudioRecord.setPreferredDevice()` on the Cardo SCO/BLE input.
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

