"""Snap OCR guesses onto a grocery lexicon (items + quantity units)."""

from __future__ import annotations

import json
import re
import unicodedata
from functools import lru_cache
from pathlib import Path

from rapidfuzz import fuzz, process

ROOT = Path(__file__).resolve().parent
LEXICON_PATH = ROOT / "grocery_lexicon.json"

# OCR often drops/ flips first letters on grocery words.
_NUM_RE = re.compile(
    r"(?P<num>\d+(?:[.,]\d+)?)\s*(?P<unit>[A-Za-z\u0900-\u097F]*)",
    re.UNICODE,
)


def _fold(text: str) -> str:
    text = unicodedata.normalize("NFKC", text or "")
    text = text.replace("\u20b9", " ").replace("|", " ")
    text = re.sub(r"[^0-9A-Za-z\u0900-\u097F\s./-]", " ", text)
    text = re.sub(r"\s+", " ", text.strip().lower())
    return text


@lru_cache(maxsize=1)
def _load_lexicon() -> dict:
    data = json.loads(LEXICON_PATH.read_text(encoding="utf-8"))
    item_map: dict[str, str] = {}
    for entry in data["items"]:
        canonical = entry["canonical"]
        keys = {_fold(canonical)}
        for alias in entry.get("aliases", []):
            keys.add(_fold(alias))
        for key in keys:
            if key:
                item_map[key] = canonical

    unit_map: dict[str, str] = {}
    for entry in data["units"]:
        canonical = entry["canonical"]
        keys = {_fold(canonical)}
        for alias in entry.get("aliases", []):
            keys.add(_fold(alias))
        for key in keys:
            if key:
                unit_map[key] = canonical
    return {"items": item_map, "units": unit_map}


def _fuzzy_cutoff(query: str, base: int) -> int:
    n = len(query)
    if n <= 2:
        return 101  # refuse fuzzy for tiny OCR scraps ("as", "lo")
    if n == 3:
        return max(base, 88)
    if n == 4:
        return max(base, 82)
    return max(base, 78)


def _accept_fuzzy(query: str, key: str, score: float, cutoff: int) -> bool:
    if score < cutoff:
        return False
    # Short staples (dal, oil, tea): require near-exact — blocks Dals→Dal, YORS→ORS.
    if len(key) <= 3 and score < 92:
        return False
    # "dals"/"oils" plural-S on a 3-letter key is almost always OCR noise.
    if len(key) <= 3 and query == key + "s":
        return False
    # Block WRatio-style inflation: short query is a substring of a long name.
    if len(key) >= max(8, len(query) * 2) and fuzz.ratio(query, key) < cutoff:
        return False
    if len(query) <= 3 and len(key) > len(query) + 1:
        if not (key.startswith(query) or query in key[: len(query) + 1]):
            return False
    return True


def looks_like_price_token(raw: str) -> bool:
    """True when OCR text is currency/amount soup, not an item name."""
    text = (raw or "").strip()
    if not text:
        return False
    compact = re.sub(r"\s+", "", text)
    if re.search(r"(?i)(rs\.?|inr|rupees?|\u20b9)", compact):
        return True
    # YORS / TORS / foR / 1ODR: trailing R/RS/RG with an amount-like body (not bare "ORS").
    m = re.match(r"(?i)^(?P<body>.+?)(?P<cur>r[sg]?)$", compact)
    if m and 3 <= len(compact) <= 10:
        body = m.group("body")
        # Need a digit or >=2 lookalike amount letters (YO, fo, 1OD) — not single "O".
        if re.search(r"\d", body) or (
            len(body) >= 2 and re.fullmatch(r"[ODoIlZSYyBbFfGgTJt0-9]+", body)
        ):
            price, meta = correct_price(text)
            if (
                price
                and meta.get("source") == "normalized"
                and re.fullmatch(r"\d{1,6}(?:\.\d{1,2})?", price)
                and int(float(price)) >= 10
            ):
                return True
    # Mixed digit+letter amounts like 215Rs already caught above; 100R too.
    # Do NOT treat item OCR with one stray digit (Boff4lo) as a price.
    letters = sum(ch.isalpha() for ch in compact)
    digits = sum(ch.isdigit() for ch in compact)
    if digits and letters:
        if letters >= 4 and digits <= 1 and not re.search(r"(?i)r[sg]?$", compact):
            return False
        price, meta = correct_price(text)
        if price and meta.get("source") == "normalized" and len(price) >= 2:
            # Amounts are digit-led; reject long letter bodies.
            if letters >= digits + 3:
                return False
            return True
    return False


