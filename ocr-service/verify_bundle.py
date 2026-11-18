"""Offline structural verification for the Parchi OCR service bundle."""

from __future__ import annotations

import json
import os
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DB = (
    ROOT
    / "catalogue_output"
    / "open"
    / "national_retail_catalogue_open.sqlite"
)


def verify() -> dict:
    required = [
        "app.py",
        "main_flow.py",
        "layout_ocr.py",
        "numeric_ocr.py",
        "grocery_correct.py",
        "grocery_lexicon.json",
        "catalogue_runtime.py",
        "catalogue_ai.py",
        "catalogue_prices.py",
        "data/catalogue_prices.json",
        "scan_server.py",
        "run_ocr.sh",
        "catalogue_output/open/national_retail_catalogue_open.sqlite",
    ]
    missing = [name for name in required if not (ROOT / name).is_file()]
    if missing:
        raise RuntimeError(f"Missing required bundle files: {missing}")
    if (ROOT / "realtime_ocr.py").is_file():
        raise RuntimeError("realtime_ocr.py must be removed (legacy-only service)")

    with sqlite3.connect(DB) as connection:
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
        expected_tables = {"master", "aliases", "prices", "listings"}
        if not expected_tables.issubset(tables):
            raise RuntimeError(
                f"Catalogue tables missing: {sorted(expected_tables - tables)}"
            )
        sku_count = int(connection.execute("SELECT COUNT(*) FROM master").fetchone()[0])
    if sku_count < 1000:
        raise RuntimeError(f"Catalogue unexpectedly small: {sku_count}")

    previous_ai = os.environ.get("PARCHI_AI_CATALOGUE")
    previous_db = os.environ.get("PARCHI_CATALOGUE_DB")
    try:
        os.environ["PARCHI_AI_CATALOGUE"] = "0"
        os.environ["PARCHI_CATALOGUE_DB"] = str(DB)
        from catalogue_runtime import CatalogueRuntime

        result = CatalogueRuntime(DB).suggest("mulligein atfer", "5 kg")
    finally:
        if previous_ai is None:
            os.environ.pop("PARCHI_AI_CATALOGUE", None)
        else:
            os.environ["PARCHI_AI_CATALOGUE"] = previous_ai
        if previous_db is None:
            os.environ.pop("PARCHI_CATALOGUE_DB", None)
        else:
            os.environ["PARCHI_CATALOGUE_DB"] = previous_db
    if not result.get("matched") or "atta" not in str(
        (result.get("selected_sku") or {}).get("name", "")
    ).lower():
        raise RuntimeError("Catalogue spelling-correction smoke test failed")

    return {
        "ok": True,
        "pipeline": "legacy",
        "sku_count": sku_count,
        "smoke_product": result["selected_sku"]["name"],
    }


if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
