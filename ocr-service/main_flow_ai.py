"""AI handwriting → base catalogue enrich (no dual-OCR)."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
os.environ.setdefault(
    "PARCHI_CATALOGUE_DB",
    str(ROOT / "catalogue_output" / "open" / "national_retail_catalogue_open.sqlite"),
)
# Lexical catalogue match is enough after Gemini; skip BGE rerank cost/latency.
os.environ.setdefault("PARCHI_AI_CATALOGUE", "0")


def process_shopping_list_ai(
    image: str | Path,
    *,
    role: str = "customer",
    selections_by_row: dict[int, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    from ai_handwriting import read_handwritten_list
    from app import _catalogue_enrich_row

    read = read_handwritten_list(image, role=role)
    selections_by_row = selections_by_row or {}
    rows: list[dict[str, Any]] = []
    for index, row in enumerate(read.get("rows") or []):
        enriched = _catalogue_enrich_row(
            dict(row),
            selections_by_row.get(index) or selections_by_row.get(index + 1) or {},
        )
        rows.append(enriched)

    return {
        "rows": rows,
        "mode": "ai-handwriting",
        "engine": read.get("model"),
        "pipeline": ["gemini_flash_lite", "base_catalogue"],
        "ai_usage": read.get("usage") or {},
        "message": f"AI handwriting via {read.get('model')}",
    }
