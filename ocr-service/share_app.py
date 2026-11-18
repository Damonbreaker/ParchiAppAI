# -*- coding: utf-8 -*-
"""Public Gradio share: OCR + pencil-edit item/qty/price + catalogue bill."""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

for key in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY"):
    value = os.environ.get(key)
    if value and "://" not in value:
        os.environ[key] = "http://" + value
if not os.environ.get("http_proxy"):
    os.environ["http_proxy"] = "http://10.10.78.61:3128"
    os.environ["https_proxy"] = "http://10.10.78.61:3128"

for key in ("no_proxy", "NO_PROXY"):
    cur = os.environ.get(key, "")
    extra = "localhost,127.0.0.1,0.0.0.0,::1"
    os.environ[key] = extra if not cur else f"{cur},{extra}"

import gradio as gr
from PIL import Image

from catalogue_prices import enrich_rows, ensure_price_file

# The public share uses only redistributable catalogue records.  The private
# FastAPI app keeps the richer internal catalogue by default.
os.environ.setdefault(
    "PARCHI_CATALOGUE_DB",
    str(
        Path(__file__).resolve().parent
        / "catalogue_output"
        / "open"
        / "national_retail_catalogue_open.sqlite"
    ),
)
os.environ.setdefault("PARCHI_AI_CATALOGUE", "1")
os.environ.setdefault("PARCHI_AI_DEVICE", "cuda:2")
os.environ.setdefault("PARCHI_NUMERIC_OCR", "1")
os.environ.setdefault("PARCHI_NUMERIC_OCR_DEVICE", "cuda:1")
from catalogue_ai import warm_ai
from catalogue_runtime import suggest_row

ensure_price_file()

# Prefer legacy warm (higher accuracy). Realtime is optional.
try:
    from layout_ocr import get_engine

    get_engine()
except Exception as exc:
    print("legacy warm skipped:", exc, flush=True)

print("catalogue AI warm:", warm_ai(), flush=True)
try:
    from numeric_ocr import warm_numeric_ocr

    print("numeric OCR warm:", warm_numeric_ocr(), flush=True)
except Exception as exc:
    print("numeric OCR warm skipped:", exc, flush=True)


def _cat_label(row: dict) -> str:
    guide = row.get("catalogue_guidance") or {}
    if guide.get("matched"):
        sku = guide.get("selected_sku") or {}
        price = guide.get("price") or {}
        bits = [
            (
                "Suggested - tap pencil to verify"
                if guide.get("review_recommended")
                else str(sku.get("brand") or "Catalogue match")
            ),
            str(sku.get("pack") or ""),
        ]
        if price.get("amount") is not None:
            bits.append(
                f"Rs {price['amount']} ({price.get('price_label') or price.get('price_type')})"
            )
        else:
            bits.append("price unavailable")
        return " | ".join(bit for bit in bits if bit)
    if row.get("catalogue_price") is None:
        return "-"
    unit = row.get("catalogue_unit") or "flat"
    suffix = {
        "per_kg": "/kg",
        "per_L": "/L",
        "per_g": "/g",
        "per_ml": "/ml",
        "per_pcs": "/pc",
        "per_packet": "/pkt",
        "flat": "",
    }.get(unit, "")
    return f"Rs {row['catalogue_price']}{suffix}"


def _line_label(row: dict) -> str:
    if row.get("line_total") is None:
        return "-"
    return f"Rs {row['line_total']}"


def _resolve_row(row: dict, selections: dict | None = None) -> dict:
    from app import _catalogue_enrich_row

    out = _catalogue_enrich_row(row, selections or {})
    guide = out.get("catalogue_guidance") or {}
    price = guide.get("price") or {}
    if out.get("price"):
        try:
            out["line_total"] = float(out["price"])
            out["total_source"] = (
                "handwritten_price"
                if out.get("price_from_handwriting")
                else price.get("price_type") or "catalogue"
            )
        except (TypeError, ValueError):
            pass
    return out


def _resolve_rows(rows: list[dict]) -> dict:
    resolved = [
        _resolve_row(row, row.get("catalogue_selection") or {})
        for row in rows
    ]
    fallback = enrich_rows([row for row in resolved if not row.get("line_total")])
    fallback_map = {
        (row.get("item"), row.get("quantity")): row for row in fallback["rows"]
    }
    final = []
    total = 0.0
    for row in resolved:
        if row.get("line_total") is None:
            row = fallback_map.get((row.get("item"), row.get("quantity")), row)
        total += float(row.get("line_total") or 0)
        final.append(row)
    return {"rows": final, "bill_estimate": round(total, 2)}


