# MOEX MVP — web (отладка через Tailscale)

Web-версия **того же приложения**, что Android APK (`com.example.moexmvp`): вкладки **Рынок / Портфель / Тест страт. / …**, 15м TATN/TATNP, rolling Z 30д.

Это **не** `strategy-web/` (отдельный тестер стратегий).

## Зачем

- Правки на сервере → обновили git + перезапуск → открыли в браузере по Tailscale.
- Не нужен новый APK на каждый фикс графика / 3M / симуляции.
- Когда стабилизируем — снова основной канал: [debug APK](https://github.com/dimonix2019/111/releases/download/moexmvp-debug-latest/moexmvp-debug.apk).

## Запуск

```bash
cd moex-web
chmod +x deploy/run.sh
./deploy/run.sh
```

Браузер на телефоне (Tailscale **включён**): `http://<tailscale-ip>:8080`

Получить IP на сервере: `tailscale ip -4`

Если «не удаётся получить доступ»: `./deploy/check-access.sh`

Подробно: [deploy/TAILSCALE.md](deploy/TAILSCALE.md)

## Паритет с APK (сейчас / план)

| Вкладка | Web сейчас | APK |
|--------|------------|-----|
| Рынок | Z-график 1D–3M, MOEX 15м | полный UI |
| Тест страт. | симуляция 255д, метрики | полный UI |
| Портфель | заглушка + ссылка на APK | демо-сделки, Tinkoff |
| Журнал / Песочница / О приложении | заглушки | полный |

Логика Z и симуляции — порт Kotlin → Python (`strategy-web/zsim.py` + ISS loader, те же константы что `MoexConstants.kt`).

## Compose → Web (долгосрочно)

Полный 1:1 UI на Compose Multiplatform (Wasm) — отдельный этап; сейчас — **быстрый web-MVP** для отладки стабильности данных и Z.
