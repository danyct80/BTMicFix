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
