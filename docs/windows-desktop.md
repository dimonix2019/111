# MOEX Bar Replay — Windows-приложение

Нативное **Windows-приложение** (Kotlin + Swing) в папке `MoexMvp/desktop/` — Bar Replay Z-score 15м без Android-эмулятора.

## Требования

| Компонент | Версия |
|-----------|--------|
| **JDK** | 17 (Temurin / OpenJDK) |
| **ОЗУ** | 8 ГБ — закройте браузер на время первой сборки |
| **Данные** | `strategy-web/data/m15_tatn_255d.csv` (уже в репо) |

## Быстрый старт

```bat
cd C:\Users\root\MoexMvp
run-desktop.bat
```

Первый запуск скачает зависимости Gradle (5–15 мин на X1).

## Сборка JAR

```bat
gradlew.bat :desktop:fatJar
java -jar desktop\build\libs\desktop-1.0.0-all.jar
```

## Сборка EXE (опционально, jpackage)

После `fatJar` можно упаковать через `jpackage` (JDK 17+):

```bat
jpackage --input desktop\build\libs --name MoexBarReplay --main-jar desktop-1.0.0-all.jar --type exe
```

## Свой CSV

```bat
gradlew.bat :desktop:run --args="C:\path\to\m15.csv"
```

Формат: `timestamp,z_score,spread_percent,tatn_close,tatnp_close` (как в `strategy-web/data/`).

## Управление

| Элемент | Действие |
|---------|----------|
| ▶ / ⏸ | авто-шаг по 15м барам |
| ⏪ / ⏩ | −1 / +1 бар |
| ⏮ / ⏭ | к началу / к концу ряда |
| 0.5×…10× | скорость (1× ≈ 900 ms/бар) |
| Слайдер | scrub по индексу |
| Вх / Вых | пороги Z (как на «Тест страт.») |

## Тесты

```bat
gradlew.bat :desktop:test
```

## Android vs Windows

| | Android (`app/`) | Windows (`desktop/`) |
|--|------------------|----------------------|
| Данные | MOEX live + SQLite | CSV из `strategy-web/data/` |
| График | TradingView `z_chart.html` | Swing Canvas (Z-line) |
| Replay engine | `BarReplayEngine` | тот же алгоритм (parity-тест) |

Для полного бэктеста с MOEX live — Android APK или вкладка «Тест страт.».

## Gradle на 8 ГБ

В `gradle.properties` уже есть `-Xmx3g`. На слабом ноуте можно снизить до `1536m` (см. `docs/simulator-replay-x1-local.md`).

## Связанные файлы

- `desktop/src/main/kotlin/.../Main.kt` — окно и UI
- `desktop/.../DesktopBarReplayEngine.kt` — движок replay
- `docs/simulator-desktop.md` — архитектура Replay