# Frequent handwritten OCR swaps for common breakfast/staples.
# Values are lexicon search keys (aliases / canonicals), never free junk text.
_OCR_ITEM_HINTS = {
    "dals": "oats",  # Oats misread as Dals under pink light
    "dal5": "oats",
    "das": "oats",
    "0ats": "oats",
    "onts": "oats",
    "qats": "oats",
    "aats": "oats",
    "mik": "milk",
    "miln": "milk",
    "mille": "milk",
    "doodh": "milk",
    # Glued / mangled "toned milk" (TrOCR + Gemini both invent this often)
    "tonedmille": "toned milk",
    "tonedlmille": "toned milk",
    "tonedmilk": "toned milk",
    "tonedmil": "toned milk",
    "tonedmllk": "toned milk",
    "toned mlk": "toned milk",
    "tone milk": "toned milk",
    "dtmilk": "double toned milk",
    "dt milk": "double toned milk",
    "aneen": "paneer",
    "aneer": "paneer",
    "aner": "paneer",
    "hawe": "chawal",
    "hawd": "chawal",
    "chawl": "chawal",
    "chawol": "chawal",
    "iatto": "atta",
    "ata": "atta",
    # Buffalo / buffalo milk (magenta paper often drops vowels)
    "bffalo": "buffalo milk",
    "boffulo": "buffalo milk",
    "boffalo": "buffalo milk",
    "boff4lo": "buffalo milk",
    "bufflo": "buffalo milk",
    "bafalo": "buffalo milk",
    "buffalo": "buffalo milk",
    "buffallo": "buffalo milk",
    "bhains": "buffalo milk",
    "potutfo": "potato",
    "potutfoe": "potato",
    "muligroin": "multigrain",
    "mulligein": "multigrain",
    "mulligrain": "multigrain",
    "atfer": "atta",
    "kbl": "kabuli",
    "chn": "chana",
    "chna": "chana",
    "rjma": "rajma",
    "tmtr": "tomato",
    "tmatr": "tomato",
    "pyj": "onion",
    "pyz": "onion",
    "onon": "onion",
    "sgr": "sugar",
    "nmk": "salt",
    "slt": "salt",
    "mlk": "milk",
    "ddh": "milk",
    "chwl": "rice",
    "brd": "bread",
    "bsct": "biscuit",
    "bnna": "banana",
    "cflks": "cornflakes",
    "brwnbrd": "brown bread",
    "mstrdol": "mustard oil",
    "snflwroil": "sunflower oil",
    "clgt": "colgate",
}

# Trailing staple words often glued onto a mangled prefix (tonedmille → toned + milk).
_GLUED_SUFFIXES = (
    "milk",
    "mille",
    "mil",
    "mlk",
    "atta",
    "aata",
    "oil",
    "tel",
    "bread",
    "brd",
    "rice",
    "chawal",
    "dal",
    "daal",
    "sugar",
    "salt",
    "namak",
    "paneer",
    "butter",
    "ghee",
)


def repair_ocr_item(raw: str) -> str:
    """Expand known OCR junk / glued compounds into searchable grocery text."""
    text = (raw or "").strip()
    if not text:
        return ""
    folded = _fold(text)
    if folded in _OCR_ITEM_HINTS:
        return _OCR_ITEM_HINTS[folded]
    compact = folded.replace(" ", "")
    if compact in _OCR_ITEM_HINTS:
        return _OCR_ITEM_HINTS[compact]

    # Token-wise hints: "mulligein atfer" → "multigrain atta"
    tokens = folded.split()
    if tokens:
        repaired = [_OCR_ITEM_HINTS.get(tok, tok) for tok in tokens]
        if repaired != tokens:
            return " ".join(repaired)

    # Deglue: tonedmille / brwnbrd → insert space before a known staple suffix.
    if " " not in folded and len(folded) >= 6:
        for suffix in sorted(_GLUED_SUFFIXES, key=len, reverse=True):
            if folded.endswith(suffix) and len(folded) > len(suffix) + 2:
                prefix = folded[: -len(suffix)]
                prefix = _OCR_ITEM_HINTS.get(prefix, prefix)
                suffix_word = _OCR_ITEM_HINTS.get(suffix, suffix)
                if suffix in {"mille", "mil", "mlk"}:
                    suffix_word = "milk"
                candidate = f"{prefix} {suffix_word}".strip()
                if candidate != folded:
                    # Prefer a second hint pass (toned milk).
                    return repair_ocr_item(candidate) if candidate != raw else candidate
    return text


