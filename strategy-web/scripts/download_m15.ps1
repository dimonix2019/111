# Полная выгрузка 255д с MOEX ISS (надёжнее кнопки Streamlit — 2–5 мин)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$Py = Join-Path (Split-Path -Parent $Root) ".venv\Scripts\python.exe"
if (-not (Test-Path $Py)) { $Py = "python" }
& $Py scripts\export_m15_iss.py --days 255 --out data\m15_tatn_255d.csv
Write-Host "Готово. Проверьте строки в data\m15_tatn_255d.csv (нужно ~13000+)."
