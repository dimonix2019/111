# Симулятор replay на Lenovo X1 (локально)
 
**Для кого:** разработка и прогон Bar Replay на ноуте **Lenovo 6-го поколения, 8 ГБ ОЗУ**, без мощного ПК.

**Кодовые имена в чате:**
- `Симулятор replay` — Bar Replay в приложении (▶ ⏸)
- `Симулятор десктоп` — отдельный CLI (позже, не сейчас)

**Спека архитектуры:** этот файл + раздел Replay в [`simulator-desktop.md`](simulator-desktop.md).

---

## 0. Что получится в итоге

На вкладке **«Тест страт.»** (или «Симулятор»):

1. Toggle **«Режим replay»**
2. График Z-score 15м — свечи появляются **по одной** (как TradingView Bar Replay)
3. Кнопки **▶ ⏸ ⏪ ⏩**, скорость **0.5×…10×**, слайдер перемотки
4. **R2 (в 1.7.232):** маркеры Z-сигналов по мере cursor; PnL/таблица сделок — фаза R3

Данные — уже загруженные 15м TATN/TATNP из SQLite/MOEX в приложении. **Тики и 1м за год на старте не нужны.**

---

## 1. Подготовка ноутбука (один раз)

### 1.1. Железо и ОС

| Параметр | Рекомендация |
|----------|--------------|
| RAM | 8 ГБ — закрыть Chrome/Telegram на время сборки |
| Диск | ≥ 5 ГБ свободно (Android SDK + Gradle cache) |
| Сеть | нужна для первого `./gradlew` и MOEX при тестах |

### 1.2. Установить

1. **JDK 17** (Temurin / OpenJDK 17) — не 21, если Gradle в проекте на 8.5.x.
2. **Android Studio** (или только SDK Command-line Tools) — `compileSdk 34`, Build-Tools 34.
3. **Git**
4. **Cursor** (IDE + агент)

Проверка:

```bash
java -version          # 17.x
echo $ANDROID_HOME     # путь к SDK
git --version
```

### 1.3. Клонировать репозиторий

```bash
cd ~/projects   # или куда удобно
git clone https://github.com/dimonix2019/111.git MoexMvp
cd MoexMvp
git fetch origin
git checkout main
git pull origin main
```

Если работаешь из уже существующей копии — `git status` должен быть чистым или осознанно сохранённым.

### 1.4. Память Gradle (обязательно на 8 ГБ)

Создай/допиши `~/.gradle/gradle.properties` **или** `gradle.properties` в корне проекта:

```properties
org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8
org.gradle.daemon=true
org.gradle.parallel=false
org.gradle.caching=true
kotlin.daemon.jvmargs=-Xmx1024m
```

Перед тяжёлой сборкой закрой браузер. Не запускай одновременно Android Studio + Cursor + эмулятор — на 8 ГБ это OOM.

### 1.5. Проверка, что проект собирается

```bash
cd ~/projects/MoexMvp
./gradlew :app:assembleDebug -q
```

Первый раз — 10–30 минут (зависимости). Повторно — несколько минут.

Unit-тесты (без эмулятора):

```bash
./gradlew :app:testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_dual_thresholds_1m_compound
```

(нужен интернет к MOEX ISS для этого теста)

### 1.6. Документация в репо

Убедись, что есть файлы:

- `docs/simulator-desktop.md` (раздел Replay)
- `docs/simulator-replay-x1-local.md` (этот файл)
- `AGENTS.md`

Если `docs/simulator-desktop.md` ещё нет на `main` — возьми с ветки, где он есть, или скопируй из облачного агента.

---

## 2. Как открыть новый чат в Cursor (локально)

1. Открой папку проекта в Cursor: `File → Open Folder → MoexMvp`
2. **Agent mode** (не Ask) — нужны правки кода
3. Новый чат (**New Agent Chat**)
4. Вставь **промпт из раздела 4** целиком
5. Дождись, пока агент прочитает docs и начнёт R1
6. Не переключай модель на «только план» — нужна реализация

### Ветка

Агент должен создать:

```text
cursor/strategy-test-bar-replay-ad2a
```

от актуального `main`.

---

## 3. План работ (что говорить агенту по ходу)

| Шаг | Фраза в чате | Результат |
|-----|--------------|-----------|
| Старт | промпт из §4 | ветка + R1 |
| После R1 | «Сделай R2: сигналы и маркеры» | сделки на графике |
| После R2 | «Сделай R3: PnL и таблица сделок» | виртуальный портфель |
| Сборка APK | «Собери debug APK и bump версии» | `assembleDebug` + changelog |

**Не проси сразу** десктопный CLI / тики / 1м за год — это перегрузит X1 и раздует scope.

---

## 4. Промпт для нового чата (скопировать целиком)

````
# Симулятор Bar Replay на X1 (локально) — фаза R1 → R2

Ты работаешь **локально** в репозитории Android Kotlin MOEX MVP (`app/`).
Железо разработчика: **Lenovo 6 gen, 8 ГБ ОЗУ** — экономить RAM, не запускать эмулятор без нужды, Gradle уже ограничен `-Xmx1536m`.

## Правила проекта
- Только Kotlin/Compose/assets этого репо. **Запрещено:** React, Spring, PostgreSQL, «с нуля веб», общие планы из 7 шагов без кода.
- После фичи: bump `versionCode`/`versionName` в `app/build.gradle.kts`, верхняя строка в `APP_CHANGELOG` (`MoexConstants.kt`), в финальном ответе версия + APK:
  https://github.com/dimonix2019/111/releases/download/moexmvp-debug-latest/moexmvp-debug.apk
