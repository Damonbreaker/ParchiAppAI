# -*- coding: utf-8 -*-
"""Catalogue reference prices + rough bill estimates for matched grocery rows.

Prices are India kirana / quick-commerce ballpark (INR), marked as estimates.
Edit data/catalogue_prices.json or lexicon fields to tune for your city/store.
"""

from __future__ import annotations

import json
import re
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LEXICON_PATH = ROOT / "grocery_lexicon.json"
PRICE_PATH = ROOT / "data" / "catalogue_prices.json"

# Default INR rates when an item has no explicit override.
# price_unit: per_kg | per_g | per_L | per_ml | per_pcs | per_packet | flat
_CATEGORY_DEFAULTS: dict[str, dict] = {
    "vegetables": {"ref_price": 40, "price_unit": "per_kg"},
    "fruits": {"ref_price": 80, "price_unit": "per_kg"},
    "dairy": {"ref_price": 60, "price_unit": "per_L"},
    "bakery": {"ref_price": 40, "price_unit": "flat"},
    "staples": {"ref_price": 45, "price_unit": "per_kg"},
    "rice": {"ref_price": 70, "price_unit": "per_kg"},
    "millets_flours": {"ref_price": 60, "price_unit": "per_kg"},
    "pulses": {"ref_price": 120, "price_unit": "per_kg"},
    "oils_ghee": {"ref_price": 160, "price_unit": "per_L"},
    "spices_whole": {"ref_price": 40, "price_unit": "per_packet"},
    "spices_powder": {"ref_price": 45, "price_unit": "per_packet"},
    "masala_mix": {"ref_price": 50, "price_unit": "per_packet"},
    "dry_fruits": {"ref_price": 800, "price_unit": "per_kg"},
    "beverages": {"ref_price": 40, "price_unit": "flat"},
    "snacks_namkeen": {"ref_price": 30, "price_unit": "per_packet"},
    "sweets": {"ref_price": 50, "price_unit": "per_packet"},
    "ready_to_cook": {"ref_price": 28, "price_unit": "per_packet"},
    "sauces_condiments": {"ref_price": 90, "price_unit": "flat"},
    "breakfast_cereals": {"ref_price": 180, "price_unit": "per_packet"},
    "meat_seafood_eggs": {"ref_price": 280, "price_unit": "per_kg"},
    "frozen": {"ref_price": 120, "price_unit": "per_packet"},
    "personal_care": {"ref_price": 50, "price_unit": "flat"},
    "household_cleaning": {"ref_price": 80, "price_unit": "flat"},
    "pooja": {"ref_price": 30, "price_unit": "flat"},
    "baby": {"ref_price": 200, "price_unit": "flat"},
    "pet": {"ref_price": 250, "price_unit": "flat"},
    "stationery": {"ref_price": 20, "price_unit": "flat"},
    "packaging_disposables": {"ref_price": 40, "price_unit": "flat"},
    "other_fmcg": {"ref_price": 40, "price_unit": "flat"},
}

# Well-known item overrides (INR). Keys are folded canonical lower names.
_ITEM_DEFAULTS: dict[str, dict] = {
    "aloo": {"ref_price": 30, "price_unit": "per_kg"},
    "pyaz": {"ref_price": 35, "price_unit": "per_kg"},
    "tamatar": {"ref_price": 40, "price_unit": "per_kg"},
    "atta": {"ref_price": 42, "price_unit": "per_kg"},
    "chawal": {"ref_price": 60, "price_unit": "per_kg"},
    "basmati rice": {"ref_price": 120, "price_unit": "per_kg"},
    "milk": {"ref_price": 56, "price_unit": "per_L"},
    "buffalo milk": {"ref_price": 70, "price_unit": "per_L"},
    "paneer": {"ref_price": 360, "price_unit": "per_kg"},  # ~180/500g
    "dahi": {"ref_price": 60, "price_unit": "per_kg"},
    "ghee": {"ref_price": 550, "price_unit": "per_kg"},
    "butter": {"ref_price": 560, "price_unit": "per_kg"},
    "eggs": {"ref_price": 7, "price_unit": "per_pcs"},
    "oil": {"ref_price": 140, "price_unit": "per_L"},
    "mustard oil": {"ref_price": 160, "price_unit": "per_L"},
    "sugar": {"ref_price": 45, "price_unit": "per_kg"},
    "namak": {"ref_price": 25, "price_unit": "per_kg"},
    "tea": {"ref_price": 400, "price_unit": "per_kg"},
    "oats": {"ref_price": 200, "price_unit": "per_kg"},  # ~40/200g
    "maggi": {"ref_price": 14, "price_unit": "per_packet"},
    "bread": {"ref_price": 40, "price_unit": "flat"},
    "biscuit": {"ref_price": 30, "price_unit": "per_packet"},
    "chicken": {"ref_price": 220, "price_unit": "per_kg"},
    "mutton": {"ref_price": 750, "price_unit": "per_kg"},
    "fish": {"ref_price": 300, "price_unit": "per_kg"},
    "arhar dal": {"ref_price": 160, "price_unit": "per_kg"},
    "moong dal": {"ref_price": 140, "price_unit": "per_kg"},
    "detergent": {"ref_price": 80, "price_unit": "flat"},
    "soap": {"ref_price": 30, "price_unit": "per_pcs"},
}


