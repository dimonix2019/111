# Debug reports (MOEX MVP)

Сюда приложение загружает отчёты с телефона через **О приложении → Журнал → «В GitHub»**.

Структура:

```
debug-reports/{git-branch}/v{versionCode}/{timestamp}/
  event-log.txt      — полный журнал MoexDiagnostics
  manifest.json      — версия, ветка, устройство, список файлов
  screenshot-01.png  — скриншоты (экран / галерея)
```

Ветка коммита совпадает с веткой CI-сборки APK (`BuildConfig.GIT_BRANCH`).

Для локальных сборок загрузка идёт в `main`, если не задана другая ветка.

Настройка токена: GitHub repo secret `DEBUG_REPORT_UPLOAD_TOKEN` (fine-grained PAT, Contents read/write).
