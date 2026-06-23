"""Restore strategy-web files from Cursor agent transcript (last Write/StrReplace wins)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

TRANSCRIPT = Path(
    r"C:\Users\Lenovo\.cursor\projects\c-Users-Lenovo-Documents-strategy-web"
    r"\agent-transcripts\b88d9f89-f339-444d-8034-561cfa79e631"
    r"\b88d9f89-f339-444d-8034-561cfa79e631.jsonl"
)
ROOT = Path(__file__).resolve().parents[1]


def norm(path: str) -> str | None:
    p = path.replace("\\", "/")
    low = p.lower()
    for marker in ("/strategy-web/", "strategy-web/"):
        idx = low.find(marker.lower())
        if idx >= 0:
            p = p[idx + len(marker) :]
            break
    p = p.lstrip("./")
    if not p or p.startswith("..") or p.startswith("app/"):
        return None
    return p


def main() -> int:
    if not TRANSCRIPT.is_file():
        print(f"Transcript not found: {TRANSCRIPT}", file=sys.stderr)
        return 1

    files: dict[str, str] = {}
    with TRANSCRIPT.open(encoding="utf-8") as fh:
        for line in fh:
            if "tool_use" not in line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            content = obj.get("message", {}).get("content", [])
            if not isinstance(content, list):
                continue
            for part in content:
                if not isinstance(part, dict) or part.get("type") != "tool_use":
                    continue
                name = part.get("name")
                inp = part.get("input") or {}
                if not isinstance(inp, dict):
                    continue
                rel = norm(str(inp.get("path") or ""))
                if not rel:
                    continue
                if name == "Write" and "contents" in inp:
                    files[rel] = str(inp["contents"])
                elif name == "StrReplace" and rel in files:
                    old = inp.get("old_string")
                    new = inp.get("new_string")
                    if old is None or new is None:
                        continue
                    if inp.get("replace_all"):
                        files[rel] = files[rel].replace(str(old), str(new))
                    elif str(old) in files[rel]:
                        files[rel] = files[rel].replace(str(old), str(new), 1)

    for rel, text in sorted(files.items()):
        target = ROOT / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8", newline="\n")

    print(f"restored {len(files)} files -> {ROOT}")
    for key in sorted(files):
        if any(
            key.endswith(s)
            for s in ("run_tester.bat", "stop_tester.bat", "charts.py")
        ) or key.startswith(("api/", "frontend/", "tests/test_")):
            print(f"  {key}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
