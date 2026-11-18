"""Read a shopping-list photo into editable rows: item, quantity, price.

Primary engine: PaddleOCR (angle-corrected, stronger on phone photos).
Fallback: RapidOCR. Large horizontal gaps become columns
(name | qty | price); tokens are grouped into rows by Y position.
"""

from __future__ import annotations

import os
import re
import threading
from dataclasses import dataclass
from typing import Any

# PaddleCPU + OneDNN often throws "could not execute a primitive" under threads.
os.environ.setdefault("FLAGS_use_mkldnn", "0")
os.environ.setdefault("FLAGS_enable_pir_api", "0")
os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("MKL_NUM_THREADS", "1")

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter, ImageOps

_PADDLE = None
_RAPID = None
_OCR_LOCK = threading.Lock()


def get_engine():
    """Warm the primary OCR so the first request is not cold."""
    return _get_paddle()


def _paddle_kwargs(lang: str) -> dict:
    return {
        "use_angle_cls": True,
        "lang": lang,
        "show_log": False,
        "use_gpu": False,
        "enable_mkldnn": False,
    }


def _reset_paddle() -> None:
    global _PADDLE
    _PADDLE = None


def _get_paddle():
    global _PADDLE
    if _PADDLE is None:
        from paddleocr import PaddleOCR

        # English + digits first (lists, prices). Hindi is loaded on demand.
        try:
            _PADDLE = {"en": PaddleOCR(**_paddle_kwargs("en"))}
        except TypeError:
            # Older paddleocr without enable_mkldnn
            kw = _paddle_kwargs("en")
            kw.pop("enable_mkldnn", None)
            _PADDLE = {"en": PaddleOCR(**kw)}
    return _PADDLE


def _get_paddle_hi():
    engines = _get_paddle()
    if "hi" not in engines:
        from paddleocr import PaddleOCR

        try:
            engines["hi"] = PaddleOCR(**_paddle_kwargs("hi"))
        except TypeError:
            kw = _paddle_kwargs("hi")
            kw.pop("enable_mkldnn", None)
            engines["hi"] = PaddleOCR(**kw)
    return engines["hi"]


def _get_rapid():
    global _RAPID
    if _RAPID is None:
        from rapidocr_onnxruntime import RapidOCR

        _RAPID = RapidOCR()
    return _RAPID


def _safe_arr(arr: np.ndarray) -> np.ndarray:
    """Contiguous RGB uint8; mild downscale only for huge phone photos."""
    if arr.ndim == 2:
        arr = np.stack([arr, arr, arr], axis=-1)
    if arr.ndim == 3 and arr.shape[2] == 4:
        arr = arr[:, :, :3]
    arr = np.ascontiguousarray(arr, dtype=np.uint8)
    h, w = arr.shape[:2]
    max_side = max(h, w)
    # Keep detail for handwriting; only shrink extreme camera dumps.
    if max_side > 2400:
        scale = 2400 / float(max_side)
        new_w = max(32, int(w * scale))
        new_h = max(32, int(h * scale))
        arr = np.array(
            Image.fromarray(arr).resize((new_w, new_h), Image.Resampling.LANCZOS)
        )
    return arr


def _paddle_ocr(lang: str, arr: np.ndarray):
    """Thread-safe Paddle call; recreate engine once on primitive failures."""
    arr = _safe_arr(arr)
    with _OCR_LOCK:
        for attempt in range(2):
            try:
                engine = _get_paddle()["en"] if lang == "en" else _get_paddle_hi()
                return engine.ocr(arr, cls=True)
            except Exception as exc:
                msg = str(exc).lower()
                if attempt == 0 and (
                    "primitive" in msg
                    or "mkldnn" in msg
                    or "onednn" in msg
                    or "segment" in msg
                ):
                    _reset_paddle()
                    continue
                raise


@dataclass
class Token:
    text: str
    x1: float
    y1: float
    x2: float
    y2: float
    score: float = 1.0

    @property
    def cx(self) -> float:
        return (self.x1 + self.x2) / 2.0

    @property
    def cy(self) -> float:
        return (self.y1 + self.y2) / 2.0

    @property
    def height(self) -> float:
        return max(1.0, self.y2 - self.y1)


