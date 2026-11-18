from __future__ import annotations

import asyncio
import os
import unittest
from unittest.mock import patch

os.environ["PARCHI_AI_CATALOGUE"] = "0"

from app import catalogue_resolve, catalogue_suggest, estimate
from catalogue_ai import RerankResult
from catalogue_runtime import CatalogueRuntime


class CatalogueRuntimeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalogue = CatalogueRuntime()
        cls.catalogue.status()

    def test_rice_keeps_number_and_corrects_grams_to_kilograms(self) -> None:
        result = self.catalogue.suggest("rice", "5 g")
        self.assertTrue(result["matched"])
        self.assertEqual(result["quantity"]["ocr_number"], "5")
        self.assertEqual(result["quantity"]["recommended_unit"], "kg")
        self.assertEqual(result["quantity"]["corrected"], "5 kg")
        self.assertTrue(result["quantity"]["unit_was_corrected"])

    def test_milk_keeps_number_and_corrects_mass_to_volume(self) -> None:
        result = self.catalogue.suggest("milk", "1 kg")
        self.assertTrue(result["matched"])
        self.assertEqual(result["quantity"]["corrected"], "1 L")

    def test_plausible_explicit_fine_unit_survives_dirty_pack_metadata(self) -> None:
        result = self.catalogue.suggest("unpolished rajma", "500 g")
        self.assertTrue(result["matched"])
        self.assertEqual(result["quantity"]["corrected"], "500 g")

    def test_damaged_multigrain_atta_is_not_promoted_to_same_pack_random_item(self) -> None:
        result = self.catalogue.suggest("mulligein atfer", "1 kg")
        self.assertTrue(result["matched"])
        self.assertEqual(result["selected_sku"]["name"].lower(), "multigrain atta")

    def test_joined_toned_milk_ocr_is_recovered(self) -> None:
        result = self.catalogue.suggest("tonedlmille", "500 ml")
        self.assertTrue(result["matched"])
        self.assertIn("toned milk", result["selected_sku"]["name"].lower())

    def test_common_vowelless_short_forms_are_auto_corrected(self) -> None:
        expected = {
            "kbl chn": "kabuli chana",
            "tmtr": "tomato",
            "pyj": "onion",
            "sgr": "sugar",
            "brd": "bread",
            "bnna": "banana",
        }
        for query, product in expected.items():
            with self.subTest(query=query):
                result = self.catalogue.suggest(query)
                self.assertTrue(result["matched"])
                self.assertIn(product, result["selected_sku"]["name"].lower())
        branded = self.catalogue.suggest("clgt")
        self.assertTrue(branded["matched"])
        self.assertIn("colgate", branded["selected_sku"]["brand"].lower())

    def test_selected_pack_is_a_real_catalogue_sku(self) -> None:
        initial = self.catalogue.suggest("paneer", "200 g")
        selected = initial["candidates"][0]
        resolved = self.catalogue.suggest(
            "paneer",
            "200 g",
            {"catalogue_id": selected["catalogue_id"]},
        )
        self.assertEqual(
            resolved["selected_sku"]["catalogue_id"],
            selected["catalogue_id"],
        )
        price = resolved["price"]
        if price:
            self.assertIn(
                price["price_type"],
                {
                    "mrp",
                    "market_price_reference",
                    "reference_price_before_discount",
                    "selling_price",
                    "observed_selling_price",
                },
            )
            self.assertEqual(price["is_mrp"], price["price_type"] == "mrp")

    def test_unknown_item_stays_manual(self) -> None:
        result = self.catalogue.suggest("xyzabc-no-such-product", "2 kg")
        self.assertFalse(result["matched"])
        self.assertEqual(result["selection"]["product"], "xyzabc-no-such-product")
        self.assertEqual(result["quantity"]["corrected"], "2 kg")

    def test_ai_only_reranks_real_candidates(self) -> None:
        candidates = [
            (
                {
                    "catalogue_id": "sku_first",
                    "canonical_name": "Jalapeno Snack",
                    "display_quantity_value": None,
                    "display_quantity_unit": "",
                },
                100.0,
            ),
            (
                {
                    "catalogue_id": "sku_second",
                    "canonical_name": "Ball Pen",
                    "display_quantity_value": None,
                    "display_quantity_unit": "",
                },
                90.0,
            ),
        ]
        with patch(
            "catalogue_runtime.rerank_scores",
            return_value=RerankResult(
                scores=[0.1, 0.9],
                model="test-reranker",
                device="cpu",
            ),
        ):
            reranked, scores, metadata = CatalogueRuntime._rerank_with_ai(
                candidates,
                "pen",
                "2 pcs",
            )
        self.assertEqual(reranked[0][0]["catalogue_id"], "sku_second")
        self.assertEqual(set(scores), {"sku_first", "sku_second"})
        self.assertTrue(metadata["used"])


class CatalogueApiTests(unittest.TestCase):
    def test_suggest_resolve_and_estimate(self) -> None:
        suggestion = asyncio.run(
            catalogue_suggest({"item": "rice", "quantity": "5 g", "limit": 3})
        )
        self.assertTrue(suggestion["matched"])

        resolved = asyncio.run(
            catalogue_resolve(
                {
                    "row": {"item": "rice", "quantity": "5 g", "price": ""},
                    "selections": suggestion["selection"],
                }
            )
        )
        self.assertEqual(resolved["quantity"], "5 kg")
        self.assertIn("catalogue_guidance", resolved)

        bill = asyncio.run(estimate({"rows": [resolved]}))
        self.assertEqual(len(bill["rows"]), 1)
        self.assertIn("price_disclaimer", bill)


if __name__ == "__main__":
    unittest.main()
