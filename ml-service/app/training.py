"""Training, calibration, evaluation and the promote-only-if-better rule."""
from dataclasses import dataclass

import numpy as np
import pandas as pd
from sklearn.calibration import CalibratedClassifierCV
from sklearn.ensemble import HistGradientBoostingClassifier, RandomForestClassifier
from sklearn.metrics import average_precision_score, brier_score_loss, log_loss, roc_auc_score
from sklearn.model_selection import GroupKFold

from .features import FEATURE_NAMES

AUTO_THRESHOLD = 0.90


def make_estimator(algorithm: str):
    if algorithm == "rf":
        return RandomForestClassifier(n_estimators=300, min_samples_leaf=2, n_jobs=-1, random_state=7)
    if algorithm == "gb":
        return HistGradientBoostingClassifier(max_depth=6, learning_rate=0.08, max_iter=250,
                                              l2_regularization=1.0, random_state=7)
    raise ValueError(f"unknown algorithm {algorithm!r} (use 'gb' or 'rf')")


def metrics(y_true, p) -> dict:
    p = np.clip(p, 1e-6, 1 - 1e-6)
    auto = p >= AUTO_THRESHOLD
    tp = int(((y_true == 1) & auto).sum())
    return {
        "rows": int(len(y_true)),
        "positives": int(y_true.sum()),
        "average_precision": float(average_precision_score(y_true, p)),
        "roc_auc": float(roc_auc_score(y_true, p)) if 0 < y_true.sum() < len(y_true) else None,
        "log_loss": float(log_loss(y_true, p, labels=[0, 1])),
        "brier": float(brier_score_loss(y_true, p)),
        "precision_at_auto": float(tp / auto.sum()) if auto.sum() else None,
        "recall_at_auto": float(tp / y_true.sum()) if y_true.sum() else None,
    }


@dataclass
class Trained:
    model: object
    holdout_metrics: dict
    train_rows: int
    algorithm: str


def fit(train_df: pd.DataFrame, holdout_df: pd.DataFrame, algorithm: str = "gb") -> Trained:
    """Fit on `train_df`, calibrate with grouped CV (groups = transaction), score on the untouched holdout."""
    X, y, groups = train_df[FEATURE_NAMES], train_df["label"].astype(int), train_df["txn_id"]
    n_groups = groups.nunique()
    folds = min(3, n_groups, int(y.sum())) if y.sum() > 0 else 0
    if folds >= 2:
        cv = list(GroupKFold(n_splits=folds).split(X, y, groups))
        method = "isotonic" if y.sum() >= 100 else "sigmoid"
        model = CalibratedClassifierCV(make_estimator(algorithm), method=method, cv=cv)
    else:
        model = make_estimator(algorithm)
    model.fit(X, y)
    p = model.predict_proba(holdout_df[FEATURE_NAMES])[:, 1]
    return Trained(model, metrics(holdout_df["label"].astype(int).to_numpy(), p), len(train_df), algorithm)


def better(candidate: dict, active: dict | None) -> tuple[bool, str]:
    """Promote only if the new model is meaningfully better on the same holdout."""
    if active is None:
        return True, "no active model"
    ap_new, ap_old = candidate["average_precision"], active["average_precision"]
    if ap_new >= ap_old + 0.002:
        return True, f"average precision {ap_old:.4f} -> {ap_new:.4f}"
    if abs(ap_new - ap_old) < 0.002 and candidate["log_loss"] < active["log_loss"] - 0.001:
        return True, f"same ranking quality, better calibration (log loss {active['log_loss']:.4f} -> {candidate['log_loss']:.4f})"
    return False, f"not better than the active model (AP {ap_old:.4f} vs {ap_new:.4f})"