def _to_view(rows: list[dict]) -> list[list]:
    out = []
    for i, row in enumerate(rows or [], start=1):
        out.append(
            [
                str(i),
                row.get("item", ""),
                row.get("quantity", ""),
                row.get("price", ""),
                _cat_label(row),
                _line_label(row),
            ]
        )
    return out or [["1", "", "", "", "-", "-"]]


def _state_rows(table) -> list[dict]:
    """Read editable columns from the view table / state."""
    rows = []
    if table is None:
        values = []
    elif hasattr(table, "values") and hasattr(table.values, "tolist"):
        values = table.values.tolist()
    else:
        values = table
    for item in values:
        if len(item) < 4:
            continue
        # view columns: #, item, qty, price, catalogue, line
        rows.append(
            {
                "item": str(item[1] if len(item) > 1 else "").strip(),
                "quantity": str(item[2] if len(item) > 2 else "").strip(),
                "price": str(item[3] if len(item) > 3 else "").strip(),
            }
        )
    return rows


def _choices(n: int) -> list[str]:
    if n <= 0:
        return ["Row 1"]
    return [f"Row {i}" for i in range(1, n + 1)]


def read_list(image: Image.Image, mode: str):
    if image is None:
        empty = [["1", "", "", "", "-", "-"]]
        return empty, empty, "Upload a list photo first.", "Est. bill: Rs 0", gr.update(choices=["Row 1"], value="Row 1"), "", "", ""

    mode = (mode or "legacy").strip().lower()
    if mode not in {"", "legacy"}:
        empty = [["1", "", "", "", "-", "-"]]
        return (
            empty,
            empty,
            "Only legacy OCR is supported (realtime-fast removed).",
            "Est. bill: Rs 0",
            gr.update(choices=["Row 1"], value="Row 1"),
            "",
            "",
            "",
        )
    try:
        from layout_ocr import extract_list

        result = extract_list(image)
    except Exception as exc:
        empty = [["1", "", "", "", "-", "-"]]
        return empty, empty, f"OCR failed: {exc}", "Est. bill: Rs 0", gr.update(choices=["Row 1"], value="Row 1"), "", "", ""

    rows = result.get("rows") or []
    if not rows:
        rows = [{"item": "", "quantity": "", "price": ""}]
    bill = _resolve_rows(rows)
    rows = bill["rows"]
    view = _to_view(rows)
    msg = result.get("message", "Done.")
    numeric = result.get("numeric_ocr") or {}
    attempted = int(numeric.get("attempted") or 0)
    changed = int(numeric.get("changed") or 0)
    if numeric.get("loaded") and attempted:
        msg = f"{msg} Dual OCR corrected {changed}/{attempted} amount box(es)."
    elif numeric.get("error"):
        msg = f"{msg} Numeric OCR skipped: {numeric.get('error')}"
    bill_txt = f"Est. bill: Rs {bill['bill_estimate']}"
    choices = _choices(len(rows))
    first = rows[0]
    return (
        view,
        view,
        msg,
        bill_txt,
        gr.update(choices=choices, value=choices[0]),
        first.get("item", ""),
        first.get("quantity", ""),
        first.get("price", ""),
    )


def load_row_for_edit(table, row_sel: str):
    rows = _state_rows(table)
    try:
        idx = max(0, int(str(row_sel).replace("Row", "").strip()) - 1)
    except Exception:
        idx = 0
    if not rows:
        return "", "", ""
    idx = min(idx, len(rows) - 1)
    r = rows[idx]
    return r.get("item", ""), r.get("quantity", ""), r.get("price", "")


def apply_pencil_edit(
    table,
    row_sel: str,
    item: str,
    qty: str,
    price: str,
    department: str,
    category: str,
    product_type: str,
    brand: str,
    product: str,
    pack_id: str,
    unit: str,
):
    rows = _state_rows(table)
    try:
        idx = max(0, int(str(row_sel).replace("Row", "").strip()) - 1)
    except Exception:
        idx = 0
    if not rows:
        rows = [{"item": "", "quantity": "", "price": ""}]
    while len(rows) <= idx:
        rows.append({"item": "", "quantity": "", "price": ""})
    edited = {
        "item": (item or "").strip(),
        "quantity": (qty or "").strip(),
        "price": (price or "").strip(),
    }
    selections = {
        "department": department or "",
        "category": category or "",
        "type": product_type or "",
        "brand": brand or "",
        "product": product or "",
        "catalogue_id": pack_id or "",
        "unit": unit or "",
    }
    rows[idx] = _resolve_row(edited, selections)
    bill = _resolve_rows(rows)
    view = _to_view(bill["rows"])
    return (
        view,
        view,
        f"Est. bill: Rs {bill['bill_estimate']}",
        gr.update(choices=_choices(len(bill["rows"])), value=f"Row {idx + 1}"),
        f"Updated Row {idx + 1}",
    )


