# Диаграмма CI/CD для draw.io

Готовый файл со **swimlanes (дорожками ролей)**: [`cicd-process.drawio`](cicd-process.drawio).

Версия с **чистой маршрутизацией**: возвраты идут только по левому «коридору возвратов», happy-path слева направо, междорожечные передачи — короткие вертикали в одной колонке.

Для копирования XML: [`cicd-process-xml-paste.txt`](cicd-process-xml-paste.txt).

## Способ 1 (рекомендуется): открыть `.drawio`

1. Откройте [https://app.diagrams.net](https://app.diagrams.net) (или desktop draw.io).
2. **File → Open from → Device** и выберите `docs/cicd-process.drawio`.
3. Либо перетащите файл в окно браузера.

Так сохраняются настоящие BPMN-дорожки, цвета шлюзов и межролевые стрелки.

## Способ 2: вставить XML

1. **File → New → Blank diagram**.
2. **Extras → Edit Diagram…**
3. Замените содержимое XML из `cicd-process.drawio` целиком (от `<mxfile>` до `</mxfile>`).
4. **OK**.

## Способ 3: вставить Mermaid

**Arrange → Insert → Advanced → Mermaid** — вставьте код ниже.

Draw.io превращает `subgraph` в группы, а не в классические swimlanes. Для дорожек используйте способ 1 или 2.

```mermaid
flowchart TD
  %% =========================================================
  %% Легенда типов узлов (classDef → цвета в draw.io)
  %% =========================================================
  classDef event fill:#d5e8d4,stroke:#82b366,stroke-width:2px,color:#1a1a1a
  classDef task fill:#dae8fc,stroke:#6c8ebf,stroke-width:1.5px,color:#1a1a1a
  classDef auto fill:#e1d5e7,stroke:#9673a6,stroke-width:1.5px,color:#1a1a1a
  classDef gateway fill:#fff2cc,stroke:#d6b656,stroke-width:1.5px,color:#1a1a1a
  classDef rework fill:#f8cecc,stroke:#b85450,stroke-width:1.5px,stroke-dasharray: 6 4,color:#1a1a1a
  classDef success fill:#d5e8d4,stroke:#82b366,stroke-width:2px,color:#1a1a1a
  classDef finish fill:#82b366,stroke:#1e4d2b,stroke-width:3px,color:#ffffff

  %% =========================================================
  %% Дорожка: Разработчик (Dev)
  %% Старт → локальная проверка → push → MR.
  %% Правки — вход с красных веток CI/QA/релиза.
  %% =========================================================
  subgraph DEV["Разработчик (Dev)"]
    direction LR
    evStart(["Старт процесса"])
    actCode["Написание кода и локальная проверка"]
    actPush["Git Push в ветку фичи"]
    actPR["Создание Pull Request (MR)"]
    actFixes["Внесение исправлений по замечаниям"]
    evStart --> actCode --> actPush --> actPR
  end

  %% =========================================================
  %% Дорожка: CI/CD Система (Автоматизация)
  %% Триггер: Push и open/sync MR. Quality-gate, затем аппрувы,
  %% затем auto-merge в develop/master.
  %% =========================================================
  subgraph CI["CI/CD Система (Автоматизация)"]
    direction LR
    actPipeline["Запуск пайплайна CI"]
    actTests["Шаг 1. Запуск автотестов (Unit/Integration)"]
    actLinters["Шаг 2. Статический анализ кода (Linters)"]
    actRegs["Шаг 3. Проверка соответствия регламентам (ключи задач, листинги)"]
    actYaml["Шаг 4. Проверка YAML-конфигураций и SQL-скриптов"]
    gwChecks{"Все проверки пройдены?"}
    actCheckAppr["Шаг 5. Проверка наличия всех обязательных аппрувов"]
    gwAppr{"Аппрувы собраны?"}
    actMerge["Автоматический Merge (Auto-merge) в develop/master"]
    actPipeline --> actTests --> actLinters --> actRegs --> actYaml --> gwChecks
    gwChecks -->|ДА| actCheckAppr --> gwAppr
    gwAppr -->|ДА| actMerge
  end

  %% =========================================================
  %% Дорожка: Команда QA / Архитектура
  %% Человеческий контроль. Approve закрывает gate аппрувов.
  %% Замечания возвращают работу разработчику.
  %% =========================================================
  subgraph QA["Команда QA / Архитектура"]
    direction LR
    actReview["Ревью кода (Code Review)"]
    actArch["Проверка архитектурных требований"]
    actApprove["Оставление аппрува (Approve) в MR"]
    actComments["Комментарии и запрос правок"]
    actReview --> actArch
    actArch -->|без замечаний| actApprove
    actArch -->|есть замечания| actComments
    actReview --> actComments
  end

  %% =========================================================
  %% Дорожка: Релиз-менеджер / DevOps
  %% После merge: артефакты, миграции, патч, отчёты, SR.
  %% Шлюз согласования — допуск к передаче внедренцу.
  %% =========================================================
  subgraph REL["Релиз-менеджер / DevOps"]
    direction LR
    actInit["Инициация сборки релиза"]
    actArt["Шаг 1. Сборка артефактов (бинарники, конфиги)"]
    actMig["Шаг 2. Генерация скриптов миграции БД (Liquibase/Alembic)"]
    actPatch["Шаг 3. Формирование единого релизного патча"]
    actReports["Шаг 4. Генерация отчетов (анализатор кода, результаты тестов)"]
    actSR["Создание Service Request (SR) / Тикета"]
    actAttach["Прикрепление патча и отчетов к SR"]
    gwRel{"Согласован ли релиз?"}
    actRework["Исправление релиза (DevOps)"]
    actInit --> actArt --> actMig --> actPatch --> actReports --> actSR --> actAttach --> gwRel
  end

  %% =========================================================
  %% Дорожка: Внедренец (Production)
  %% Только после ДА на шлюзе релиза. Инфра → БД → деплой → смоук.
  %% =========================================================
  subgraph PROD["Внедренец (Production)"]
    direction LR
    actRecv["Получение патча от Релиз-менеджера"]
    actInfra["Проверка готовности инфраструктуры"]
    actDb["Применение миграций БД"]
    actDeploy["Деплой приложений в Production"]
    actSmoke["Смоук-тестирование в прод"]
    evEnd(["Успешный релиз"])
    actRecv --> actInfra --> actDb --> actDeploy --> actSmoke --> evEnd
  end

  %% Междорожечные связи
  actPush -.->|webhook Push| actPipeline
  actPR -->|open / sync MR| actPipeline
  gwChecks -->|НЕТ: отчёт об ошибках| actFixes
  actFixes -.->|повторный Push| actPush
  gwAppr -.->|НЕТ: ожидание аппрувов| actReview
  actApprove -->|Approve зафиксирован| actCheckAppr
  actComments -.->|запрос правок| actFixes
  actMerge -->|после Merge| actInit
  gwRel -->|ДА: передать патч| actRecv
  gwRel -->|НЕТ: к разработчику| actFixes
  gwRel -->|НЕТ: к DevOps| actRework
  actRework -.->|повторная сборка| actInit

  class evStart event
  class evEnd finish
  class actCode,actPush,actPR task
  class actFixes,actComments,actRework rework
  class actPipeline,actTests,actLinters,actRegs,actYaml,actCheckAppr auto
  class gwChecks,gwAppr,gwRel gateway
  class actMerge,actApprove,actRecv,actInfra,actDb,actDeploy,actSmoke success
  class actReview,actArch task
  class actInit,actArt,actMig,actPatch,actReports,actSR,actAttach task
```
