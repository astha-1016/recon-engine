#!/usr/bin/env python3
"""Build the ML training set from several synthetic datasets (different seeds).

For each seed: generate a dataset, let the Java engine export labelled candidate rows (ground truth is used for
labels only), then concatenate with seed-prefixed transaction ids so cross-validation groups never collide.
Seed 42's 'test' customers and the separate evaluation seeds are never trained on.

--noisy-seeds adds datasets generated WITH noise (typos, dropped invoice numbers, payment-lag jitter, deducted
bank fees, amount collisions) so the model also learns from messy data.
"""
import argparse
import csv
import gzip
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "data-generator"))
from generate_v2 import build, HEADERS  # noqa: E402
from generate_v3_noisy import build_noisy  # noqa: E402


def write_dataset(out: Path, seed: int, short: int, over: int, returns: int, noisy: bool = False, level: float = 1.0):
    data, _summary = build_noisy(1000, seed, short, over, returns, level) if noisy else build(1000, seed, short, over, returns)
    out.mkdir(parents=True, exist_ok=True)
    for name, rows in data.items():
        with (out / f"{name}.csv").open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=HEADERS[name].split())
            w.writeheader()
            w.writerows(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--classes", default=str(ROOT / "build" / "classes"), help="compiled engine classes")
    ap.add_argument("--out", default=str(ROOT / "ml-service" / "data" / "training_base.csv.gz"))
    ap.add_argument("--seeds", type=int, nargs="+", default=[42, 101, 102, 103, 104])
    ap.add_argument("--noisy-seeds", type=int, nargs="*", default=[], help="extra seeds generated with noise")
    ap.add_argument("--noise-level", type=float, default=1.0)
    ap.add_argument("--ml", default=None, help="URL of a running ML service: export rows following ITS decisions "
                    "(includes payments re-examined in later resolution rounds). Without it the rule scorer is used.")
    args = ap.parse_args()

    jobs = [(s, False) for s in args.seeds] + [(s, True) for s in args.noisy_seeds]
    rows, header = [], None
    with tempfile.TemporaryDirectory() as tmp:
        for seed, noisy in jobs:
            tag = f"{'n' if noisy else 's'}{seed}"
            d = Path(tmp) / tag
            heavy = seed != 42  # extra short-pay / overpay / cheque-return volume for the rare scenarios
            write_dataset(d, seed, 40 if heavy else 30, 30 if heavy else 20, 25 if heavy else 20, noisy, args.noise_level)
            feats = Path(tmp) / f"f{tag}.csv"
            cmd = ["java", "-cp", args.classes, "com.reconcile.engine.Cli", "export", "--data", str(d), "--out", str(feats)]
            if args.ml:
                cmd += ["--ml", args.ml]
            subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)
            with feats.open() as f:
                r = csv.reader(f)
                header = next(r)
                for row in r:
                    row[0] = f"{tag}:{row[0]}"
                    if seed != 42 and row[1] == "test":
                        row[1] = "train"  # extra seeds contribute all their customers; only seed 42's test customers are held out
                    rows.append(row)
            print(f"  {tag}: done", flush=True)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    opener = gzip.open if args.out.endswith(".gz") else open
    with opener(args.out, "wt", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)
    print(f"Wrote {len(rows)} rows from {len(jobs)} datasets ({len(args.noisy_seeds)} noisy) to {args.out}")


if __name__ == "__main__":
    main()
