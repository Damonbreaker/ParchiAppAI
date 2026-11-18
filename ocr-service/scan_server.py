#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ParchiAppAI OCR — cheap Gemini Flash-Lite handwriting + kirana base catalogue."""

from __future__ import annotations

import logging
import os
import re
import tempfile
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("parchi-ai-ocr")

ROOT = Path(__file__).resolve().parent
os.chdir(ROOT)

os.environ.setdefault(
    "PARCHI_CATALOGUE_DB",
    str(ROOT / "catalogue_output" / "open" / "national_retail_catalogue_open.sqlite"),
)
# After Gemini reads the page, lexical catalogue match is enough (cheaper/faster).
os.environ.setdefault("PARCHI_AI_CATALOGUE", "0")
os.environ.setdefault("PARCHI_AI_OCR_MODEL", "gemini-2.0-flash-lite")

from main_flow_ai import process_shopping_list_ai  # noqa: E402

app = FastAPI(title="ParchiAppAI OCR", version="2.0.0-ai")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


def _price_payload(price: Any) -> dict[str, Any] | None:
    if not isinstance(price, dict) or price.get("amount") is None:
        return None
    try:
        amount = float(price.get("amount"))
    except (TypeError, ValueError):
        return None
    out = {
        "amount": amount,
        "currency": price.get("currency") or "INR",
        "label": price.get("label") or ("MRP" if price.get("price_type") == "mrp" else "Price estimate"),
        "price_type": price.get("price_type") or "",
        "estimate": bool(price.get("estimate", True)),
    }
    for key in ("amount_min", "amount_max", "variant_count"):
        raw = price.get(key)
        if raw is None or raw == "":
            continue
        try:
            out[key] = float(raw) if key != "variant_count" else int(raw)
        except (TypeError, ValueError):
            continue
    return out


def _ocr_line_price(row: dict[str, Any]) -> float | None:
    raw = row.get("price")
    if raw is None or raw == "":
        return None
    if isinstance(raw, (int, float)):
        return float(raw) if float(raw) > 0 else None
    text = str(raw).strip().replace(",", "").replace("₹", "").replace("Rs", "").replace("rs", "")
    m = re.search(r"(\d+(?:\.\d+)?)", text)
    if not m:
        return None
    try:
        val = float(m.group(1))
    except ValueError:
        return None
    return val if val > 0 else None


def _public_row(row: dict[str, Any], *, role: str = "customer") -> dict[str, Any]:
    guidance = row.get("catalogue_guidance") or {}
    selection = guidance.get("selection") or row.get("catalogue_selection") or {}
    options = guidance.get("options") or {}
    selected_sku = guidance.get("selected_sku") or {}
    mode = str(guidance.get("mode") or selection.get("mode") or row.get("catalogue_mode") or "simple")
    shop = role.strip().lower() in {"shop", "shopkeeper", "store"}

    base_name = str(
        guidance.get("base_name")
        or selection.get("base_name")
        or row.get("base_name")
        or ""
    ).strip()
    name = str(row.get("item") or "").strip()
    ocr_item = str(row.get("ocr_item") or "").strip()
    if name.lower() == "needs confirmation":
        name = ocr_item or name
    if mode != "advanced" and base_name:
        name = base_name

    qty = str(row.get("quantity") or "").strip()
    conf = 0.8
    level = str(guidance.get("confidence_level") or row.get("catalogue_confidence") or "").lower()
    if level in {"low", "medium"} or guidance.get("review_recommended"):
        conf = 0.5
    if str(row.get("item") or "").strip().lower() == "needs confirmation":
        conf = 0.4

    mrp = _price_payload(guidance.get("price") or row.get("catalogue_price") or selected_sku.get("price"))
    ocr_price = _ocr_line_price(row) if shop else None

    return {
        "name": name,
        "base_name": base_name or name,
        "ocr_item": ocr_item,
        "quantity": qty,
        "confidence": conf,
        "mode": mode,
        "role": "shop" if shop else "customer",
        "catalogue_id": (selection.get("catalogue_id") or "") if mode == "advanced" else "",
        "brand": (selection.get("brand") or "") if mode == "advanced" else "",
        "department": (selection.get("department") or "") if mode == "advanced" else "",
        "category": (selection.get("category") or "") if mode == "advanced" else "",
        "type": (selection.get("type") or "") if mode == "advanced" else "",
        "pack": (selection.get("pack") or "") if mode == "advanced" else "",
        "unit_hint": selection.get("unit") or "",
        "mrp": mrp,
        "price": ocr_price if ocr_price is not None else (mrp.get("amount") if shop and mrp else None),
        "options": {
            "base_names": options.get("base_names") or ([base_name] if base_name else []),
            "departments": options.get("departments") or [],
            "categories": options.get("categories") or [],
            "types": options.get("types") or [],
            "brands": options.get("brands") or [],
            "products": options.get("products") or [],
            "packs": options.get("packs") or [],
            "units": options.get("units") or [],
        },
        "selection": selection,
    }


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "pipeline": "ai-handwriting",
        "model": os.environ.get("PARCHI_AI_OCR_MODEL", "gemini-2.0-flash-lite"),
        "catalogue": "base+detail",
    }


