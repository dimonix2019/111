"""Прокси к LM Studio (OpenAI-compatible API)."""

from __future__ import annotations

import os
from typing import Any, Optional

import requests
from fastapi import HTTPException

LM_STUDIO_BASE = os.environ.get("LM_STUDIO_BASE", "http://127.0.0.1:1234/v1").rstrip("/")
LM_STUDIO_MODEL = os.environ.get("LM_STUDIO_MODEL", "gemma-4-26b-a4b-it")
LLM_TIMEOUT = int(os.environ.get("LLM_TIMEOUT", "120"))


def llm_status() -> dict[str, Any]:
    try:
        r = requests.get(f"{LM_STUDIO_BASE}/models", timeout=5)
        r.raise_for_status()
        data = r.json()
        models = [m.get("id") for m in data.get("data", []) if m.get("id")]
        return {
            "ok": True,
            "base_url": LM_STUDIO_BASE,
            "model": LM_STUDIO_MODEL,
            "models": models,
        }
    except Exception as exc:
        return {
            "ok": False,
            "base_url": LM_STUDIO_BASE,
            "model": LM_STUDIO_MODEL,
            "error": str(exc),
        }


def llm_chat(
    messages: list[dict[str, str]],
    *,
    temperature: float = 0.4,
    max_tokens: int = 800,
    model: Optional[str] = None,
) -> dict[str, str]:
    use_model = model or LM_STUDIO_MODEL
    try:
        r = requests.post(
            f"{LM_STUDIO_BASE}/chat/completions",
            json={
                "model": use_model,
                "messages": messages,
                "temperature": temperature,
                "max_tokens": max_tokens,
            },
            timeout=LLM_TIMEOUT,
        )
        r.raise_for_status()
        data = r.json()
        content = data["choices"][0]["message"]["content"]
        return {"content": str(content).strip(), "model": use_model}
    except requests.exceptions.Timeout as exc:
        raise HTTPException(
            status_code=504,
            detail=f"Таймаут LM Studio ({LLM_TIMEOUT} с). Модель слишком долго отвечает.",
        ) from exc
    except requests.exceptions.ConnectionError as exc:
        raise HTTPException(
            status_code=503,
            detail="LM Studio недоступен. Запустите Local Server (http://127.0.0.1:1234).",
        ) from exc
    except (KeyError, IndexError, TypeError) as exc:
        raise HTTPException(status_code=502, detail=f"Неверный ответ LM Studio: {exc}") from exc
    except requests.HTTPError as exc:
        detail = exc.response.text[:500] if exc.response is not None else str(exc)
        raise HTTPException(status_code=502, detail=f"LM Studio error: {detail}") from exc
    except Exception as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
