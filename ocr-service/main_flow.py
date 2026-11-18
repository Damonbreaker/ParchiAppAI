"""Stable integration API for the OCR -> catalogue -> final-list flow."""

from __future__ import annotations

import io
import os
from pathlib import Path
from typing import Any

from PIL import Image

ROOT = Path(__file__).resolve().parent
os.environ.setdefault(
    "PARCHI_CATALOGUE_DB",
    str(
        ROOT
        / "catalogue_output"
        / "open"
        / "national_retail_catalogue_open.sqlite"
    ),
)
os.environ.setdefault("PARCHI_AI_CATALOGUE", "1")
os.environ.setdefault("PARCHI_NUMERIC_OCR", "1")
os.environ.setdefault("PARCHI_NUMERIC_OCR_DEVICE", "auto")


def _image(value: Image.Image | bytes | bytearray | str | Path) -> Image.Image:
    if isinstance(value, Image.Image):
        return value
    if isinstance(value, (bytes, bytearray)):
        return Image.open(io.BytesIO(value))
    return Image.open(Path(value))


def process_shopping_list(
    image: Image.Image | bytes | bytearray | str | Path,
    *,
    mode: str = "legacy",
    selections_by_row: dict[int, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Return catalogue-validated rows suitable for the shopkeeper flow.

    Invalid OCR strings are never emitted as final products. If no catalogue
    match is safe, the row item is ``Needs confirmation`` and the raw OCR is
    retained in ``ocr_item`` for review.

    Only the legacy dual-OCR pipeline is supported (realtime-fast removed).
    """
    from app import _catalogue_enrich_row

    if mode.strip().lower() not in {"", "legacy"}:
        raise ValueError("Only legacy OCR is supported (realtime-fast removed)")

    from layout_ocr import extract_list

    result = extract_list(_image(image))

    selections_by_row = selections_by_row or {}
    rows: list[dict[str, Any]] = []
    total = 0.0
    for index, row in enumerate(result.get("rows") or []):
        enriched = _catalogue_enrich_row(
            row,
            selections_by_row.get(index) or selections_by_row.get(index + 1) or {},
        )
        try:
            if enriched.get("price"):
                enriched["line_total"] = float(enriched["price"])
                total += float(enriched["price"])
        except (TypeError, ValueError):
            pass
        rows.append(enriched)

    numeric = result.get("numeric_ocr") or {}
    message = result.get("message") or "Done."
    changed = int(numeric.get("changed") or 0)
    attempted = int(numeric.get("attempted") or 0)
    if numeric.get("loaded") and attempted:
        message = (
            f"{message} Dual OCR corrected {changed}/{attempted} amount box(es)."
        ).strip()

    return {
        "rows": rows,
        "bill_estimate": round(total, 2),
        "mode": mode,
        "engine": result.get("engine") or mode,
        "pipeline": [
            "layout_ocr",
            "numeric_ocr",
            "grocery_correct",
            "catalogue_match",
            "ai_rerank",
        ],
        "numeric_ocr": numeric,
        "message": message,
    }


def warm_complete_ocr() -> dict[str, Any]:
    """Preload layout OCR, numeric OCR, catalogue, and AI reranker."""
    status: dict[str, Any] = {"ok": True}
    try:
        from layout_ocr import get_engine

        get_engine()
        status["layout_ocr"] = {"ready": True}
    except Exception as exc:
        status["layout_ocr"] = {"ready": False, "error": str(exc)}
    try:
        from numeric_ocr import warm_numeric_ocr

        status["numeric_ocr"] = warm_numeric_ocr()
    except Exception as exc:
        status["numeric_ocr"] = {"enabled": False, "error": str(exc)}
    try:
        from catalogue_ai import warm_ai

        status["catalogue"] = catalogue_status()
        status["catalogue_ai"] = warm_ai()
    except Exception as exc:
        status["catalogue"] = {"available": False, "error": str(exc)}
    return status


def catalogue_status() -> dict[str, Any]:
    from catalogue_runtime import get_catalogue

    return get_catalogue().status()

