#!/usr/bin/env python3
"""Applies the two small edits this upgrade needs to files that already exist in your repo.
Safe to run twice. Run from the repository root:   python scripts/apply_noisy_upgrade.py
"""
from pathlib import Path


def edit(path, fn, label):
    p = Path(path)
    if not p.exists():
        print(f"[skip] {label}: {path} not found (are you in the repository root?)")
        return
    text = p.read_bytes().decode("utf-8")
    new, status = fn(text)
    if new != text:
        p.write_bytes(new.encode("utf-8"))
    print(f"[{status}] {label}")


def evaluator(text):
    old = 't.scenario().equals("short_pay")'
    if 'equals("fee_deducted")' in text:
        return text, "already done"
    if old not in text:
        return text, "NOT APPLIED: could not find the short_pay line; make the edit by hand (see docs/NOISY_UPGRADE.md)"
    return text.replace(old, '(t.scenario().equals("short_pay") || t.scenario().equals("fee_deducted"))', 1), "patched"


def compose(text):
    if '8000:8000' in text:
        return text, "already done"
    eol = "\r\n" if "\r\n" in text else "\n"
    anchor = "  ml:" + eol + "    build: ./ml-service" + eol
    if anchor not in text:
        return text, "NOT APPLIED: could not find the ml service; add  ports: [\"8000:8000\"]  to it by hand"
    return text.replace(anchor, anchor + '    ports: ["8000:8000"]' + eol, 1), "patched"


edit("engine/src/main/java/com/reconcile/engine/Evaluator.java", evaluator,
     "Evaluator counts fee_deducted payments as correctly closed (PAID)")
edit("docker-compose.yml", compose, "docker-compose publishes the ML service on port 8000")
print("Done. Rebuild the backend image only if you changed Java files used by the backend: docker compose up -d --build backend")
