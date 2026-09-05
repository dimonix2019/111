"""Правило «ситуация 3D» (бывш. закрытие 3D).

На конец дня 3 (held == 3, три ночи овернайта уже в MTM), если позиция открыта:
1. MTM ≥ 0 → держать до ТП / уровня
2. MTM < 0 и (до выхода > 1.0 п.п. ИЛИ спред ушёл против входа ≥ 0.5 п.п.)
   → сигнал плохой ситуации («закрывать» в старой терминологии)
3. MTM < 0, но до выхода ≤ 1.0 и против < 0.5 → ждать 1–2 дня;
   к дню 5 при всё ещё минусе → сигнал ситуации

Режимы галочки:
- ``indicator``: не резать открытую сделку; показать индикатор;
  запретить новые входы базы/добора/экстра той же стороны, пока ситуация не снята.
- ``force`` (по умолчанию): force-close открытой позиции; после среза тоже
  держим блок повторного входа, пока спред не вернётся к зоне штатного входа.

Снятие ситуации: спред снова в зоне, где возможен штатный вход
(Long ≤ enter_narrow, Short ≥ enter_wide) — не до уровня выхода старой сделки.
Prod/Test AUTO: Long 3.2/4.0 · Short 6.1/5.8.

Против: Long — спред упал от входа; Short — спред вырос от входа.
Дистанция до выхода: Long = exit − current; Short = current − exit.
"""

from __future__ import annotations

from typing import Any, Literal

Decision = Literal["none", "hold", "close", "wait"]
Mode = Literal["indicator", "force"]

DAY_REVIEW = 3
DAY_FORCE = 5
NEAR_EXIT_PP = 1.0
ADVERSE_PP = 0.5

MODE_INDICATOR: Mode = "indicator"
MODE_FORCE: Mode = "force"

_MINUS = "\u2212"


def normalize_mode(raw: Any) -> Mode:
    """force | indicator; пустое / неизвестное → force (резать + блок)."""
    s = str(raw or "").strip().lower()
    if s in ("indicator", "monitor", "block", "индикатор", "блок"):
        return MODE_INDICATOR
    return MODE_FORCE


def _fmt_num(v: float, digits: int = 2) -> str:
    s = f"{float(v):.{digits}f}".replace(".", ",")
    return s


def _fmt_signed(v: float, digits: int = 2) -> str:
    x = round(float(v), digits)
    if abs(x) < 5e-4 * (10 ** (2 - digits)):
        return f"+{_fmt_num(0.0, digits)}"
    sign = _MINUS if x < 0 else "+"
    return f"{sign}{_fmt_num(abs(x), digits)}"


def _norm_side(side: str | None) -> str | None:
    s = str(side or "").strip().lower()
    if s.startswith("l"):
        return "Long"
    if s.startswith("s"):
        return "Short"
    return None


def dist_to_exit_pp(*, side: str, current_spread: float, exit_level: float) -> float:
    """П.п. до уровня выхода (>0 — ещё не дошли)."""
    s = str(side or "").strip().lower()
    cur = float(current_spread)
    ex = float(exit_level)
    if s.startswith("l"):
        return ex - cur
    return cur - ex


def adverse_from_entry_pp(*, side: str, entry_spread: float, current_spread: float) -> float:
    """Насколько спред ушёл против входа (п.п., ≥0 = против)."""
    s = str(side or "").strip().lower()
    es = float(entry_spread)
    cs = float(current_spread)
    if s.startswith("l"):
        return es - cs
    return cs - es