def _fold(name: str) -> str:
    return re.sub(r"\s+", " ", (name or "").strip().lower())


@lru_cache(maxsize=1)
def _price_overrides() -> dict[str, dict]:
    if not PRICE_PATH.exists():
        return {}
    data = json.loads(PRICE_PATH.read_text(encoding="utf-8"))
    out = {}
    for row in data.get("items", []):
        key = _fold(row.get("canonical") or row.get("name") or "")
        if not key:
            continue
        out[key] = {
            "ref_price": float(row["ref_price"]),
            "price_unit": row.get("price_unit") or "flat",
            "note": row.get("note") or "",
        }
    return out


@lru_cache(maxsize=1)
def _item_meta() -> dict[str, dict]:
    """canonical_lower -> {canonical, category, ref_price, price_unit}"""
    data = json.loads(LEXICON_PATH.read_text(encoding="utf-8"))
    overrides = _price_overrides()
    meta: dict[str, dict] = {}
    for entry in data.get("items", []):
        canonical = entry["canonical"]
        key = _fold(canonical)
        cat = entry.get("category") or "other_fmcg"
        base = dict(_CATEGORY_DEFAULTS.get(cat, {"ref_price": 40, "price_unit": "flat"}))
        if key in _ITEM_DEFAULTS:
            base.update(_ITEM_DEFAULTS[key])
        # lexicon-embedded fields win
        if "ref_price" in entry:
            base["ref_price"] = float(entry["ref_price"])
        if "price_unit" in entry:
            base["price_unit"] = entry["price_unit"]
        if key in overrides:
            base.update(overrides[key])
        meta[key] = {
            "canonical": canonical,
            "category": cat,
            "ref_price": float(base["ref_price"]),
            "price_unit": base["price_unit"],
            "note": base.get("note") or "market estimate",
        }
    return meta


def get_catalogue_entry(item_name: str) -> dict | None:
    if not item_name:
        return None
    return _item_meta().get(_fold(item_name))


def _qty_parts(quantity: str) -> tuple[float | None, str]:
    text = (quantity or "").strip()
    if not text:
        return None, ""
    m = re.match(
        r"(?i)^\s*(\d+(?:\.\d+)?)\s*(kg|g|gm|gms|l|ltr|lt|liter|litre|ml|pcs?|piece|pieces|packet|pkt|pack)?\s*$",
        text,
    )
    if not m:
        m2 = re.search(r"(\d+(?:\.\d+)?)", text)
        return (float(m2.group(1)), "") if m2 else (None, "")
    num = float(m.group(1))
    unit = (m.group(2) or "").lower()
    if unit in {"gm", "gms"}:
        unit = "g"
    if unit in {"ltr", "lt", "liter", "litre"}:
        unit = "l"
    if unit in {"pc", "piece", "pieces"}:
        unit = "pcs"
    if unit in {"pkt", "pack"}:
        unit = "packet"
    return num, unit


