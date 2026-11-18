from __future__ import annotations

import os
import unittest
from unittest.mock import patch

os.environ["PARCHI_AI_CATALOGUE"] = "0"

from app import _catalogue_enrich_row
from grocery_correct import correct_quantity
from layout_ocr import Token, _merge_tokens, rows_from_tokens_flexible
from numeric_ocr import extract_number, fuse_number


def token(text: str, x: float, y: float = 50) -> Token:
    return Token(text=text, x1=x, y1=y - 10, x2=x + 80, y2=y + 10)


class FlexibleColumnTests(unittest.TestCase):
    def test_item_only(self) -> None:
        rows, columns = rows_from_tokens_flexible([token("Rice", 10)])
        self.assertEqual(rows, [{"item": "Rice", "quantity": "", "price": ""}])
        self.assertEqual(columns, 1)

    def test_item_and_quantity(self) -> None:
        rows, columns = rows_from_tokens_flexible(
            [token("Rice", 10), token("5kg", 300)]
        )
        self.assertEqual(rows[0]["quantity"], "5 kg")
        self.assertEqual(rows[0]["price"], "")
        self.assertEqual(columns, 2)

    def test_item_and_price(self) -> None:
        rows, _ = rows_from_tokens_flexible(
            [token("Rice", 10), token("Rs 250", 300)]
        )
        self.assertEqual(rows[0]["quantity"], "")
        self.assertEqual(rows[0]["price"], "250")

    def test_all_three_fields(self) -> None:
        rows, columns = rows_from_tokens_flexible(
            [token("Rice", 10), token("5kg", 260), token("Rs 400", 500)]
        )
        self.assertEqual(
            rows[0],
            {"item": "Rice", "quantity": "5 kg", "price": "400"},
        )
        self.assertEqual(columns, 3)

    def test_one_bare_amount_is_deferred(self) -> None:
        rows, _ = rows_from_tokens_flexible(
            [token("Rice", 10), token("500", 300)]
        )
        self.assertEqual(rows[0]["unassigned_amount"], "500")
        self.assertEqual(rows[0]["quantity"], "")
        self.assertEqual(rows[0]["price"], "")

    def test_quantity_or_price_only_rows_survive_parsing(self) -> None:
        quantity_rows, _ = rows_from_tokens_flexible([token("1kg", 300)])
        price_rows, _ = rows_from_tokens_flexible([token("Rs 90", 300)])
        self.assertEqual(quantity_rows[0]["quantity"], "1 kg")
        self.assertEqual(price_rows[0]["price"], "90")

    def test_contained_ocr_scrap_does_not_replace_full_item(self) -> None:
        full = Token("Unpolished Rajma", 10, 40, 300, 90, 0.82)
        scrap = Token("Raina", 180, 50, 280, 85, 0.90)
        merged = _merge_tokens([scrap], [full])
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0].text, "Unpolished Rajma")

    def test_ky_ocr_unit_is_kilograms(self) -> None:
        quantity, _ = correct_quantity("1 ky")
        self.assertEqual(quantity, "1 kg")


class CatalogueAmountInferenceTests(unittest.TestCase):
    @staticmethod
    def guidance(pack_quantity: float, unit: str, price: float | None = None) -> dict:
        price_data = (
            {
                "amount": price,
                "price_type": "mrp",
                "price_label": "MRP",
                "source": "test",
                "is_mrp": True,
            }
            if price is not None
            else None
        )
        return {
            "matched": True,
            "selection": {
                "product": "Test Rice",
                "catalogue_id": "sku_test",
                "unit": unit,
            },
            "selected_sku": {
                "name": "Test Rice",
                "catalogue_id": "sku_test",
                "pack_quantity": pack_quantity,
                "unit": unit,
            },
            "quantity": {
                "corrected": f"{int(pack_quantity)} {unit}",
            },
            "price": price_data,
            "ai": {"used": True},
        }

    def test_bare_amount_matching_pack_becomes_quantity(self) -> None:
        with patch(
            "catalogue_runtime.suggest_row",
            return_value=self.guidance(500, "g"),
        ):
            row = _catalogue_enrich_row(
                {"item": "Rice", "quantity": "", "price": "", "unassigned_amount": "500"}
            )
        self.assertEqual(row["quantity"], "500 g")
        self.assertEqual(row["amount_inferred_as"], "quantity")

    def test_bare_amount_matching_mrp_becomes_price(self) -> None:
        with patch(
            "catalogue_runtime.suggest_row",
            return_value=self.guidance(5, "kg", 120),
        ):
            row = _catalogue_enrich_row(
                {"item": "Rice", "quantity": "", "price": "", "unassigned_amount": "120"}
            )
        self.assertEqual(row["price"], "120")
        self.assertEqual(row["amount_inferred_as"], "price")

    def test_unmatched_ocr_is_not_exposed_as_a_final_item(self) -> None:
        with patch(
            "catalogue_runtime.suggest_row",
            return_value={
                "matched": False,
                "selection": {},
                "selected_sku": None,
            },
        ):
            row = _catalogue_enrich_row(
                {"item": "potutfo IKE", "quantity": "1 kg", "price": ""}
            )
        self.assertEqual(row["item"], "Needs confirmation")
        self.assertEqual(row["ocr_item"], "potutfo IKE")
        self.assertTrue(row["catalogue_match_required"])
        self.assertFalse(row["catalogue_validated"])

    def test_matched_final_item_always_uses_catalogue_name(self) -> None:
        guidance = self.guidance(1, "kg")
        guidance["ai"] = {"used": False}
        with patch("catalogue_runtime.suggest_row", return_value=guidance):
            row = _catalogue_enrich_row(
                {"item": "mulligein atfer", "quantity": "1 kg", "price": ""}
            )
        self.assertEqual(row["item"], "Test Rice")
        self.assertTrue(row["catalogue_validated"])


class NumericOcrFusionTests(unittest.TestCase):
    def test_digit_lookalikes_are_normalized(self) -> None:
        self.assertEqual(extract_number("IOO"), "100")
        self.assertEqual(extract_number("five"), "5")

    def test_specialist_consensus_corrects_digits_and_preserves_unit(self) -> None:
        fused, changed, reason = fuse_number("2004 mg", ["200", "200"])
        self.assertEqual(fused, "200 mg")
        self.assertTrue(changed)
        self.assertEqual(reason, "specialist_consensus")

    def test_primary_specialist_agreement_wins_split_vote(self) -> None:
        fused, changed, reason = fuse_number("50 kg", ["50", "500"])
        self.assertEqual(fused, "50 kg")
        self.assertFalse(changed)
        self.assertEqual(reason, "primary_specialist_agreement")

    def test_disagreeing_specialists_do_not_overwrite_primary(self) -> None:
        fused, changed, reason = fuse_number("250 g", ["200", "500"])
        self.assertEqual(fused, "250 g")
        self.assertFalse(changed)
        self.assertEqual(reason, "specialists_disagree")

    def test_untrusted_unit_noise_is_not_promoted(self) -> None:
        fused, changed, _reason = fuse_number("5thy", ["5", "5"])
        self.assertEqual(fused, "5")
        self.assertTrue(changed)


if __name__ == "__main__":
    unittest.main()