_QTY_RE = re.compile(
    r"^(\d+([./]\d+)?\s*(kg|g|gm|gram|grams|ltr|l|ml|dozen|pcs?|packet|pkts?|bunch|piece|pieces)?)$",
    re.I,
)
_PRICE_RE = re.compile(r"^(rs\.?|inr)?\s*\d+([.,]\d{1,2})?$", re.I)
_NUM_RE = re.compile(r"\d")
_DEVANAGARI_RE = re.compile(r"[\u0900-\u097F]")


def _resize_for_ocr(img: Image.Image) -> Image.Image:
    w, h = img.size
    long_side = max(w, h)
    # Handwriting needs more pixels than print.
    if long_side < 1600:
        scale = 1600 / long_side
        return img.resize((int(w * scale), int(h * scale)), Image.Resampling.LANCZOS)
    if long_side > 2800:
        scale = 2800 / long_side
        return img.resize((int(w * scale), int(h * scale)), Image.Resampling.LANCZOS)
    return img


def preprocess(image: Image.Image) -> Image.Image:
    """Clean phone photos: upright RGB, bigger, more contrast, light sharpen."""
    img = image.convert("RGB")
    img = ImageOps.exif_transpose(img)
    img = _resize_for_ocr(img)
    img = ImageOps.autocontrast(img, cutoff=1)
    img = ImageEnhance.Contrast(img).enhance(1.25)
    img = ImageEnhance.Sharpness(img).enhance(1.45)
    # No median blur — it melts thin handwritten strokes (2Kg → hy?).
    return img


