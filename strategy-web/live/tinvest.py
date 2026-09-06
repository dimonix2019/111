"""T‑Invest REST client (Sandbox + Prod) — port of TinkoffSandboxApi."""

from __future__ import annotations

import json
import re
import uuid
from typing import Any

from live.constants import (
    PROD_INSTRUMENTS_PREFIXES,
    PROD_MARKETDATA_PREFIXES,
    PROD_ORDERS_PREFIXES,
    PROD_USERS_PREFIXES,
    SANDBOX_INSTRUMENTS_PREFIXES,
    SANDBOX_MARKETDATA_PREFIXES,
    SANDBOX_PREFIXES,
    TATN_FALLBACK_ID,
    TATN_FIGI,
    TATN_INSTRUMENT_UID,
    TATNP_FALLBACK_ID,
    TATNP_FIGI,
    TATNP_INSTRUMENT_UID,
    USER_AGENT,
)
from live.ssl_util import format_tinvest_error, requests_post_verified, resolve_requests_verify

_ASCII_TOKEN = re.compile(r"[^\x21-\x7e]")


def normalize_token(raw: str) -> str:
    t = (raw or "").strip()
    if t.lower().startswith("bearer "):
        t = t[7:].strip()
    t = re.sub(r"[\s\u00a0\u1680\u2000-\u200b\u202f\u205f\u3000\ufeff]+", "", t)
    return "".join(ch for ch in t if 33 <= ord(ch) <= 126 and ch not in "\"\\")


def _extract_error(http_code: int, body: str) -> str:
    try:
        o = json.loads(body)
    except Exception:
        snippet = (body or "").strip().replace("\n", " ")[:320]
        return f"HTTP {http_code}" + (f": {snippet}" if snippet else "")
    msg = (
        o.get("message")
        or (o.get("status") or {}).get("message")
        or o.get("description")
        or o.get("error")
        or ((o.get("error") or {}) if isinstance(o.get("error"), dict) else {}).get("message")
        or ""
    )
    if isinstance(msg, dict):
        msg = msg.get("message") or str(msg)
    code = o.get("code")
    snippet = (body or "").strip().replace("\n", " ")[:320]
    parts = [f"HTTP {http_code}"]
    if code is not None:
        parts.append(f"code={code}")
    if msg:
        parts.append(str(msg))
    elif snippet:
        parts.append(snippet)
    return " · ".join(parts) if len(parts) > 1 else parts[0]


def _post_raw(
    prefixes: list[str],
    token: str,
    method: str,
    body: dict[str, Any],
    *,
    timeout: float = 30.0,
) -> dict[str, Any]:
    norm = normalize_token(token)
    if not norm:
        raise RuntimeError(
            "Пустой или недопустимый токен API. Вставьте токен из кабинета Т‑Инвест."
        )
    verify = resolve_requests_verify()
    last: Exception | None = None
    for prefix in prefixes:
        url = f"{prefix}/{method}"
        try:
            resp = requests_post_verified(
                url,
                headers={
                    "Authorization": f"Bearer {norm}",
                    "Accept": "application/json",
                    "Content-Type": "application/json; charset=utf-8",
                    "User-Agent": USER_AGENT,
                },
                json_body=body,
                timeout=timeout,
                verify=verify,
            )
            text = resp.text or ""
            if not resp.ok:
                last = RuntimeError(f"{_extract_error(resp.status_code, text)} · {url}")
                continue
            if not text.strip():
                return {}
            try:
                return resp.json()
            except Exception:
                return {"_raw": text}
        except Exception as exc:
            last = RuntimeError(format_tinvest_error(exc))
    raise RuntimeError(str(last) if last else "T‑Invest REST: нет доступных хостов")


def _unwrap(root: dict[str, Any], keys: list[str]) -> dict[str, Any]:
    cur = root
    for k in keys:
        if isinstance(cur, dict) and k in cur and isinstance(cur[k], dict):
            return cur[k]
    return root


def quotation_to_float(o: dict[str, Any] | None) -> float | None:
    if not o or not isinstance(o, dict):
        return None
    nano = float(o.get("nano") or o.get("Nano") or 0)
    raw = o.get("units", o.get("Units"))
    if raw is None:
        units = 0.0
    elif isinstance(raw, (int, float)):
        units = float(raw)
    else:
        try:
            units = float(str(raw).strip())
        except ValueError:
            return None
    return units + nano / 1_000_000_000.0


