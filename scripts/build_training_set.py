#!/usr/bin/env python3
"""Build the ML training set from several synthetic datasets (different seeds).

For each seed: generate a dataset, let the Java engine export labelled candidate rows (ground truth is used for
labels only), then concatenate with seed-prefixed transaction ids so cross-validation groups never collide.
Seed 42's 'test' customers and the separate evaluation seed are never trained on.
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


def write_dataset(out: Path, seed: int, short: int, over: int, returns: int):
    data, summary = build(1000, seed, short, over, returns)
    out.mkdir(parents=True, exist_ok=True)
    for name, rows in data.items():
        with (out / f"{name}.csv").open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=HEADERS[name].split())
            w.writeheader()
            w.writerows(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--classes", default=str(ROOT / "engine" / "target" / "classes"), help="compiled engine classes")
    ap.add_argument("--out", default=str(ROOT / "ml-service" / "data" / "training_base.csv.gz"))
    ap.add_argument("--seeds", type=int, nargs="+", default=[42, 101, 102, 103, 104])
    args = ap.parse_args()

    rows, header = [], None
    with tempfile.TemporaryDirectory() as tmp:
        for seed in args.seeds:
            d = Path(tmp) / f"s{seed}"
            heavy = seed != 42  # extra short-pay / overpay / cheque-return volume for the rare scenarios
            write_dataset(d, seed, 40 if heavy else 30, 30 if heavy else 20, 25 if heavy else 20)
            feats = Path(tmp) / f"f{seed}.csv"
            subprocess.run(["java", "-cp", args.classes, "com.reconcile.engine.Cli", "export", "--data", str(d), "--out", str(feats)],
                           check=True, stdout=subprocess.DEVNULL)
            with feats.open() as f:
                r = csv.reader(f)
                header = next(r)
                for row in r:
                    row[0] = f"s{seed}:{row[0]}"
                    if seed != 42 and row[1] == "test":
                        row[1] = "train"  # extra seeds contribute all their customers; only seed 42's test customers are held out
                    rows.append(row)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    opener = gzip.open if args.out.endswith(".gz") else open
    with opener(args.out, "wt", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)
    print(f"Wrote {len(rows)} rows from seeds {args.seeds} to {args.out}")


if __name__ == "__main__":
    main()
