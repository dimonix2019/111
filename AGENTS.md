## Cursor Cloud specific instructions

- Do not include manual Android emulator/`adb` UI runs in the default test plan; this Cloud VM does not provide a usable Android device/emulator. Use Gradle unit tests and `assembleDebug` unless the user explicitly asks otherwise.
- After each application feature/fix, bump `app/build.gradle.kts` `versionCode`/`versionName`, add a matching top entry to `APP_CHANGELOG` in `MoexConstants.kt`, and include the resulting version number in the final response.