def money_to_float(o: Any) -> float | None:
    """MoneyValue / Quotation / raw number → float ₽."""
    if o is None:
        return None
    if isinstance(o, (int, float)):
        x = float(o)
        return x if x == x else None
    return quotation_to_float(o if isinstance(o, dict) else None)


_TATN_IDS = {
    "TATN",
    TATN_FALLBACK_ID.upper(),
    TATN_FIGI.upper(),
    TATN_INSTRUMENT_UID.upper(),
}
_TATNP_IDS = {
    "TATNP",
    TATNP_FALLBACK_ID.upper(),
    TATNP_FIGI.upper(),
    TATNP_INSTRUMENT_UID.upper(),
}


def collect_portfolio_positions(portfolio: dict[str, Any]) -> list[dict[str, Any]]:
    """Walk GetPortfolio JSON for positions[] (camel/snake, nested envelope)."""
    out: list[dict[str, Any]] = []
    seen: set[int] = set()

    def walk(o: Any, depth: int) -> None:
        if not isinstance(o, dict) or depth > 8:
            return
        arr = o.get("positions") or o.get("Positions")
        if isinstance(arr, list) and id(arr) not in seen:
            seen.add(id(arr))
            for p in arr:
                if isinstance(p, dict):
                    out.append(p)
        for v in o.values():
            if isinstance(v, dict):
                walk(v, depth + 1)

    walk(portfolio, 0)
    return out


def _position_identity_keys(pos: dict[str, Any]) -> set[str]:
    keys: set[str] = set()
    for k in (
        "ticker",
        "Ticker",
        "figi",
        "FIGI",
        "instrumentUid",
        "instrument_uid",
        "uid",
        "instrumentId",
        "instrument_id",
        "positionUid",
        "position_uid",
    ):
        v = pos.get(k)
        if v:
            keys.add(str(v).strip().upper())
    return keys


def _position_current_price_rub(pos: dict[str, Any]) -> float | None:
    return money_to_float(
        pos.get("currentPrice")
        or pos.get("current_price")
        or pos.get("averagePositionPrice")
        or pos.get("average_position_price")
    )


def _position_expected_yield_rub(pos: dict[str, Any]) -> float | None:
    return money_to_float(pos.get("expectedYield") or pos.get("expected_yield"))


def parse_spread_leg_broker_pnl(
    portfolio: dict[str, Any] | None,
    direction: str | None = None,
) -> dict[str, Any] | None:
    """
    Unrealized PnL of TATN/TATNP from GetPortfolio.expectedYield (APK / T‑Invest).

    net_gross_rub = TATN.expectedYield + TATNP.expectedYield — not local spread MTM.
    """
    if not isinstance(portfolio, dict):
        return None
    tatn_y = tatnp_y = 0.0
    tatn_ok = tatnp_ok = False
    tatn_px = tatnp_px = None
    for pos in collect_portfolio_positions(portfolio):
        ids = _position_identity_keys(pos)
        y = _position_expected_yield_rub(pos)
        if y is None:
            continue
        if ids & _TATN_IDS:
            tatn_y += y
            tatn_ok = True
            if tatn_px is None:
                tatn_px = _position_current_price_rub(pos)
        elif ids & _TATNP_IDS:
            tatnp_y += y
            tatnp_ok = True
            if tatnp_px is None:
                tatnp_px = _position_current_price_rub(pos)
    if not tatn_ok or not tatnp_ok:
        return None
    d = (direction or "").upper()
    if "SHORT" in d:
        long_y, short_y = tatnp_y, tatn_y
        long_px, short_px = tatnp_px, tatn_px
    else:
        long_y, short_y = tatn_y, tatnp_y
        long_px, short_px = tatn_px, tatnp_px
    net = tatn_y + tatnp_y
    return {
        "tatn_yield_rub": tatn_y,
        "tatnp_yield_rub": tatnp_y,
        "long_leg_yield_rub": long_y,
        "short_leg_yield_rub": short_y,
        "net_gross_rub": net,
        "expected_yield_rub": net,
        "long_leg_price_rub": long_px,
        "short_leg_price_rub": short_px,
        "pnl_source": "tinkoff_expected_yield",
    }


