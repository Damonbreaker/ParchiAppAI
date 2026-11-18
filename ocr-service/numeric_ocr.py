"""Complementary handwritten-number OCR for quantity and price token crops.

The primary OCR still detects text and layout. This module only revisits
amount-like boxes, reads two crop variants with TrOCR, and replaces digits only
when the specialist reaches consensus or agrees with the primary OCR.
"""

from __future__ import annotations

import math
import os
import re
import threading
from dataclasses import dataclass
from typing import Any

import numpy as np
from PIL import Image, ImageEnhance, ImageOps

MODEL_ID = os.environ.get(
    "PARCHI_NUMERIC_OCR_MODEL", "microsoft/trocr-small-handwritten"
)
_NUMBER_RE = re.compile(r"\d+(?:[.,/]\d+)?")
_AMOUNT_LIKE_RE = re.compile(r"(?i)^[0-9iIlLoOsSbBzZ?rskKgmMeE.,/ -]{1,12}$")
_WORD_NUMBERS = {
    "zero": "0",
    "one": "1",
    "two": "2",
    "three": "3",
    "four": "4",
    "five": "5",
    "six": "6",
    "seven": "7",
    "eight": "8",
    "nine": "9",
    "ten": "10",
}


def numeric_ocr_enabled() -> bool:
    return os.environ.get("PARCHI_NUMERIC_OCR", "1").strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }


@dataclass
class NumericReading:
    value: str
    confidence: float
    raw: str


class NumericRecognizer:
    def __init__(self, model_id: str = MODEL_ID) -> None:
        self.model_id = model_id
        self._lock = threading.Lock()
        self._infer_lock = threading.Lock()
        self._loaded = False
        self._error = ""
        self._device = "not-loaded"
        self._processor = None
        self._model = None

    @staticmethod
    def _choose_device(torch_module: Any) -> str:
        requested = os.environ.get("PARCHI_NUMERIC_OCR_DEVICE", "auto").strip()
        if requested and requested != "auto":
            return requested
        if torch_module.cuda.is_available():
            count = torch_module.cuda.device_count()
            return "cuda:1" if count >= 2 else "cuda:0"
        return "cpu"

    def load(self) -> None:
        if self._loaded or self._error or not numeric_ocr_enabled():
            return
        with self._lock:
            if self._loaded or self._error:
                return
            try:
                import torch
                from transformers import TrOCRProcessor, VisionEncoderDecoderModel

                self._device = self._choose_device(torch)
                offline = os.environ.get("PARCHI_NUMERIC_OCR_OFFLINE", "0") == "1"
                self._processor = TrOCRProcessor.from_pretrained(
                    self.model_id,
                    local_files_only=offline,
                )
                self._model = VisionEncoderDecoderModel.from_pretrained(
                    self.model_id,
                    local_files_only=offline,
                )
                self._model.to(self._device)
                self._model.eval()
                self._loaded = True
            except Exception as exc:
                self._error = f"{type(exc).__name__}: {exc}"

    def status(self) -> dict[str, Any]:
        return {
            "enabled": numeric_ocr_enabled(),
            "loaded": self._loaded,
            "model": self.model_id,
            "device": self._device,
            "error": self._error,
        }

    def read(self, crops: list[Image.Image]) -> list[NumericReading | None]:
        if not crops or not numeric_ocr_enabled():
            return [None] * len(crops)
        self.load()
        if not self._loaded or self._processor is None or self._model is None:
            return [None] * len(crops)

        import torch

        batch_size = max(1, int(os.environ.get("PARCHI_NUMERIC_OCR_BATCH_SIZE", "16")))
        readings: list[NumericReading | None] = []
        with self._infer_lock, torch.inference_mode():
            for start in range(0, len(crops), batch_size):
                batch = crops[start : start + batch_size]
                pixels = self._processor(
                    images=[crop.convert("RGB") for crop in batch],
                    return_tensors="pt",
                ).pixel_values.to(self._device)
                generated = self._model.generate(
                    pixels,
                    max_new_tokens=12,
                    num_beams=3,
                    return_dict_in_generate=True,
                    output_scores=True,
                )
                texts = self._processor.batch_decode(
                    generated.sequences,
                    skip_special_tokens=True,
                )
                sequence_scores = getattr(generated, "sequences_scores", None)
                for index, text in enumerate(texts):
                    value = extract_number(text)
                    score = (
                        float(sequence_scores[index].detach().float().cpu())
                        if sequence_scores is not None
                        else -1.5
                    )
                    confidence = max(0.0, min(1.0, math.exp(score)))
                    readings.append(
                        NumericReading(value, confidence, text.strip())
                        if value
                        else None
                    )
        return readings


def extract_number(text: str) -> str:
    raw = (text or "").strip().lower()
    if raw in _WORD_NUMBERS:
        return _WORD_NUMBERS[raw]
    match = _NUMBER_RE.search(raw)
    if match:
        return match.group(0).replace(",", ".")
    compact = re.sub(r"\s+", "", raw)
    if compact and _AMOUNT_LIKE_RE.fullmatch(compact):
        translated = compact.translate(
            str.maketrans(
                {
                    "o": "0",
                    "O": "0",
                    "i": "1",
                    "I": "1",
                    "l": "1",
                    "L": "1",
                    "s": "5",
                    "S": "5",
                    "b": "8",
                    "B": "8",
                    "z": "2",
                    "Z": "2",
                }
            )
        )
        match = _NUMBER_RE.search(translated)
        if match:
            return match.group(0).replace(",", ".")
    return ""


