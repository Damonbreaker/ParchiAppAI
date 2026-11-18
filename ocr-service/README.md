# ParchiApp OCR (legacy dual-OCR + catalogue)

First-class OCR for ParchiApp. **Realtime-fast is not included.**

Pipeline: layout OCR → TrOCR numeric → grocery correct → open national catalogue → optional BGE rerank.

## Endpoints

- `GET /health` → `{ "status": "ok", "pipeline": "legacy" }`
- `POST /scan` multipart `file` → `{ "lines": [ { "text": "Atta 5 kg", "confidence": 0.8 } ], "pipeline": "legacy" }`

## Run

Prefer the app launcher:

```bash
../scripts/run.sh
```

Or alone:

```bash
./run_ocr.sh
```
