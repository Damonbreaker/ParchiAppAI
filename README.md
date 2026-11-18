# ParchiAppAI

Clone of **ParchiApp** where handwriting recognition is done by **Gemini Flash-Lite** (cheap multimodal AI) instead of local dual-OCR (Paddle + TrOCR).

Same kirana product flow: scan ? base catalogue names (`Atta`, `Chawal`, ù) ? optional advanced brand/pack ? send to shopkeeper.

## Ports (side-by-side with original ParchiApp)

| Service | ParchiApp | ParchiAppAI |
|---|---|---|
| UI | 5173 | **5174** |
| API | 8080 | **8081** |
| OCR | 7870 | **7871** |
| DB | `kirana` | **`kirana_ai`** |

## Cost

- One Flash-Lite vision call per photo (typically fractions of a cent).
- Catalogue match is lexical only (`PARCHI_AI_CATALOGUE=0`) ù no local BGE GPU rerank.
- No local OCR GPUs required for reading.

API key: `~/.config/gemini/api_key` or env `GEMINI_API_KEY`.  
Model override: `PARCHI_AI_OCR_MODEL` (default `gemini-flash-lite-latest`).

## Run

```bash
./scripts/run.sh
./scripts/stop.sh
```

- UI: http://127.0.0.1:5174  
- Customer: `9000000001` / `test123`  
- Shop: `9000000011` / `test123`

Outbound Gemini calls use the campus HTTP proxy; the app itself binds on localhost.
