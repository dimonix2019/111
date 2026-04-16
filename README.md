# 111

## Telegram spread alert automation

The repository includes a ready-to-run script:

- `automation/spread_alert.py`

It fetches latest MOEX prices for `TATN` and `TATNP`, computes:

`spread = (TATN / TATNP - 1) * 100`

and sends a Telegram alert when the spread zone changes:

- `NORMAL` -> `ABOVE_UPPER`
- `NORMAL` -> `BELOW_LOWER`
- and back when it returns to `NORMAL`

This prevents duplicate spam every run.

### Environment variables

Required:

- `SPREAD_UPPER` or `SPREAD_LOWER` (at least one)
- `TELEGRAM_BOT_TOKEN` (unless `--dry-run`)
- `TELEGRAM_CHAT_ID` (unless `--dry-run`)

Optional:

- `SPREAD_BASE` (default: `TATN`)
- `SPREAD_QUOTE` (default: `TATNP`)
- `MOEX_BOARD` (default: `TQBR`)
- `HTTP_TIMEOUT_SECONDS` (default: `20`)
- `SPREAD_STATE_FILE` (default: `automation/.state/spread_state.json`)
- `ALERT_ON_START` (`true/false`, default: `true`)

### Local dry run

```bash
SPREAD_UPPER=9.5 \
SPREAD_LOWER=8.2 \
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
- `SPREAD_UPPER`
- `SPREAD_LOWER`
