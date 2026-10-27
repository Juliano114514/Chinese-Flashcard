"""Read the explicitly reviewed vocabulary fingerprint; never infer a new trusted snapshot."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VALIDATION = ROOT / "docs" / "wordlist-validation.json"


def load_snapshot() -> dict:
    snapshot = json.loads(VALIDATION.read_text(encoding="utf-8"))
    fingerprint = snapshot.get("sha256", "")
    if (not isinstance(fingerprint, str) or len(fingerprint) != 64
            or any(character not in "0123456789abcdef" for character in fingerprint)
            or type(snapshot.get("words")) is not int or not 1 <= snapshot["words"] <= 20_000
            or type(snapshot.get("bytes")) is not int or not 1 <= snapshot["bytes"] <= 32 * 1024 * 1024
            or type(snapshot.get("examples")) is not int or snapshot["examples"] < snapshot["words"]):
        raise ValueError("Invalid reviewed wordlist fingerprint")
    return snapshot


def verify_snapshot(path: Path) -> dict:
    snapshot = load_snapshot()
    payload = path.read_bytes()
    if len(payload) != snapshot["bytes"] or hashlib.sha256(payload).hexdigest() != snapshot["sha256"]:
        raise ValueError("CSV differs from the explicitly reviewed wordlist snapshot")
    return snapshot
