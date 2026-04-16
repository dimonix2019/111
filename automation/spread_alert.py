#!/usr/bin/env python3
"""Send Telegram alerts when TATN/TATNP spread crosses thresholds.

Designed for scheduled runs (e.g. Cursor Automation cron):
  python3 automation/spread_alert.py
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional
from urllib import parse, request


DEFAULT_BOARD = "TQBR"
DEFAULT_BASE = "TATN"
DEFAULT_QUOTE = "TATNP"
DEFAULT_TIMEOUT_SECONDS = 20
DEFAULT_STATE_FILE = "automation/.state/spread_state.json"


@dataclass
class AlertState:
    zone: str
    spread: float
    updated_at: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="MOEX spread Telegram alert")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print alert text but do not call Telegram API",
    )
    return parser.parse_args()


def env_str(name: str, default: Optional[str] = None) -> str:
    value = os.getenv(name)
    if value is None or value == "":
        if default is None:
            raise ValueError(f"Environment variable {name} is required")
        return default
    return value


def env_optional_float(name: str) -> Optional[float]:
    raw = os.getenv(name)
    if raw is None or raw == "":
        return None
    try:
        return float(raw)
    except ValueError as exc:
        raise ValueError(f"Environment variable {name} must be a float") from exc


def env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or raw == "":
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise ValueError(f"Environment variable {name} must be an integer") from exc


def env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw == "":
        return default
    normalized = raw.strip().lower()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    raise ValueError(f"Environment variable {name} must be boolean-like")


def http_get_json(url: str, timeout_seconds: int) -> dict[str, Any]:
    req = request.Request(url, headers={"User-Agent": "spread-alert-bot/1.0"})
    with request.urlopen(req, timeout=timeout_seconds) as response:
        return json.loads(response.read().decode("utf-8"))


def to_float(value: Any) -> Optional[float]:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return None
    return None


def fetch_latest_price(secid: str, board: str, timeout_seconds: int) -> float:
    params = parse.urlencode(
        {
            "iss.meta": "off",
            "marketdata.columns": "SECID,LAST,LCLOSEPRICE",
        }
    )
    url = (
        "https://iss.moex.com/iss/engines/stock/markets/shares/boards/"
        f"{board}/securities/{secid}.json?{params}"
    )
    payload = http_get_json(url, timeout_seconds=timeout_seconds)
    marketdata = payload.get("marketdata", {})
    rows = marketdata.get("data", [])
    if not rows:
        raise RuntimeError(f"No marketdata rows for {secid}")

    row = rows[0]
    # Order is SECID, LAST, LCLOSEPRICE.
    last = to_float(row[1] if len(row) > 1 else None)
    lclose = to_float(row[2] if len(row) > 2 else None)
    price = last if last is not None else lclose
    if price is None:
        raise RuntimeError(f"Neither LAST nor LCLOSEPRICE available for {secid}")
    return price


def compute_zone(spread: float, lower: Optional[float], upper: Optional[float]) -> str:
    if upper is not None and spread >= upper:
        return "ABOVE_UPPER"
    if lower is not None and spread <= lower:
        return "BELOW_LOWER"
    return "NORMAL"


def load_state(path: Path) -> Optional[AlertState]:
    if not path.exists():
        return None
    raw = json.loads(path.read_text(encoding="utf-8"))
    return AlertState(
        zone=str(raw.get("zone", "NORMAL")),
        spread=float(raw.get("spread", 0.0)),
        updated_at=str(raw.get("updated_at", "")),
    )


def save_state(path: Path, state: AlertState) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(asdict(state), indent=2), encoding="utf-8")


def build_message(
    zone: str,
    prev_zone: Optional[str],
    spread: float,
    base: str,
    quote: str,
    base_price: float,
    quote_price: float,
    lower: Optional[float],
    upper: Optional[float],
) -> str:
    now = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S %Z")
    parts = [
        "Spread alert",
        f"Pair: {base}/{quote}",
        f"Zone: {prev_zone or 'N/A'} -> {zone}",
        f"Spread: {spread:.4f}%",
        f"{base}: {base_price:.4f}",
        f"{quote}: {quote_price:.4f}",
    ]
    if lower is not None:
        parts.append(f"Lower: {lower:.4f}%")
    if upper is not None:
        parts.append(f"Upper: {upper:.4f}%")
    parts.append(f"Time: {now}")
    return "\n".join(parts)


def send_telegram(token: str, chat_id: str, text: str, timeout_seconds: int) -> None:
    url = f"https://api.telegram.org/bot{token}/sendMessage"
    payload = parse.urlencode({"chat_id": chat_id, "text": text}).encode("utf-8")
    req = request.Request(url, data=payload, method="POST")
    with request.urlopen(req, timeout=timeout_seconds) as response:
        body = json.loads(response.read().decode("utf-8"))
        if not body.get("ok", False):
            raise RuntimeError(f"Telegram API error: {body}")


def main() -> int:
    args = parse_args()
    try:
        board = env_str("MOEX_BOARD", DEFAULT_BOARD)
        base = env_str("SPREAD_BASE", DEFAULT_BASE)
        quote = env_str("SPREAD_QUOTE", DEFAULT_QUOTE)
        lower = env_optional_float("SPREAD_LOWER")
        upper = env_optional_float("SPREAD_UPPER")
        timeout_seconds = env_int("HTTP_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS)
        alert_on_start = env_bool("ALERT_ON_START", True)
        state_file = Path(env_str("SPREAD_STATE_FILE", DEFAULT_STATE_FILE))

        if lower is None and upper is None:
            raise ValueError("At least one threshold required: SPREAD_LOWER or SPREAD_UPPER")

        token = os.getenv("TELEGRAM_BOT_TOKEN", "")
        chat_id = os.getenv("TELEGRAM_CHAT_ID", "")
        if not args.dry_run and (not token or not chat_id):
            raise ValueError("TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID are required")

        base_price = fetch_latest_price(base, board, timeout_seconds=timeout_seconds)
        quote_price = fetch_latest_price(quote, board, timeout_seconds=timeout_seconds)
        if quote_price == 0:
            raise RuntimeError(f"{quote} price is zero, spread cannot be calculated")

        spread = (base_price / quote_price - 1.0) * 100.0
        zone = compute_zone(spread, lower=lower, upper=upper)

        prev = load_state(state_file)
        prev_zone = prev.zone if prev else None
        should_send = False

        if prev is None:
            should_send = alert_on_start and zone != "NORMAL"
        elif zone != prev.zone:
            should_send = True

        if should_send:
            message = build_message(
                zone=zone,
                prev_zone=prev_zone,
                spread=spread,
                base=base,
                quote=quote,
                base_price=base_price,
                quote_price=quote_price,
                lower=lower,
                upper=upper,
            )
            if args.dry_run:
                print("[DRY RUN] Telegram message would be sent:")
                print(message)
            else:
                send_telegram(token, chat_id, message, timeout_seconds=timeout_seconds)
                print("Alert sent to Telegram.")
        else:
            print(
                "No alert sent. "
                f"zone={zone}, prev_zone={prev_zone}, spread={spread:.4f}%"
            )

        save_state(
            state_file,
            AlertState(
                zone=zone,
                spread=spread,
                updated_at=datetime.now(timezone.utc).isoformat(),
            ),
        )
        return 0
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
