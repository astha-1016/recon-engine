"""FastAPI scoring + training service for the reconciliation engine."""
import os
import threading
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .features import FEATURE_NAMES
from .registry import ModelRegistry
from .training import better, fit, metrics

DATA_DIR = Path(os.environ.get("ML_DATA_DIR", "data"))
BASE_DATASET = Path(os.environ.get("ML_BASE_DATASET", DATA_DIR / "training_base.csv.gz"))
FEEDBACK_FILE = DATA_DIR / "feedback.csv"

registry = ModelRegistry(Path(os.environ.get("ML_MODEL_DIR", DATA_DIR / "models")))
_train_lock = threading.Lock()


@asynccontextmanager
async def lifespan(_app):
    """First start: if base data is present and there is no model, train one so /score works immediately."""
    if usable_version() is None and BASE_DATASET.exists():
        train(TrainRequest(use_feedback=False))
    yield


app = FastAPI(title="Reconciliation ML service", version="1.0", lifespan=lifespan)


class ScoreRequest(BaseModel):
    features: list[str] = Field(description="Column names, in the order used by each row")
    rows: list[list[float]]


class ScoreResponse(BaseModel):
    probabilities: list[float]
    model_version: str


class FeedbackRow(BaseModel):
    txn_id: str
    label: int = Field(ge=0, le=1, description="1 = human confirmed this plan, 0 = human rejected it")
    features: dict[str, float]


class FeedbackRequest(BaseModel):
    rows: list[FeedbackRow]


class TrainRequest(BaseModel):
    algorithm: str = "gb"
    use_feedback: bool = True
    activate_if_better: bool = True


def load_base() -> pd.DataFrame:
    if not BASE_DATASET.exists():
        raise HTTPException(409, f"Base training data not found at {BASE_DATASET}. Export it with the engine CLI first.")
    return pd.read_csv(BASE_DATASET)


def load_feedback() -> pd.DataFrame:
    if not FEEDBACK_FILE.exists():
        return pd.DataFrame(columns=["txn_id", "label"] + FEATURE_NAMES)
    return pd.read_csv(FEEDBACK_FILE)


def usable_version():
    """The active model, but only if it was trained on exactly the feature list the engine sends now.
    A model from an older feature set would fail or silently mis-score, so it is treated as absent."""
    version = registry.active_version()
    if version is None:
        return None
    try:
        return version if registry.meta(version).get("features") == FEATURE_NAMES else None
    except FileNotFoundError:
        return None


@app.get("/health")
def health():
    active = registry.active_version()
    return {"status": "ok", "active_model": active, "model_compatible": usable_version() is not None,
            "feedback_rows": len(load_feedback()), "features": len(FEATURE_NAMES)}


@app.get("/features")
def features():
    return {"features": FEATURE_NAMES}


@app.post("/score", response_model=ScoreResponse)
def score(req: ScoreRequest):
    version = usable_version()
    if version is None:
        raise HTTPException(503, "No usable trained model (none yet, or it was trained on an older feature set). POST /train first.")
    missing = [f for f in FEATURE_NAMES if f not in req.features]
    if missing:
        raise HTTPException(422, f"Missing features: {missing}")
    if any(len(r) != len(req.features) for r in req.rows):
        raise HTTPException(422, "Every row must have one value per feature name")
    if not req.rows:
        return ScoreResponse(probabilities=[], model_version=version)
    df = pd.DataFrame(req.rows, columns=req.features)[FEATURE_NAMES]
    p = registry.load(version).predict_proba(df)[:, 1]
    return ScoreResponse(probabilities=[float(x) for x in p], model_version=version)


@app.post("/feedback")
def feedback(req: FeedbackRequest):
    """Store human decisions as labelled training rows for the next retrain."""
    rows = []
    for r in req.rows:
        unknown = set(r.features) - set(FEATURE_NAMES)
        if unknown:
            raise HTTPException(422, f"Unknown features: {sorted(unknown)}")
        row = {"txn_id": r.txn_id, "label": r.label}
        row.update({f: r.features.get(f, 0.0) for f in FEATURE_NAMES})
        rows.append(row)
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    df = pd.DataFrame(rows, columns=["txn_id", "label"] + FEATURE_NAMES)
    df.to_csv(FEEDBACK_FILE, mode="a", header=not FEEDBACK_FILE.exists(), index=False)
    return {"stored": len(rows), "total_feedback_rows": len(load_feedback())}


@app.delete("/feedback/{txn_id}")
def remove_feedback(txn_id: str):
    """Undo: drop the stored labelled rows for one transaction (used when a person undoes a decision)."""
    fb = load_feedback()
    keep = fb[fb["txn_id"] != txn_id]
    removed = len(fb) - len(keep)
    if removed:
        keep.to_csv(FEEDBACK_FILE, index=False)
    return {"removed": removed, "total_feedback_rows": len(keep)}


@app.post("/train")
def train(req: TrainRequest):
    """Retrain on base training rows (+ stored human feedback), evaluate on the fixed validation holdout,
    and make the new model active only if it beats the current one."""
    with _train_lock:
        base = load_base()
        train_df = base[base["split"] == "train"]
        holdout = base[base["split"] == "validation"]
        if holdout.empty or train_df.empty:
            raise HTTPException(409, "Base dataset needs both 'train' and 'validation' rows")
        fb = load_feedback() if req.use_feedback else pd.DataFrame()
        if len(fb):
            train_df = pd.concat([train_df, fb[["txn_id", "label"] + FEATURE_NAMES]], ignore_index=True)
        try:
            trained = fit(train_df, holdout, req.algorithm)
        except ValueError as e:
            raise HTTPException(422, str(e))
        version = registry.new_version(req.algorithm)
        active = usable_version()
        # Re-score the active model on *this* holdout so the comparison is apples-to-apples even if the data changed.
        active_metrics = None
        if active:
            active_p = registry.load(active).predict_proba(holdout[FEATURE_NAMES])[:, 1]
            active_metrics = metrics(holdout["label"].astype(int).to_numpy(), active_p)
        promote, reason = better(trained.holdout_metrics, active_metrics)
        meta = {"algorithm": req.algorithm, "features": FEATURE_NAMES, "trained_at": datetime.now(timezone.utc).isoformat(),
                "train_rows": trained.train_rows, "feedback_rows": int(len(fb)),
                "holdout_metrics": trained.holdout_metrics}
        registry.save(version, trained.model, meta)
        promoted = promote and req.activate_if_better
        if promoted:
            registry.activate(version)
        return {"version": version, "promoted": promoted, "reason": reason,
                "holdout_metrics": trained.holdout_metrics, "previous_active": active,
                "previous_metrics": active_metrics, "train_rows": trained.train_rows, "feedback_rows": int(len(fb))}


@app.get("/models")
def models():
    return {"active": registry.active_version(), "models": registry.list()}


@app.post("/models/{version}/activate")
def activate(version: str):
    try:
        registry.activate(version)
    except KeyError:
        raise HTTPException(404, f"Unknown model version {version}")
    return {"active": version}