def _find_nested_money(obj: Any, keys: list[str], depth: int = 0) -> dict[str, Any] | None:
    if not isinstance(obj, dict) or depth > 8:
        return None
    for k in keys:
        v = obj.get(k)
        if isinstance(v, dict):
            return v
    for v in obj.values():
        found = _find_nested_money(v, keys, depth + 1)
        if found:
            return found
    return None


class TInvestClient:
    def __init__(self, mode: str, token: str):
        self.mode = "prod" if mode == "prod" else "sandbox"
        self.token = token

    def _sandbox(
        self, method: str, body: dict[str, Any] | None = None, *, timeout: float = 30.0
    ) -> dict[str, Any]:
        return _post_raw(
            SANDBOX_PREFIXES, self.token, method, body or {}, timeout=timeout
        )

    def _sbx_instr(self, method: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        return _post_raw(SANDBOX_INSTRUMENTS_PREFIXES, self.token, method, body or {})

    def _prod_orders(
        self, method: str, body: dict[str, Any] | None = None, *, timeout: float = 30.0
    ) -> dict[str, Any]:
        return _post_raw(
            PROD_ORDERS_PREFIXES, self.token, method, body or {}, timeout=timeout
        )

    def _prod_instr(self, method: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        return _post_raw(PROD_INSTRUMENTS_PREFIXES, self.token, method, body or {})

    def _prod_users(self, method: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        return _post_raw(PROD_USERS_PREFIXES, self.token, method, body or {})

    def _marketdata(
        self,
        method: str,
        body: dict[str, Any] | None = None,
        *,
        timeout: float = 30.0,
    ) -> dict[str, Any]:
        prefixes = (
            SANDBOX_MARKETDATA_PREFIXES if self.mode == "sandbox" else PROD_MARKETDATA_PREFIXES
        )
        return _post_raw(prefixes, self.token, method, body or {}, timeout=timeout)

    def get_trading_status(
        self, instrument_id: str, *, timeout: float = 30.0
    ) -> dict[str, Any]:
        """MarketDataService/GetTradingStatus — weekend dealer = DEALER_NORMAL_TRADING."""
        iid = (instrument_id or "").strip()
        if not iid:
            raise RuntimeError("GetTradingStatus: пустой instrumentId")
        last: Exception | None = None
        for body in (
            {"instrumentId": iid},
            {"instrument_id": iid},
            {"figi": iid},
        ):
            try:
                raw = self._marketdata("GetTradingStatus", body, timeout=float(timeout))
                return _unwrap(
                    raw,
                    [
                        "getTradingStatusResponse",
                        "get_trading_status_response",
                        "tradingStatus",
                        "trading_status",
                    ],
                )
            except Exception as exc:
                last = exc
        raise RuntimeError(str(last) if last else "GetTradingStatus failed")

    def get_last_prices(
        self,
        instrument_ids: list[str],
        *,
        last_price_type: str = "LAST_PRICE_DEALER",
        timeout: float = 30.0,
    ) -> dict[str, float]:
        """
        MarketDataService/GetLastPrices.
        Returns map instrumentId → last price float.
        Weekend quotes: last_price_type=LAST_PRICE_DEALER.
        """
        ids = [str(x).strip() for x in instrument_ids if str(x).strip()]
        if not ids:
            return {}
        last: Exception | None = None
        bodies = [
            {"instrumentId": ids, "lastPriceType": last_price_type},
            {"instrument_id": ids, "last_price_type": last_price_type},
            {"instrumentId": ids, "last_price_type": last_price_type},
            {"figi": ids, "lastPriceType": last_price_type},
        ]
        for body in bodies:
            try:
                raw = self._marketdata("GetLastPrices", body, timeout=float(timeout))
                env = _unwrap(
                    raw,
                    [
                        "getLastPricesResponse",
                        "get_last_prices_response",
                    ],
                )
                rows = env.get("lastPrices") or env.get("last_prices") or env.get("LastPrices")
                if rows is None and isinstance(env.get("prices"), list):
                    rows = env["prices"]
                if not isinstance(rows, list):
                    # sometimes root is already the list wrapper
                    rows = raw.get("lastPrices") or raw.get("last_prices") or []
                out: dict[str, float] = {}
                for row in rows if isinstance(rows, list) else []:
                    if not isinstance(row, dict):
                        continue
                    key = (
                        row.get("instrumentUid")
                        or row.get("instrument_uid")
                        or row.get("figi")
                        or row.get("FIGI")
                        or row.get("instrumentId")
                        or row.get("instrument_id")
                        or row.get("ticker")
                    )
                    px = quotation_to_float(row.get("price") or row.get("Price"))
                    if key and px is not None and px > 0:
                        out[str(key)] = px
                # Successful HTTP with empty list — try next body shape
                if out:
                    return out
                last = RuntimeError("GetLastPrices: пустой список цен")
            except Exception as exc:
                last = exc
        raise RuntimeError(str(last) if last else "GetLastPrices failed")

    def get_order_book(
        self,
        instrument_id: str,
        *,
        depth: int = 1,
        order_book_type: str | None = "ORDERBOOK_TYPE_DEALER",
        timeout: float = 30.0,
        max_attempts: int | None = None,
    ) -> dict[str, Any]:
        """MarketDataService/GetOrderBook — optionally ORDERBOOK_TYPE_DEALER."""
        iid = (instrument_id or "").strip()
        if not iid:
            raise RuntimeError("GetOrderBook: пустой instrumentId")
        d = max(1, min(50, int(depth)))
        last: Exception | None = None
        bodies: list[dict[str, Any]] = []
        if order_book_type:
            bodies.extend(
                [
                    {"instrumentId": iid, "depth": d, "orderBookType": order_book_type},
                    {
                        "instrument_id": iid,
                        "depth": d,
                        "order_book_type": order_book_type,
                    },
                ]
            )
        bodies.extend(
            [
                {"instrumentId": iid, "depth": d},
                {"instrument_id": iid, "depth": d},
                {"figi": iid, "depth": d},
            ]
        )
        if max_attempts is not None and max_attempts > 0:
            bodies = bodies[: int(max_attempts)]
        for body in bodies:
            try:
                return self._marketdata("GetOrderBook", body, timeout=float(timeout))
            except Exception as exc:
                last = exc
        raise RuntimeError(str(last) if last else "GetOrderBook failed")

    def get_candles(
        self,
        instrument_id: str,
        *,
        interval: str = "CANDLE_INTERVAL_1_MIN",
        from_dt: Any = None,
        to_dt: Any = None,
        candle_source_type: str | None = "CANDLE_SOURCE_DEALER_WEEKEND",
        limit: int | None = None,
        timeout: float = 30.0,
        max_attempts: int | None = None,
        accept_empty: bool = False,
    ) -> list[dict[str, Any]]:
        """MarketDataService/GetCandles — dealer weekend uses 1m + DEALER_WEEKEND source.

        Desk/monitor must pass a short timeout + capped attempts: unbounded
        source×id retries with timeout=30 can block the uvicorn thread pool
        for minutes and freeze Trade Desk.
        """
        from datetime import datetime, timezone

        iid = (instrument_id or "").strip()
        if not iid:
            raise RuntimeError("GetCandles: пустой instrumentId")

        def _ts(v: Any) -> Any:
            if v is None:
                return None
            if isinstance(v, dict):
                return v
            if isinstance(v, datetime):
                if v.tzinfo is None:
                    v = v.replace(tzinfo=timezone.utc)
                return v.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            return str(v)

        fr = _ts(from_dt)
        to = _ts(to_dt)
        last: Exception | None = None
        bodies: list[dict[str, Any]] = []
        base: dict[str, Any] = {"interval": interval}
        if fr is not None:
            base["from"] = fr
        if to is not None:
            base["to"] = to
        if limit is not None:
            base["limit"] = int(limit)

        # Prefer instrumentId; figi last. Cap attempts for dealer desk path.
        id_variants = (
            {"instrumentId": iid},
            {"instrument_id": iid},
            {"figi": iid},
        )
        sources = []
        if candle_source_type:
            sources.append(candle_source_type)
            if candle_source_type == "CANDLE_SOURCE_DEALER_WEEKEND":
                sources.append("CANDLE_SOURCE_INCLUDE_WEEKEND")
            elif candle_source_type != "CANDLE_SOURCE_INCLUDE_WEEKEND":
                sources.append("CANDLE_SOURCE_DEALER_WEEKEND")
        sources.append(None)
        seen_src: set[str] = set()
        for src in sources:
            key = src or ""
            if key in seen_src:
                continue
            seen_src.add(key)
            for idb in id_variants:
                body = {**base, **idb}
                if src:
                    body["candleSourceType"] = src
                    body["candle_source_type"] = src
                bodies.append(body)

        if max_attempts is not None and max_attempts > 0:
            bodies = bodies[: int(max_attempts)]

        for body in bodies:
            try:
                raw = self._marketdata("GetCandles", body, timeout=float(timeout))
                env = _unwrap(
                    raw,
                    [
                        "getCandlesResponse",
                        "get_candles_response",
                    ],
                )
                rows = env.get("candles") or env.get("Candles") or raw.get("candles") or []
                if not isinstance(rows, list):
                    continue
                out: list[dict[str, Any]] = []
                for row in rows:
                    if not isinstance(row, dict):
                        continue
                    close = quotation_to_float(row.get("close") or row.get("Close"))
                    if close is None or close <= 0:
                        continue
                    ts = row.get("time") or row.get("Time")
                    out.append(
                        {
                            "time": ts,
                            "open": quotation_to_float(row.get("open") or row.get("Open")),
                            "high": quotation_to_float(row.get("high") or row.get("High")),
                            "low": quotation_to_float(row.get("low") or row.get("Low")),
                            "close": close,
                            "volume": row.get("volume") or row.get("Volume"),
                            "is_complete": row.get("isComplete", row.get("is_complete")),
                            "candle_source": row.get("candleSource")
                            or row.get("candle_source")
                            or row.get("candleSourceType"),
                        }
                    )
                if out:
                    return out
                if accept_empty:
                    return []
                last = RuntimeError("GetCandles: пустой список")
            except Exception as exc:
                last = exc
        raise RuntimeError(str(last) if last else "GetCandles failed")

    def get_accounts(self) -> list[dict[str, str]]:
        if self.mode == "sandbox":
            root = self._sandbox("GetSandboxAccounts", {})
            rows = self._parse_accounts(root)
            if not rows:
                root = self._sandbox("GetSandboxAccounts", {"status": "ACCOUNT_STATUS_ALL"})
                rows = self._parse_accounts(root)
            return rows
        root = self._prod_users("GetAccounts", {})
        rows = self._parse_accounts(root)
        if not rows:
            root = self._prod_users("GetAccounts", {"status": "ACCOUNT_STATUS_ALL"})
            rows = self._parse_accounts(root)
        return rows

    def _parse_accounts(self, root: dict[str, Any]) -> list[dict[str, str]]:
        env = _unwrap(root, ["getAccountsResponse", "get_accounts_response"])
        arr = env.get("accounts") or env.get("Accounts") or []
        out: list[dict[str, str]] = []
        for o in arr:
            if not isinstance(o, dict):
                continue
            aid = o.get("id") or o.get("accountId") or o.get("account_id")
            if not aid:
                continue
            name = o.get("name") or o.get("Name") or ""
            out.append({"id": str(aid), "name": str(name)})
        return out

    def get_portfolio(self, account_id: str, *, timeout: float = 30.0) -> dict[str, Any]:
        """SandboxService/GetSandboxPortfolio or OperationsService/GetPortfolio."""
        from live.constants import PROD_HOST_TBANK, PROD_HOST_TINKOFF

        bodies = [
            {"accountId": account_id.strip()},
            {"account_id": account_id.strip()},
            {"accountId": account_id.strip(), "currency": "RUB"},
            {"account_id": account_id.strip(), "currency": "RUB"},
        ]
        # Desk path passes a short timeout — don't multiply it by 4 body variants.
        if float(timeout) <= 10.0:
            bodies = bodies[:2]
        ops = "tinkoff.public.invest.api.contract.v1.OperationsService"
        prod_ops = [f"{PROD_HOST_TBANK}/{ops}", f"{PROD_HOST_TINKOFF}/{ops}"]
        last: Exception | None = None
        for body in bodies:
            try:
                if self.mode == "sandbox":
                    raw = self._sandbox("GetSandboxPortfolio", body, timeout=float(timeout))
                else:
                    try:
                        raw = _post_raw(
                            prod_ops, self.token, "GetPortfolio", body, timeout=float(timeout)
                        )
                    except Exception:
                        raw = self._prod_orders(
                            "GetPortfolio", body, timeout=float(timeout)
                        )
                return _unwrap(
                    raw,
                    [
                        "getSandboxPortfolioResponse",
                        "get_sandbox_portfolio_response",
                        "getPortfolioResponse",
                        "get_portfolio_response",
                        "portfolio",
                        "Portfolio",
                    ],
                )
            except Exception as exc:
                last = exc
        raise RuntimeError(str(last) if last else "GetPortfolio failed")

    def portfolio_cash_rub(self, portfolio: dict[str, Any]) -> float | None:
        q = _find_nested_money(portfolio, ["totalAmountCurrencies", "total_amount_currencies"])
        return quotation_to_float(q)

    def portfolio_total_rub(self, portfolio: dict[str, Any]) -> float | None:
        q = _find_nested_money(portfolio, ["totalAmountPortfolio", "total_amount_portfolio"])
        return quotation_to_float(q)

    def _portfolio_positions(self, portfolio: dict[str, Any]) -> list[dict[str, Any]]:
        for key in ("positions", "Positions", "portfolioPositions", "portfolio_positions"):
            arr = portfolio.get(key)
            if isinstance(arr, list):
                return [p for p in arr if isinstance(p, dict)]
        return []

    def _position_signed_qty(self, pos: dict[str, Any]) -> float:
        """Штуки (quantity); отрицательное = шорт."""
        for key in ("quantity", "Quantity", "balance", "Balance"):
            v = pos.get(key)
            if isinstance(v, dict):
                q = quotation_to_float(v)
                if q is not None:
                    return q
            if isinstance(v, (int, float)):
                return float(v)
        for key in ("quantityLots", "quantity_lots", "QuantityLots"):
            v = pos.get(key)
            if isinstance(v, dict):
                q = quotation_to_float(v)
                if q is not None:
                    return q
            if isinstance(v, (int, float)):
                return float(v)
        return 0.0

    def _position_matches(self, pos: dict[str, Any], ticker: str, instrument_id: str) -> bool:
        want = ticker.strip().upper()
        t = str(pos.get("ticker") or pos.get("Ticker") or "").strip().upper()
        if t == want:
            return True
        ids = {
            str(pos.get("figi") or ""),
            str(pos.get("FIGI") or ""),
            str(pos.get("instrumentUid") or ""),
            str(pos.get("instrument_uid") or ""),
            str(pos.get("uid") or ""),
            str(pos.get("instrumentId") or ""),
            str(pos.get("instrument_id") or ""),
        }
        return bool(instrument_id) and instrument_id in ids

    def detect_spread_position(self, portfolio: dict[str, Any]) -> dict[str, Any] | None:
        """
        Ищет спред TATN/TATNP на брокере.
        LONG = long TATN + short TATNP; SHORT = long TATNP + short TATN.
        """
        try:
            tatn_id = self.resolve_instrument_id("TATN")
        except Exception:
            tatn_id = TATN_FALLBACK_ID
        try:
            tatnp_id = self.resolve_instrument_id("TATNP")
        except Exception:
            tatnp_id = TATNP_FALLBACK_ID

        qty_n = 0.0
        qty_np = 0.0
        for pos in self._portfolio_positions(portfolio):
            if self._position_matches(pos, "TATN", tatn_id):
                qty_n = self._position_signed_qty(pos)
            elif self._position_matches(pos, "TATNP", tatnp_id):
                qty_np = self._position_signed_qty(pos)

        # lot size 1 для TATN/TATNP TQBR
        lots_n = int(round(abs(qty_n)))
        lots_np = int(round(abs(qty_np)))
        if lots_n < 1 or lots_np < 1:
            return None

        if qty_n > 0 and qty_np < 0:
            direction = "LONG"
            signal = "ENTER_LONG"
        elif qty_n < 0 and qty_np > 0:
            direction = "SHORT"
            signal = "ENTER_SHORT"
        else:
            return None

        lots = max(1, min(lots_n, lots_np))
        return {
            "direction": direction,
            "entry_signal": signal,
            "quantity_lots": lots,
            "qty_tatn": qty_n,
            "qty_tatnp": qty_np,
        }

    def get_margin_attributes(self, account_id: str) -> dict[str, float] | None:
        if self.mode != "prod":
            return None
        last: Exception | None = None
        for body in ({"accountId": account_id}, {"account_id": account_id}):
            try:
                raw = self._prod_users("GetMarginAttributes", body)
                env = _unwrap(
                    raw,
                    [
                        "getMarginAttributesResponse",
                        "get_margin_attributes_response",
                        "marginAttributes",
                        "margin_attributes",
                    ],
                )
                liquid_q = _find_nested_money(env, ["liquidPortfolio", "liquid_portfolio"])
                corrected_q = _find_nested_money(env, ["correctedMargin", "corrected_margin"])
                starting_q = _find_nested_money(env, ["startingMargin", "starting_margin"])
                liquid = quotation_to_float(liquid_q)
                corrected = quotation_to_float(corrected_q)
                starting = quotation_to_float(starting_q)
                if liquid is None:
                    return None
                return {
                    "liquid_portfolio_rub": liquid,
                    "corrected_margin_rub": corrected if corrected is not None else (starting or 0.0),
                    "starting_margin_rub": starting if starting is not None else (corrected or 0.0),
                }
            except Exception as exc:
                last = exc
        if last:
            return None
        return None

    def resolve_instrument_id(self, ticker: str) -> str:
        want = ticker.strip().upper()

        def parse(root: dict[str, Any]) -> list[dict[str, Any]]:
            arr = root.get("instruments") or root.get("Instruments") or []
            matches = []
            for raw in arr:
                if not isinstance(raw, dict):
                    continue
                o = raw.get("instrument") if isinstance(raw.get("instrument"), dict) else raw
                t = str(o.get("ticker") or o.get("Ticker") or "").strip().upper()
                if t == want:
                    matches.append(o)
            return matches

        def pick_id(o: dict[str, Any]) -> str | None:
            for k in ("instrumentUid", "instrument_uid", "uid", "figi", "FIGI"):
                v = o.get(k)
                if v:
                    return str(v)
            return None

        roots = []
        post = self._sbx_instr if self.mode == "sandbox" else self._prod_instr
        roots.append(
            post(
                "FindInstrument",
                {
                    "query": want,
                    "instrumentKind": "INSTRUMENT_TYPE_SHARE",
                    "apiTradeAvailableFlag": True,
                },
            )
        )
        try:
            roots.append(
                post(
                    "FindInstrument",
                    {"query": want, "instrumentKind": "INSTRUMENT_TYPE_UNSPECIFIED"},
                )
            )
        except Exception:
            pass
        all_m: list[dict[str, Any]] = []
        seen: set[str] = set()
        for r in roots:
            for m in parse(r):
                key = str(m)
                if key not in seen:
                    seen.add(key)
                    all_m.append(m)
        if not all_m:
            raise RuntimeError(f"FindInstrument: тикер {want} не найден")
        tqbr = next(
            (
                m
                for m in all_m
                if str(m.get("classCode") or m.get("class_code") or "").upper() == "TQBR"
            ),
            None,
        )
        chosen = tqbr or all_m[0]
        iid = pick_id(chosen)
        if not iid:
            raise RuntimeError(f"FindInstrument: нет uid/figi для {want}")
        return iid

    def _order_bodies(
        self, account_id: str, instrument_id: str, direction: str, quantity_lots: int
    ) -> list[dict[str, Any]]:
        oid = str(uuid.uuid4())
        price = {"units": "0", "nano": 0}
        qty = str(quantity_lots)
        snake = {
            "account_id": account_id,
            "instrument_id": instrument_id,
            "quantity": qty,
            "direction": direction,
            "order_direction": direction,
            "order_type": "ORDER_TYPE_MARKET",
            "order_id": oid,
            "price": price,
            "time_in_force": "TIME_IN_FORCE_DAY",
            "confirm_margin_trade": True,
        }
        camel = {
            "accountId": account_id,
            "instrumentId": instrument_id,
            "quantity": qty,
            "direction": direction,
            "orderDirection": direction,
            "orderType": "ORDER_TYPE_MARKET",
            "orderId": oid,
            "price": price,
            "timeInForce": "TIME_IN_FORCE_DAY",
            "confirmMarginTrade": True,
        }
        snake_np = {k: v for k, v in snake.items() if k != "price"}
        camel_np = {k: v for k, v in camel.items() if k != "price"}
        bodies = [snake, camel, snake_np, camel_np]
        snake_bp = {**snake_np, "order_type": "ORDER_TYPE_BESTPRICE", "order_id": str(uuid.uuid4())}
        camel_bp = {**camel_np, "orderType": "ORDER_TYPE_BESTPRICE", "orderId": str(uuid.uuid4())}
        bodies.extend([snake_bp, camel_bp])
        if instrument_id.upper().startswith("BBG"):
            for base in (snake, camel, snake_np, camel_np):
                b = dict(base)
                b.pop("instrument_id", None)
                b.pop("instrumentId", None)
                b["figi"] = instrument_id
                b["order_id" if "order_id" in b else "orderId"] = str(uuid.uuid4())
                bodies.append(b)
        return bodies

    def post_market_order(
        self, account_id: str, instrument_id: str, direction: str, quantity_lots: int = 1
    ) -> dict[str, Any]:
        last: Exception | None = None
        for body in self._order_bodies(account_id, instrument_id, direction, quantity_lots):
            try:
                if self.mode == "sandbox":
                    return self._sandbox("PostSandboxOrder", body)
                return self._prod_orders("PostOrder", body)
            except Exception as exc:
                last = exc
        raise RuntimeError(str(last) if last else "PostOrder failed")

    def execute_spread_entry(
        self, account_id: str, signal: str, quantity_lots: int = 1
    ) -> list[dict[str, Any]]:
        qty = max(1, quantity_lots)
        try:
            tatn_id = self.resolve_instrument_id("TATN")
        except Exception:
            tatn_id = TATN_FALLBACK_ID
        try:
            tatnp_id = self.resolve_instrument_id("TATNP")
        except Exception:
            tatnp_id = TATNP_FALLBACK_ID
        buy, sell = "ORDER_DIRECTION_BUY", "ORDER_DIRECTION_SELL"

        def leg(ticker: str, iid: str, direction: str, buy_leg: bool) -> dict[str, Any]:
            order = self.post_market_order(account_id, iid, direction, qty)
            pf = None
            try:
                pf = self.get_portfolio(account_id)
            except Exception:
                pass
            return {
                "ticker": ticker,
                "side": "buy" if buy_leg else "sell",
                "side_ru": f"{'покупка' if buy_leg else 'продажа'} {qty} лот",
                "order": order,
                "portfolio_cash_rub": self.portfolio_cash_rub(pf) if pf else None,
                "portfolio_total_rub": self.portfolio_total_rub(pf) if pf else None,
            }

        if signal in ("ENTER_LONG", "EnterLong"):
            return [
                leg("TATN", tatn_id, buy, True),
                leg("TATNP", tatnp_id, sell, False),
            ]
        if signal in ("ENTER_SHORT", "EnterShort"):
            return [
                leg("TATNP", tatnp_id, buy, True),
                leg("TATN", tatn_id, sell, False),
            ]
        raise RuntimeError("Только ENTER_LONG / ENTER_SHORT")

    def execute_spread_exit(
        self, account_id: str, opened_with: str, quantity_lots: int = 1
    ) -> list[dict[str, Any]]:
        qty = max(1, quantity_lots)
        try:
            tatn_id = self.resolve_instrument_id("TATN")
        except Exception:
            tatn_id = TATN_FALLBACK_ID
        try:
            tatnp_id = self.resolve_instrument_id("TATNP")
        except Exception:
            tatnp_id = TATNP_FALLBACK_ID
        buy, sell = "ORDER_DIRECTION_BUY", "ORDER_DIRECTION_SELL"

        def leg(ticker: str, iid: str, direction: str, buy_leg: bool) -> dict[str, Any]:
            order = self.post_market_order(account_id, iid, direction, qty)
            pf = None
            try:
                pf = self.get_portfolio(account_id)
            except Exception:
                pass
            return {
                "ticker": ticker,
                "side": "buy" if buy_leg else "sell",
                "side_ru": f"{'покупка' if buy_leg else 'продажа'} {qty} лот",
                "order": order,
                "portfolio_cash_rub": self.portfolio_cash_rub(pf) if pf else None,
                "portfolio_total_rub": self.portfolio_total_rub(pf) if pf else None,
            }

        if opened_with in ("ENTER_LONG", "EnterLong", "LONG"):
            return [
                leg("TATN", tatn_id, sell, False),
                leg("TATNP", tatnp_id, buy, True),
            ]
        if opened_with in ("ENTER_SHORT", "EnterShort", "SHORT"):
            return [
                leg("TATNP", tatnp_id, sell, False),
                leg("TATN", tatn_id, buy, True),
            ]
        raise RuntimeError("Укажите тип входа ENTER_LONG или ENTER_SHORT")