def decide_force_close_3d(
    *,
    hold_days: int,
    side: str,
    entry_spread: float,
    current_spread: float,
    exit_level: float,
    mtm: float,
    near_exit_pp: float = NEAR_EXIT_PP,
    adverse_pp: float = ADVERSE_PP,
    day_review: int = DAY_REVIEW,
    day_force: int = DAY_FORCE,
) -> dict[str, Any]:
    """Чистая функция: день / сторона / спреды / MTM → решение + причина.

    Возвращает dict:
      decision: none|hold|close|wait
      label: короткая русская метка
      reason: пояснение с цифрами
      hold_days, mtm, dist_exit_pp, adverse_pp
    """
    try:
        held = int(hold_days)
    except (TypeError, ValueError):
        held = -1
    try:
        mtm_v = float(mtm)
        es = float(entry_spread)
        cs = float(current_spread)
        ex = float(exit_level)
    except (TypeError, ValueError):
        return {
            "decision": "none",
            "label": "—",
            "reason": "нет данных",
            "hold_days": held if held >= 0 else None,
            "mtm": None,
            "dist_exit_pp": None,
            "adverse_pp": None,
        }

    dist = dist_to_exit_pp(side=side, current_spread=cs, exit_level=ex)
    adv = adverse_from_entry_pp(side=side, entry_spread=es, current_spread=cs)
    # Δ от входа: плюс = в пользу позиции (Long: current−entry; Short: entry−current).
    toward = -adv
    bits = (
        f"день {held} · MTM {_fmt_signed(mtm_v, 0)} ₽ · "
        f"Δ до выхода {_fmt_num(dist)} · Δ от входа {_fmt_signed(toward)}"
    )

    if held < int(day_review):
        return {
            "decision": "none",
            "label": "—",
            "reason": bits,
            "hold_days": held,
            "mtm": round(mtm_v, 2),
            "dist_exit_pp": round(dist, 4),
            "adverse_pp": round(adv, 4),
        }

    if mtm_v >= 0:
        return {
            "decision": "hold",
            "label": "держать",
            "reason": f"MTM≥0 · {bits}",
            "hold_days": held,
            "mtm": round(mtm_v, 2),
            "dist_exit_pp": round(dist, 4),
            "adverse_pp": round(adv, 4),
        }

    near = float(near_exit_pp)
    adv_lim = float(adverse_pp)
    far_or_adverse = dist > near or adv >= adv_lim

    if held >= int(day_force):
        return {
            "decision": "close",
            "label": "плохая ситуация",
            "reason": f"день≥{int(day_force)}, MTM<0 · {bits}",
            "hold_days": held,
            "mtm": round(mtm_v, 2),
            "dist_exit_pp": round(dist, 4),
            "adverse_pp": round(adv, 4),
        }

    # дни 3–4
    if far_or_adverse:
        why = []
        if dist > near:
            why.append(f"до выхода>{_fmt_num(near)}")
        if adv >= adv_lim:
            why.append(f"против≥{_fmt_num(adv_lim)}")
        return {
            "decision": "close",
            "label": "плохая ситуация",
            "reason": f"{' · '.join(why)} · {bits}",
            "hold_days": held,
            "mtm": round(mtm_v, 2),
            "dist_exit_pp": round(dist, 4),
            "adverse_pp": round(adv, 4),
        }

    return {
        "decision": "wait",
        "label": "ждём 1–2 дня",
        "reason": f"близко к выходу и без сильного против · {bits}",
        "hold_days": held,
        "mtm": round(mtm_v, 2),
        "dist_exit_pp": round(dist, 4),
        "adverse_pp": round(adv, 4),
    }


def should_force_close_3d(
    *,
    hold_days: int,
    side: str,
    entry_spread: float,
    current_spread: float,
    exit_level: float,
    mtm: float,
    mode: Any = MODE_FORCE,
    **kwargs: Any,
) -> bool:
    """True → force-close сейчас (только режим ``force``)."""
    if normalize_mode(mode) != MODE_FORCE:
        return False
    d = decide_force_close_3d(
        hold_days=hold_days,
        side=side,
        entry_spread=entry_spread,
        current_spread=current_spread,
        exit_level=exit_level,
        mtm=mtm,
        **kwargs,
    )
    return d.get("decision") == "close"


def situation_cleared(
    *,
    side: str,
    current_spread: float,
    entry_level: float,
    toward_entry: bool = True,
) -> bool:
    """Ситуация снята: спред снова у зоны штатного входа (или лучше).

    По умолчанию (toward_entry=True):
      Long ≤ entry_level (Prod 3.2), Short ≥ entry_level (Prod 6.1).

    toward_entry=False — старое сравнение с уровнем выхода
    (Long ≥ level, Short ≤ level); только для бэктеста.
    """
    ns = _norm_side(side)
    if ns is None:
        return False
    try:
        cur = float(current_spread)
        lv = float(entry_level)
    except (TypeError, ValueError):
        return False
    if toward_entry:
        if ns == "Long":
            return cur <= lv
        return cur >= lv
    if ns == "Long":
        return cur >= lv
    return cur <= lv


