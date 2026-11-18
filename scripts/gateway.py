#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Serve frontend/dist and proxy /api to Spring :8081 (ParchiAppAI)."""

from __future__ import annotations

import mimetypes
import os
from pathlib import Path

import httpx
import uvicorn
from fastapi import FastAPI, Request, Response
from fastapi.responses import FileResponse

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "frontend" / "dist"
API = os.environ.get("PARCHI_API", "http://127.0.0.1:8081")

app = FastAPI()
# trust_env=False: never send localhost API calls through campus HTTP_PROXY/Squid
client = httpx.AsyncClient(
    base_url=API, timeout=httpx.Timeout(180.0, connect=5.0), trust_env=False
)


@app.on_event("shutdown")
async def _close() -> None:
    await client.aclose()


@app.api_route(
    "/api/{path:path}",
    methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"],
)
async def proxy_api(path: str, request: Request) -> Response:
    body = await request.body()
    headers = {
        k: v
        for k, v in request.headers.items()
        if k.lower() not in {"host", "content-length", "connection", "origin"}
    }
    upstream = await client.request(
        request.method,
        f"/api/{path}",
        params=request.query_params,
        content=body,
        headers=headers,
    )
    out_headers = {
        k: v
        for k, v in upstream.headers.items()
        if k.lower()
        not in {"content-encoding", "transfer-encoding", "connection", "content-length"}
    }
    return Response(
        content=upstream.content,
        status_code=upstream.status_code,
        headers=out_headers,
    )


@app.get("/{full_path:path}")
async def spa(full_path: str = "") -> FileResponse:
    if full_path:
        candidate = (DIST / full_path).resolve()
        if str(candidate).startswith(str(DIST.resolve())) and candidate.is_file():
            media = mimetypes.guess_type(str(candidate))[0] or "application/octet-stream"
            return FileResponse(candidate, media_type=media)
    return FileResponse(DIST / "index.html", media_type="text/html")


if __name__ == "__main__":
    if not (DIST / "index.html").is_file():
        raise SystemExit(f"Missing {DIST}/index.html - build the frontend first")
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PARCHI_UI_PORT", "5174")))