def add_row(table):
    rows = _state_rows(table)
    rows.append({"item": "", "quantity": "", "price": ""})
    bill = _resolve_rows(rows)
    view = _to_view(bill["rows"])
    n = len(bill["rows"])
    return (
        view,
        view,
        f"Est. bill: Rs {bill['bill_estimate']}",
        gr.update(choices=_choices(n), value=f"Row {n}"),
        "",
        "",
        "",
    )


def recalculate(table):
    bill = _resolve_rows(_state_rows(table))
    view = _to_view(bill["rows"])
    return view, view, f"Est. bill: Rs {bill['bill_estimate']}"


def save_rows(table, bill_text: str):
    rows = _state_rows(table)
    stamp = time.strftime("%Y%m%d_%H%M%S")
    root = Path(__file__).resolve().parent
    path = root / "runs" / f"edited_share_{stamp}.json"
    path.parent.mkdir(exist_ok=True)
    payload = {"rows": rows, "bill_text": bill_text}
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    labels = root / "data" / "corrections.jsonl"
    labels.parent.mkdir(parents=True, exist_ok=True)
    with labels.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps({"ts": stamp, **payload}, ensure_ascii=False) + "\n")
    return f"Saved {len(rows)} row(s) as {path.name}"


def load_catalogue_choices(table, row_sel: str):
    rows = _state_rows(table)
    try:
        idx = max(0, int(str(row_sel).replace("Row", "").strip()) - 1)
    except Exception:
        idx = 0
    row = rows[min(idx, len(rows) - 1)] if rows else {"item": "", "quantity": ""}
    guide = suggest_row(row.get("item", ""), row.get("quantity", ""))
    options = guide.get("options") or {}
    selected = guide.get("selection") or {}
    packs = [
        (
            " | ".join(
                str(value)
                for value in (pack.get("brand"), pack.get("product"), pack.get("label"))
                if value
            ),
            pack.get("catalogue_id"),
        )
        for pack in options.get("packs") or []
    ]
    quantity = guide.get("quantity") or {}
    note = (
        f"Suggested: {selected.get('product') or row.get('item', '')} - "
        f"{selected.get('brand') or 'brand not specified'} - "
        f"{selected.get('pack') or 'pack not specified'}. "
        f"{quantity.get('reason') or ''} "
        f"{'AI reranked the candidates.' if (guide.get('ai') or {}).get('used') else ''}"
        if guide.get("matched")
        else "No safe catalogue match. Keep the manually editable text."
    )
    return (
        gr.update(choices=options.get("departments") or [], value=selected.get("department") or None),
        gr.update(choices=options.get("categories") or [], value=selected.get("category") or None),
        gr.update(choices=options.get("types") or [], value=selected.get("type") or None),
        gr.update(choices=options.get("brands") or [], value=selected.get("brand") or None),
        gr.update(choices=options.get("products") or [], value=selected.get("product") or None),
        gr.update(choices=packs, value=selected.get("catalogue_id") or None),
        gr.update(choices=options.get("units") or [], value=selected.get("unit") or None),
        note,
    )


