#!/usr/bin/env python3
"""Apply the frozen validator-reference and response-order corpus errata."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

try:
    from research import binance_btcusdt_fixed_maturity_delivery_carry_corpus_v1 as v1
    from research import binance_btcusdt_fixed_maturity_delivery_carry_corpus_v2 as v2
except ModuleNotFoundError:  # Direct script launch from the research directory.
    import binance_btcusdt_fixed_maturity_delivery_carry_corpus_v1 as v1
    import binance_btcusdt_fixed_maturity_delivery_carry_corpus_v2 as v2


ORIGINAL_V1_LOAD_SPEC = v1.load_spec


def load_spec(path: Path) -> tuple[dict[str, Any], str]:
    resolved = path.resolve()
    try:
        resolved.relative_to(v1.REPO_ROOT)
    except ValueError as error:
        raise v1.DeliveryCorpusReject(f"SPEC_REJECT:PATH:{resolved}") from error
    raw = resolved.read_bytes()
    try:
        erratum = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise v1.DeliveryCorpusReject("SPEC_REJECT:JSON") from error
    if (
        erratum.get("document_type")
        != "BTC_BINANCE_FIXED_MATURITY_DELIVERY_CARRY_CORPUS_IMPLEMENTATION_ERRATUM_V3"
        or erratum.get("authorization")
        != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or erratum.get("scientific_gate_changed") is not False
    ):
        raise v1.DeliveryCorpusReject("SPEC_REJECT:ERRATUM_IDENTITY")
    if erratum.get("corpus_adapter_sha256") != v1.sha256(Path(__file__).resolve()):
        raise v1.DeliveryCorpusReject("SPEC_REJECT:V3_ADAPTER_SHA256")
    scientific = erratum["scientific_spec"]
    scientific_path = (v1.REPO_ROOT / scientific["path"]).resolve()
    if v1.sha256(scientific_path) != scientific["sha256"]:
        raise v1.DeliveryCorpusReject("SPEC_REJECT:SCIENTIFIC_SPEC_SHA256")
    spec, scientific_sha = ORIGINAL_V1_LOAD_SPEC(scientific_path)
    if scientific_sha != scientific["sha256"]:
        raise v1.DeliveryCorpusReject("SPEC_REJECT:SCIENTIFIC_SPEC_READBACK")
    decision = erratum["v2_decision"]
    decision_path = (v1.REPO_ROOT / decision["path"]).resolve()
    if v1.sha256(decision_path) != decision["sha256"]:
        raise v1.DeliveryCorpusReject("SPEC_REJECT:V2_DECISION_SHA256")
    return spec, v1.sha256(raw)


def main() -> int:
    v1.load_spec = load_spec
    v1.parse_delivery_prices = v2.parse_delivery_prices
    return v1.main()


if __name__ == "__main__":
    raise SystemExit(main())
