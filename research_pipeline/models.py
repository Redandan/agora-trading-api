from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum
from typing import Any


RESEARCH_AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
EXPERIMENT_ID = re.compile(r"^[a-z0-9][a-z0-9-]{2,79}$")


class Stage(str, Enum):
    PREREGISTERED = "PREREGISTERED"
    OOS_READY = "OOS_READY"
    CLOSED = "CLOSED"
    BLOCKED = "BLOCKED"
    FAILED = "FAILED"


TERMINAL_STAGES = frozenset(
    {
        Stage.CLOSED.value,
        Stage.BLOCKED.value,
        Stage.FAILED.value,
    }
)


def is_terminal_stage(stage: str) -> bool:
    return stage in TERMINAL_STAGES


@dataclass(frozen=True)
class ExperimentManifest:
    schema_version: str
    experiment_id: str
    title: str
    thesis: str
    economic_rationale: str
    hypothesis_source: str
    parent: str
    adapter: str
    created_at: str
    selection_cutoff: str
    oos_cutoff: str | None
    max_variants: int
    authorization: str
    objective: dict[str, Any]
    adapter_config: dict[str, Any] | None = None

    @classmethod
    def from_dict(cls, value: dict[str, Any], *, max_variants: int) -> "ExperimentManifest":
        required = {
            "schema_version",
            "experiment_id",
            "title",
            "thesis",
            "economic_rationale",
            "hypothesis_source",
            "parent",
            "adapter",
            "created_at",
            "selection_cutoff",
            "max_variants",
            "authorization",
            "objective",
        }
        missing = sorted(required.difference(value))
        if missing:
            raise ValueError(f"manifest missing fields: {', '.join(missing)}")
        allowed = required | {"oos_cutoff", "adapter_config"}
        unknown = sorted(set(value).difference(allowed))
        if unknown:
            raise ValueError(f"manifest has unknown fields: {', '.join(unknown)}")
        if value["schema_version"] != "1":
            raise ValueError("manifest schema_version must be 1")
        experiment_id = str(value["experiment_id"])
        if not EXPERIMENT_ID.fullmatch(experiment_id):
            raise ValueError("experiment_id must be a 3-80 character lowercase slug")
        if value["authorization"] != RESEARCH_AUTHORIZATION:
            raise ValueError("manifest authorization must remain research-only")
        variants = int(value["max_variants"])
        if variants < 1 or variants > max_variants:
            raise ValueError(f"max_variants must be between 1 and {max_variants}")
        for field in ("created_at", "selection_cutoff"):
            parse_timestamp(str(value[field]), field)
        oos_cutoff = value.get("oos_cutoff")
        if oos_cutoff is not None:
            parse_timestamp(str(oos_cutoff), "oos_cutoff")
        objective = value["objective"]
        if not isinstance(objective, dict) or not objective.get("primary_metric"):
            raise ValueError("objective.primary_metric is required")
        adapter_config = value.get("adapter_config")
        if adapter_config is not None and not isinstance(adapter_config, dict):
            raise ValueError("adapter_config must be an object")
        return cls(
            schema_version="1",
            experiment_id=experiment_id,
            title=nonblank(value, "title"),
            thesis=nonblank(value, "thesis"),
            economic_rationale=nonblank(value, "economic_rationale"),
            hypothesis_source=nonblank(value, "hypothesis_source"),
            parent=nonblank(value, "parent"),
            adapter=nonblank(value, "adapter"),
            created_at=str(value["created_at"]),
            selection_cutoff=str(value["selection_cutoff"]),
            oos_cutoff=None if oos_cutoff is None else str(oos_cutoff),
            max_variants=variants,
            authorization=RESEARCH_AUTHORIZATION,
            objective=dict(objective),
            adapter_config=None if adapter_config is None else dict(adapter_config),
        )

    def to_dict(self) -> dict[str, Any]:
        result = {
            "schema_version": self.schema_version,
            "experiment_id": self.experiment_id,
            "title": self.title,
            "thesis": self.thesis,
            "economic_rationale": self.economic_rationale,
            "hypothesis_source": self.hypothesis_source,
            "parent": self.parent,
            "adapter": self.adapter,
            "created_at": self.created_at,
            "selection_cutoff": self.selection_cutoff,
            "oos_cutoff": self.oos_cutoff,
            "max_variants": self.max_variants,
            "authorization": self.authorization,
            "objective": self.objective,
        }
        if self.adapter_config is not None:
            result["adapter_config"] = self.adapter_config
        return result


def nonblank(value: dict[str, Any], field: str) -> str:
    result = str(value[field]).strip()
    if not result:
        raise ValueError(f"{field} must not be blank")
    return result


def parse_timestamp(value: str, field: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{field} must be ISO-8601") from error
    if parsed.tzinfo is None:
        raise ValueError(f"{field} must include a timezone")
    return parsed


def now_utc() -> str:
    return datetime.now(timezone.utc).isoformat()
