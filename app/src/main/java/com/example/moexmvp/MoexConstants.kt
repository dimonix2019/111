package com.example.moexmvp

import java.time.format.DateTimeFormatter
import okhttp3.OkHttpClient

internal val httpClient = OkHttpClient()
internal val tradeDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
internal val updatedAtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
internal val candleTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
internal val intradayLabelFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal val portfolio15mLabelFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
internal const val DYNAMIC_Z_RECALC_HOUR = 9
internal const val DEFAULT_DYNAMIC_Z_ENTRY = 1.3
internal const val DEFAULT_DYNAMIC_Z_EXIT = 1.2
internal const val Z_STRATEGY_ENTRY_MIN_TENTHS = 8
internal const val Z_STRATEGY_ENTRY_MAX_TENTHS = 35
internal const val Z_STRATEGY_EXIT_MIN_TENTHS = 0
internal const val Z_STRATEGY_EXIT_MAX_TENTHS = 25
internal const val Z_STRATEGY_MIN_TRADES = 4
internal const val ALERT_PREFS_NAME = "moex_alert_prefs"
internal const val PREF_DYNAMIC_Z_ENTRY = "dynamic_z_entry"
internal const val PREF_DYNAMIC_Z_EXIT = "dynamic_z_exit"
internal const val PREF_DYNAMIC_Z_DATE = "dynamic_z_date"
internal const val PREF_Z_STRATEGY_POSITION = "z_strategy_position"
internal const val PREF_Z_DAILY_SIGNAL_DATE = "z_daily_signal_date"
internal const val PREF_Z_DAILY_SIGNAL_COUNT = "z_daily_signal_count"
internal const val PREF_Z_DAILY_SIGNAL_ENTRY = "z_daily_signal_entry_legacy"
internal const val PREF_Z_DAILY_SIGNAL_EXIT = "z_daily_signal_exit_legacy"
internal const val DAILY_SIGNAL_MAX_PER_DAY = 20
/** Skip duplicate journal rows when UI and background service see the same edge within this window. */
internal const val STRATEGY_SIGNAL_JOURNAL_DEDUP_WALL_MS = 25_000L
internal const val FIXED_REALTIME_INTERVAL_MS = 5_000L
internal const val DEFAULT_PORTFOLIO_NOTIONAL_RUB = 100_000.0

/** Portfolio tab: entry/exit |Z| limits are independent (UI steppers). */
internal const val PORTFOLIO_Z_THRESHOLD_MIN = 0.0
internal const val PORTFOLIO_Z_THRESHOLD_MAX = 8.0
internal const val PORTFOLIO_Z_THRESHOLD_STEP = 0.05
/** Calendar days of history for 15m-style portfolio (10m ISS → 15m bars). ~1y; smaller than 365*1m traffic. */
internal const val PORTFOLIO_M15_LOOKBACK_DAYS = 252L

/** When refreshing from MOEX, re-fetch this many calendar days before last cached bar (overlap for ISS corrections). */
internal const val PORTFOLIO_M15_INCREMENTAL_OVERLAP_DAYS = 3L
internal const val TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY = 0.033

/** Прямая загрузка debug APK (если репозиторий private — нужна авторизация GitHub в браузере, иначе будет 404). */
internal const val APK_DOWNLOAD_DIRECT_URL =
    "https://github.com/dimonix2019/111/releases/download/moexmvp-debug-latest/moexmvp-debug.apk"

internal const val APK_GITHUB_RELEASES_PAGE_URL = "https://github.com/dimonix2019/111/releases"

