# National Retail Catalogue Build Report

Built: 2026-11-11T13:03:32+00:00

## Outputs

- **Open**: 22,046 master SKUs, 501 listings, 648 prices - `/media/user/New/Jatin/parchi-ocr/catalogue_output/National_Retail_Catalogue_Open.xlsx`
- **Internal**: 54,018 master SKUs, 37,747 listings, 75,136 prices - `/media/user/New/Jatin/parchi-ocr/catalogue_output/National_Retail_Catalogue_Internal.xlsx`

## Available sources

- **packsense_india** (ODbL-1.0): 25,209 source rows; SHA-256 `9007b7a48fe9bc12...`
- **bigbasket_28k** (unclear): 27,555 source rows; SHA-256 `3b41689c4755f195...`
- **dmart_5k** (unclear): 5,189 source rows; SHA-256 `2cff72decea6e8a9...`
- **zepto_inventory** (unclear): 3,730 source rows; SHA-256 `05f33e67581ebc76...`
- **jiomart_products** (unclear): 1,026 source rows; SHA-256 `ecd24d18d85e3fa9...`
- **open_prices_india** (ODbL-1.0): 343,096 source rows; SHA-256 `019eee3e89a3bd7e...`

## Skipped sources

- **agmarknet_daily**: RuntimeError: DATA_GOV_IN_API_KEY is not set

## Important interpretation

- This is a source-backed national catalogue foundation, not a guarantee that every Indian retail SKU is captured.
- `market_price_reference` is not represented as verified MRP.
- Selling prices and wholesale mandi prices remain separate from MRP.
- Marketplace snapshots with unclear licences appear only in the internal workbook.
- Clothing records are excluded; regulated goods are retained with flags where detectable.
