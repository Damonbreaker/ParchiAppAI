"""Cheap AI handwriting reader for kirana lists (Gemini Flash-Lite).

One multimodal API call per photo → structured JSON rows.
No local TrOCR / PaddleOCR. Catalogue matching stays separate.
"""

from __future__ import annotations

import base64
import json
import mimetypes
import os
import re
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent

# Flash-Lite is the cheapest Gemini vision tier that still reads handwriting well.
DEFAULT_MODEL = os.environ.get("PARCHI_AI_OCR_MODEL", "gemini-flash-lite-latest")
API_KEY_PATHS = [
    Path(os.environ["GEMINI_API_KEY_FILE"]) if os.environ.get("GEMINI_API_KEY_FILE") else None,
    Path.home() / ".config" / "gemini" / "api_key",
    ROOT / ".gemini_api_key",
]

_CUSTOMER_PROMPT = """You read a handwritten Indian kirana / grocery shopping list photo.

Return ONLY valid JSON (no markdown) with this shape:
{"rows":[{"item":"string","quantity":"string","price":""}]}

Rules:
- item: the grocery name as written (Hindi or English), keep short.
- quantity: number + unit if present (e.g. "5 kg", "2", "500 g"). Empty string if none.
- price: ALWAYS empty string for customer lists (they do not write prices).
- One row per line item. Skip titles, dates, totals, doodles.
- If unsure of a word, best guess from context; do not invent items not on the page.
- Prefer simple staple words when that is what was written (atta, rice, dal).
- Fix glued / mangled handwriting into normal grocery words
  (e.g. tonedmille → toned milk, aata → atta, brwnbrd → brown bread).
- Never invent nonsense compound words; use the everyday kirana name.
"""

_SHOP_PROMPT = """You read a handwritten or printed Indian kirana list that may have THREE columns: item, quantity, price.

Return ONLY valid JSON (no markdown) with this shape:
{"rows":[{"item":"string","quantity":"string","price":"string"}]}

Rules:
- item: grocery name as written.
- quantity: number + unit if present, else "".
- price: rupee amount if a price column/value is present, else "".
- One row per line. Skip headers and bill totals unless they are clearly line items.
- Do not invent items that are not on the page.
"""


def _load_api_key() -> str:
    env = (os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY") or "").strip()
    if env:
        return env
    for path in API_KEY_PATHS:
        if path and path.is_file():
            key = path.read_text(encoding="utf-8").strip()
            if key:
                return key
    raise RuntimeError(
        "No Gemini API key. Set GEMINI_API_KEY or put the key in ~/.config/gemini/api_key"
    )


def _extract_json(text: str) -> dict[str, Any]:
    raw = (text or "").strip()
    if raw.startswith("```"):
        raw = re.sub(r"^```(?:json)?\s*", "", raw)
        raw = re.sub(r"\s*```$", "", raw)
    try:
        data = json.loads(raw)
        if isinstance(data, dict):
            return data
        if isinstance(data, list):
            return {"rows": data}
    except json.JSONDecodeError:
        pass
    m = re.search(r"\{[\s\S]*\}", raw)
    if not m:
        return {"rows": []}
    data = json.loads(m.group(0))
    return data if isinstance(data, dict) else {"rows": []}


def _normalize_rows(payload: dict[str, Any]) -> list[dict[str, Any]]:
    rows_in = payload.get("rows") or payload.get("items") or []
    out: list[dict[str, Any]] = []
    if not isinstance(rows_in, list):
        return out
    for entry in rows_in:
        if not isinstance(entry, dict):
            continue
        item = str(entry.get("item") or entry.get("name") or "").strip()
        if not item:
            continue
        qty = str(entry.get("quantity") or entry.get("qty") or "").strip()
        price = str(entry.get("price") or "").strip()
        out.append({"item": item, "quantity": qty, "price": price, "ocr_item": item})
    return out


def read_handwritten_list(image_path: str | Path, *, role: str = "customer") -> dict[str, Any]:
    """Call Gemini Flash-Lite once and return {rows, model, usage}."""
    path = Path(image_path)
    mime = mimetypes.guess_type(str(path))[0] or "image/jpeg"
    blob = path.read_bytes()
    if not blob:
        return {"rows": [], "model": DEFAULT_MODEL, "error": "empty image"}

    key = _load_api_key()
    models = [
        DEFAULT_MODEL,
        "gemini-3.1-flash-lite",
        "gemini-flash-lite-latest",
    ]
    # de-dupe while preserving order
    seen: set[str] = set()
    model_list = [m for m in models if not (m in seen or seen.add(m))]
    prompt = _SHOP_PROMPT if role.strip().lower() in {"shop", "shopkeeper", "store"} else _CUSTOMER_PROMPT
    body = {
        "contents": [
            {
                "role": "user",
                "parts": [
                    {"text": prompt},
                    {
                        "inline_data": {
                            "mime_type": mime,
                            "data": base64.b64encode(blob).decode("ascii"),
                        }
                    },
                ],
            }
        ],
        "generationConfig": {
            "temperature": 0.1,
            "maxOutputTokens": 2048,
            "responseMimeType": "application/json",
        },
    }
    payload: dict[str, Any] | None = None
    model_used = model_list[0]
    last_err: Exception | None = None
    for model in model_list:
        model_used = model
        url = (
            f"https://generativelanguage.googleapis.com/v1beta/models/"
            f"{model}:generateContent?key={key}"
        )
        raw_body = json.dumps(body).encode("utf-8")
        for attempt in range(3):
            req = urllib.request.Request(
                url,
                data=raw_body,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            try:
                with urllib.request.urlopen(req, timeout=120) as resp:
                    payload = json.loads(resp.read().decode("utf-8"))
                last_err = None
                break
            except urllib.error.HTTPError as exc:
                detail = exc.read().decode("utf-8", errors="replace")[:400]
                last_err = RuntimeError(f"Gemini HTTP {exc.code}: {detail}")
                if exc.code in {429, 503} and attempt < 2:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                break
        if payload is not None:
            break
    if payload is None:
        raise last_err or RuntimeError("Gemini request failed")

    candidates = payload.get("candidates") or []
    text = ""
    if candidates:
        parts = ((candidates[0].get("content") or {}).get("parts")) or []
        text = "".join(str(p.get("text") or "") for p in parts)
    rows = _normalize_rows(_extract_json(text))
    usage = payload.get("usageMetadata") or {}
    return {
        "rows": rows,
        "model": model_used,
        "raw_text": text[:2000],
        "usage": {
            "prompt_tokens": usage.get("promptTokenCount"),
            "candidates_tokens": usage.get("candidatesTokenCount"),
            "total_tokens": usage.get("totalTokenCount"),
        },
    }
