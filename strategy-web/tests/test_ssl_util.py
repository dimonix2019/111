"""Unit tests for T‑Invest SSL helpers."""

from __future__ import annotations

import ssl
from pathlib import Path

import pytest

from live import ssl_util


def test_format_ssl_hint_short_ru():
    msg = ssl_util.format_tinvest_error(
        ssl.SSLCertVerificationError(
            "certificate verify failed: self-signed certificate in certificate chain"
        )
    )
    assert msg.startswith("SSL: цепочка сертификатов")
    assert "Russian Trusted" in msg or "certifi" in msg
    assert "traceback" not in msg.lower()
    assert len(msg) < 400


def test_is_ssl_cert_error_detects_urllib_style():
    exc = RuntimeError(
        "HTTPSConnectionPool(host='invest-public-api.tinkoff.ru', port=443): "
        "Max retries exceeded (Caused by SSLError(SSLCertVerificationError("
        "'certificate verify failed: self-signed certificate in certificate chain')))"
    )
    assert ssl_util.is_ssl_cert_error(exc)


def test_resolve_verify_respects_env(monkeypatch):
    monkeypatch.setenv("MOEX_SSL_VERIFY", "0")
    monkeypatch.delenv("REQUESTS_CA_BUNDLE", raising=False)
    monkeypatch.delenv("SSL_CERT_FILE", raising=False)
    monkeypatch.delenv("MOEX_CA_BUNDLE", raising=False)
    with pytest.warns(RuntimeWarning, match="SSL verify DISABLED"):
        assert ssl_util.resolve_requests_verify() is False


def test_resolve_verify_ca_bundle(monkeypatch, tmp_path):
    monkeypatch.setenv("MOEX_SSL_VERIFY", "1")
    pem = tmp_path / "ca.pem"
    pem.write_text("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n")
    monkeypatch.setenv("MOEX_CA_BUNDLE", str(pem))
    assert ssl_util.resolve_requests_verify() == str(pem)


def test_default_bundle_includes_russian_trusted(monkeypatch, tmp_path):
    monkeypatch.setenv("MOEX_SSL_VERIFY", "1")
    for key in ("MOEX_CA_BUNDLE", "REQUESTS_CA_BUNDLE", "SSL_CERT_FILE", "CURL_CA_BUNDLE"):
        monkeypatch.delenv(key, raising=False)
    # Force cache rebuild into tmp
    ssl_util._COMBINED_CACHE = None
    path = ssl_util.ensure_default_ca_bundle()
    text = Path(path).read_text(encoding="ascii", errors="replace")
    assert "BEGIN CERTIFICATE" in text
    assert Path(path).stat().st_size > 100_000  # certifi + RU CAs
    # Spot-check: combined verify works against T‑Invest host (network).
    import requests

    r = requests.get("https://invest-public-api.tinkoff.ru/", timeout=15, verify=path)
    assert r.status_code in (200, 404, 401, 405)
