## Cursor Cloud specific instructions

- Do not include manual Android emulator/`adb` UI runs in the default test plan; this Cloud VM does not provide a usable Android device/emulator. Use Gradle unit tests and `assembleDebug` unless the user explicitly asks otherwise.
- After each application feature/fix, bump `app/build.gradle.kts` `versionCode`/`versionName`, add a matching top entry to `APP_CHANGELOG` in `MoexConstants.kt`, and include the resulting version number in the final response.
- In every user-facing summary, include the debug APK download link: https://github.com/dimonix2019/111/releases/download/moexmvp-debug-latest/moexmvp-debug.apk (rolling build `moexmvp-debug-latest`; private repo may require GitHub login).
- Named backlog items live under `docs/`. To resume deferred work, the user may say e.g. **«Переход на 10-и минутки»** → read `docs/perehod-na-10-minutki.md`.


---
name: MOEX code only
---

Проект УЖЕ существует: Android Kotlin MOEX MVP (Gradle, папка app/).
ЗАПРЕЩЕНО: предлагать React, Spring Boot, PostgreSQL, «с нуля веб», общие планы из 7 шагов.
ОБЯЗАТЕЛЬНО: править только файлы из @file / контекста @folder; выводить изменения как патч/diff для Kotlin.
Если нет @file — попроси указать файл, не выдумывай новый стек.