def entry_blocked_by_situation(
    *,
    active: bool,
    situation_side: str | None,
    want_side: str | None,
) -> bool:
    """Блок входа той же стороны, пока ситуация активна."""
    if not active:
        return False
    ss = _norm_side(situation_side)
    ws = _norm_side(want_side)
    if ss is None or ws is None:
        return False
    return ss == ws


def activate_situation(
    *,
    side: str,
    decision_row: dict[str, Any] | None = None,
    current_spread: float | None = None,
    exit_level: float | None = None,
    entry_level: float | None = None,
    mode: Any = MODE_FORCE,
) -> dict[str, Any]:
    """Собрать payload активной ситуации для API / сима."""
    ns = _norm_side(side) or str(side or "")
    row = dict(decision_row or {})
    md = normalize_mode(mode)
    if entry_level is not None:
        clear_hint = (
            f"блок до возврата к входу {_fmt_num(float(entry_level))}"
            + (
                " (Long)"
                if ns == "Long"
                else " (Short)"
                if ns == "Short"
                else ""
            )
        )
    else:
        clear_hint = "блок до возврата к уровню входа"
    return {
        "active": True,
        "side": ns,
        "mode": md,
        "decision": row.get("decision") or "close",
        "label": row.get("label") or "плохая ситуация",
        "reason": row.get("reason") or "сигнал 3D",
        "hold_days": row.get("hold_days"),
        "mtm": row.get("mtm"),
        "dist_exit_pp": row.get("dist_exit_pp"),
        "adverse_pp": row.get("adverse_pp"),
        "trigger_spread": (
            round(float(current_spread), 4) if current_spread is not None else None
        ),
        "exit_level": (
            round(float(exit_level), 4) if exit_level is not None else None
        ),
        "entry_level": (
            round(float(entry_level), 4) if entry_level is not None else None
        ),
        "entry_blocked": True,
        "clear_hint": clear_hint,
        "action": (
            "мониторим · позицию не режем · новые входы этой стороны запрещены"
            if md == MODE_INDICATOR
            else "force-close · новые входы этой стороны запрещены до возврата к входу"
        ),
    }


def step_situation(
    state: dict[str, Any] | None,
    *,
    enabled: bool,
    mode: Any = MODE_FORCE,
    flat: bool,
    open_side: str | None,
    hold_days: int | None,
    entry_spread: float | None,
    current_spread: float | None,
    exit_level: float | None,
    entry_level: float | None = None,
    mtm: float | None,
    toward_entry_clear: bool = True,
) -> tuple[dict[str, Any] | None, bool]:
    """Обновить состояние ситуации на баре.

    Returns:
      (new_state_or_None, should_force_close_now)
    """
    if not enabled:
        return None, False
    md = normalize_mode(mode)
    st = dict(state) if isinstance(state, dict) and state.get("active") else None

    # Снятие: спред снова у зоны штатного входа (не у цели выхода).
    if st is not None and current_spread is not None:
        clr = st.get("entry_level")
        if clr is None:
            clr = entry_level
        # Старый JSON мог хранить только exit_level — не снимать по нему
        # при toward_entry_clear (иначе рано откроем до возврата к входу).
        if clr is None and not toward_entry_clear:
            clr = st.get("exit_level")
            if clr is None:
                clr = exit_level
        if clr is not None and situation_cleared(
            side=str(st.get("side") or ""),
            current_spread=float(current_spread),
            entry_level=float(clr),
            toward_entry=toward_entry_clear,
        ):
            return None, False

    force_now = False
    # Триггер по открытой позиции.
    if (
        not flat
        and open_side
        and hold_days is not None
        and entry_spread is not None
        and current_spread is not None
        and exit_level is not None
        and mtm is not None
    ):
        d = decide_force_close_3d(
            hold_days=int(hold_days),
            side=str(open_side),
            entry_spread=float(entry_spread),
            current_spread=float(current_spread),
            exit_level=float(exit_level),
            mtm=float(mtm),
        )
        if d.get("decision") == "close":
            if toward_entry_clear:
                clr_store = (
                    float(entry_level) if entry_level is not None else None
                )
            else:
                clr_store = (
                    float(exit_level) if exit_level is not None else None
                )
            st = activate_situation(
                side=str(open_side),
                decision_row=d,
                current_spread=float(current_spread),
                exit_level=float(exit_level),
                entry_level=clr_store,
                mode=md,
            )
            force_now = md == MODE_FORCE

    return st, force_now


