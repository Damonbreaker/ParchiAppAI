"""Runtime search and guided SKU resolution for handwritten grocery rows.

The catalogue builder remains the source of truth.  This module reads its
internal SQLite export and returns only real hierarchy/SKU combinations.
"""

from __future__ import annotations

import os
import re
import sqlite3
import threading
import unicodedata
from collections import defaultdict
from functools import lru_cache
from pathlib import Path
from typing import Any

from rapidfuzz import fuzz, process

from catalogue_ai import get_ai_status, rerank_scores
from grocery_correct import correct_quantity, match_item

ROOT = Path(__file__).resolve().parent
DEFAULT_DB = Path(
    os.environ.get(
        "PARCHI_CATALOGUE_DB",
        str(
            ROOT
            / "catalogue_output"
            / "internal"
            / "national_retail_catalogue_internal.sqlite"
        ),
    )
)

_TOKEN_RE = re.compile(r"[0-9a-z\u0900-\u097f]+")
_EMPTY_VALUES = {"", "unknown", "none", "nan", "n/a"}
_SAFE_MATCH_SCORE = 58.0
_TENTATIVE_MATCH_SCORE = 32.0
_PRICE_PRIORITY = {
    "mrp": 0,
    "market_price_reference": 1,
    "reference_price_before_discount": 2,
    "selling_price": 3,
    "observed_selling_price": 4,
}
_PRICE_LABELS = {
    "mrp": "MRP",
    "market_price_reference": "Market price reference",
    "reference_price_before_discount": "Reference price before discount",
    "selling_price": "Selling price",
    "observed_selling_price": "Observed selling price",
}

# Used only when a catalogue pack has no usable unit.  SKU pack data always wins.
_EXPECTED_UNITS = {
    "rice": ["kg", "g"],
    "chawal": ["kg", "g"],
    "atta": ["kg", "g"],
    "flour": ["kg", "g"],
    "dal": ["kg", "g"],
    "pulses": ["kg", "g"],
    "rajma": ["kg", "g"],
    "chana": ["kg", "g"],
    "kabuli": ["kg", "g"],
    "soya": ["kg", "g"],
    "chunks": ["kg", "g"],
    "paneer": ["kg", "g"],
    "sugar": ["kg", "g"],
    "salt": ["kg", "g"],
    "milk": ["L", "ml"],
    "doodh": ["L", "ml"],
    "oil": ["L", "ml"],
    "juice": ["L", "ml"],
    "water": ["L", "ml"],
    "eggs": ["pcs", "dozen"],
    "egg": ["pcs", "dozen"],
}

