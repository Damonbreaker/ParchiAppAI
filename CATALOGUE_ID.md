# Catalogue model (kirana-first)

## Two layers

1. **Base names** (what customers usually write)  
   From `ocr-service/grocery_lexicon.json` via `base_products.py`  
   Examples: `Atta`, `Chawal`, `Arhar Dal`, `Oil`, `Milk`, `Sugar`

2. **Detailed SKUs** (hidden unless needed)  
   SQLite: `ocr-service/catalogue_output/open/national_retail_catalogue_open.sqlite`  
   Column: `master.catalogue_id` (e.g. `sku_gtin_8906008233254`)  
   Env: `PARCHI_CATALOGUE_DB`

## Default behaviour

- Handwritten **atta** → output **Atta** only (simple mode). No brand forced.
- Est. MRP may still be shown from linked SKUs under that base name.
- Brand / pack / type stay behind **“Need advanced description?”**
- If the note already says a brand (e.g. *aashirvaad multigrain atta*), advanced SKU matching opens.

## Flow

OCR text → `resolve_base_name` → simple base display  
→ optional advanced → `catalogue_id` / brand / pack from national catalogue  
→ UI `CataloguePolls.jsx` (collapsed by default)