@app.post("/scan")
async def scan(
    file: UploadFile = File(...),
    role: str = "customer",
) -> dict[str, Any]:
    name = (file.filename or "").lower()
    ctype = (file.content_type or "").lower()
    if ctype and not ctype.startswith("image/"):
        if not any(name.endswith(ext) for ext in (".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tif", ".tiff")):
            raise HTTPException(status_code=400, detail="Upload an image file")

    suffix = Path(file.filename or "parchi.jpg").suffix or ".jpg"
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="Empty image")

    role_norm = (role or "customer").strip().lower()
    tmp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
            tmp.write(raw)
            tmp_path = Path(tmp.name)

        log.info("AI scan start bytes=%s name=%s role=%s", len(raw), file.filename, role_norm)
        result = process_shopping_list_ai(str(tmp_path), role=role_norm)
        rows = []
        for r in result.get("rows") or []:
            pub = _public_row(r, role=role_norm)
            if pub.get("name"):
                rows.append(pub)
        lines = []
        for row in rows:
            text = row["name"]
            if row.get("quantity"):
                text = f'{row["name"]} {row["quantity"]}'.strip()
            if role_norm in {"shop", "shopkeeper", "store"} and row.get("price") is not None:
                text = f'{text} @ {row["price"]}'.strip()
            lines.append({"text": text, "confidence": row.get("confidence") or 0.8})
        log.info("AI scan done rows=%s model=%s usage=%s", len(rows), result.get("engine"), result.get("ai_usage"))
        return {
            "rows": rows,
            "lines": lines,
            "pipeline": "ai-handwriting",
            "model": result.get("engine"),
            "ai_usage": result.get("ai_usage"),
            "catalogue": "base+detail",
            "role": "shop" if role_norm in {"shop", "shopkeeper", "store"} else "customer",
        }
    except Exception as exc:  # noqa: BLE001
        log.exception("AI scan failed")
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    finally:
        if tmp_path is not None:
            try:
                tmp_path.unlink(missing_ok=True)
            except OSError:
                pass


@app.post("/catalogue/suggest")
async def catalogue_suggest(payload: dict[str, Any]) -> dict[str, Any]:
    from catalogue_runtime import suggest_row

    selections = dict(payload.get("selections") or {})
    if payload.get("advanced"):
        selections["advanced"] = True
    return suggest_row(
        str(payload.get("item") or ""),
        str(payload.get("quantity") or ""),
        selections,
        limit=max(1, min(int(payload.get("limit") or 10), 25)),
    )


@app.post("/catalogue/resolve")
async def catalogue_resolve(payload: dict[str, Any]) -> dict[str, Any]:
    from app import _catalogue_enrich_row

    row = dict(payload.get("row") or payload)
    selections = dict(payload.get("selections") or {})
    if payload.get("advanced") or selections.get("brand") or selections.get("catalogue_id"):
        selections["advanced"] = True
    if "item" not in row and row.get("name"):
        row["item"] = row["name"]
    enriched = _catalogue_enrich_row(row, selections)
    role = str(payload.get("role") or "customer")
    return _public_row(enriched, role=role)


if __name__ == "__main__":
    import uvicorn

    host = os.environ.get("OCR_HOST", "127.0.0.1")
    port = int(os.environ.get("OCR_PORT", "7871"))
    uvicorn.run(app, host=host, port=port)
