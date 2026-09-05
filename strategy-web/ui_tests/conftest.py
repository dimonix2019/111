"""Pytest + Playwright: headless Chromium for desk UI tests."""
from __future__ import annotations

import pytest


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line("markers", "ui: web-desk Playwright tests")
