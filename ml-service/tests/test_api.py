import re
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from app import main
from app.features import FEATURE_NAMES
from app.registry import ModelRegistry

REPO = Path(__file__).resolve().parents[2]


def synthetic_base(n_txn=700, seed=1, noise=0.0):
    """Learnable toy problem: a plan is right when the amount is exact AND the payer is identified."""
    rng = np.random.default_rng(seed)
    rows = []
    for t in range(n_txn):
        split = "train" if t % 10 < 7 else "validation"
        for c in range(6):
            f = {name: float(rng.random()) for name in FEATURE_NAMES}
            f["amount_exact"] = float(rng.random() < 0.3)
            f["identity_score"] = float(rng.random())
            label = int(f["amount_exact"] == 1 and f["identity_score"] > 0.8)
            if rng.random() < noise:
                label = 1 - label
            rows.append({"txn_id": f"T{t}", "split": split, "label": label, **f})
    return pd.DataFrame(rows)


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DATA_DIR", tmp_path)
    monkeypatch.setattr(main, "BASE_DATASET", tmp_path / "base.csv")
    monkeypatch.setattr(main, "FEEDBACK_FILE", tmp_path / "feedback.csv")
    monkeypatch.setattr(main, "registry", ModelRegistry(tmp_path / "models"))
    synthetic_base().to_csv(tmp_path / "base.csv", index=False)
    return TestClient(main.app)


def score_body(rows):
    return {"features": FEATURE_NAMES, "rows": rows}


def test_feature_names_match_java():
    java = (REPO / "engine/src/main/java/com/reconcile/engine/Features.java").read_text()
    block = java[java.index("NAMES = List.of("):java.index(");", java.index("NAMES = List.of("))]
    assert re.findall(r'"([a-z0-9_]+)"', block) == FEATURE_NAMES


def test_score_without_model_is_503(client):
    r = client.post("/score", json=score_body([[0.0] * len(FEATURE_NAMES)]))
    assert r.status_code == 503


def test_train_promotes_first_model_and_scores(client):
    t = client.post("/train", json={"algorithm": "gb"}).json()
    assert t["promoted"] and t["reason"] == "no active model"
    assert t["holdout_metrics"]["average_precision"] > 0.9
    good = [0.0] * len(FEATURE_NAMES)
    good[FEATURE_NAMES.index("amount_exact")] = 1
    good[FEATURE_NAMES.index("identity_score")] = 1
    bad = [0.0] * len(FEATURE_NAMES)
    p = client.post("/score", json=score_body([good, bad])).json()
    assert p["model_version"] == t["version"]
    assert p["probabilities"][0] > 0.9 > 0.2 > p["probabilities"][1]


def test_score_rejects_missing_features_and_ragged_rows(client):
    client.post("/train", json={})
    assert client.post("/score", json={"features": FEATURE_NAMES[:5], "rows": [[0] * 5]}).status_code == 422
    assert client.post("/score", json={"features": FEATURE_NAMES, "rows": [[0.0]]}).status_code == 422


def test_empty_batch(client):
    client.post("/train", json={})
    assert client.post("/score", json=score_body([])).json()["probabilities"] == []


def test_feedback_is_stored_and_used_for_retraining(client):
    client.post("/train", json={})
    rows = [{"txn_id": f"F{i}", "label": 1, "features": {"amount_exact": 1.0, "identity_score": 1.0}} for i in range(20)]
    assert client.post("/feedback", json={"rows": rows}).json()["stored"] == 20
    assert client.get("/health").json()["feedback_rows"] == 20
    t = client.post("/train", json={"use_feedback": True}).json()
    assert t["feedback_rows"] == 20


def test_unknown_feedback_feature_rejected(client):
    r = client.post("/feedback", json={"rows": [{"txn_id": "x", "label": 1, "features": {"nonsense": 1.0}}]})
    assert r.status_code == 422


def test_worse_model_is_not_promoted(client):
    first = client.post("/train", json={}).json()
    # poisoned feedback: confident but wrong labels drag the new model down
    poison = [{"txn_id": f"P{i}", "label": 0, "features": {"amount_exact": 1.0, "identity_score": 1.0}} for i in range(4000)]
    client.post("/feedback", json={"rows": poison})
    second = client.post("/train", json={"use_feedback": True}).json()
    assert second["promoted"] is False
    assert client.get("/models").json()["active"] == first["version"]


def test_rollback_to_earlier_version(client):
    a = client.post("/train", json={"algorithm": "gb"}).json()["version"]
    b = client.post("/train", json={"algorithm": "rf"}).json()["version"]
    assert client.post(f"/models/{a}/activate").json()["active"] == a
    assert client.post("/models/does-not-exist/activate").status_code == 404
    assert {m["version"] for m in client.get("/models").json()["models"]} >= {a, b}