def fuse_number(original: str, specialist_values: list[str]) -> tuple[str, bool, str]:
    """Fuse two specialist crop variants with the primary OCR text."""
    values = [value for value in specialist_values if value]
    if not values:
        return original, False, "no_specialist_reading"
    primary_match = _NUMBER_RE.search(original.replace(",", "."))
    primary = primary_match.group(0) if primary_match else ""

    counts: dict[str, int] = {}
    for value in values:
        counts[value] = counts.get(value, 0) + 1
    specialist = max(counts, key=lambda value: (counts[value], value == primary))
    consensus = counts[specialist] >= 2
    if primary and not consensus and primary not in values:
        return original, False, "specialists_disagree"
    chosen = primary if primary and primary in values else specialist

    suffix_match = re.search(r"(?i)\d+(?:[.,/]\d+)?\s*([a-z]+)\s*$", original)
    raw_unit = suffix_match.group(1).lower() if suffix_match else ""
    unit = {
        "kg": "kg",
        "kgs": "kg",
        "k9": "kg",
        "ky": "kg",
        "hy": "kg",
        "g": "g",
        "gm": "g",
        "gms": "g",
        "gram": "g",
        "grams": "g",
        "mg": "mg",
        "ml": "ml",
        "m1": "ml",
        "l": "L",
        "lt": "L",
        "ltr": "L",
        "pcs": "pcs",
        "pc": "pcs",
    }.get(raw_unit, "")
    has_currency = bool(
        re.search(r"(?i)(?:\u20b9|rs\.?|inr|rupees?)", original)
    )
    if has_currency:
        fused = f"Rs {chosen}"
    elif unit:
        fused = f"{chosen} {unit}"
    else:
        fused = chosen
    return fused, fused.strip() != original.strip(), (
        "specialist_consensus" if consensus else "primary_specialist_agreement"
    )


def _crop(arr: np.ndarray, token: Any, pad: float = 0.16) -> Image.Image:
    height, width = arr.shape[:2]
    box_width = max(1.0, token.x2 - token.x1)
    box_height = max(1.0, token.y2 - token.y1)
    x1 = max(0, int(token.x1 - box_width * pad))
    y1 = max(0, int(token.y1 - box_height * pad))
    x2 = min(width, int(token.x2 + box_width * pad))
    y2 = min(height, int(token.y2 + box_height * pad))
    image = Image.fromarray(arr[y1:y2, x1:x2]).convert("RGB")
    target_height = 96
    scale = max(1.0, target_height / max(1, image.height))
    image = image.resize(
        (max(16, int(image.width * scale)), target_height),
        Image.Resampling.LANCZOS,
    )
    return ImageOps.expand(image, border=10, fill="white")


def _variants(crop: Image.Image) -> list[Image.Image]:
    base = ImageOps.autocontrast(crop.convert("L"), cutoff=1)
    strong = ImageEnhance.Contrast(base).enhance(1.8)
    threshold = strong.point(lambda value: 255 if value > 155 else 0)
    return [base.convert("RGB"), threshold.convert("RGB")]


def _candidate_tokens(tokens: list[Any]) -> list[Any]:
    from layout_ocr import _group_tokens_by_line

    candidates: list[Any] = []
    for line in _group_tokens_by_line(tokens):
        for index, token in enumerate(line):
            compact = re.sub(r"\s+", "", str(token.text or ""))
            if re.search(r"\d", compact):
                candidates.append(token)
                continue
            if (
                index > 0
                and index == len(line) - 1
                and token.x1 - line[index - 1].x2
                > max(24.0, token.height * 1.2)
                and _AMOUNT_LIKE_RE.fullmatch(compact)
            ):
                candidates.append(token)
    return candidates


def refine_numeric_tokens(
    image: Image.Image | np.ndarray,
    tokens: list[Any],
) -> tuple[list[Any], dict[str, Any]]:
    """Run number OCR on amount-like boxes and modify only their numeric text."""
    recognizer = _RECOGNIZER
    if not numeric_ocr_enabled() or not tokens:
        return tokens, {**recognizer.status(), "attempted": 0, "changed": 0}

    arr = (
        np.asarray(image.convert("RGB"), dtype=np.uint8)
        if isinstance(image, Image.Image)
        else np.asarray(image, dtype=np.uint8)
    )
    candidates = _candidate_tokens(tokens)
    if not candidates:
        return tokens, {**recognizer.status(), "attempted": 0, "changed": 0}

    crops: list[Image.Image] = []
    for token in candidates:
        crops.extend(_variants(_crop(arr, token)))
    readings = recognizer.read(crops)

    changed = 0
    evidence = []
    for index, token in enumerate(candidates):
        pair = readings[index * 2 : index * 2 + 2]
        values = [reading.value for reading in pair if reading is not None]
        fused, used, reason = fuse_number(token.text, values)
        evidence.append(
            {
                "original": token.text,
                "specialist": values,
                "selected": fused,
                "used": used,
                "reason": reason,
            }
        )
        if used:
            token.text = fused
            changed += 1
    return tokens, {
        **recognizer.status(),
        "attempted": len(candidates),
        "changed": changed,
        "evidence": evidence,
    }


_RECOGNIZER = NumericRecognizer()


def numeric_ocr_status() -> dict[str, Any]:
    return _RECOGNIZER.status()


def warm_numeric_ocr() -> dict[str, Any]:
    _RECOGNIZER.load()
    return _RECOGNIZER.status()

