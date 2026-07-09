# MOEX Bar Replay — Windows-приложение

Нативное **Windows-приложение** (Kotlin + Swing + **TradingView lightweight-charts**) — Bar Replay Z-score 15м.

## Быстрый старт

```bat
cd C:\Users\root\MoexMvp
run-desktop.bat
```

**JDK 17** обязателен: [Temurin 17](https://adoptium.net/temurin/releases/?version=17)

## Интерфейс (как TradingView Bar Replay)

```
┌──────────────────────────────────────────────────────────────────┐
│ TATN/TATNP Z · 15м   Период: [30д][3М][6М][Всё]   Вх/Вых ±    │
├────────────────────────────────────────────┬─────────────────────┤
│  TradingView Z-свечи + маркеры сделок     │  Таблица сделок     │
│  (lightweight-charts, z_chart.html)        │  № Тип Вход Выход   │
├────────────────────────────────────────────┴─────────────────────┤
│ ⏮ В начало  ⏪ −1  ▶ Play  ⏩ +1  ⏭ В конец                     │
│ Скорость: [0.5×][1×][2×][5×][10×]          Прогресс ████░ 67%  │
└──────────────────────────────────────────────────────────────────┘
```

| Элемент | Назначение |
|---------|------------|
| **Период** | Окно графика: 30д / 3М / 6М / весь ряд |
| **Вх / Вых** | Пороги Z (как «Тест страт.») |
| **▶ Play** | Авто-шаг 15м баров |
| **Таблица** | Сделки, появившиеся к текущему cursor |
| **Слайдер** | Scrub по всему ряду |

## Данные

Автозагрузка: `strategy-web\data\m15_tatn_255d.csv`

Свой файл:

```bat
gradlew.bat :desktop:run --args="D:\data\m15.csv"
```

## Сборка

```bat
gradlew.bat :desktop:test
gradlew.bat :desktop:fatJar
java -jar desktop\build\libs\desktop-1.1.0-all.jar
```

## Технически

- График: тот же `z_chart.html` + `setReplayCursor` (parity с Android)
- Движок: `BarReplayEngine` (инкрементальные Z-сигналы)
- WebView: JavaFX внутри Swing

## Android

Полный бэктест с MOEX live — вкладка «Тест страт.» или APK:
https://github.com/dimonix2019/111/releases/download/moexmvp-debug-latest/moexmvp-debug.apk