def match_item(raw: str, *, min_score: int = 78) -> tuple[str, int, str]:
    """Return (display_name, score, source) where source is exact|fuzzy|raw."""
    text = (raw or "").strip()
    if not text:
        return "", 0, "empty"
    # Never promote price OCR (YORS/1ODRS) into a grocery name (ORS, etc.).
    if looks_like_price_token(text):
        return "", 0, "price_noise"
    repaired = repair_ocr_item(text)
    folded = _fold(repaired)
    lexicon = _load_lexicon()["items"]
    if folded in lexicon:
        return lexicon[folded], 100, "exact" if repaired == text else "fuzzy"
    hint = _OCR_ITEM_HINTS.get(folded)
    if hint and hint in lexicon:
        return lexicon[hint], 93, "fuzzy"
    # Hint may be multi-word ("toned milk") already in lexicon as an alias.
    if hint:
        hint_folded = _fold(hint)
        if hint_folded in lexicon:
            return lexicon[hint_folded], 95, "fuzzy"

    # QRatio avoids substring false-positives (e.g. "as" ? "washing powder").
    choices = sorted(lexicon.keys(), key=len, reverse=True)
    cutoff = _fuzzy_cutoff(folded, min_score)

    def _best(query: str, score_floor: int) -> tuple[str, int] | None:
        if score_floor > 100:
            return None
        hit = process.extractOne(
            query,
            choices,
            scorer=fuzz.QRatio,
            score_cutoff=score_floor,
        )
        if not hit:
            return None
        key, score, _ = hit
        if not _accept_fuzzy(query, key, score, score_floor):
            return None
        return key, int(score)

    hit = _best(folded, cutoff)
    if hit:
        key, score = hit
        return lexicon[key], score, "fuzzy"

    compact = folded.replace(" ", "")
    if compact != folded:
        compact_choices = sorted({c.replace(" ", "") for c in choices}, key=len, reverse=True)
        compact_hit = process.extractOne(
            compact,
            compact_choices,
            scorer=fuzz.QRatio,
            score_cutoff=cutoff,
        )
        if compact_hit:
            compact_key, score, _ = compact_hit
            if _accept_fuzzy(compact, compact_key, score, cutoff):
                for key, canonical in lexicon.items():
                    if key.replace(" ", "") == compact_key:
                        return canonical, int(score), "fuzzy"

    # First-letter OCR drop (aneer ? paneer). Only for longer tokens.
    if len(folded) >= 4:
        for prefix in "pbmtcasdghkrvwyn":
            trial = _fold(prefix + folded)
            if trial in lexicon:
                return lexicon[trial], 90, "fuzzy"
            prefixed = _best(trial, max(cutoff, 82))
            if prefixed:
                key, score = prefixed
                return lexicon[key], score, "fuzzy"
            # Soft prefix: hawd ? chawd ? chawal (needs high partial overlap).
            soft = process.extractOne(
                trial,
                choices,
                scorer=fuzz.QRatio,
                score_cutoff=72,
            )
            if soft:
                key, score, _ = soft
                if (
                    abs(len(trial) - len(key)) <= 2
                    and fuzz.partial_ratio(trial, key) >= 85
                    and fuzz.ratio(trial, key) >= 70
                ):
                    return lexicon[key], int(score), "fuzzy"

    return text, 0, "raw"


def match_unit(raw: str, *, min_score: int = 70) -> str:
    folded = _fold(raw)
    if not folded:
        return ""
    lexicon = _load_lexicon()["units"]
    if folded in lexicon:
        return lexicon[folded]
    # Bare "k" from "5k" / "2k" handwritten shorthand.
    if folded in {"k", "K".lower()}:
        return "kg"
    hit = process.extractOne(folded, list(lexicon.keys()), scorer=fuzz.WRatio, score_cutoff=min_score)
    if hit:
        return lexicon[hit[0]]
    # piece / pcs OCR: pie4, pece, pcs
    if fuzz.QRatio(folded, "piece") >= 70 or fuzz.QRatio(folded, "pcs") >= 70:
        return "pcs"
    return ""


_CURRENCY_RE = re.compile(
    r"(?i)(?:rs\.?|inr|rupees?|\u20b9|r(?:s|g)?)\s*$"
)