with gr.Blocks(title="Parchi List OCR + Bill") as demo:
    gr.Markdown(
        """
# Parchi list reader + national catalogue

1. Upload a list photo  
2. Tap **Read list** (legacy dual-OCR)  
3. Rewrite any field, or use the catalogue polls for type, brand, pack, unit, and labelled price
"""
    )
    state_table = gr.State([["1", "", "", "", "-", "-"]])

    with gr.Row():
        photo = gr.Image(type="pil", label="List photo")
        with gr.Column():
            mode = gr.State("legacy")
            status = gr.Textbox(label="Status", interactive=False)
            bill = gr.Textbox(label="Bill", value="Est. bill: Rs 0", interactive=False)
            read_btn = gr.Button("Read list", variant="primary")

    table = gr.Dataframe(
        headers=["#", "Item", "Quantity", "Price", "Catalogue", "Line total"],
        datatype=["str", "str", "str", "str", "str", "str"],
        row_count=(1, "dynamic"),
        col_count=(6, "fixed"),
        interactive=True,
        label="Detected rows — click Item / Qty / Price cells to edit",
    )

    gr.Markdown("### Pencil rewrite (optional)")
    with gr.Row():
        row_sel = gr.Dropdown(choices=["Row 1"], value="Row 1", label="Which row?")
    with gr.Row():
        item_in = gr.Textbox(label="✏️ Item name", placeholder="e.g. Paneer")
        qty_in = gr.Textbox(label="✏️ Quantity", placeholder="e.g. 500 g")
        price_in = gr.Textbox(label="✏️ Price", placeholder="e.g. 215")
    gr.Markdown("### Catalogue polls (optional — the first suggestion is final if skipped)")
    with gr.Row():
        department_in = gr.Dropdown(label="Department")
        category_in = gr.Dropdown(label="Category")
        type_in = gr.Dropdown(label="Type")
        brand_in = gr.Dropdown(label="Brand")
    with gr.Row():
        product_in = gr.Dropdown(label="Item / variant")
        pack_in = gr.Dropdown(label="Variant / pack")
        unit_in = gr.Dropdown(label="Quantity unit")
    guide_status = gr.Textbox(label="Catalogue suggestion", interactive=False)
    with gr.Row():
        apply_btn = gr.Button("✏️ Apply edit", variant="primary")
        add_btn = gr.Button("Add row")
        recalc_btn = gr.Button("Recalculate bill")
        save_btn = gr.Button("Save edits")
    edit_status = gr.Textbox(label="Edit status", interactive=False)
    save_status = gr.Textbox(label="Save", interactive=False)

    def _sync_table_edits(table):
        """When user edits dataframe cells directly, refresh catalogue + bill."""
        bill_pack = _resolve_rows(_state_rows(table))
        view = _to_view(bill_pack["rows"])
        return view, f"Est. bill: Rs {bill_pack['bill_estimate']}"

    read_event = read_btn.click(
        fn=read_list,
        inputs=[photo, mode],
        outputs=[table, state_table, status, bill, row_sel, item_in, qty_in, price_in],
    )
    read_event.then(
        fn=load_catalogue_choices,
        inputs=[state_table, row_sel],
        outputs=[
            department_in,
            category_in,
            type_in,
            brand_in,
            product_in,
            pack_in,
            unit_in,
            guide_status,
        ],
    )
    table.change(
        fn=_sync_table_edits,
        inputs=[table],
        outputs=[state_table, bill],
    )
    row_sel.change(
        fn=load_row_for_edit,
        inputs=[state_table, row_sel],
        outputs=[item_in, qty_in, price_in],
    )
    row_sel.change(
        fn=load_catalogue_choices,
        inputs=[state_table, row_sel],
        outputs=[
            department_in,
            category_in,
            type_in,
            brand_in,
            product_in,
            pack_in,
            unit_in,
            guide_status,
        ],
    )
    apply_event = apply_btn.click(
        fn=apply_pencil_edit,
        inputs=[
            state_table,
            row_sel,
            item_in,
            qty_in,
            price_in,
            department_in,
            category_in,
            type_in,
            brand_in,
            product_in,
            pack_in,
            unit_in,
        ],
        outputs=[table, state_table, bill, row_sel, edit_status],
    )
    apply_event.then(
        fn=load_catalogue_choices,
        inputs=[state_table, row_sel],
        outputs=[
            department_in,
            category_in,
            type_in,
            brand_in,
            product_in,
            pack_in,
            unit_in,
            guide_status,
        ],
    )
    add_btn.click(
        fn=add_row,
        inputs=[state_table],
        outputs=[table, state_table, bill, row_sel, item_in, qty_in, price_in],
    )
    recalc_btn.click(
        fn=recalculate,
        inputs=[state_table],
        outputs=[table, state_table, bill],
    )
    save_btn.click(fn=save_rows, inputs=[state_table, bill], outputs=save_status)

if __name__ == "__main__":
    os.environ.setdefault("PARCHI_DEVICE", "cuda")
    os.environ.setdefault("PARCHI_CUDA_DEVICE", "1")
    os.environ.setdefault("PARCHI_TROCR", "0")
    demo.queue(default_concurrency_limit=1).launch(
        server_name="0.0.0.0",
        server_port=7867,
        share=True,
        show_error=True,
    )
