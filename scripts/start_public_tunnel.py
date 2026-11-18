#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Public HTTPS tunnel to ParchiAppAI gateway :5174 (works outside IITD)."""

from __future__ import annotations

import os
import secrets
import time
from pathlib import Path

from gradio.networking import setup_tunnel

LOG = Path(__file__).resolve().parents[1] / ".run-logs"
LOG.mkdir(exist_ok=True)
PORT = int(os.environ.get("PARCHI_UI_PORT", "5174"))


def main() -> None:
    print(f"starting tunnel to 127.0.0.1:{PORT}...", flush=True)
    url = setup_tunnel("127.0.0.1", PORT, secrets.token_urlsafe(32), None, None).strip()
    (LOG / "public-url.txt").write_text(url + "\n")
    print("PUBLIC_URL=" + url, flush=True)
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