- Cloud VM / adb UI не обязателен; локально: unit-тесты + `assembleDebug`.
- Ветка: `cursor/strategy-test-bar-replay-ad2a` от `main`. Commit, push, draft PR.

## Обязательно прочитать перед кодом
1. `docs/simulator-replay-x1-local.md` — локальный workflow X1
2. `docs/simulator-desktop.md` — раздел **«Replay-симулятор в стиле TradingView (Bar Replay)»** (UX, BarReplayEngine, фазы R1–R5)
3. `AGENTS.md`
4. Код:
   - `app/src/main/assets/tradingview/z_chart.html`
   - `MoexTradingViewChart.kt`
   - `MoexPortfolioUiStrategyTest.kt`, `MoexScreenTabStrategyTest.kt`
   - `MoexZStrategy15mReplay.kt`, `MoexDailySimReplay.kt`
   - `determineZStrategySignalBetweenBars` в `MoexIssStrategy.kt`

## Цель продукта
**TradingView-style Bar Replay** на вкладке «Тест страт.»:
- ▶ ⏸ ⏪ ⏩, скорость 0.5×…10×, слайдер
- на графике видны только бары **≤ cursor**
- данные: существующий 15м ряд `strategyTestM15ChartPoints` + пороги со степперов
- **без** второй загрузки MOEX, **без** тиков, **без** десктоп-модуля в этом PR

## Сделать сейчас

### R1 (обязательно в первом PR)
1. `BarReplayEngine` (Kotlin): Idle/Playing/Paused, cursor, `stepForward`/`seekTo`, speed.
2. UI: toggle «Режим replay» + `ReplayControlBar` на «Тест страт.» (не ломать статичный режим).
3. `z_chart.html`: лёгкий API `setReplayCursor` / slice свечей без полного remount WebView.
4. Видимое окно ~30 календарных дней от cursor.
5. Unit-тесты engine.
6. Версия + changelog + `assembleDebug`.

### R2 (в том же PR, если стабильно; иначе отдельный commit следом)
1. Инкрементальные сигналы через `determineZStrategySignalBetweenBars` / `advanceZStrategyPosition`.
2. Маркеры входа/выхода только когда cursor дошёл до бара.
3. Parity-тест: `stepForward()` × N ≡ `collectZStrategy15mSignalEdgesFull`.

### Не делать
- `moex-core` / CLI desktop
- 1м за год / тики / RTX
- эмулятор UI-тесты как обязательный шаг
- рефакторинг всего «Тест страт.»

## Критерий готовности R1
На «Тест страт.» включается replay → Play → свечи Z идут по одной; Pause/scrub работают; статичный режим без регрессий; сборка проходит на 8 ГБ.

Начни: прочитай docs → создай ветку → реализуй R1 → тесты → commit/push/PR.
````

---

## 5. Ручной чеклист после работы агента

```bash
# 1. Ветка
git branch --show-current   # cursor/strategy-test-bar-replay-ad2a

# 2. Тесты
./gradlew :app:testDebugUnitTest --tests '*BarReplay*' --tests '*Replay*'

# 3. APK
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# 4. Установка на телефон (USB)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

На телефоне: **Тест страт.** → включить **Режим replay** → ▶.

Если нет телефона — достаточно unit-тестов + `assembleDebug`.

---

## 6. Советы по X1 во время разработки

| Ситуация | Действие |
|----------|----------|
| Gradle падает с OOM | закрыть браузер; `-Xmx1536m`; `./gradlew --stop` |
| Cursor тормозит | закрыть лишние вкладки; не держать Android Studio открытым |
| Долгий `assembleDebug` | нормально 5–15 мин на HDD/слабом SSD |
| Хочется «быстрее смотреть год» | в replay ставь **10×**, не качай 1м |
| Нужен полный бэктест без анимации | оставь статичный режим «Тест страт.» (как сейчас) |

Оценка нагрузки replay:

- 255д × 15м ≈ **8700** шагов
- 1× ≈ ~2 ч просмотра; **10× ≈ ~13 мин**
- RAM на ряд: **несколько МБ** (узкое место — WebView, не симуляция)

---

## 7. Что отложить до мощного ноута

- Загрузка **1м за год** с MOEX (30–90 мин на X1)
- Сетка порогов 400+ комбинаций в фоне IDE
- Десктопный `moex-simulator` CLI
- Тиковый архив

На X1 фокус: **R1 → R2 → R3** внутри Android-приложения.

---

## 8. Связанные документы

| Файл | Содержание |
|------|------------|
| [`simulator-desktop.md`](simulator-desktop.md) | архитектура CLI + **полный раздел Replay** |
| [`perehod-na-10-minutki.md`](perehod-na-10-minutki.md) | отложенный переход на 10м (не мешать) |
| `AGENTS.md` | версия, APK, запрет веб-стека |

---

## 9. Быстрый старт (3 команды + чат)

```bash
cd ~/projects/MoexMvp
git checkout main && git pull
# Открыть Cursor → New Agent Chat → вставить промпт из §4
```

### Windows-приложение (без телефона)

```bat
cd C:\Users\root\MoexMvp
run-desktop.bat
```

См. [`windows-desktop.md`](windows-desktop.md) — Swing Bar Replay из CSV `strategy-web/data/m15_tatn_255d.csv`.

Готово. Дальше ведёт агент по R1.
