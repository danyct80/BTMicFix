# BTMicFix W622 / Android Auto changes

Modified files:

- `app/src/main/aidl/com/btmicfix/IPrivilegedService.aidl`
- `app/src/main/java/com/btmicfix/audio/AudioRoutingManager.kt`
- `app/src/main/java/com/btmicfix/shizuku/PrivilegedServiceImpl.kt`
- `app/src/main/java/com/btmicfix/shizuku/ShizukuManager.kt`
- `app/src/main/java/com/btmicfix/ui/components/ShizukuStatusCard.kt`
- `app/src/main/java/com/btmicfix/ui/screens/HomeScreen.kt`
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
