"""SSL helpers for T‑Invest HTTPS (Russian Trusted CA + optional corp MITM)."""

from __future__ import annotations

import os
import ssl
import warnings
from pathlib import Path
from typing import Any

_SSL_FALSE = frozenset({"0", "false", "no", "off"})
_SSL_TRUE = frozenset({"1", "true", "yes", "on"})

_CERTS_DIR = Path(__file__).resolve().parent / "certs"
_COMBINED_CACHE: Path | None = None


def _env_flag(name: str) -> bool | None:
    raw = (os.environ.get(name) or "").strip().lower()
    if not raw:
        return None
    if raw in _SSL_FALSE:
        return False
    if raw in _SSL_TRUE:
        return True
    return None


def ssl_verify_enabled() -> bool:
    """
    Default True. Escape hatch (explicit):
      MOEX_SSL_VERIFY=0|false  or  TINVEST_SSL_VERIFY=0|false
      live_settings key ssl_verify=0
    """
    for key in ("MOEX_SSL_VERIFY", "TINVEST_SSL_VERIFY"):
        flag = _env_flag(key)
        if flag is not None:
            return flag
    try:
        from live import store

        raw = (store.get_setting("ssl_verify", "1") or "1").strip().lower()
        if raw in _SSL_FALSE:
            return False
    except Exception:
        pass
    return True


def ca_bundle_path() -> str | None:
    for key in ("MOEX_CA_BUNDLE", "REQUESTS_CA_BUNDLE", "SSL_CERT_FILE", "CURL_CA_BUNDLE"):
        p = (os.environ.get(key) or "").strip().strip('"')
        if p and os.path.isfile(p):
            return p
    try:
        from live import store

        p = (store.get_setting("ssl_ca_bundle", "") or "").strip()
        if p and os.path.isfile(p):
            return p
    except Exception:
        pass
    return None


def _bundled_russian_ca_pems() -> list[Path]:
    names = (
        "russian_trusted_ca.pem",
        "russian_trusted_root_ca.pem",
        "russian_trusted_sub_ca.pem",
    )
    out: list[Path] = []
    seen: set[str] = set()
    for name in names:
        p = _CERTS_DIR / name
        if not p.is_file():
            continue
        key = str(p.resolve())
        if key in seen:
            continue
        seen.add(key)
        out.append(p)
    return out


def ensure_default_ca_bundle() -> str:
    """
    certifi + vendored Russian Trusted Root/Sub CA (T‑Invest / Минцифры).
    Mozilla cacert.pem does not include Russian Trusted Root — OpenSSL then
    reports «self-signed certificate in certificate chain».
    """
    global _COMBINED_CACHE
    if _COMBINED_CACHE is not None and _COMBINED_CACHE.is_file():
        return str(_COMBINED_CACHE)

    import certifi

    parts: list[bytes] = [Path(certifi.where()).read_bytes()]
    for p in _bundled_russian_ca_pems():
        parts.append(b"\n" + p.read_bytes())

    # Prefer writable data/ next to strategy-web; fall back to temp.
    candidates = [
        Path(__file__).resolve().parents[1] / "data" / "ca_bundle_certifi_ru.pem",
        Path(os.environ.get("TEMP") or os.environ.get("TMP") or ".")
        / "moex_ca_bundle_certifi_ru.pem",
    ]
    blob = b"".join(parts)
    last_err: Exception | None = None
    for path in candidates:
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.is_file() and path.read_bytes() == blob:
                _COMBINED_CACHE = path
                return str(path)
            path.write_bytes(blob)
            _COMBINED_CACHE = path
            return str(path)
        except Exception as exc:
            last_err = exc
    if last_err:
        raise last_err
    raise RuntimeError("cannot write CA bundle")


def resolve_requests_verify() -> bool | str:
    """Value for requests ``verify=`` (False | CA path)."""
    if not ssl_verify_enabled():
        warnings.warn(
            "T‑Invest SSL verify DISABLED (MOEX_SSL_VERIFY/ssl_verify=false) — MITM risk",
            RuntimeWarning,
            stacklevel=2,
        )
        return False
    bundle = ca_bundle_path()
    if bundle:
        return bundle
    try:
        return ensure_default_ca_bundle()
    except Exception:
        # Last resort: stock certifi (will fail on Russian Trusted hosts).
        try:
            import certifi

            return certifi.where()
        except Exception:
            return True


def is_ssl_cert_error(exc: BaseException) -> bool:
    text = str(exc).lower()
    needles = (
        "certificate verify failed",
        "sslcertverificationerror",
        "self-signed certificate",
        "ssl: certificate_verify_failed",
        "certificate_verify_failed",
        "crypt_e_no_revocation",
        "sslerror",
    )
    if any(n in text for n in needles):
        return True
    try:
        import requests

        if isinstance(exc, requests.exceptions.SSLError):
            return True
    except Exception:
        pass
    return isinstance(exc, ssl.SSLError)


def format_ssl_hint(exc: BaseException | None = None) -> str:
    return (
        "SSL: цепочка сертификатов — T‑Invest на Russian Trusted CA "
        "(нет в Mozilla/certifi) или антивирус/прокси MITM. "
        "Уже пробуем live/certs; иначе REQUESTS_CA_BUNDLE / "
        "исключение API из HTTPS-проверки Kaspersky / MOEX_SSL_VERIFY=0"
    )


def format_tinvest_error(exc: BaseException) -> str:
    if is_ssl_cert_error(exc):
        return format_ssl_hint(exc)
    msg = str(exc).strip().replace("\n", " ")
    if len(msg) > 280:
        msg = msg[:277] + "…"
    return msg


def requests_post_verified(
    url: str,
    *,
    headers: dict[str, str],
    json_body: dict[str, Any],
    timeout: float,
    verify: bool | str,
) -> Any:
    """requests.post with resolved verify path."""
    import requests

    return requests.post(
        url, json=json_body, headers=headers, timeout=timeout, verify=verify
    )
