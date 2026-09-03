# MOEX Bar Replay — Windows

> **Рекомендуется:** [Web-приложение](replay/README.md) — график TradingView в браузере (`run-replay-web.bat`).  
> Desktop Swing/JavaFX часто не рендерит chart (чёрный экран).

## Web (основной способ)

```bat
cd C:\Users\root\MoexMvp
run-replay-web.bat
```

Браузер: **http://127.0.0.1:8765**

## Desktop (legacy)

```bat
run-desktop.bat
```

JDK 17 + JavaFX; если график пустой — используйте web.

## Android

APK с replay на «Тест страт.»:  
https://github.com/dimonix2019/111/releases/download/moexmvp-debug-latest/moexmvp-debug.apk (v1.7.233)
