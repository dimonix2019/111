#!/usr/bin/env python3
"""
Добавляет на новый лист сводку в стиле сводной таблицы (через pandas).
Нативную интерактивную «Сводную таблицу» Excel из Python надёжно не создать;
см. https://openpyxl.readthedocs.io/en/stable/pivot.html

Примеры:
  python scripts/excel_add_pivot_sheet.py data.xlsx -o out.xlsx \\
    --index Регион --columns Квартал --values Сумма

  python scripts/excel_add_pivot_sheet.py data.xlsx \\
    --index col1 col2 --values amount --aggfunc sum
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


def main() -> int:
    p = argparse.ArgumentParser(description="Добавить лист со сводной (pandas) в xlsx")
    p.add_argument("input", type=Path, help="Входной .xlsx")
    p.add_argument("-o", "--output", type=Path, help="Выходной .xlsx (по умолчанию: перезапись input)")
    p.add_argument("--sheet", default=0, help="Лист: имя или индекс (по умолчанию 0)")
    p.add_argument("--pivot-sheet", default="Сводная", help="Имя нового листа")
    p.add_argument("--index", nargs="+", required=True, help="Столбцы в строках")
    p.add_argument("--columns", nargs="*", default=None, help="Столбцы в колонках сводной")
    p.add_argument("--values", required=True, help="Столбец с числами для агрегации")
    p.add_argument(
        "--aggfunc",
        default="sum",
        choices=("sum", "mean", "count", "min", "max", "median"),
        help="Функция агрегации",
    )
    args = p.parse_args()
    cols_arg = args.columns if args.columns else None

    inp: Path = args.input
    if not inp.is_file():
        print(f"Файл не найден: {inp}", file=sys.stderr)
        return 1

    out: Path = args.output or inp

    try:
        import pandas as pd
    except ImportError:
        print("Установите зависимости: pip install -r requirements-excel.txt", file=sys.stderr)
        return 1

    sheet = int(args.sheet) if str(args.sheet).isdigit() else args.sheet
    df = pd.read_excel(inp, sheet_name=sheet)
    for c in args.index:
        if c not in df.columns:
            print(f"Нет столбца: {c}", file=sys.stderr)
            return 1
    if cols_arg:
        for c in cols_arg:
            if c not in df.columns:
                print(f"Нет столбца: {c}", file=sys.stderr)
                return 1
    if args.values not in df.columns:
        print(f"Нет столбца values: {args.values}", file=sys.stderr)
        return 1

    agg = args.aggfunc
    pivot = pd.pivot_table(
        df,
        index=args.index,
        columns=cols_arg,
        values=args.values,
        aggfunc=agg,
        margins=False,
    )

    pivot_name = str(args.pivot_sheet)[:31]
    same_file = out.resolve() == inp.resolve()

    if same_file:
        with pd.ExcelWriter(
            out, engine="openpyxl", mode="a", if_sheet_exists="replace"
        ) as writer:
            pivot.to_excel(writer, sheet_name=pivot_name)
    else:
        sheets = pd.read_excel(inp, sheet_name=None)
        with pd.ExcelWriter(out, engine="openpyxl", mode="w") as writer:
            for name, sheet_df in sheets.items():
                safe = str(name)[:31]
                sheet_df.to_excel(writer, sheet_name=safe, index=False)
            pivot.to_excel(writer, sheet_name=pivot_name)

    print(f"Готово: {out} (лист «{args.pivot_sheet}»)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
