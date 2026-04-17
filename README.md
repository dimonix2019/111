# 111

## Telegram spread alert automation

The repository includes a ready-to-run script:

- `automation/spread_alert.py`

It fetches latest MOEX prices for `TATN` and `TATNP`, computes:

`spread = (TATN / TATNP - 1) * 100`

and can send Telegram alerts in two modes:

1) **Target levels mode** (recommended for reminders at 6%, 5%, 4%):
- set `SPREAD_LEVELS=6,5,4` (or `SPREAD_TARGET_LEVELS=6,5,4`)
- alert is sent when spread crosses a target up/down
- state prevents duplicate spam between runs

2) **Upper/lower zone mode** (legacy):

- `NORMAL` -> `ABOVE_UPPER`
- `NORMAL` -> `BELOW_LOWER`
- and back when it returns to `NORMAL`

This prevents duplicate spam every run.

### Environment variables

Required:

- `SPREAD_LEVELS` (or `SPREAD_TARGET_LEVELS`) **OR** at least one of `SPREAD_UPPER`/`SPREAD_LOWER`
- `TELEGRAM_BOT_TOKEN` (unless `--dry-run`)
- `TELEGRAM_CHAT_ID` (unless `--dry-run`)

Optional:

- `SPREAD_BASE` (default: `TATN`)
- `SPREAD_QUOTE` (default: `TATNP`)
- `SPREAD_LEVELS` (example: `6,5,4`)
- `SPREAD_TARGET_LEVELS` (alias of `SPREAD_LEVELS`)
- `MOEX_BOARD` (default: `TQBR`)
- `HTTP_TIMEOUT_SECONDS` (default: `20`)
- `SPREAD_STATE_FILE` (default: `automation/.state/spread_state.json`)
- `ALERT_ON_START` (`true/false`, default: `true`)

### Local dry run

```bash
SPREAD_LEVELS=6,5,4 \
python3 automation/spread_alert.py --dry-run
```

### Cursor Automation schedule example

Run every 5 minutes:

```bash
python3 automation/spread_alert.py
```

Configure these secrets in Automation:

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHAT_ID`
- `SPREAD_LEVELS` (recommended: `6,5,4`)