/** Shown on the About tab (keep short; dates are illustrative). */
internal const val APP_CHANGELOG = """
1.6.26 — Рынок: локальный снимок графиков MOEX (точки Z/спред + свечи) на 15 минут; при ошибке сети показывается кэш, в сводке — «Кэш 15м» и время последней удачной загрузки.
1.6.25 — О приложении: подсказка при 404 с телефона (часто private repo на GitHub); кнопки открытия прямой ссылки APK и страницы релизов.
1.6.24 — CI: отключена отмена параллельных прогонов (release иначе мог не создаться → 404 по ссылке). Публикация релиза moexmvp-debug-latest через action-gh-release + предварительное удаление старого релиза.
1.6.23 — Ссылка на debug APK: в GitHub «latest» мог перехватываться чужой релиз (например v1.21). CI публикует один релиз с тегом moexmvp-debug-latest. Прямая ссылка: https://github.com/dimonix2019/111/releases/download/moexmvp-debug-latest/moexmvp-debug.apk
1.6.22 — Песочница (тест): режим «Ручной» / «Авто» — в авто после сигнала входа отправляются 2 заявки без карточки «Принять»; по каждой ноге отдельное уведомление (дата/время МСК, тикер, сторона, кратко заявка, номинал стратегии и плечо, баланс портфеля и деньги после ноги). В ручном после «Принять» — те же уведомления по ногам. Плечо в тексте синхронизируется с вкладкой «Портфель». Исполнение спрэда запрашивает портфель после каждой ноги.
1.6.21 — Карточка «Принять» после push: исправлен ранний return при дедупе журнала 25 с (теперь pending для входа всё равно обновляется); запись pending через commit(); в PendingIntent уведомлений входа передаются тип/Z/время — при тапе из шторки карточка восстанавливается; при старте/resume — догрузка pending из журнала если позиция совпадает; MainActivity singleTop + onNewIntent; при выходе по Z сбрасывается устаревший pending.
1.6.20 — Портфель (подтверждённые): детальные блоки в сворачиваемых секциях (по умолчанию закрыты); сверху компактно «Итого» и «Макс. просадка». Сделки — карточки с двумя явными ногами LONG/SHORT (TATN/TATNP) и одной строкой общего PnL по спрэду. Тест стратегии: сводка и сетка метрик тоже в спойлерах.
1.6.19 — Портфель: после «Принять» на песочнице показывается блок с двумя ногами спрэда (2 заявки); в журнал не пишется второй дубль входа, если такой вход уже есть (иначе в модели портфеля оставалась одна «сделка»). Сортировка событий на одной свече по времени.
1.6.18 — Песочница: PostSandboxOrder — сначала тела snake_case (корректнее direction), дубли полей orderDirection/order_direction; строка портфеля — «Всего» и отдельно «Деньги (₽)» из ответа API, чтобы после продажи было видно поступление рублей.
1.6.17 — Установка поверх: debug APK подписывается фиксированным keystore из репозитория (один сертификат на CI и локально). Раньше GitHub Actions каждый раз использовал новый ephemeral debug‑ключ — Android не давал обновить приложение без удаления. Первый переход со старого CI‑APK на 1.6.17 может потребовать одну переустановку; дальше обновления с GitHub — поверх.
1.6.16 — Сигнал «Принять»: уточнены тексты — на песочнице всегда 2 заявки (1×покупка + 1×продажа по ногам спрэда TATN/TATNP); логика без изменений.
1.6.15 — Песочница: токен и Account ID дублируются в отдельный SharedPreferences (только песочница); при сбое EncryptedSharedPreferences/Keystore после обновления APK значения подтягиваются из резерва. Кнопка «Очистить» стирает и основное, и резервное хранилище.
1.6.14 — Песочница: токен и Account ID в состоянии главного экрана — при переключении вкладок поля не очищаются; при возврате в приложение синхронизация с диском (ON_RESUME). Добавлен TinkoffSandboxStorage.hydrateCredentialsForUi.
1.6.13 — Песочница: кнопки тестовой рыночной покупки и продажи (1 лот TATN) с иконками; после заявки автоматически запрашивается портфель для строки оценки в ₽.
1.6.12 — Песочница: исправлен REST-путь к SandboxService и InstrumentsService — в URL сегмент **точками** (`...v1.SandboxService/...`), как в доке T‑Bank; вариант со слэшем (`...v1/SandboxService/`) давал HTTP 404 на обоих хостах.
1.6.11 — Песочница OpenSandboxAccount: сначала тело как в доке (name строка, вариант Name); массив name — запасной; глубокий поиск accountId в ответе; при HTTP-ошибке перебираются оба хоста (.tbank / .tinkoff); charset в теле POST (OkHttp).
1.6.10 — Песочница: «Открыть счёт» и остальные действия — чтение/запись токена и EncryptedSharedPreferences только на IO (устранение вылетов на главном потоке); OpenSandboxAccount — тело name как массив (как в схеме API) + запасные варианты; развёртка openSandboxAccountResponse; GetSandboxAccounts — развёртка getAccountsResponse; перехват Throwable в UI.
1.6.9 — Песочница: PostSandboxOrder снова как в примерах T‑Invest (quantity и price.units строками); варианты BESTPRICE и figi для BBG; FindInstrument — вложенный instrument, второй запрос без apiTrade; GetSandboxPortfolio — accountId без currency, развёртка оболочки ответа, рекурсивный поиск total; заголовки Content-Type/User-Agent.
1.6.7 — Песочница «Принять»: FindInstrument по TATN/TATNP (UID/FIGI для PostSandboxOrder); quantity как число; цена-котировка units=0; вся цепочка на IO; понятнее Toast при пустом message.
1.6.6 — Журнал: уникальные ключи списка (без вылета при дубликатах). Портфель: привязка сигналов к 15м с «хвостом» после последнего бара; после «Принять» в песочнице — запись в журнал и обновление списка.
1.6.5 — Песочница: fallback на обычные prefs если EncryptedSharedPreferences падают; сохранение на IO; разбор API-ошибок (в т.ч. «Ошибка» + фрагмент тела); PostSandboxOrder без поля price.
1.6.4 — Песочница: токен и счёт автоматически сохраняются при вводе (EncryptedSharedPreferences); опционально sandbox-token.properties для локальной сборки (не в git).
1.6.3 — Песочница: по кнопке «Принять» на сигнале входа — 2 рыночные заявки в песочнице (TATN/TATNP, 1 лот); переключатель «Исполнять на демо»; портфель песочницы; сводка на «Рынке».
1.6.2 — Песочница: REST на хосте sandbox-invest-public-api (не продовый invest-public-api); SandboxPayIn: units/nano числом; запасной домен .tinkoff.ru; в ошибке — URL запроса.
1.6.1 — Песочница: нормализация токена (убрать лишний Bearer), разбор ошибок API, повтор GetSandboxAccounts, SandboxPayIn camel/snake JSON.
1.6.0 — Вкладка «Песочница»: токен T‑Invest sandbox в EncryptedSharedPreferences, OpenSandboxAccount, GetSandboxAccounts, SandboxPayIn (REST, только клиент).
1.5.7 — Портфель: пересчёт при изменении журнала сигналов (Итого/PnL не залипали); подсказка про реализованное + нереализованное.
1.5.6 — Исправлен вылет при открытии «Журнал» (вложенный LazyColumn без высоты); «О приложении» с weight.
1.5.5 — Рынок: снова кнопка «Тест» уведомления; в тексте — текущие пороги входа/выхода Z (ручные с «Тест страт.» или авто).
1.5.4 — Журнал: сигналы пишутся всегда при срабатывании стратегии (не только при успешном push); с BG по умолчанию снова видны сделки. Дедуп 25 с тем же типом.
1.5.3 — Рынок: убраны режимы «Реальные/Модель», маркеры только из журнала; без легенды под Z-графиком; убрана кнопка Test; один переключатель фонового монитора.
1.5.2 — Портфель: без «MOEX заново»; время свечей графика = Москва — подтверждённые сделки из журнала совпадают с 15м; фоновый монитор (BG) по умолчанию вкл. и стартует с приложением.
1.5.1 — Вкладка «Тест страт.»: пороги Z, пресеты, walk-forward, сделки симуляции. «Портфель» — только подтверждённые сделки (журнал вход/выход).
1.5.0 — Иконки на кнопках; карточка «виртуальная сделка» Принять/Отклонить после сигнала входа (push/фон).
1.4.2 — Портфель только на 15-мин ряду (ISS→кэш); дневной расчёт убран. Walk-forward на тех же 15 мин.
1.4.1 — Портфель: компактная вёрстка; «Итого» и «Макс. просадка» рядом под порогами.
1.4.0 — Сводка на «Рынке», pull-to-refresh, статус данных, журнал сигналов, пресеты портфеля, walk-forward пороги, офлайн-снимок, экран «О приложении».
1.3.0 — Сплит файлов, .cursorignore, кэш 15м SQLite.
1.2.x — Портфель: плечо, комиссия, овернайт Тинькофф, режимы сигналов, пороги вручную.
"""