def estimate_line(item: str, quantity: str, ocr_price: str = "") -> dict:
    """Return catalogue estimate + preferred line amount for one row."""
    entry = get_catalogue_entry(item)
    qty_n, qty_u = _qty_parts(quantity)
    ocr_val = None
    if ocr_price and re.fullmatch(r"\d+(?:\.\d{1,2})?", str(ocr_price).strip()):
        ocr_val = float(ocr_price)

    result = {
        "catalogue_price": None,
        "catalogue_unit": None,
        "estimate": None,
        "line_total": None,
        "total_source": "none",
        "catalogue_note": "",
    }
    if not entry:
        if ocr_val is not None:
            result["line_total"] = round(ocr_val, 2)
            result["total_source"] = "ocr_price"
        return result

    ref = float(entry["ref_price"])
    unit = entry["price_unit"]
    result["catalogue_price"] = ref
    result["catalogue_unit"] = unit
    result["catalogue_note"] = entry.get("note") or "market estimate"

    estimate = None
    if unit == "flat" or unit == "per_packet":
        estimate = ref
    elif unit == "per_kg":
        if qty_n is None:
            estimate = ref  # assume 1 kg
        elif qty_u in {"kg", ""}:
            estimate = ref * (qty_n if qty_u == "kg" or qty_n <= 20 else qty_n / 1000.0)
            if qty_u == "" and qty_n >= 100:  # likely grams written as bare number
                estimate = ref * (qty_n / 1000.0)
        elif qty_u == "g":
            estimate = ref * (qty_n / 1000.0)
        else:
            estimate = ref * qty_n
    elif unit == "per_g":
        estimate = ref * (qty_n or 1.0)
    elif unit == "per_L":
        if qty_n is None:
            estimate = ref
        elif qty_u in {"l", ""}:
            estimate = ref * qty_n
        elif qty_u == "ml":
            estimate = ref * (qty_n / 1000.0)
        else:
            estimate = ref * qty_n
    elif unit == "per_ml":
        estimate = ref * (qty_n or 1.0)
    elif unit == "per_pcs":
        estimate = ref * (qty_n or 1.0)
    else:
        estimate = ref

    if estimate is not None:
        estimate = round(float(estimate), 2)
        result["estimate"] = estimate

    # Prefer handwritten/OCR price when present; else catalogue estimate.
    if ocr_val is not None and ocr_val > 0:
        result["line_total"] = round(ocr_val, 2)
        result["total_source"] = "ocr_price"
    elif estimate is not None:
        result["line_total"] = estimate
        result["total_source"] = "catalogue"
    return result


def enrich_rows(rows: list[dict]) -> dict:
    """Attach catalogue + bill fields; fill empty OCR price from estimate."""
    enriched = []
    bill = 0.0
    for row in rows:
        out = dict(row)
        item = str(out.get("item") or "").strip()
        qty = str(out.get("quantity") or "").strip()
        price = str(out.get("price") or "").strip()
        est = estimate_line(item, qty, price)
        out.update(est)
        # If OCR missed price, show catalogue estimate in the editable price box.
        if not price and est.get("estimate") is not None:
            out["price"] = str(int(est["estimate"]) if float(est["estimate"]).is_integer() else est["estimate"])
            out["price_filled_from_catalogue"] = True
            # recompute source after fill
            est2 = estimate_line(item, qty, out["price"])
            out["line_total"] = est2["line_total"]
            out["total_source"] = "catalogue"
            out["estimate"] = est.get("estimate")
        if out.get("line_total") is not None:
            bill += float(out["line_total"])
        enriched.append(out)
    return {
        "rows": enriched,
        "bill_estimate": round(bill, 2),
        "currency": "INR",
        "price_disclaimer": "Catalogue prices are rough market estimates - edit any cell.",
    }


def ensure_price_file() -> Path:
    """Write editable overrides file if missing (seeded from item defaults)."""
    PRICE_PATH.parent.mkdir(parents=True, exist_ok=True)
    if PRICE_PATH.exists():
        return PRICE_PATH
    items = []
    for name, meta in sorted(_ITEM_DEFAULTS.items()):
        items.append(
            {
                "canonical": name.title() if name.islower() else name,
                "ref_price": meta["ref_price"],
                "price_unit": meta["price_unit"],
                "note": "seed estimate - edit for your city/store",
            }
        )
    payload = {
        "currency": "INR",
        "disclaimer": "Rough India market estimates for bill preview. Update anytime.",
        "items": items,
    }
    PRICE_PATH.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return PRICE_PATH


if __name__ == "__main__":
    ensure_price_file()
    demo = enrich_rows(
        [
            {"item": "Paneer", "quantity": "500 g", "price": ""},
            {"item": "Atta", "quantity": "5 kg", "price": "210"},
            {"item": "Milk", "quantity": "1 L", "price": ""},
            {"item": "Oats", "quantity": "200 g", "price": ""},
        ]
    )
    print(json.dumps(demo, indent=2))
