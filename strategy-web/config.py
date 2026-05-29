from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEFAULT_M15 = ROOT / "data" / "m15_tatn_255d.csv"
UPLOADED_CSV = ROOT / "data" / "_uploaded.csv"

PRESETS = {
    "Свои": None,
    "Как Android": {
        "entry": 0.8,
        "exit_z": 0.7,
        "notional": 100_000.0,
        "leverage": 7.0,
        "commission": 0.04,
        "compound": False,
    },
    "Консервативный": {
        "entry": 1.2,
        "exit_z": 0.5,
        "notional": 100_000.0,
        "leverage": 5.0,
        "commission": 0.04,
        "compound": False,
    },
    "Агрессивный": {
        "entry": 0.6,
        "exit_z": 0.5,
        "notional": 100_000.0,
        "leverage": 10.0,
        "commission": 0.04,
        "compound": True,
    },
}

SESSION_DEFAULTS = {
    "inp_entry": 0.8,
    "inp_exit_z": 0.7,
    "inp_notional": 100_000.0,
    "inp_leverage": 7.0,
    "inp_commission": 0.04,
    "inp_compound": False,
    "inp_z_mode": "rolling30",
    "preset_sel": "Свои",
    "data_ready": False,
}
