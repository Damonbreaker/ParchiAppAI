"""Kirana base-product layer.

Customers usually write simple names (atta, rice, arhar dal). The open national
SKU catalogue is much more detailed (brand + pack). This module:

1. Resolves OCR text onto a grocery-lexicon *base name* (Atta, Chawal, …).
2. Detects when the handwriting already names a brand / specific variant.
3. Links detailed catalogue SKUs under a base name for advanced picks + MRP.
"""

from __future__ import annotations

import json
import re
import unicodedata
from functools import lru_cache
from pathlib import Path
from typing import Any

from rapidfuzz import fuzz

ROOT = Path(__file__).resolve().parent
LEXICON_PATH = ROOT / "grocery_lexicon.json"

_TOKEN_RE = re.compile(r"[0-9a-z\u0900-\u097f]+")

# Brand / marketing tokens → open advanced SKU detail.
# Pulse/rice/oil *types* (arhar, basmati, mustard) are base names themselves, not brands.
_DETAIL_TOKENS = {
    "aashirvaad",
    "aashirwad",
    "aashivaad",
    "ashirvad",
    "ashirwad",
    "pillsbury",
    "pilsbury",
    "patanjali",
    "fortune",
    "britannia",
    "britania",
    "amul",
    "nestle",
    "maggi",
    "tata",
    "saffola",
    "dhara",
    "gemini",
    "engine",
    "freedom",
    "organic",
    "multigrain",
    "chakki",
    "superior",
    "refined",
}

_QTY_NOISE = {
    "kg",
    "kilo",
    "kilos",
    "g",
    "gm",
    "gram",
    "grams",
    "ltr",
    "liter",
    "litre",
    "l",
    "ml",
    "pcs",
    "pc",
    "packet",
    "pkt",
    "pack",
    "pk",
    "dozen",
}

# Prefer these short staples when OCR is only the staple word.
_STAPLE_PREFERENCE = {
    "atta": "Atta",
    "aata": "Atta",
    "flour": "Atta",
    "rice": "Chawal",
    "chawal": "Chawal",
    "dal": "Dal",
    "daal": "Dal",
    "oil": "Oil",
    "tel": "Oil",
    "milk": "Milk",
    "doodh": "Milk",
    "sugar": "Sugar",
    "cheeni": "Sugar",
    "chini": "Sugar",
    "salt": "Namak",
    "namak": "Namak",
    "onion": "Pyaz",
    "pyaz": "Pyaz",
    "pyaj": "Pyaz",
    "potato": "Aloo",
    "aloo": "Aloo",
    "alu": "Aloo",
    "tomato": "Tamatar",
    "tamatar": "Tamatar",
}


