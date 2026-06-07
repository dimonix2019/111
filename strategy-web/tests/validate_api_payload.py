import json
import urllib.request

body = json.dumps({"csv_path": "data/m15_tatn_255d.csv", "auto_download": False, "entry": 0.8, "exit_z": 0.7}).encode()
sim = json.loads(
    urllib.request.urlopen(
        urllib.request.Request(
            "http://127.0.0.1:8766/api/simulate",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        ),
        timeout=60,
    ).read()
)
p = sim["packs"][0]
checks = [
    ("trades_count", len(p["trades"]) == 140),
    ("market_metrics", len(p["market_context"]["metrics"]) == 4),
    ("trade_fields", all(k in p["trades"][0] for k in ["no", "direction", "entry_spread", "entry_z", "exit_z", "pnl_rub"])),
    ("equity_time_int", isinstance(p["equity"][0]["time"], int)),
]
for m in p["market_context"]["metrics"]:
    checks.append((f"hist_{m['id']}", len(m["histogram"]) > 0))

for name, ok in checks:
    print("OK" if ok else "FAIL", name)