# High-frequency handwriting/OCR confusions that are too distorted for plain
# edit distance. Values are catalogue search terms, never final free text.
_OCR_QUERY_TOKEN_HINTS = {
    "potutfo": "potato",
    "potutfoe": "potato",
    "muligroin": "multigrain",
    "mulligein": "multigrain",
    "mulligrain": "multigrain",
    "atfer": "atta",
    "tonedmille": "toned milk",
    "tonedlmille": "toned milk",
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


def _norm(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).lower()
    text = text.replace("&", " and ")
    return " ".join(_TOKEN_RE.findall(text))


def _clean_option(value: Any) -> str:
    text = str(value or "").strip()
    if _norm(text) in _EMPTY_VALUES:
        return ""
    # Raw Open Food Facts tag paths are provenance, not useful poll labels.
    if text.startswith("en:") or "|" in text or len(text) > 90:
        return ""
    return text


def _number(value: Any) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if result > 0 else None


def _display_number(value: float | None) -> str:
    if value is None:
        return ""
    if value.is_integer():
        return str(int(value))
    return f"{value:g}"


def _display_pack(value: Any, unit: Any, count: Any = 1) -> tuple[float | None, str, str]:
    amount = _number(value)
    raw_unit = str(unit or "").strip()
    unit_key = _norm(raw_unit).replace(" ", "")
    display_unit = {
        "g": "g",
        "gm": "g",
        "gms": "g",
        "gram": "g",
        "grams": "g",
        "kg": "kg",
        "kgs": "kg",
        "kilogram": "kg",
        "kilograms": "kg",
        "ml": "ml",
        "millilitre": "ml",
        "millilitres": "ml",
        "l": "L",
        "lt": "L",
        "ltr": "L",
        "litre": "L",
        "litres": "L",
        "liter": "L",
        "liters": "L",
        "pc": "pcs",
        "pcs": "pcs",
        "piece": "pcs",
        "pieces": "pcs",
    }.get(unit_key, raw_unit)
    display_amount = amount
    if amount is not None and display_unit == "g" and amount >= 1000:
        display_amount, display_unit = amount / 1000, "kg"
    elif amount is not None and display_unit == "ml" and amount >= 1000:
        display_amount, display_unit = amount / 1000, "L"

    pack = ""
    pack_count = int(_number(count) or 1)
    if display_amount is not None and display_unit:
        pack = f"{_display_number(display_amount)} {display_unit}"
        if pack_count > 1:
            pack = f"{pack_count} x {pack}"
    return display_amount, display_unit, pack


def _same_number(left: float | None, right: float | None) -> bool:
    if left is None or right is None:
        return False
    return abs(left - right) <= max(0.01, abs(right) * 0.01)


class CatalogueRuntime:
    """In-memory search index backed by the generated catalogue SQLite file."""

    def __init__(self, db_path: Path | str = DEFAULT_DB) -> None:
        self.db_path = Path(db_path)
        self._lock = threading.Lock()
        self._loaded = False
        self.rows: list[dict[str, Any]] = []
        self.by_id: dict[str, dict[str, Any]] = {}
        self.search_docs: list[str] = []
        self.token_index: dict[str, set[int]] = defaultdict(set)
        self.alias_to_items: dict[str, set[str]] = defaultdict(set)
        self.item_to_aliases: dict[str, set[str]] = defaultdict(set)

    def _ensure_loaded(self) -> None:
        if self._loaded:
            return
        with self._lock:
            if self._loaded:
                return
            if not self.db_path.exists():
                raise FileNotFoundError(f"Catalogue database not found: {self.db_path}")

            with sqlite3.connect(self.db_path) as connection:
                connection.row_factory = sqlite3.Row
                price_rows = connection.execute(
                    """
                    SELECT catalogue_id, price_type, amount_inr, is_mrp,
                           source_id, observed_at, confidence
                    FROM prices
                    WHERE amount_inr > 0
                    ORDER BY catalogue_id,
                             CASE price_type
                               WHEN 'mrp' THEN 0
                               WHEN 'market_price_reference' THEN 1
                               WHEN 'reference_price_before_discount' THEN 2
                               WHEN 'selling_price' THEN 3
                               WHEN 'observed_selling_price' THEN 4
                               ELSE 9
                             END,
                             observed_at DESC
                    """
                ).fetchall()
                best_price: dict[str, dict[str, Any]] = {}
                for price in price_rows:
                    catalogue_id = str(price["catalogue_id"])
                    if catalogue_id in best_price:
                        continue
                    price_type = str(price["price_type"] or "")
                    best_price[catalogue_id] = {
                        "amount": float(price["amount_inr"]),
                        "price_type": price_type,
                        "price_label": _PRICE_LABELS.get(price_type, price_type.replace("_", " ").title()),
                        "is_mrp": bool(price["is_mrp"]),
                        "source": str(price["source_id"] or ""),
                        "observed_at": str(price["observed_at"] or ""),
                    }

                listing_brands: dict[str, str] = {}
                for listing in connection.execute(
                    """
                    SELECT catalogue_id, MAX(TRIM(brand)) AS brand
                    FROM listings
                    WHERE TRIM(COALESCE(brand, '')) <> ''
                    GROUP BY catalogue_id
                    """
                ):
                    listing_brands[str(listing["catalogue_id"])] = str(listing["brand"] or "")

                for alias in connection.execute(
                    "SELECT canonical_item, alias FROM aliases"
                ):
                    alias_key = _norm(alias["alias"])
                    canonical = str(alias["canonical_item"] or "").strip()
                    if alias_key and canonical:
                        self.alias_to_items[alias_key].add(canonical)
                        self.item_to_aliases[canonical].add(alias_key)

                masters = connection.execute(
                    """
                    SELECT catalogue_id, canonical_name, brand, variant, gtin,
                           pack_count, quantity_value, quantity_unit,
                           department_l1, category_l2, category_l3,
                           source_primary, source_count, confidence, review_status
                    FROM master
                    WHERE active_status <> 'out_of_stock'
                       OR TRIM(COALESCE(active_status, '')) = ''
                    """
                ).fetchall()

            for master in masters:
                row = dict(master)
                catalogue_id = str(row["catalogue_id"])
                brand = str(row.get("brand") or "").strip() or listing_brands.get(catalogue_id, "")
                row["brand"] = brand
                row["department_l1"] = _clean_option(row.get("department_l1"))
                row["category_l2"] = _clean_option(row.get("category_l2"))
                row["category_l3"] = _clean_option(row.get("category_l3"))
                row["variant"] = _clean_option(row.get("variant"))
                amount, unit, pack = _display_pack(
                    row.get("quantity_value"),
                    row.get("quantity_unit"),
                    row.get("pack_count"),
                )
                row["display_quantity_value"] = amount
                row["display_quantity_unit"] = unit
                row["pack_display"] = pack or "Pack not specified"
                row["price"] = best_price.get(catalogue_id)
                doc = _norm(
                    " ".join(
                        str(row.get(key) or "")
                        for key in (
                            "canonical_name",
                            "brand",
                            "variant",
                            "department_l1",
                            "category_l2",
                            "category_l3",
                        )
                    )
                )
                row["_name_norm"] = _norm(row.get("canonical_name"))
                row["_doc"] = doc
                index = len(self.rows)
                self.rows.append(row)
                self.by_id[catalogue_id] = row
                self.search_docs.append(doc)
                for token in set(_TOKEN_RE.findall(doc)):
                    if len(token) >= 2:
                        self.token_index[token].add(index)
            self._loaded = True

    def status(self) -> dict[str, Any]:
        self._ensure_loaded()
        return {
            "available": True,
            "database": self.db_path.name,
            "sku_count": len(self.rows),
            "priced_sku_count": sum(bool(row.get("price")) for row in self.rows),
            "ai": get_ai_status(),
        }

    def _query_terms(self, item: str) -> list[str]:
        terms: list[str] = []
        raw = _norm(item)
        if raw:
            terms.append(raw)
        corrected, _, source = match_item(item)
        corrected_norm = _norm(corrected)
        if source != "price_noise" and corrected_norm and corrected_norm not in terms:
            terms.append(corrected_norm)
        # Recover one damaged grocery word inside a longer phrase
        # (e.g. "unpolshed rayma" -> Rajma) before semantic reranking.
        corrected_tokens: list[str] = []
        recovered_token_terms: list[str] = []
        token_was_corrected = False
        for token in raw.split():
            hinted = _OCR_QUERY_TOKEN_HINTS.get(token)
            if hinted:
                token_was_corrected = True
                corrected_tokens.append(hinted)
                if hinted not in recovered_token_terms:
                    recovered_token_terms.append(hinted)
                continue
            if len(token) < 4:
                corrected_tokens.append(token)
                continue
            token_item, token_score, token_source = match_item(token, min_score=72)
            token_norm = _norm(token_item)
            if (
                token_source in {"exact", "fuzzy"}
                and token_score >= 80
                and token_norm
                and token_norm[0] == token[0]
            ):
                token_was_corrected = token_was_corrected or token_norm != token
                corrected_tokens.append(token_norm)
                if token_norm not in recovered_token_terms:
                    recovered_token_terms.append(token_norm)
            else:
                corrected_tokens.append(token)
        corrected_phrase = " ".join(corrected_tokens)
        if token_was_corrected and corrected_phrase:
            if corrected_phrase in terms:
                terms.remove(corrected_phrase)
            # A known OCR/short-form recovery is stronger evidence than a
            # whole-string fuzzy hit (e.g. bnna must be Banana, not Ganna).
            terms.insert(1 if terms else 0, corrected_phrase)
        for token_term in recovered_token_terms:
            if token_term not in terms:
                terms.append(token_term)
        if "toned" in raw and "milk" in terms and "toned milk" not in terms:
            terms.append("toned milk")
        alias_seeds = list(terms)
        if token_was_corrected:
            # The recovered term is already a catalogue search key. Expanding
            # it to broad aliases can erase specificity (clgt -> Colgate must
            # not become generic toothpaste; multigrain atta must stay exact).
            alias_seeds = []
        for seed in alias_seeds:
            for canonical in sorted(self.alias_to_items.get(seed, ())):
                canonical_norm = _norm(canonical)
                if canonical_norm and canonical_norm not in terms:
                    terms.append(canonical_norm)
                for alias in self.item_to_aliases.get(canonical, ()):
                    if len(alias.split()) <= 3 and alias not in terms:
                        terms.append(alias)
        return terms[:12]

    def _candidate_indexes(self, terms: list[str]) -> set[int]:
        indexes: set[int] = set()
        for term in terms:
            tokens = [token for token in term.split() if len(token) >= 2]
            for token in tokens:
                indexes.update(self.token_index.get(token, set()))
            for _, _, index in process.extract(
                term,
                self.search_docs,
                scorer=fuzz.WRatio,
                limit=160,
                score_cutoff=45,
            ):
                indexes.add(index)
        return indexes

    @staticmethod
    def _expected_units(terms: list[str]) -> list[str]:
        units: list[str] = []
        for term in terms:
            for key, expected in _EXPECTED_UNITS.items():
                if key in term.split():
                    for unit in expected:
                        if unit not in units:
                            units.append(unit)
        return units

    @staticmethod
    def _tentative_token_coverage(item: str, row: dict[str, Any]) -> float:
        """How much of uncertain OCR resembles the proposed product name."""
        query_tokens = [token for token in _norm(item).split() if len(token) >= 3]
        name_tokens = [
            token for token in str(row.get("_name_norm") or "").split() if len(token) >= 3
        ]
        if not query_tokens or not name_tokens:
            return 0.0
        compact_name = "".join(name_tokens)
        matched = 0
        for query in query_tokens:
            if max(
                [fuzz.ratio(query, name) for name in name_tokens]
                + [fuzz.ratio(query, compact_name)]
            ) >= 68:
                matched += 1
        return matched / len(query_tokens)

    def _rank(
        self,
        row: dict[str, Any],
        terms: list[str],
        quantity_number: float | None,
        ocr_unit: str,
    ) -> float:
        name = row["_name_norm"]
        doc = row["_doc"]
        brand = _norm(row.get("brand"))
        best = 0.0
        for term in terms:
            term_tokens = set(term.split())
            name_tokens = name.split()
            token_coverage = (
                len(term_tokens.intersection(name_tokens)) / max(1, len(term_tokens))
            )
            score = fuzz.WRatio(term, name) * 0.45 + token_coverage * 32
            if name == term:
                score += 75 + min(15, max(0, len(term_tokens) - 1) * 5)
            elif name.startswith(term + " ") or name.endswith(" " + term):
                score += 30
            elif set(term.split()).issubset(set(name_tokens)):
                score += 18
            # A handwritten generic name should prefer plain products over a
            # long prepared-food/cosmetic title that happens to contain it.
            score -= min(28, max(0, len(name_tokens) - len(term.split())) * 2.4)
            if term in doc:
                score += 5
            if brand and brand == term:
                score += 70
            best = max(best, score)

        display_amount = row.get("display_quantity_value")
        display_unit = str(row.get("display_quantity_unit") or "")
        if _same_number(quantity_number, display_amount):
            # Pack size is useful only after the item name has matched.  A
            # large bonus here allowed unrelated 1 kg products to outrank a
            # damaged-but-recognisable item name.
            best += 8
            if ocr_unit and ocr_unit.lower() == display_unit.lower():
                best += 4
        if row.get("price"):
            best += 6
            if row["price"].get("is_mrp"):
                best += 4
        if row.get("review_status") == "manual_review":
            best -= 3
        return best

    @staticmethod
    def _rerank_with_ai(
        ranked: list[tuple[dict[str, Any], float]],
        item: str,
        quantity: str,
    ) -> tuple[
        list[tuple[dict[str, Any], float]],
        dict[str, float],
        dict[str, Any],
    ]:
        eligible = [
            pair for pair in ranked if pair[1] >= _SAFE_MATCH_SCORE
        ][: max(8, int(os.environ.get("PARCHI_AI_CANDIDATES", "64")))]
        result = rerank_scores(item, quantity, [row for row, _ in eligible])
        if result is None:
            status = get_ai_status()
            return ranked, {}, {
                **status,
                "used": False,
                "candidate_count": len(eligible),
            }

        count = len(eligible)
        _, desired_quantity = correct_quantity(quantity)
        desired_number = _number(desired_quantity.get("number"))
        desired_unit = str(desired_quantity.get("unit") or "")
        ai_order = sorted(
            range(count),
            key=lambda index: result.scores[index],
            reverse=True,
        )
        ai_rank = {index: rank for rank, index in enumerate(ai_order)}
        rescored: list[tuple[dict[str, Any], float]] = []
        ai_scores: dict[str, float] = {}
        for lexical_rank, (row, lexical_score) in enumerate(eligible):
            ai_percentile = 1.0 - ai_rank[lexical_rank] / max(1, count)
            lexical_percentile = 1.0 - lexical_rank / max(1, count)
            # Rank fusion is more stable than assuming model logits are
            # calibrated probabilities across unrelated item queries.
            fused = 60.0 + 100.0 * (
                0.55 * ai_percentile + 0.45 * lexical_percentile
            )
            row_number = _number(row.get("display_quantity_value"))
            row_unit = str(row.get("display_quantity_unit") or "")
            if (
                desired_unit
                and row_unit.lower() == desired_unit.lower()
                and _same_number(desired_number, row_number)
            ):
                fused += 25.0
            elif desired_unit and row_unit.lower() == desired_unit.lower():
                fused += 3.0
            rescored.append((row, fused))
            ai_scores[str(row["catalogue_id"])] = result.scores[lexical_rank]
        rescored.sort(key=lambda pair: (-pair[1], pair[0]["canonical_name"]))
        eligible_ids = {str(row["catalogue_id"]) for row, _ in eligible}
        trailing = [
            pair
            for pair in ranked
            if str(pair[0]["catalogue_id"]) not in eligible_ids
        ]
        return rescored + trailing, ai_scores, {
            "enabled": True,
            "loaded": True,
            "used": True,
            "model": result.model,
            "device": result.device,
            "candidate_count": count,
            "error": "",
        }

    @staticmethod
    def _matches(row: dict[str, Any], selections: dict[str, Any]) -> bool:
        mapping = {
            "department": "department_l1",
            "category": "category_l2",
            "type": "category_l3",
            "brand": "brand",
            "product": "canonical_name",
        }
        for selection_key, row_key in mapping.items():
            selected = str(selections.get(selection_key) or "").strip()
            if selected:
                if selection_key == "brand" and selected == "Unbranded / not specified":
                    if _norm(row.get(row_key)):
                        return False
                elif selection_key == "type":
                    row_type = row.get("category_l3") or row.get("variant")
                    if _norm(row_type) != _norm(selected):
                        return False
                elif _norm(row.get(row_key)) != _norm(selected):
                    return False
        catalogue_id = str(selections.get("catalogue_id") or "").strip()
        return not catalogue_id or row.get("catalogue_id") == catalogue_id

    @staticmethod
    def _option_values(rows: list[dict[str, Any]], key: str, limit: int = 18) -> list[str]:
        values: list[str] = []
        seen: set[str] = set()
        for row in rows:
            value = _clean_option(row.get(key))
            norm = _norm(value)
            if not norm or norm in seen:
                continue
            seen.add(norm)
            values.append(value)
            if len(values) >= limit:
                break
        return values

    @staticmethod
    def _public_candidate(
        row: dict[str, Any],
        score: float | None = None,
        ai_score: float | None = None,
    ) -> dict[str, Any]:
        candidate = {
            "catalogue_id": row["catalogue_id"],
            "name": row.get("canonical_name") or "",
            "department": row.get("department_l1") or "",
            "category": row.get("category_l2") or "",
            "type": row.get("category_l3") or row.get("variant") or "",
            "brand": row.get("brand") or "Unbranded / not specified",
            "variant": row.get("variant") or "",
            "pack": row.get("pack_display") or "Pack not specified",
            "unit": row.get("display_quantity_unit") or "",
            "pack_quantity": row.get("display_quantity_value"),
            "gtin": row.get("gtin") or "",
            "price": row.get("price"),
            "source": row.get("source_primary") or "",
        }
        if score is not None:
            candidate["match_score"] = round(score, 1)
        if ai_score is not None:
            candidate["ai_score"] = round(ai_score, 4)
        return candidate

    def suggest(
        self,
        item: str,
        quantity: str = "",
        selections: dict[str, Any] | None = None,
        *,
        limit: int = 10,
    ) -> dict[str, Any]:
        self._ensure_loaded()
        from base_products import (
            estimate_mrp,
            filter_skus_for_base,
            resolve_base_name,
            sku_matches_base,
        )

        selections = dict(selections or {})
        base_info = resolve_base_name(item)
        base_name = str(base_info.get("base_name") or "").strip()
        # Advanced only when the writer named a brand/variant, or the UI asked
        # for brand/pack detail. Plain "atta" stays on the base name.
        wants_advanced = bool(selections.get("advanced")) or any(
            str(selections.get(key) or "").strip()
            for key in ("brand", "catalogue_id", "type", "department", "category")
        )
        selected_product = str(selections.get("product") or "").strip()
        if selected_product and _norm(selected_product) != _norm(base_name):
            wants_advanced = True
        explicit_detail = bool(base_info.get("explicit_detail"))
        simple_mode = not (wants_advanced or explicit_detail)

        # Search terms: prefer base name so AI/SKU rank stays on the staple,
        # but keep repaired OCR terms ("toned milk") so SKU rank stays specific.
        repaired = str(base_info.get("repaired") or item).strip()
        search_item = base_name if base_name and simple_mode else item
        if base_name and explicit_detail:
            search_item = f"{base_name} {item}".strip()
        terms: list[str] = []
        for seed in (search_item, repaired, item):
            if not seed:
                continue
            for term in self._query_terms(seed):
                if term not in terms:
                    terms.append(term)
        corrected_quantity, quantity_meta = correct_quantity(quantity)
        quantity_number = _number(quantity_meta.get("number"))
        ocr_unit = str(quantity_meta.get("unit") or "")
        expected_units = self._expected_units(terms)
        if (
            ocr_unit.lower() in {"", "mg"}
            and quantity_number is not None
            and quantity_number >= 50
            and len(expected_units) >= 2
        ):
            # Bare 500 beside rice/milk is much more plausibly 500 g/ml than
            # 500 kg/L. Catalogue pack evidence can still override this.
            expected_units = [expected_units[1], expected_units[0], *expected_units[2:]]
        ai_quantity = quantity
        if quantity_number is not None and expected_units:
            ai_quantity = f"{_display_number(quantity_number)} {expected_units[0]}"

        # Catalogue-only: no base lexicon match and no free-text SKU dump.
        # If the base name is known but SKUs are thin, still return the base name
        # so the UI never shows glued OCR junk like "tonedmille".
        if not base_info.get("matched"):
            return self._empty_result(item, quantity, quantity_meta)
        if not terms:
            return self._base_only_result(base_name, item, quantity, quantity_meta, base_info)

        catalogue_id = str(selections.get("catalogue_id") or "")
        ai_scores: dict[str, float] = {}
        ai_meta = {
            **get_ai_status(),
            "used": False,
            "candidate_count": 0,
        }
        if catalogue_id and catalogue_id in self.by_id:
            ranked = [(self.by_id[catalogue_id], 999.0)]
        else:
            ranked = [
                (
                    self.rows[index],
                    self._rank(self.rows[index], terms, quantity_number, ocr_unit),
                )
                for index in self._candidate_indexes(terms)
            ]
            # Prefer SKUs that belong under the base name.
            brand_hints = {
                _norm(tok) for tok in (base_info.get("brandish_tokens") or [])
            }
            if base_name:
                ranked = [
                    (row, score + (18.0 if sku_matches_base(row, base_name) else 0.0))
                    for row, score in ranked
                ]
            if brand_hints and not simple_mode:
                def _brand_boost(row: dict[str, Any], score: float) -> float:
                    brand = _norm(row.get("brand") or "")
                    name = _norm(row.get("canonical_name") or "")
                    boost = 0.0
                    for hint in brand_hints:
                        if hint in {"multigrain", "chakki", "superior", "organic", "refined"}:
                            if hint in name:
                                boost += 25.0
                        elif hint and (hint in brand or hint in name):
                            boost += 40.0
                    return score + boost

                ranked = [(row, _brand_boost(row, score)) for row, score in ranked]
            ranked.sort(key=lambda pair: (-pair[1], pair[0]["canonical_name"]))
            # Skip heavy AI rerank in simple mode — base name is enough.
            if (not simple_mode) and not any(
                str(value or "").strip() for value in selections.values()
            ):
                ranked, ai_scores, ai_meta = self._rerank_with_ai(
                    ranked,
                    item,
                    ai_quantity,
                )
                exact_priority = {
                    term: index for index, term in enumerate(terms)
                }
                available_exact = {
                    row["_name_norm"]
                    for row, _ in ranked
                    if row["_name_norm"] in exact_priority
                }
                if available_exact:
                    best_priority = min(
                        exact_priority[name] for name in available_exact
                    )
                    ranked.sort(
                        key=lambda pair: (
                            0
                            if exact_priority.get(pair[0]["_name_norm"])
                            == best_priority
                            else 1,
                            -pair[1],
                            pair[0]["canonical_name"],
                        )
                    )

        confidence_level = "high"
        broad_rows = [row for row, score in ranked[:240] if score >= _SAFE_MATCH_SCORE]
        filtered = [
            (row, score)
            for row, score in ranked
            if score >= _SAFE_MATCH_SCORE and self._matches(row, selections)
        ]
        if not filtered:
            filtered = [
                (row, score) for row, score in ranked if score >= _SAFE_MATCH_SCORE
            ]
        if not filtered:
            tentative = [
                (row, score)
                for row, score in ranked
                if score >= _TENTATIVE_MATCH_SCORE
                and self._matches(row, selections)
                and self._tentative_token_coverage(item, row) >= 0.65
            ]
            if not tentative:
                return self._base_only_result(
                    base_name, item, quantity, quantity_meta, base_info
                )
            filtered = tentative
            broad_rows = [row for row, _ in tentative[:240]]
            confidence_level = "low"

        option_rows = [row for row, _ in filtered[:160]]
        selected_row, selected_score = filtered[0]
        selected = self._public_candidate(
            selected_row,
            selected_score,
            ai_scores.get(str(selected_row["catalogue_id"])),
        )

        type_key = "category_l3"
        if not any(_clean_option(row.get(type_key)) for row in option_rows):
            type_key = "variant"

        units: list[str] = list(expected_units)
        for row in option_rows[:40]:
            unit = str(row.get("display_quantity_unit") or "")
            if unit and unit not in units:
                units.append(unit)
        if ocr_unit and ocr_unit not in units:
            units.append(ocr_unit)

        selected_pack_amount = _number(selected.get("pack_quantity"))
        selected_pack_unit = str(selected.get("unit") or "")
        implausible_bulk_unit = bool(
            quantity_number is not None
            and quantity_number >= 50
            and selected_pack_unit in {"kg", "L"}
            and expected_units
        )
        if (
            ocr_unit
            and ocr_unit.lower() != "mg"
            and ocr_unit in expected_units
            and (
                ocr_unit == expected_units[0]
                or (quantity_number is not None and quantity_number >= 50)
            )
            and not (
                quantity_number is not None
                and quantity_number >= 50
                and ocr_unit in {"kg", "L"}
            )
        ):
            # Preserve a plausible explicit handwritten fine unit such as
            # 500 g. A selected catalogue row may contain dirty pack metadata
            # (for example 500 kg) and must not override stronger OCR evidence.
            recommended_unit = ocr_unit
        elif (
            selected_pack_unit
            and not implausible_bulk_unit
            and _same_number(quantity_number, selected_pack_amount)
        ):
            recommended_unit = selected_pack_unit
        elif units:
            recommended_unit = units[0]
        else:
            recommended_unit = selected_pack_unit or ocr_unit
        selected_unit = str(selections.get("unit") or "") or recommended_unit
        corrected = ""
        if quantity_number is not None:
            corrected = _display_number(quantity_number)
            if selected_unit:
                corrected += f" {selected_unit}"

        # Restrict advanced option lists to SKUs under this base name when possible.
        base_option_rows = filter_skus_for_base(option_rows, base_name, limit=80) if base_name else option_rows
        if not base_option_rows:
            base_option_rows = option_rows
        mrp_estimate = estimate_mrp(base_option_rows) or selected.get("price")

        options = {
            "base_names": [base_name] if base_name else [],
            "departments": self._option_values(base_option_rows, "department_l1", 12),
            "categories": self._option_values(base_option_rows, "category_l2", 18),
            "types": self._option_values(base_option_rows, type_key, 18),
            "brands": self._option_values(base_option_rows, "brand", 20),
            # Advanced product list = detailed SKU names; simple UI ignores this.
            "products": self._option_values(base_option_rows, "canonical_name", 25),
            "packs": [
                {
                    "catalogue_id": row["catalogue_id"],
                    "label": row.get("pack_display") or "Pack not specified",
                    "product": row.get("canonical_name") or "",
                    "brand": row.get("brand") or "",
                    "mrp": (row.get("price") or {}).get("amount") if isinstance(row.get("price"), dict) else None,
                }
                for row in base_option_rows[:30]
            ],
            "units": units[:8],
        }

        if simple_mode:
            # Kirana default: only the base staple name, no forced brand/SKU.
            final_selection = {
                "base_name": base_name,
                "department": "",
                "category": "",
                "type": "",
                "brand": "",
                "product": base_name,
                "catalogue_id": "",
                "pack": "",
                "unit": selected_unit,
                "mode": "simple",
            }
            display_sku = {
                **selected,
                "name": base_name,
                "brand": "",
                "catalogue_id": "",
                "pack": "",
                "price": mrp_estimate,
            }
            skip_behavior = "Base kirana name is used. Open advanced description only if brand/pack is needed."
        else:
            final_selection = {
                "base_name": base_name,
                "department": selections.get("department") or selected["department"],
                "category": selections.get("category") or selected["category"],
                "type": selections.get("type") or selected["type"],
                "brand": selections.get("brand") or selected["brand"],
                "product": selections.get("product") or selected["name"],
                "catalogue_id": selected["catalogue_id"],
                "pack": selected["pack"],
                "unit": selected_unit,
                "mode": "advanced",
            }
            display_sku = selected
            skip_behavior = "Detailed catalogue SKU selected because brand/variant was given or advanced mode is on."

        return {
            "matched": True,
            "confidence_level": confidence_level,
            "review_recommended": confidence_level == "low",
            "auto_selected": True,
            "mode": "simple" if simple_mode else "advanced",
            "base_name": base_name,
            "explicit_detail": explicit_detail,
            "query": item,
            "query_terms": terms,
            "options": options,
            "selection": final_selection,
            "selected_sku": display_sku,
            "candidates": [
                self._public_candidate(
                    row,
                    score,
                    ai_scores.get(str(row["catalogue_id"])),
                )
                for row, score in filtered[:limit]
            ],
            "ai": ai_meta,
            "quantity": {
                "ocr_raw": quantity,
                "ocr_number": quantity_meta.get("number") or "",
                "ocr_unit": ocr_unit,
                "recommended_unit": recommended_unit,
                "selected_unit": selected_unit,
                "corrected": corrected or corrected_quantity,
                "unit_was_corrected": bool(
                    ocr_unit
                    and recommended_unit
                    and ocr_unit.lower() != recommended_unit.lower()
                ),
                "reason": (
                    f"Selected catalogue pack uses {recommended_unit}"
                    if recommended_unit
                    else "No catalogue unit available"
                ),
            },
            "price": mrp_estimate if simple_mode else (selected.get("price") or mrp_estimate),
            "skip_behavior": skip_behavior,
        }

    @staticmethod
    def _empty_result(
        item: str, quantity: str, quantity_meta: dict[str, Any]
    ) -> dict[str, Any]:
        return {
            "matched": False,
            "query": item,
            "query_terms": [],
            "options": {
                "departments": [],
                "categories": [],
                "types": [],
                "brands": [],
                "products": [],
                "packs": [],
                "units": [quantity_meta["unit"]] if quantity_meta.get("unit") else [],
            },
            "selection": {
                "department": "",
                "category": "",
                "type": "",
                "brand": "",
                "product": item,
                "catalogue_id": "",
                "pack": "",
                "unit": quantity_meta.get("unit") or "",
            },
            "selected_sku": None,
            "candidates": [],
            "ai": {
                **get_ai_status(),
                "used": False,
                "candidate_count": 0,
            },
            "quantity": {
                "ocr_raw": quantity,
                "ocr_number": quantity_meta.get("number") or "",
                "ocr_unit": quantity_meta.get("unit") or "",
                "recommended_unit": quantity_meta.get("unit") or "",
                "selected_unit": quantity_meta.get("unit") or "",
                "corrected": quantity,
                "unit_was_corrected": False,
                "reason": "No catalogue match; kept editable OCR value.",
            },
            "price": None,
            "skip_behavior": "No match was forced; keep or edit the OCR text.",
        }

    @staticmethod
    def _base_only_result(
        base_name: str,
        item: str,
        quantity: str,
        quantity_meta: dict[str, Any],
        base_info: dict[str, Any],
    ) -> dict[str, Any]:
        """Known kirana base name without a confident SKU hit — still usable."""
        name = (base_name or "").strip() or str(base_info.get("repaired") or item).strip()
        unit = quantity_meta.get("unit") or ""
        corrected = quantity
        number = quantity_meta.get("number")
        if number not in (None, ""):
            corrected = f"{number} {unit}".strip() if unit else str(number)
        return {
            "matched": True,
            "confidence_level": "medium",
            "review_recommended": True,
            "auto_selected": False,
            "mode": "simple",
            "base_name": name,
            "explicit_detail": bool(base_info.get("explicit_detail")),
            "query": item,
            "query_terms": [_norm(name)] if name else [],
            "options": {
                "base_names": [name] if name else [],
                "departments": [],
                "categories": [],
                "types": [],
                "brands": [],
                "products": [],
                "packs": [],
                "units": [unit] if unit else [],
            },
            "selection": {
                "department": "",
                "category": "",
                "type": "",
                "brand": "",
                "product": name,
                "base_name": name,
                "catalogue_id": "",
                "pack": "",
                "unit": unit,
                "mode": "simple",
            },
            "selected_sku": {
                "catalogue_id": "",
                "name": name,
                "brand": "",
                "unit": unit,
                "price": None,
            },
            "candidates": [],
            "ai": {
                **get_ai_status(),
                "used": False,
                "candidate_count": 0,
            },
            "quantity": {
                "ocr_raw": quantity,
                "ocr_number": quantity_meta.get("number") or "",
                "ocr_unit": unit,
                "recommended_unit": unit,
                "selected_unit": unit,
                "corrected": corrected,
                "unit_was_corrected": False,
                "reason": "Matched kirana base name; no confident branded pack yet.",
            },
            "price": None,
            "skip_behavior": "Base name kept; open advanced detail for brand/pack if needed.",
        }


@lru_cache(maxsize=1)
def get_catalogue() -> CatalogueRuntime:
    return CatalogueRuntime()


def suggest_row(
    item: str,
    quantity: str = "",
    selections: dict[str, Any] | None = None,
    *,
    limit: int = 10,
) -> dict[str, Any]:
    return get_catalogue().suggest(item, quantity, selections, limit=limit)
