# -*- coding: utf-8 -*-
"""Parchi list OCR — legacy dual-OCR only (layout + TrOCR numeric)."""

from __future__ import annotations

import io
import json
import os
import re
import time
from pathlib import Path

from fastapi import FastAPI, File, Query, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image

ROOT = Path(__file__).resolve().parent
RUNS = ROOT / "runs"
RUNS.mkdir(exist_ok=True)
os.environ.setdefault("PARCHI_AI_CATALOGUE", "1")
os.environ.setdefault("PARCHI_AI_DEVICE", "auto")
os.environ.setdefault("PARCHI_NUMERIC_OCR", "1")
os.environ.setdefault("PARCHI_NUMERIC_OCR_DEVICE", "auto")

app = FastAPI(title="Parchi List OCR", version="1.0-legacy")
app.mount("/static", StaticFiles(directory=ROOT / "static"), name="static")


def _proxy_hygiene() -> None:
    for key in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY"):
        value = os.environ.get(key)
        if value and "://" not in value:
            os.environ[key] = "http://" + value
    for key in ("no_proxy", "NO_PROXY"):
        cur = os.environ.get(key, "")
        extra = "localhost,127.0.0.1,0.0.0.0,::1"
        os.environ[key] = extra if not cur else f"{cur},{extra}"


@app.on_event("startup")
def warm() -> None:
    _proxy_hygiene()
    try:
        from layout_ocr import get_engine

        get_engine()
        print("legacy layout OCR warm: ready", flush=True)
    except Exception as exc:
        print("legacy layout OCR warm skipped:", exc, flush=True)
    try:
        from catalogue_ai import warm_ai
        from catalogue_runtime import get_catalogue

        print("catalogue warm:", get_catalogue().status(), flush=True)
        print("catalogue AI warm:", warm_ai(), flush=True)
    except Exception as exc:
        print("catalogue warm skipped:", exc, flush=True)
    try:
        from numeric_ocr import warm_numeric_ocr

        print("numeric OCR warm:", warm_numeric_ocr(), flush=True)
    except Exception as exc:
        print("numeric OCR warm skipped:", exc, flush=True)


@app.get("/")
def index():
    return FileResponse(ROOT / "static" / "index.html")


@app.get("/api/health")
def health():
    device = os.environ.get("PARCHI_DEVICE", "auto")
    result = {
        "ok": True,
        "default_mode": "legacy",
        "device": device,
        "trocr": os.environ.get("PARCHI_TROCR", "1"),
    }
    try:
        from catalogue_runtime import get_catalogue

        result["catalogue"] = get_catalogue().status()
    except Exception as exc:
        result["catalogue"] = {"available": False, "error": str(exc)}
    try:
        from numeric_ocr import numeric_ocr_status

        result["numeric_ocr"] = numeric_ocr_status()
    except Exception as exc:
        result["numeric_ocr"] = {"available": False, "error": str(exc)}
    return result