def _fold(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).lower()
    text = text.replace("&", " and ")
    text = re.sub(r"[^0-9a-z\u0900-\u097f\s]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def _tokens(value: Any) -> list[str]:
    folded = _fold(value)
    # Split glued qty units: "5kg" / "500gm" → "5", "kg"
    folded = re.sub(r"(\d+(?:\.\d+)?)(kg|g|gm|grams?|ltr|litre|liter|ml|l|pcs?)\b", r"\1 \2", folded)
    return _TOKEN_RE.findall(folded)


@lru_cache(maxsize=1)
def load_base_entries() -> list[dict[str, Any]]:
    data = json.loads(LEXICON_PATH.read_text(encoding="utf-8"))
    return list(data.get("items") or [])


@lru_cache(maxsize=1)
def base_alias_map() -> dict[str, str]:
    """folded alias/canonical -> display canonical."""
    out: dict[str, str] = {}
    for entry in load_base_entries():
        canonical = str(entry.get("canonical") or "").strip()
        if not canonical:
            continue
        keys = {_fold(canonical)}
        for alias in entry.get("aliases") or []:
            keys.add(_fold(alias))
        for key in keys:
            if key:
                out[key] = canonical
    return out


@lru_cache(maxsize=1)
def base_canonicals() -> list[str]:
    return sorted({str(e.get("canonical") or "").strip() for e in load_base_entries() if e.get("canonical")})


# Prefer a more specific display name when OCR clearly names a milk grade.
_DISPLAY_OVERRIDES = {
    "toned milk": "Toned Milk",
    "double toned milk": "Double Toned Milk",
    "buffalo milk": "Buffalo Milk",
    "full cream milk": "Full Cream Milk",
    "brown bread": "Brown Bread",
    "mustard oil": "Mustard Oil",
    "sunflower oil": "Sunflower Oil",
}


def resolve_base_name(raw: str) -> dict[str, Any]:
    """Map free OCR text to a kirana base name.

    Returns:
      base_name, matched, explicit_detail, brandish_tokens, score, source, repaired
    """
    from grocery_correct import match_item, repair_ocr_item

    text = (raw or "").strip()
    if not text:
        return {
            "base_name": "",
            "matched": False,
            "explicit_detail": False,
            "brandish_tokens": [],
            "score": 0,
            "source": "empty",
            "repaired": "",
        }

    repaired = repair_ocr_item(text)
    toks = _tokens(repaired)
    brandish = sorted({t for t in toks if t in _DETAIL_TOKENS})
    # Drop qty/unit noise: "aata 5kg" → "aata"
    content_toks = [t for t in toks if t not in _QTY_NOISE and not t.isdigit()]
    content = " ".join(content_toks) or repaired
    folded = _fold(content)

    # Single-token staples → prefer short base (atta → Atta, not Multigrain Atta).
    if len(content_toks) == 1 and content_toks[0] in _STAPLE_PREFERENCE:
        preferred = _STAPLE_PREFERENCE[content_toks[0]]
        if preferred in base_canonicals() or _fold(preferred) in {_fold(v) for v in base_alias_map().values()}:
            override = _DISPLAY_OVERRIDES.get(_fold(repaired)) or _DISPLAY_OVERRIDES.get(folded)
            return {
                "base_name": override or preferred,
                "matched": True,
                "explicit_detail": False,
                "brandish_tokens": [],
                "score": 100,
                "source": "staple",
                "repaired": repaired,
            }

    display, score, source = match_item(content, min_score=72)
    matched = source != "raw" and bool(display)

    # Brand tokens → advanced SKU path; modifiers like multigrain stay in the base query.
    brand_only = sorted(
        {t for t in brandish if t not in {"multigrain", "chakki", "superior", "organic", "refined"}}
    )

    # Brand + staple: strip brand words, keep modifiers ("aashirvaad multigrain atta" → multigrain atta).
    if not matched and brand_only:
        remainder_toks = [t for t in content_toks if t not in brand_only]
        remainder = " ".join(remainder_toks)
        if remainder:
            display, score, source = match_item(remainder, min_score=70)
            matched = source != "raw" and bool(display)
            if not matched and len(remainder_toks) == 1 and remainder_toks[0] in _STAPLE_PREFERENCE:
                display = _STAPLE_PREFERENCE[remainder_toks[0]]
                score, source, matched = 95, "staple", True

    if not matched:
        aliases = base_alias_map()
        if folded in aliases:
            display, score, source, matched = aliases[folded], 100, "exact", True
        else:
            best_name, best = "", 0
            for name in base_canonicals():
                s = fuzz.QRatio(folded, _fold(name))
                if s > best:
                    best, best_name = s, name
            if best >= 82:
                display, score, source, matched = best_name, best, "fuzzy", True

    explicit = matched and bool(brand_only)

    if not matched:
        return {
            "base_name": "",
            "matched": False,
            "explicit_detail": False,
            "brandish_tokens": brandish,
            "score": 0,
            "source": "none",
            "repaired": repaired,
        }

    override = _DISPLAY_OVERRIDES.get(_fold(repaired)) or _DISPLAY_OVERRIDES.get(folded)
    if override:
        display = override

    return {
        "base_name": display,
        "matched": True,
        "explicit_detail": explicit,
        "brandish_tokens": brandish,
        "score": int(score),
        "source": source,
        "repaired": repaired,
    }


def sku_matches_base(sku: dict[str, Any], base_name: str) -> bool:
    """Whether a catalogue SKU belongs under this base product name."""
    if not base_name:
        return False
    base_toks = [t for t in _tokens(base_name) if len(t) > 1]
    if not base_toks:
        return False
    # Noise guards for atta / flour
    blob = _fold(
        " ".join(
            str(sku.get(k) or "")
            for k in ("canonical_name", "brand", "variant", "search_blob", "_name_norm")
        )
    )
    if "atta" in base_toks or base_name.lower() == "atta":
        for bad in ("khatta", "meetha", "noodle", "bread", "rusk", "biscuit", "cassatta", "pickle", "papad"):
            if bad in blob:
                return False
    # All base tokens should appear (order-free) OR high fuzzy on name.
    name = _fold(sku.get("canonical_name") or "")
    if all(t in blob for t in base_toks):
        return True
    return fuzz.partial_ratio(_fold(base_name), name) >= 86


def filter_skus_for_base(rows: list[dict[str, Any]], base_name: str, limit: int = 80) -> list[dict[str, Any]]:
    out = [r for r in rows if sku_matches_base(r, base_name)]
    return out[:limit] if out else rows[: min(20, limit)]


def estimate_mrp(skus: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Pick a representative MRP/estimate (+ min/max range) from linked SKU prices."""
    amounts: list[tuple[int, float, dict[str, Any]]] = []
    priority = {
        "mrp": 0,
        "market_price_reference": 1,
        "reference_price_before_discount": 2,
        "selling_price": 3,
        "observed_selling_price": 4,
    }
    for row in skus:
        price = row.get("price")
        if not isinstance(price, dict):
            continue
        amount = price.get("amount")
        if amount is None:
            continue
        try:
            val = float(amount)
        except (TypeError, ValueError):
            continue
        if val <= 0:
            continue
        ptype = str(price.get("price_type") or "")
        amounts.append((priority.get(ptype, 9), val, price))
    if not amounts:
        return None
    amounts.sort(key=lambda x: (x[0], x[1]))
    # Prefer median of best priority band for a stable estimate.
    best_pri = amounts[0][0]
    band = [a for a in amounts if a[0] == best_pri]
    band.sort(key=lambda x: x[1])
    values = [a[1] for a in band]
    mid = band[len(band) // 2]
    price = dict(mid[2])
    price["amount"] = mid[1]
    price["amount_min"] = round(min(values), 2)
    price["amount_max"] = round(max(values), 2)
    price["variant_count"] = len(values)
    price["estimate"] = True
    if len(values) >= 2 and abs(values[-1] - values[0]) >= 1:
        price["label"] = "MRP range" if best_pri == 0 else "Price range"
    else:
        price["label"] = "MRP" if best_pri == 0 else (price.get("label") or "Price estimate")
    return price