def payload_for_open(
    *,
    enabled: bool,
    hold_days: int | None,
    side: str | None,
    entry_spread: float | None,
    current_spread: float | None,
    exit_level: float | None,
    entry_level: float | None = None,
    mtm: float | None,
    mode: Any = MODE_FORCE,
    situation: dict[str, Any] | None = None,
) -> dict[str, Any] | None:
    """Блок для API / Ситуации / плашки позиции. None если галочка выкл."""
    if not enabled:
        return None
    md = normalize_mode(mode)

    # Активная ситуация (в т.ч. flat + блок входа).
    if isinstance(situation, dict) and situation.get("active"):
        out = dict(situation)
        out["enabled"] = True
        out["mode"] = md
        clr = out.get("entry_level")
        if clr is None:
            clr = entry_level
        if current_spread is not None and clr is not None:
            cleared = situation_cleared(
                side=str(out.get("side") or ""),
                current_spread=float(current_spread),
                entry_level=float(clr),
            )
            if cleared:
                return {
                    "enabled": True,
                    "mode": md,
                    "active": False,
                    "decision": "clear",
                    "label": "ситуация снята",
                    "reason": "спред у зоны входа — можно снова входить по правилам",
                    "entry_blocked": False,
                    "clear_hint": "вход разрешён",
                    "action": "можно входить",
                }
        out["entry_blocked"] = True
        # Подтянуть подсказку, если в старом state не было entry_level.
        if out.get("entry_level") is None and entry_level is not None:
            out["entry_level"] = round(float(entry_level), 4)
            out["clear_hint"] = (
                f"блок до возврата к входу {_fmt_num(float(entry_level))}"
            )
        return out

    if hold_days is None or hold_days < DAY_REVIEW:
        return {
            "enabled": True,
            "mode": md,
            "active": False,
            "decision": "none",
            "label": "—",
            "reason": "рано для решения 3D" if hold_days is not None else "нет открытой / рано",
            "hold_days": hold_days,
            "entry_blocked": False,
        }
    if (
        side is None
        or entry_spread is None
        or current_spread is None
        or exit_level is None
        or mtm is None
    ):
        return {
            "enabled": True,
            "mode": md,
            "active": False,
            "decision": "none",
            "label": "—",
            "reason": "не хватает данных для решения 3D",
            "hold_days": hold_days,
            "entry_blocked": False,
        }
    d = decide_force_close_3d(
        hold_days=int(hold_days),
        side=str(side),
        entry_spread=float(entry_spread),
        current_spread=float(current_spread),
        exit_level=float(exit_level),
        mtm=float(mtm),
    )
    d["enabled"] = True
    d["mode"] = md
    d["active"] = d.get("decision") == "close"
    d["entry_blocked"] = bool(d["active"])
    if d["active"]:
        act = activate_situation(
            side=str(side),
            decision_row=d,
            current_spread=float(current_spread),
            exit_level=float(exit_level),
            entry_level=(
                float(entry_level) if entry_level is not None else None
            ),
            mode=md,
        )
        d.update({k: act[k] for k in (
            "side", "clear_hint", "action", "trigger_spread",
            "exit_level", "entry_level", "label",
        )})
    else:
        d["action"] = (
            "индикатор · позицию не режем"
            if md == MODE_INDICATOR
            else "force при сигнале close"
        )
        d["clear_hint"] = None
    return d


def situation_state_from_settings(raw: str | None) -> dict[str, Any] | None:
    """Разбор JSON-состояния из store."""
    import json

    s = (raw or "").strip()
    if not s:
        return None
    try:
        obj = json.loads(s)
    except (TypeError, ValueError, json.JSONDecodeError):
        return None
    if not isinstance(obj, dict) or not obj.get("active"):
        return None
    return obj


def situation_state_to_json(state: dict[str, Any] | None) -> str:
    import json

    if not state or not state.get("active"):
        return ""
    keep = {
        k: state.get(k)
        for k in (
            "active",
            "side",
            "mode",
            "decision",
            "label",
            "reason",
            "hold_days",
            "mtm",
            "dist_exit_pp",
            "adverse_pp",
            "trigger_spread",
            "exit_level",
            "entry_level",
            "entry_blocked",
            "clear_hint",
            "action",
        )
    }
    return json.dumps(keep, ensure_ascii=False)