def _catalogue_enrich_row(row: dict, selections: dict | None = None) -> dict:
    """Attach a real SKU choice while preserving all raw OCR evidence."""
    from catalogue_runtime import suggest_row

    out = dict(row)
    correction = out.get("correction") or {}
    item_for_match = str(out.get("item") or "")
    raw_item = str(correction.get("item_raw") or "")
    if raw_item and raw_item.lower() != item_for_match.lower():
        item_for_match = f"{item_for_match} {raw_item}".strip()
    amount_hint = str(out.get("unassigned_amount") or "").strip()
    quantity_for_match = str(out.get("quantity") or "").strip() or amount_hint
    guidance = suggest_row(
        item_for_match,
        quantity_for_match,
        selections or out.get("catalogue_selection") or {},
    )
    out["catalogue_guidance"] = guidance
    out["catalogue_selection"] = guidance.get("selection") or {}
    if not guidance.get("matched"):
        unresolved = str(out.get("item") or "").strip()
        if unresolved:
            out["ocr_item"] = out.get("ocr_item") or unresolved
            out["item"] = "Needs confirmation"
            out["catalogue_match_required"] = True
            out["catalogue_validated"] = False
        return out

    if amount_hint and out.get("price_filled_from_catalogue"):
        out["baseline_estimate_before_amount_resolution"] = out.get("price")
        out["price"] = ""
        out["line_total"] = None
        out["price_filled_from_catalogue"] = False

    selected = guidance.get("selected_sku") or {}
    selection = guidance.get("selection") or {}
    # Default display = kirana base name (Atta). Detailed SKU name only in advanced mode.
    base_name = str(guidance.get("base_name") or selection.get("base_name") or "").strip()
    selected_name = str(selected.get("name") or "")
    mode = str(guidance.get("mode") or selection.get("mode") or "simple")
    display_name = base_name or selected_name
    if mode == "advanced" and selected_name:
        display_name = selected_name
    if display_name:
        out["ocr_item"] = out.get("ocr_item") or out.get("item") or ""
        out["item"] = display_name
        out["base_name"] = base_name or display_name
        out["catalogue_mode"] = mode
        out["item_corrected_by_catalogue_ai"] = True
        out["catalogue_validated"] = True
        out["catalogue_match_required"] = False
        out["catalogue_review_recommended"] = bool(
            guidance.get("review_recommended")
        )
        out["catalogue_confidence"] = guidance.get("confidence_level") or "high"
        if guidance.get("price"):
            out["catalogue_price"] = guidance.get("price")

    if amount_hint and not out.get("quantity") and not out.get("price"):
        match = re.search(r"\d+(?:[.,]\d+)?", amount_hint)
        amount = float(match.group(0).replace(",", ".")) if match else None
        if amount is not None:
            pack_amount = selected.get("pack_quantity")
            pack_unit = str(
                selected.get("unit")
                or (guidance.get("selection") or {}).get("unit")
                or ""
            )
            quantity_score = 0
            try:
                pack_number = float(pack_amount)
                if abs(pack_number - amount) <= max(0.01, pack_number * 0.02):
                    quantity_score += 6
            except (TypeError, ValueError):
                pass
            if pack_unit and amount in {1, 2, 5, 10, 50, 100, 200, 250, 500, 1000}:
                quantity_score += 2

            price_score = 0
            reference_amounts: list[float] = []
            catalogue_price = guidance.get("price") or {}
            if catalogue_price.get("amount") is not None:
                reference_amounts.append(float(catalogue_price["amount"]))
            try:
                from catalogue_prices import estimate_line

                baseline = estimate_line(selected_name or out.get("item") or "", "", "")
                if baseline.get("estimate") is not None:
                    reference_amounts.append(float(baseline["estimate"]))
            except Exception:
                pass
            for reference in reference_amounts:
                relative_error = abs(amount - reference) / max(reference, 1.0)
                if relative_error <= 0.12:
                    price_score = max(price_score, 6)
                elif relative_error <= 0.35:
                    price_score = max(price_score, 3)

            if quantity_score > price_score and pack_unit:
                shown = str(int(amount)) if amount.is_integer() else f"{amount:g}"
                out["quantity"] = f"{shown} {pack_unit}"
                out["amount_inferred_as"] = "quantity"
            elif price_score > quantity_score:
                out["price"] = str(int(amount)) if amount.is_integer() else f"{amount:g}"
                out["amount_inferred_as"] = "price"
                out["price_from_handwriting"] = True
            else:
                out["amount_needs_choice"] = True

    quantity = guidance.get("quantity") or {}
    if (
        quantity.get("corrected")
        and (
            bool(str(row.get("quantity") or "").strip())
            or out.get("amount_inferred_as") == "quantity"
        )
    ):
        out["ocr_quantity"] = out.get("ocr_quantity") or out.get("quantity") or ""
        out["quantity"] = quantity["corrected"]

    price = guidance.get("price")
    can_fill_price = (
        not out.get("price_manual")
        and not out.get("price_from_handwriting")
        and (
            not str(out.get("price") or "").strip()
            or out.get("price_filled_from_catalogue")
        )
    )
    if price and price.get("amount") is not None and can_fill_price:
        out["ocr_price"] = out.get("ocr_price") or out.get("price") or ""
        amount = float(price["amount"])
        out["price"] = str(int(amount) if amount.is_integer() else amount)
        out["price_type"] = price.get("price_type") or ""
        out["price_label"] = price.get("price_label") or ""
        out["price_source"] = price.get("source") or ""
        out["price_is_mrp"] = bool(price.get("is_mrp"))
        out["price_filled_from_catalogue"] = True
    return out


def _run_ocr(image: Image.Image, mode: str = "legacy") -> dict:
    if (mode or "legacy").strip().lower() not in {"", "legacy", "full", "accurate"}:
        raise ValueError("Only legacy OCR is supported (realtime-fast removed)")
    from layout_ocr import extract_list

    result = extract_list(image)
    result.setdefault("engine", "legacy-dual-ocr")
    return result


