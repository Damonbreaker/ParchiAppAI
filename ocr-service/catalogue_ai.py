"""Optional local AI reranker for catalogue candidates.

The model never invents products or quantities. It only reorders real
catalogue candidates produced by the deterministic search layer.
"""

from __future__ import annotations

import math
import os
import threading
from dataclasses import dataclass
from typing import Any

MODEL_ID = os.environ.get("PARCHI_AI_MODEL", "BAAI/bge-reranker-v2-m3")


def ai_enabled() -> bool:
    return os.environ.get("PARCHI_AI_CATALOGUE", "0").strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }


def _candidate_text(row: dict[str, Any]) -> str:
    fields = [
        ("product", row.get("canonical_name")),
        ("brand", row.get("brand")),
        ("department", row.get("department_l1")),
        ("category", row.get("category_l2")),
        ("type", row.get("category_l3") or row.get("variant")),
        ("pack", row.get("pack_display")),
    ]
    return "; ".join(f"{label}: {value}" for label, value in fields if value)


def _query_text(item: str, quantity: str) -> str:
    query = (
        "Match this handwritten Indian kirana shopping-list entry to the exact "
        "catalogue product type. Prefer the ordinary item itself, not another "
        "product that merely contains the same letters or ingredient. "
        f"item: {item or 'unknown'}"
    )
    if quantity:
        query += f"; requested quantity: {quantity}"
    return query


@dataclass
class RerankResult:
    scores: list[float]
    model: str
    device: str


class LocalCatalogueReranker:
    def __init__(self, model_id: str = MODEL_ID) -> None:
        self.model_id = model_id
        self._lock = threading.Lock()
        self._infer_lock = threading.Lock()
        self._loaded = False
        self._error = ""
        self._device = "not-loaded"
        self._tokenizer = None
        self._model = None

    def _choose_device(self, torch_module) -> str:
        requested = os.environ.get("PARCHI_AI_DEVICE", "auto").strip()
        if requested and requested != "auto":
            return requested
        if torch_module.cuda.is_available():
            count = torch_module.cuda.device_count()
            # OCR normally uses GPU 1; prefer the third A4000 when available.
            return "cuda:2" if count >= 3 else f"cuda:{max(0, count - 1)}"
        return "cpu"

    def load(self) -> None:
        if self._loaded or self._error:
            return
        with self._lock:
            if self._loaded or self._error:
                return
            try:
                import torch
                from transformers import AutoModelForSequenceClassification, AutoTokenizer

                self._device = self._choose_device(torch)
                local_only = os.environ.get("PARCHI_AI_OFFLINE", "0") == "1"
                self._tokenizer = AutoTokenizer.from_pretrained(
                    self.model_id,
                    local_files_only=local_only,
                )
                dtype = torch.float16 if self._device.startswith("cuda") else torch.float32
                self._model = AutoModelForSequenceClassification.from_pretrained(
                    self.model_id,
                    dtype=dtype,
                    local_files_only=local_only,
                )
                self._model.to(self._device)
                self._model.eval()
                self._loaded = True
            except Exception as exc:
                self._error = f"{type(exc).__name__}: {exc}"

    def status(self) -> dict[str, Any]:
        return {
            "enabled": ai_enabled(),
            "loaded": self._loaded,
            "model": self.model_id,
            "device": self._device,
            "error": self._error,
        }

    def score(
        self,
        item: str,
        quantity: str,
        rows: list[dict[str, Any]],
    ) -> RerankResult | None:
        if not ai_enabled() or not rows:
            return None
        self.load()
        if not self._loaded or self._tokenizer is None or self._model is None:
            return None

        import torch

        query = _query_text(item, quantity)
        passages = [_candidate_text(row) for row in rows]
        scores: list[float] = []
        batch_size = max(1, int(os.environ.get("PARCHI_AI_BATCH_SIZE", "16")))
        with self._infer_lock, torch.inference_mode():
            for start in range(0, len(passages), batch_size):
                batch = passages[start : start + batch_size]
                encoded = self._tokenizer(
                    [query] * len(batch),
                    batch,
                    padding=True,
                    truncation=True,
                    max_length=256,
                    return_tensors="pt",
                )
                encoded = {key: value.to(self._device) for key, value in encoded.items()}
                logits = self._model(**encoded).logits.reshape(-1).float().cpu().tolist()
                scores.extend(1.0 / (1.0 + math.exp(-max(-30.0, min(30.0, value)))) for value in logits)
        return RerankResult(scores=scores, model=self.model_id, device=self._device)


_RERANKER = LocalCatalogueReranker()


def get_ai_status() -> dict[str, Any]:
    return _RERANKER.status()


def warm_ai() -> dict[str, Any]:
    if ai_enabled():
        _RERANKER.load()
    return _RERANKER.status()


def rerank_scores(
    item: str,
    quantity: str,
    rows: list[dict[str, Any]],
) -> RerankResult | None:
    return _RERANKER.score(item, quantity, rows)