def preprocess_variants(image: Image.Image) -> list[Image.Image]:
    """RGB + cast-resistant variants for magenta paper / heavy shadows."""
    base = ImageOps.exif_transpose(image.convert("RGB"))
    base = _resize_for_ocr(base)
    variants = [preprocess(image), base]

    try:
        import cv2

        bgr = cv2.cvtColor(np.array(base), cv2.COLOR_RGB2BGR)
        clahe = cv2.createCLAHE(clipLimit=2.5, tileGridSize=(8, 8))

        lab = cv2.cvtColor(bgr, cv2.COLOR_BGR2LAB)
        l_ch, a_ch, b_ch = cv2.split(lab)
        lab_eq = cv2.cvtColor(cv2.merge([clahe.apply(l_ch), a_ch, b_ch]), cv2.COLOR_LAB2RGB)
        variants.append(Image.fromarray(lab_eq))

        gray = clahe.apply(cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY))
        variants.append(Image.fromarray(cv2.cvtColor(gray, cv2.COLOR_GRAY2RGB)))

        # Max channel fights strong magenta/pink casts.
        channels = cv2.split(bgr)
        max_ch = clahe.apply(np.maximum(np.maximum(channels[0], channels[1]), channels[2]))
        variants.append(Image.fromarray(cv2.cvtColor(max_ch, cv2.COLOR_GRAY2RGB)))

        # Dark ink on bright colored paper (magenta parchi): pull ink via L + Otsu.
        blur = cv2.GaussianBlur(l_ch, (3, 3), 0)
        _, otsu = cv2.threshold(blur, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        # Ensure ink is black on white.
        if float(np.mean(otsu)) < 127:
            otsu = 255 - otsu
        variants.append(Image.fromarray(cv2.cvtColor(otsu, cv2.COLOR_GRAY2RGB)))
        adaptive = cv2.adaptiveThreshold(
            blur, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 35, 11
        )
        if float(np.mean(adaptive)) < 127:
            adaptive = 255 - adaptive
        variants.append(Image.fromarray(cv2.cvtColor(adaptive, cv2.COLOR_GRAY2RGB)))
    except Exception:
        pass

    # De-dupe identical arrays; keep at most 5 strongest variants for speed.
    unique: list[Image.Image] = []
    seen: set[bytes] = set()
    for variant in variants:
        key = np.asarray(variant).tobytes()[:4096] + str(variant.size).encode()
        if key in seen:
            continue
        seen.add(key)
        unique.append(variant)
        if len(unique) >= 5:
            break
    return unique or [preprocess(image)]


def _looks_qty(text: str) -> bool:
    cleaned = text.strip().replace(" ", "")
    if _QTY_RE.match(text.strip()) or _QTY_RE.match(cleaned):
        return True
    if re.fullmatch(r"\d{1,3}([./]\d+)?", text.strip()):
        return True
    return False


def _looks_price(text: str) -> bool:
    cleaned = text.strip().replace(",", "").lstrip("\u20b9").strip()
    if _PRICE_RE.match(cleaned):
        return True
    if re.fullmatch(r"\d{2,6}(\.\d{1,2})?", cleaned):
        return True
    return False


def _junk_token(text: str) -> bool:
    t = (text or "").strip()
    if not t:
        return True
    # Lone letters / currency scraps from shadow edges.
    if re.fullmatch(r"[A-Za-z\u0900-\u097F]", t):
        return True
    if re.fullmatch(r"[^\w\u0900-\u097F]+", t):
        return True
    return False


def _tokens_from_paddle(result) -> list[Token]:
    tokens: list[Token] = []
    if not result:
        return tokens
    # Paddle returns [ [ [box, (text, score)], ... ] ] or None lines
    pages = result if isinstance(result, list) else []
    lines = pages[0] if pages else []
    if not lines:
        return tokens
    for line in lines:
        if not line or len(line) < 2:
            continue
        box, payload = line[0], line[1]
        text = str(payload[0]).strip()
        score = float(payload[1]) if len(payload) > 1 else 0.0
        if not text or score < 0.45 or _junk_token(text):
            continue
        xs = [float(point[0]) for point in box]
        ys = [float(point[1]) for point in box]
        tokens.append(
            Token(
                text=text,
                x1=min(xs),
                y1=min(ys),
                x2=max(xs),
                y2=max(ys),
                score=score,
            )
        )
    return tokens


def _tokens_from_rapid(result) -> list[Token]:
    tokens: list[Token] = []
    if not result:
        return tokens
    for box, text, score in result:
        if not text or float(score) < 0.40 or _junk_token(str(text)):
            continue
        xs = [float(point[0]) for point in box]
        ys = [float(point[1]) for point in box]
        tokens.append(
            Token(
                text=str(text).strip(),
                x1=min(xs),
                y1=min(ys),
                x2=max(xs),
                y2=max(ys),
                score=float(score),
            )
        )
    return tokens


def _iou(a: Token, b: Token) -> float:
    x1 = max(a.x1, b.x1)
    y1 = max(a.y1, b.y1)
    x2 = min(a.x2, b.x2)
    y2 = min(a.y2, b.y2)
    if x2 <= x1 or y2 <= y1:
        return 0.0
    inter = (x2 - x1) * (y2 - y1)
    area_a = (a.x2 - a.x1) * (a.y2 - a.y1)
    area_b = (b.x2 - b.x1) * (b.y2 - b.y1)
    return inter / max(area_a + area_b - inter, 1.0)


def _containment_overlap(a: Token, b: Token) -> float:
    """Intersection divided by the smaller box (duplicate OCR inside a word box)."""
    x1 = max(a.x1, b.x1)
    y1 = max(a.y1, b.y1)
    x2 = min(a.x2, b.x2)
    y2 = min(a.y2, b.y2)
    if x2 <= x1 or y2 <= y1:
        return 0.0
    inter = (x2 - x1) * (y2 - y1)
    area_a = max(1.0, (a.x2 - a.x1) * (a.y2 - a.y1))
    area_b = max(1.0, (b.x2 - b.x1) * (b.y2 - b.y1))
    return inter / min(area_a, area_b)


def _token_richness(token: Token) -> tuple[int, int, int, float]:
    """Prefer informative OCR strings over short high-confidence scraps."""
    text = token.text or ""
    letters = sum(ch.isalpha() for ch in text)
    digits = sum(ch.isdigit() for ch in text)
    return (letters + digits, letters, len(text), token.score)


def _lexicon_rank(text: str) -> int:
    """Prefer OCR strings that are real grocery names (Oats > Dals)."""
    try:
        from grocery_correct import match_item, looks_like_price_token

        if looks_like_price_token(text):
            return -1
        _item, score, source = match_item(text)
        if source == "exact":
            return 200 + score
        if source == "fuzzy":
            return 100 + score
    except Exception:
        pass
    return 0


def _unitish_bonus(text: str) -> int:
    """Prefer qty OCR that still has a unit (200g > 2004, 2kg > 2hy)."""
    t = (text or "").strip()
    if re.search(r"(?i)\d\s*(kg|g|gm|ml|l|ltr|pcs?|piece)", t):
        return 3
    if re.fullmatch(r"(?i)\d{1,4}\s*[kKgG]", t):
        return 3
    if re.fullmatch(r"(?i)\d{1,4}(?:hy|k9)", t):
        return 2
    if re.fullmatch(r"\d{1,6}", t):
        return 1
    return 0


def _priceish_penalty(text: str) -> int:
    """Prefer clean prices (215R) over letter soup that decodes too long (215ks→2155)."""
    t = (text or "").strip()
    if re.fullmatch(r"(?i)\d{2,6}\s*(?:rs?|inr)?", t):
        return 0
    if re.search(r"(?i)\d", t) and re.search(r"(?i)[a-z]", t):
        return 1
    return 0


def _prefer_token(candidate: Token, current: Token) -> bool:
    """Replace current only when candidate is clearly better, not a tiny score bump."""
    c_unit = _unitish_bonus(candidate.text)
    e_unit = _unitish_bonus(current.text)
    if c_unit != e_unit:
        return c_unit > e_unit
    c_pen = _priceish_penalty(candidate.text)
    e_pen = _priceish_penalty(current.text)
    if c_pen != e_pen:
        return c_pen < e_pen
    c_lex = _lexicon_rank(candidate.text)
    e_lex = _lexicon_rank(current.text)
    # Exact grocery beat fuzzy/noise even if OCR confidence is slightly lower.
    if c_lex >= 200 and e_lex < 200 and candidate.score >= current.score - 0.25:
        return True
    if c_lex > e_lex + 15 and candidate.score >= current.score - 0.12:
        return True

    c_rich = _token_richness(candidate)
    e_rich = _token_richness(current)
    # Longer / richer readings beat short high-confidence scraps (aneer > as, Chawl > hawd).
    if c_rich[0] > e_rich[0] and candidate.score >= current.score - 0.15:
        return True
    if c_rich[0] >= e_rich[0] + 2 and candidate.score >= current.score - 0.20:
        return True
    if c_rich[0] == e_rich[0] and len(candidate.text) > len(current.text) and candidate.score >= current.score - 0.08:
        return True
    if c_rich[0] == e_rich[0] and len(candidate.text) == len(current.text) and candidate.score > current.score + 0.01:
        return True
    # Strong score win only when not shrinking a useful word into scraps.
    if candidate.score >= current.score + 0.08 and c_rich[0] >= max(1, e_rich[0] - 1):
        return True
    return False


def _merge_tokens(*groups: list[Token]) -> list[Token]:
    merged: list[Token] = []
    for group in groups:
        for token in group:
            overlap = None
            contained_duplicate = False
            for existing in merged:
                containment = _containment_overlap(token, existing)
                if (
                    _iou(token, existing) > 0.35
                    or containment > 0.72
                    or (
                    abs(token.cx - existing.cx) < 18 and abs(token.cy - existing.cy) < 14
                    )
                ):
                    overlap = existing
                    contained_duplicate = containment > 0.72
                    break
            if overlap is None:
                merged.append(
                    Token(
                        text=token.text,
                        x1=token.x1,
                        y1=token.y1,
                        x2=token.x2,
                        y2=token.y2,
                        score=token.score,
                    )
                )
            elif (
                _token_richness(token) > _token_richness(overlap)
                if contained_duplicate
                else _prefer_token(token, overlap)
            ):
                overlap.text = token.text
                overlap.score = token.score
                overlap.x1, overlap.y1, overlap.x2, overlap.y2 = (
                    token.x1,
                    token.y1,
                    token.x2,
                    token.y2,
                )
    return merged


def _ocr_array(arr: np.ndarray, use_hindi: bool = True) -> list[Token]:
    arr = _safe_arr(arr)
    paddle_tokens: list[Token] = []
    try:
        paddle_tokens = _tokens_from_paddle(_paddle_ocr("en", arr))
    except Exception as exc:
        print("paddle en failed, using RapidOCR:", exc, flush=True)
        paddle_tokens = []

    needs_hi = use_hindi and (
        len(paddle_tokens) < 2
        or any(_DEVANAGARI_RE.search(token.text) for token in paddle_tokens)
        or sum(ch.isalpha() for t in paddle_tokens for ch in t.text) < 3
    )
    hi_tokens: list[Token] = []
    if needs_hi:
        try:
            hi_tokens = _tokens_from_paddle(_paddle_ocr("hi", arr))
        except Exception:
            hi_tokens = []

    rapid_tokens: list[Token] = []
    try:
        with _OCR_LOCK:
            rapid_result, _ = _get_rapid()(arr)
        rapid_tokens = _tokens_from_rapid(rapid_result)
    except Exception:
        rapid_tokens = []

    if not paddle_tokens and not hi_tokens and not rapid_tokens:
        return []
    return _merge_tokens(paddle_tokens, hi_tokens, rapid_tokens)


def _collapse_qty_scraps(tokens: list[Token]) -> list[Token]:
    """Drop bare digits that sit inside a stronger qty like '5kg' / '500gm'."""
    kept: list[Token] = []
    for token in tokens:
        text = token.text.strip()
        if re.fullmatch(r"\d{1,3}", text):
            covered = False
            for other in tokens:
                if other is token:
                    continue
                other_l = other.text.lower().replace(" ", "")
                if not re.search(r"\d+(kg|g|gm|ml|l|ltr)$", other_l, re.I):
                    continue
                if abs(token.cy - other.cy) <= 18 and abs(token.cx - other.cx) <= 55:
                    covered = True
                    break
            if covered:
                continue
        kept.append(token)
    return kept


def run_ocr(image: Image.Image, use_hindi: bool = True) -> list[Token]:
    tokens: list[Token] = []
    for variant in preprocess_variants(image):
        tokens = _merge_tokens(tokens, _ocr_array(np.array(variant), use_hindi=use_hindi))
    tokens = _collapse_qty_scraps(tokens)
    tokens.sort(key=lambda token: (token.cy, token.cx))
    return tokens


def _column_breaks(centers: list[float], min_gap_ratio: float = 0.06) -> list[float]:
    if len(centers) < 2:
        return []
    ordered = sorted(centers)
    span = max(ordered[-1] - ordered[0], 1.0)
    gaps = []
    for left, right in zip(ordered, ordered[1:]):
        gap = right - left
        if gap / span >= min_gap_ratio and gap >= 22:
            gaps.append((gap, (left + right) / 2.0))
    gaps.sort(reverse=True)
    return sorted(mid for _gap, mid in gaps[:2])


def assign_columns(tokens: list[Token]) -> tuple[list[int], int]:
    centers = [token.cx for token in tokens]
    cuts = _column_breaks(centers)
    n_cols = len(cuts) + 1
    col_ids = []
    for token in tokens:
        col = 0
        for cut in cuts:
            if token.cx > cut:
                col += 1
        col_ids.append(col)
    return col_ids, n_cols


def _group_tokens_by_line(tokens: list[Token]) -> list[list[Token]]:
    """Group by vertical overlap before deciding which optional fields exist."""
    if not tokens:
        return []
    ordered = sorted(tokens, key=lambda token: (token.cy, token.cx))
    median_height = sorted(token.height for token in ordered)[len(ordered) // 2]
    tolerance = max(16.0, median_height * 0.62)
    rows: list[list[Token]] = []
    row_centers: list[float] = []
    for token in ordered:
        best_index = None
        best_distance = float("inf")
        for index, center in enumerate(row_centers):
            distance = abs(token.cy - center)
            if distance <= tolerance and distance < best_distance:
                best_index = index
                best_distance = distance
        if best_index is None:
            rows.append([token])
            row_centers.append(token.cy)
        else:
            rows[best_index].append(token)
            row_centers[best_index] = sum(part.cy for part in rows[best_index]) / len(
                rows[best_index]
            )
    for row in rows:
        row.sort(key=lambda token: token.cx)
    return [row for _, row in sorted(zip(row_centers, rows), key=lambda pair: pair[0])]


def _token_amount_role(text: str) -> tuple[str, str]:
    """Return quantity, price, ambiguous, or item plus its normalized amount."""
    raw = (text or "").strip()
    compact = re.sub(r"\s+", "", raw)
    if not raw or not re.search(r"\d", raw):
        return "item", raw

    from grocery_correct import correct_price, correct_quantity

    quantity, quantity_meta = correct_quantity(raw)
    if quantity_meta.get("number") and quantity_meta.get("unit"):
        return "quantity", quantity

    has_currency = bool(re.search(r"(?i)(?:\u20b9|rs\.?|inr|rupees?)", raw))
    price, price_meta = correct_price(raw)
    if has_currency:
        numeric = re.search(r"\d+(?:[.,]\d+)?", raw)
        if numeric:
            return "price", numeric.group(0).replace(",", "")
        if price_meta.get("source") == "normalized":
            return "price", price

    # Unit-like OCR damage is still quantity evidence (5hy -> 5 kg).
    if re.fullmatch(r"(?i)\d{1,5}(?:hy|k9|kg|kgs|gm|gms|ml|ltr|lt|l|mg)", compact):
        return "quantity", quantity or raw

    if re.fullmatch(r"\d+(?:[.,]\d+)?", compact):
        return "ambiguous", compact.replace(",", "")
    return "item", raw


def rows_from_tokens_flexible(tokens: list[Token]) -> tuple[list[dict[str, str]], int]:
    """Parse each line independently; item/quantity/price are all optional."""
    rows: list[dict[str, str]] = []
    max_columns = 1
    for line_tokens in _group_tokens_by_line(tokens):
        item_parts: list[str] = []
        quantities: list[str] = []
        prices: list[str] = []
        ambiguous: list[str] = []
        for token in line_tokens:
            role, normalized = _token_amount_role(token.text)
            if role == "quantity":
                quantities.append(normalized)
            elif role == "price":
                prices.append(normalized)
            elif role == "ambiguous":
                ambiguous.append(normalized)
            else:
                item_parts.append(token.text)

        # With two unlabeled amounts, conventional left-to-right order is
        # quantity then price. One bare amount remains unresolved for catalogue
        # pack/MRP comparison after the item has been matched.
        if len(ambiguous) >= 2:
            if not quantities:
                quantities.append(ambiguous.pop(0))
            prices.extend(ambiguous)
            ambiguous = []
        elif ambiguous and quantities and not prices:
            prices.append(ambiguous.pop(0))
        elif ambiguous and prices and not quantities:
            quantities.append(ambiguous.pop(0))

        row = {
            "item": _join(item_parts),
            "quantity": _join(quantities),
            "price": _join(prices),
        }
        if ambiguous:
            row["unassigned_amount"] = ambiguous[0]
        populated = sum(bool(row.get(key)) for key in ("item", "quantity", "price"))
        max_columns = max(max_columns, populated)
        rows.append(row)
    return rows, max_columns



def align_columns_to_rows(tokens: list[Token], col_ids: list[int], n_cols: int) -> list[dict[str, str]]:
    """Match columns by sorted order first (best for lists), then nearest leftovers."""
    cols: list[list[Token]] = [[] for _ in range(max(n_cols, 1))]
    for token, col in zip(tokens, col_ids):
        cols[min(col, len(cols) - 1)].append(token)
    for bucket in cols:
        bucket.sort(key=lambda token: token.cy)

    if not cols[0]:
        rows_raw = group_rows(tokens, col_ids)
        return [row_to_fields(cells, n_cols) for cells in rows_raw]

    items = cols[0]
    qtys = list(cols[1]) if n_cols >= 2 else []
    prices = list(cols[2]) if n_cols >= 3 else []

    # Equal-length columns: zip in reading order (stable for tilted pages).
    if qtys and len(qtys) == len(items):
        paired_qty = qtys
        qtys = []
    else:
        paired_qty = [None] * len(items)
    if prices and len(prices) == len(items):
        paired_price = prices
        prices = []
    else:
        paired_price = [None] * len(items)

    med_h = sorted(token.height for token in tokens)[len(tokens) // 2]
    if len(items) >= 2:
        spacings = [items[i + 1].cy - items[i].cy for i in range(len(items) - 1)]
        max_dist = max(med_h * 1.8, sorted(spacings)[len(spacings) // 2] * 0.6)
    else:
        max_dist = med_h * 2.5

    def take_nearest(pool: list[Token], y: float) -> Token | None:
        if not pool:
            return None
        best_i = min(range(len(pool)), key=lambda i: abs(pool[i].cy - y))
        if abs(pool[best_i].cy - y) > max_dist:
            return None
        return pool.pop(best_i)

    rows: list[dict[str, str]] = []
    for i, item in enumerate(items):
        qty = paired_qty[i] if paired_qty[i] is not None else take_nearest(qtys, item.cy)
        price = paired_price[i] if paired_price[i] is not None else take_nearest(prices, item.cy)
        fields = {
            "item": item.text,
            "quantity": qty.text if qty else "",
            "price": price.text if price else "",
        }
        if n_cols == 2 and qty and _looks_price(qty.text) and not _looks_qty(qty.text):
            fields = {"item": item.text, "quantity": "", "price": qty.text}
        rows.append(fields)

    for token in qtys:
        rows.append({"item": "", "quantity": token.text, "price": ""})
    for token in prices:
        rows.append({"item": "", "quantity": "", "price": token.text})
    return rows


def group_rows(tokens: list[Token], col_ids: list[int]) -> list[list[tuple[Token, int]]]:
    """Build rows by slicing the page at midpoints between item anchors (works on tilted photos)."""
    if not tokens:
        return []
    med_h = sorted(token.height for token in tokens)[len(tokens) // 2]
    thresh = max(28.0, med_h * 1.35)

    anchors = [i for i, col in enumerate(col_ids) if col == 0]
    if len(anchors) < max(1, len(tokens) // 5):
        order = sorted(range(len(tokens)), key=lambda i: (tokens[i].cy, tokens[i].cx))
        rows: list[list[tuple[Token, int]]] = []
        current: list[tuple[Token, int]] = []
        current_y = None
        for idx in order:
            token = tokens[idx]
            col = col_ids[idx]
            if current_y is None or abs(token.cy - current_y) <= thresh:
                current.append((token, col))
                current_y = token.cy if current_y is None else (current_y * 0.55 + token.cy * 0.45)
            else:
                rows.append(current)
                current = [(token, col)]
                current_y = token.cy
        if current:
            rows.append(current)
        return rows

    anchors = sorted(anchors, key=lambda i: tokens[i].cy)
    ys = [tokens[i].cy for i in anchors]
    bounds = []
    for i, y in enumerate(ys):
        top = (ys[i - 1] + y) / 2.0 if i > 0 else y - 1e6
        bottom = (y + ys[i + 1]) / 2.0 if i + 1 < len(ys) else y + 1e6
        bounds.append((top, bottom))

    buckets: list[list[tuple[Token, int]]] = [[] for _ in anchors]
    leftovers: list[tuple[Token, int]] = []
    for idx, token in enumerate(tokens):
        placed = False
        for b, (top, bottom) in enumerate(bounds):
            if top < token.cy <= bottom:
                buckets[b].append((token, col_ids[idx]))
                placed = True
                break
        if not placed:
            leftovers.append((token, col_ids[idx]))
    for token, col in leftovers:
        buckets.append([(token, col)])
    return [bucket for bucket in buckets if bucket]




def merge_split_rows(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    """Join a bare item line with the next qty/price-only line (common after rotation)."""
    if not rows:
        return []
    out: list[dict[str, str]] = []
    i = 0
    while i < len(rows):
        cur = dict(rows[i])
        if i + 1 < len(rows):
            nxt = rows[i + 1]
            cur_item, cur_rest = cur.get("item"), (cur.get("quantity") or cur.get("price"))
            nxt_item, nxt_rest = nxt.get("item"), (nxt.get("quantity") or nxt.get("price"))
            if cur_item and not cur_rest and nxt_rest and not nxt_item:
                cur["quantity"] = nxt.get("quantity") or ""
                cur["price"] = nxt.get("price") or ""
                i += 2
                out.append(cur)
                continue
            if cur_rest and not cur_item and nxt_item and not nxt_rest:
                cur["item"] = nxt.get("item") or ""
                i += 2
                out.append(cur)
                continue
        out.append(cur)
        i += 1
    return out


def _join(parts: list[str]) -> str:
    return " ".join(part for part in parts if part).strip()


def row_to_fields(cells: list[tuple[Token, int]], n_cols: int) -> dict[str, str]:
    by_col: dict[int, list[str]] = {}
    for token, col in cells:
        by_col.setdefault(col, []).append(token.text)

    if n_cols >= 3:
        return {
            "item": _join(by_col.get(0, [])),
            "quantity": _join(by_col.get(1, [])),
            "price": _join(by_col.get(2, [])),
        }

    if n_cols == 2:
        left = _join(by_col.get(0, []))
        right = _join(by_col.get(1, []))
        if _looks_price(right) and not _looks_qty(right):
            return {"item": left, "quantity": "", "price": right}
        if _looks_qty(right):
            return {"item": left, "quantity": right, "price": ""}
        return {"item": left, "quantity": right, "price": ""}

    line = _join([token.text for token, _ in sorted(cells, key=lambda cell: cell[0].cx)])
    return parse_single_line(line)


def parse_single_line(line: str) -> dict[str, str]:
    raw = line.strip()
    if not raw:
        return {"item": "", "quantity": "", "price": ""}

    price = ""
    qty = ""
    item = raw

    price_match = re.search(r"(rs\.?|inr)?\s*\d{1,6}([.,]\d{1,2})?\s*$", raw, re.I)
    if price_match and _NUM_RE.search(price_match.group(0)):
        before = raw[: price_match.start()].strip(" -\t")
        if before:
            price = price_match.group(0).strip()
            item = before

    qty_match = re.search(
        r"(\d+([./]\d+)?\s*(kg|g|gm|ltr|l|ml|dozen|pcs?|packet|pkts?|bunch)?)\s*$",
        item,
        re.I,
    )
    if qty_match:
        qty = qty_match.group(1).strip()
        item = item[: qty_match.start()].strip(" -\t")

    return {"item": item, "quantity": qty, "price": price}


def extract_list(image: Image.Image) -> dict[str, Any]:
    tokens = run_ocr(image)
    numeric_status: dict[str, Any] = {
        "enabled": False,
        "attempted": 0,
        "changed": 0,
    }
    if tokens:
        try:
            from numeric_ocr import refine_numeric_tokens

            tokens, numeric_status = refine_numeric_tokens(preprocess(image), tokens)
        except Exception as exc:
            numeric_status = {
                "enabled": True,
                "attempted": 0,
                "changed": 0,
                "error": f"{type(exc).__name__}: {exc}",
            }
    if not tokens:
        return {
            "rows": [],
            "columns_detected": 0,
            "message": "No text found. Try a clearer, brighter photo with space between columns.",
        }

    rows, n_cols = rows_from_tokens_flexible(tokens)
    cleaned = []
    for fields in rows:
        if not fields["item"] and not fields["quantity"] and not fields["price"]:
            continue
        joined = f"{fields['item']} {fields['quantity']} {fields['price']}".lower().strip()
        if joined in {"item qty price", "item quantity price", "s.no item qty price"}:
            continue
        cleaned.append(fields)
    rows = merge_split_rows(cleaned)
    from grocery_correct import correct_rows

    before = len(rows)
    rows = correct_rows(rows)
    dropped = max(0, before - len(rows))
    for index, row in enumerate(rows, start=1):
        row["id"] = index

    matched = sum(
        1
        for row in rows
        if (row.get("correction") or {}).get("item_source") in {"exact", "fuzzy"}
    )
    from catalogue_prices import enrich_rows

    bill = enrich_rows(rows)
    rows = bill["rows"]
    noise_note = f" Dropped {dropped} non-grocery noise line(s)." if dropped else ""
    return {
        "rows": rows,
        "columns_detected": n_cols,
        "token_count": len(tokens),
        "engine": "paddle+rapid+numeric-trocr+grocery-match",
        "numeric_ocr": numeric_status,
        "grocery_matched": matched,
        "noise_dropped": dropped,
        "bill_estimate": bill["bill_estimate"],
        "currency": bill["currency"],
        "price_disclaimer": bill["price_disclaimer"],
        "message": (
            f"Detected {n_cols} column(s) and {len(rows)} grocery line(s) "
            f"from {len(tokens)} text boxes. "
            f"Matched {matched} item(s) to the grocery list.{noise_note} "
            f"Est. bill Rs {bill['bill_estimate']}. Edit any cell."
        ),
    }
