"""T‑Invest REST client (Sandbox + Prod) — port of TinkoffSandboxApi."""

from __future__ import annotations

import json
import re
import uuid
from typing import Any

import requests

from live.constants import (
    PROD_INSTRUMENTS_PREFIXES,
    PROD_ORDERS_PREFIXES,
    PROD_USERS_PREFIXES,
    SANDBOX_INSTRUMENTS_PREFIXES,
    SANDBOX_PREFIXES,
    TATN_FALLBACK_ID,
    TATNP_FALLBACK_ID,
    USER_AGENT,
)

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


def _post_raw(prefixes: list[str], token: str, method: str, body: dict[str, Any]) -> dict[str, Any]:
    norm = normalize_token(token)
    if not norm:
        raise RuntimeError(
            "Пустой или недопустимый токен API. Вставьте токен из кабинета Т‑Инвест."
        )
    last: Exception | None = None
    for prefix in prefixes:
        url = f"{prefix}/{method}"
        try:
            resp = requests.post(
                url,
                json=body,
                headers={
                    "Authorization": f"Bearer {norm}",
                    "Accept": "application/json",
                    "Content-Type": "application/json; charset=utf-8",
                    "User-Agent": USER_AGENT,
                },
                timeout=30,
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
        except requests.RequestException as exc:
            last = exc
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

    def _sandbox(self, method: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        return _post_raw(SANDBOX_PREFIXES, self.token, method, body or {})

    def _sbx_instr(self, method: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        return _post_raw(SANDBOX_INSTRUMENTS_PREFIXES, self.token, method, body or {})

    def _prod_orders(self, method: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        return _post_raw(PROD_ORDERS_PREFIXES, self.token, method, body or {})

    def _prod_instr(self, method: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        return _post_raw(PROD_INSTRUMENTS_PREFIXES, self.token, method, body or {})

    def _prod_users(self, method: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        return _post_raw(PROD_USERS_PREFIXES, self.token, method, body or {})

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

    def get_portfolio(self, account_id: str) -> dict[str, Any]:
        """SandboxService/GetSandboxPortfolio or OperationsService/GetPortfolio."""
        from live.constants import PROD_HOST_TBANK, PROD_HOST_TINKOFF

        bodies = [
            {"accountId": account_id.strip()},
            {"account_id": account_id.strip()},
            {"accountId": account_id.strip(), "currency": "RUB"},
            {"account_id": account_id.strip(), "currency": "RUB"},
        ]
        ops = "tinkoff.public.invest.api.contract.v1.OperationsService"
        prod_ops = [f"{PROD_HOST_TBANK}/{ops}", f"{PROD_HOST_TINKOFF}/{ops}"]
        last: Exception | None = None
        for body in bodies:
            try:
                if self.mode == "sandbox":
                    raw = self._sandbox("GetSandboxPortfolio", body)
                else:
                    try:
                        raw = _post_raw(prod_ops, self.token, "GetPortfolio", body)
                    except Exception:
                        raw = self._prod_orders("GetPortfolio", body)
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
            return {
                "ticker": ticker,
                "side": "buy" if buy_leg else "sell",
                "side_ru": f"{'покупка' if buy_leg else 'продажа'} {qty} лот",
                "order": order,
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