@app.post("/api/ocr")
async def ocr(
    file: UploadFile = File(...),
    mode: str = Query("legacy", description="legacy dual-OCR only"),
):
    raw = await file.read()
    if not raw:
        return JSONResponse({"error": "Empty file"}, status_code=400)
    try:
        image = Image.open(io.BytesIO(raw))
    except Exception:
        return JSONResponse({"error": "Could not read that image"}, status_code=400)

    stamp = time.strftime("%Y%m%d_%H%M%S")
    saved = RUNS / f"{stamp}_{Path(file.filename or 'list').name}"
    try:
        image.convert("RGB").save(saved)
    except Exception:
        saved.write_bytes(raw)

    try:
        result = _run_ocr(image, mode)
    except ValueError as exc:
        return JSONResponse({"error": str(exc)}, status_code=400)
    rows = result.get("rows") or []
    result["rows"] = [_catalogue_enrich_row(row) for row in rows]
    result["saved_as"] = saved.name
    result["mode"] = "legacy"
    return result


@app.get("/api/catalogue/status")
def catalogue_status():
    from catalogue_runtime import get_catalogue

    return get_catalogue().status()


@app.post("/api/catalogue/suggest")
async def catalogue_suggest(payload: dict):
    """Return ranked SKU polls and the default final choice for an OCR row."""
    from catalogue_runtime import suggest_row

    return suggest_row(
        str(payload.get("item") or ""),
        str(payload.get("quantity") or ""),
        payload.get("selections") or {},
        limit=max(1, min(int(payload.get("limit") or 10), 25)),
    )


@app.post("/api/catalogue/resolve")
async def catalogue_resolve(payload: dict):
    """Resolve a hierarchy/brand/pack/unit poll choice to one real SKU."""
    row = dict(payload.get("row") or payload)
    return _catalogue_enrich_row(row, payload.get("selections") or {})


@app.post("/api/estimate")
async def estimate(payload: dict):
    """Resolve selected SKUs; use rough estimates only when no SKU price exists."""
    from catalogue_prices import enrich_rows, ensure_price_file

    ensure_price_file()
    rows = payload.get("rows") or []
    resolved = [
        _catalogue_enrich_row(row, row.get("catalogue_selection") or {})
        for row in rows
    ]
    fallback = enrich_rows(
        [row for row in resolved if not (row.get("catalogue_guidance") or {}).get("price")]
    )
    fallback_by_key = {
        (str(row.get("item")), str(row.get("quantity"))): row
        for row in fallback.get("rows") or []
    }
    final_rows = []
    total = 0.0
    for row in resolved:
        guidance = row.get("catalogue_guidance") or {}
        price = guidance.get("price")
        manual_price = None
        if row.get("price_manual"):
            try:
                manual_price = float(row.get("price"))
            except (TypeError, ValueError):
                pass
        if manual_price is not None:
            out = dict(row)
            out["line_total"] = manual_price
            out["total_source"] = "manual_price"
        elif price and price.get("amount") is not None:
            out = dict(row)
            out["line_total"] = float(price["amount"])
            out["total_source"] = price.get("price_type") or "catalogue_price"
        else:
            out = fallback_by_key.get(
                (str(row.get("item")), str(row.get("quantity"))),
                row,
            )
        if out.get("line_total") is not None:
            total += float(out["line_total"])
        final_rows.append(out)
    return {
        "rows": final_rows,
        "bill_estimate": round(total, 2),
        "currency": "INR",
        "price_disclaimer": (
            "Prices are labelled by source semantics (MRP, selling, observed, "
            "or reference). Edit any field before saving."
        ),
    }


@app.post("/api/save")
async def save_edits(payload: dict):
    """Save user corrections - these become fine-tune labels later."""
    stamp = time.strftime("%Y%m%d_%H%M%S")
    path = RUNS / f"edited_{stamp}.json"
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    # Also append to a cumulative labels store for future fine-tuning.
    labels = ROOT / "data" / "corrections.jsonl"
    labels.parent.mkdir(parents=True, exist_ok=True)
    with labels.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps({"ts": stamp, **payload}, ensure_ascii=False) + "\n")
    return {"ok": True, "file": path.name, "labels": str(labels.name)}


if __name__ == "__main__":
    import uvicorn

    _proxy_hygiene()
    os.environ.setdefault("PARCHI_DEVICE", "cuda")
    os.environ.setdefault("PARCHI_CUDA_DEVICE", "1")
    os.environ.setdefault("PARCHI_TROCR", "0")  # enable after fine-tune on Indian lists

    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", "7866")), log_level="info")
