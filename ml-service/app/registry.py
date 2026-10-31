"""On-disk model registry: every trained model is kept, one is active, and any version can be re-activated (rollback)."""
import json
import threading
from datetime import datetime, timezone
from pathlib import Path

import joblib


class ModelRegistry:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._cache = {}

    def _active_file(self):
        return self.root / "active.json"

    def new_version(self, algorithm: str) -> str:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f")
        return f"{algorithm}-{stamp}"

    def save(self, version: str, model, meta: dict):
        with self._lock:
            d = self.root / version
            d.mkdir(parents=True, exist_ok=True)
            joblib.dump(model, d / "model.joblib")
            (d / "meta.json").write_text(json.dumps(meta, indent=2))
            self._cache[version] = model

    def active_version(self):
        f = self._active_file()
        return json.loads(f.read_text())["version"] if f.exists() else None

    def activate(self, version: str):
        with self._lock:
            if not (self.root / version / "model.joblib").exists():
                raise KeyError(version)
            self._active_file().write_text(json.dumps({"version": version,
                                                       "activated_at": datetime.now(timezone.utc).isoformat()}))

    def meta(self, version: str) -> dict:
        return json.loads((self.root / version / "meta.json").read_text())

    def load(self, version: str):
        with self._lock:
            if version not in self._cache:
                self._cache[version] = joblib.load(self.root / version / "model.joblib")
            return self._cache[version]

    def list(self):
        active = self.active_version()
        out = []
        for d in sorted(self.root.iterdir()):
            if (d / "meta.json").exists():
                m = json.loads((d / "meta.json").read_text())
                m["version"] = d.name
                m["active"] = d.name == active
                out.append(m)
        return out