# Handwritten price/qty letter→digit confusions (apply AFTER stripping currency/units).
_PRICE_LOOKALIKE = str.maketrans(
    {
        "O": "0",
        "o": "0",
        "D": "0",
        "Q": "0",
        "I": "1",
        "l": "1",
        "|": "1",
        "!": "1",
        "Z": "2",
        "z": "2",
        "A": "4",
        "S": "5",
        "s": "5",
        "G": "6",
        "b": "6",
        "F": "6",
        "f": "6",
        "T": "7",
        "J": "7",
        "j": "7",
        "Y": "8",
        "y": "8",
        "B": "8",
        "g": "9",
        "q": "9",
    }
)


def _fix_number_ocr(number: str) -> str:
    """Fix digit lookalikes only inside the numeric token."""
    fixed = []
    for ch in number:
        if ch in "OoDdQ":
            fixed.append("0")
        elif ch in "Il|!":
            fixed.append("1")
        elif ch in "Zz":
            fixed.append("2")
        elif ch in "Ss":
            fixed.append("5")
        elif ch == ",":
            fixed.append(".")
        else:
            fixed.append(ch)
    return "".join(fixed)


def _strip_currency(text: str) -> str:
    text = (text or "").replace("\u20b9", " ")
    text = re.sub(r"\s+", "", text)
    text = _CURRENCY_RE.sub("", text)
    # Handwritten Rs → ks / rs / r5 / rg leftovers after OCR.
    text = re.sub(r"(?i)(?:rs|ks|r5|rg|r)$", "", text)
    return text


def correct_price(raw: str) -> tuple[str, dict]:
    """Salvage grocery prices from OCR letter soup (1ODRS→100, YORS→80, fo RS→60)."""
    text = (raw or "").strip()
    if not text:
        return "", {"raw": "", "source": "empty"}

    compact = _strip_currency(text.replace(" ", ""))
    if not compact:
        return "", {"raw": text, "source": "empty"}

    # Map lookalike letters to digits, then keep digit/decimal runs.
    mapped = compact.translate(_PRICE_LOOKALIKE)
    mapped = re.sub(r"[^\d.]", "", mapped)
    mapped = mapped.replace(",", "")
    # Collapse multiple dots.
    if mapped.count(".") > 1:
        left, _, right = mapped.partition(".")
        mapped = left + "." + right.replace(".", "")

    if not mapped or not re.search(r"\d", mapped):
        return "", {"raw": text, "source": "unreadable"}

    # Prefer ordinary grocery amounts (avoid leading junk digit if a better body exists).
    # e.g. keep 100 from 100, 80 from 80, 70 from 70.
    if re.fullmatch(r"\d{1,6}(?:\.\d{1,2})?", mapped):
        # Drop meaningless leading zeros unless value is 0.
        if "." in mapped:
            price = mapped
        else:
            price = str(int(mapped)) if mapped else ""
        return price, {"raw": text, "source": "normalized", "decoded": mapped}

    m = re.search(r"\d{1,6}(?:\.\d{1,2})?", mapped)
    if not m:
        return "", {"raw": text, "source": "unreadable"}
    price = m.group(0)
    if "." not in price:
        price = str(int(price))
    return price, {"raw": text, "source": "normalized", "decoded": mapped}


def correct_quantity(raw: str) -> tuple[str, dict]:
    """Keep the number; snap unit onto the grocery unit list. Drop OCR garbage."""
    text = (raw or "").strip()
    if not text:
        return "", {"number": "", "unit": "", "source": "empty"}

    compact = re.sub(r"\s+", "", text)
    compact = re.sub(r"[?؟]+", "", compact)
    # "2hy" / "2Kg" OCR soup → keep leading digits + unitish tail.
    compact = re.sub(r"(?i)^(\d{1,4})h[yil1]?$", r"\1kg", compact)
    compact = re.sub(r"(?i)^(\d{1,4})k[g9]?$", r"\1kg", compact)
    compact = re.sub(r"(?i)^(\d{1,4})k[yv]$", r"\1kg", compact)

    # Common full-token qty OCR forms.
    # "IL" / "Il" is almost always handwritten "1L", not "11".
    if re.fullmatch(r"(?i)[il1]l", compact):
        return "1 L", {"number": "1", "unit": "L", "source": "normalized"}
    litre = re.fullmatch(r"(?i)([0-9OoSsZz]{1,4})(l|lt|ltr|liter|litre)", compact)
    if litre:
        number = _fix_number_ocr(litre.group(1))
        if number.isdigit():
            return f"{int(number)} L", {
                "number": str(int(number)),
                "unit": "L",
                "source": "normalized",
            }

    # 2004 from "200g" (g→4); 5009 from "500g".
    g_as_digit = re.fullmatch(r"(?i)(\d{2,4})[4]", compact)
    if g_as_digit and not re.search(r"(?i)kg|ml|pcs?", compact):
        number = str(int(g_as_digit.group(1)))
        # Grocery grams are usually 50–999; treat trailing 4 as g.
        if 10 <= int(number) <= 999:
            return f"{number} g", {
                "number": number,
                "unit": "g",
                "source": "normalized",
            }

    # 5k / 2k / 500gm / 200g
    unitish = re.fullmatch(
        r"(?i)([0-9OoIl|SsZzDd.,]+)(kg|kgs|k|g|gm|gms|gram|grams|ml|l|ltr|lt|dozen|dz|pcs?|piece|pieces|pie\d*|pkt|pack|packet)?",
        compact,
    )
    if unitish:
        number = _fix_number_ocr(unitish.group(1))
        number = number.replace(",", "")
        if re.fullmatch(r"\d+(?:\.\d+)?", number):
            if "." not in number:
                number = str(int(number))
            unit_raw = unitish.group(2) or ""
            unit = match_unit(unit_raw) if unit_raw else ""
            if unit:
                return f"{number} {unit}", {
                    "number": number,
                    "unit": unit,
                    "source": "normalized",
                }
            return number, {"number": number, "unit": "", "source": "number_only"}

    # Fallback: salvage any digit run + trailing unit letters.
    salvaged = compact.translate(
        str.maketrans({"O": "0", "o": "0", "I": "1", "l": "1", "|": "1", "S": "5", "s": "5", "Z": "2", "z": "2"})
    )
    match = _NUM_RE.search(salvaged)
    if match:
        number = _fix_number_ocr(match.group("num"))
        if re.fullmatch(r"\d+(?:\.\d+)?", number):
            if "." not in number:
                number = str(int(number))
            unit_raw = match.group("unit") or salvaged[match.end() :].strip()
            unit = match_unit(unit_raw) if unit_raw else ""
            if unit:
                return f"{number} {unit}", {
                    "number": number,
                    "unit": unit,
                    "source": "normalized",
                }
            return number, {"number": number, "unit": "", "source": "number_only"}

    # No readable number → drop garbage (hy?, asd, etc.)
    return "", {"number": "", "unit": "", "source": "unreadable", "raw": text}


def correct_row(row: dict, *, item_min_score: int = 78) -> dict:
    item_raw = str(row.get("item") or "").strip()
    qty_raw = str(row.get("quantity") or "").strip()
    price_raw = str(row.get("price") or "").strip()

    item, item_score, item_source = match_item(item_raw, min_score=item_min_score)
    # Keep raw OCR text when lexicon misses — user can pencil-edit.
    if item_source == "raw":
        item = item_raw
    elif item_source == "price_noise":
        item = ""

    qty, qty_meta = correct_quantity(qty_raw)
    price, price_meta = correct_price(price_raw)

    out = dict(row)
    out["item"] = item
    out["quantity"] = qty
    out["price"] = price
    out["correction"] = {
        "item_raw": item_raw,
        "item_score": item_score,
        "item_source": item_source,
        "quantity_raw": qty_raw,
        "quantity_meta": qty_meta,
        "price_raw": price_raw,
        "price_meta": price_meta,
    }
    return out


def is_grocery_row(row: dict) -> bool:
    """Keep catalogue hits and plausible OCR rows (qty/price present)."""
    corr = row.get("correction") or {}
    source = corr.get("item_source")
    if source in {"exact", "fuzzy"} and row.get("item"):
        return True
    item = (row.get("item") or "").strip()
    qty = (row.get("quantity") or "").strip()
    price = (row.get("price") or "").strip()
    if not item:
        # Flexible forms may contain only an amount; keep it editable or let
        # merge_split_rows attach it to the preceding item.
        return bool(qty or price)
    if source == "price_noise":
        return bool(qty or price)
    # Drop tiny OCR crumbs with no amount.
    if len(item) <= 2 and not qty and not price:
        return False
    # Keep hard handwriting even if lexicon missed (Doctor, brand names, etc.).
    if qty or price:
        return True
    # Bare item with letters only — keep if it looks like a word.
    if len(item) >= 3 and re.search(r"[A-Za-z\u0900-\u097F]{3,}", item):
        return True
    return False


def correct_rows(rows: list[dict], *, item_min_score: int = 78) -> list[dict]:
    fixed = [correct_row(row, item_min_score=item_min_score) for row in rows]
    kept = [row for row in fixed if is_grocery_row(row)]
    return kept
