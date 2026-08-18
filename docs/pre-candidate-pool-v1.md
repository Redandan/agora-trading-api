# Pre-Candidate Pool and Funnel V1

Status: `ACTIVE_READ_ONLY_DERIVED_VIEW`

## Purpose

The pre-candidate pool keeps the strategy search broad without pretending that
an idea is already a candidate. It answers four questions from one read-only
status surface:

1. Which distinct mechanisms are still open?
2. What exact evidence is missing before each mechanism can advance?
3. Which family deserves attention next?
4. Which families are permanently closed and must not be tuned or rerun?

The catalog is `research_pipeline/pre-candidate-pool.v1.json`. Its schema is
`research_pipeline/pre-candidate-pool.v1.schema.json`. Every repository
evidence binding is SHA-256 verified before the pool is returned.

## Authority and safety

`get_research_status` derives `candidate_funnel` from three existing inputs:

- the Git-versioned pool catalog;
- the server-canonical research registry;
- the existing canonical microstructure diagnostic.

The funnel never writes canonical state, opens OOS, executes a runner, registers
a hypothesis, or activates a strategy. It adds no MCP operation, timer, queue,
or writer. The one Codex cloud Ops schedule remains the sole clock and the
server Research Worker remains the state authority.

## Pool contract

- Keep 5 to 10 open, deduplicated strategy families.
- Keep at most one non-terminal experiment.
- Keep at most one open `CANDIDATE_OOS` trigger.
- Permanently exclude a family when a sealed result and manager acceptance
  close it. Canonical closed experiments are appended as dynamic tombstones.
- Require every open family to state its decision feature, causal and economic
  thesis, parent, matched comparator, runner, next gate, evidence bindings, and
  missing proof.
- Require economic visibility for fees, adverse slippage, realized,
  unrealized, and total PnL, maximum drawdown, holding age, terminal inventory,
  and breadth/path risk. `MISSING_PROOF` remains explicit.

The initial V1 catalog contains eight open families and eight repository-sealed
closed families. Catalog membership is not authorization to start an
experiment.

## Ranking semantics

The ranking orders attention, not expected alpha. Its fixed dimensions are:

1. active evidence integrity;
2. evidence readiness;
3. matched-capital economic visibility;
4. path-risk visibility;
5. estimated time to the next gate;
6. mechanism independence;
7. compute cost;
8. stable family id tie-break.

An integrity problem in an active prospective evidence line ranks first because
unreliable evidence can invalidate later conclusions. A family marked
`READY_FOR_HYPOTHESIS` is still not a formal candidate. It becomes a formal
candidate only after one frozen hypothesis and experiment reaches
`CANDIDATE_FROZEN`; independent sealed OOS remains a later gate.

## Promotion and closure

The funnel may recommend only the next bounded gate. Actual promotion continues
through the existing single-lane research pipeline:

```text
PRE_CANDIDATE
  -> READY_FOR_HYPOTHESIS
  -> PREREGISTERED EXPERIMENT
  -> DESIGN / VALIDATION
  -> CANDIDATE_FROZEN
  -> INDEPENDENT SEALED OOS
  -> REPORTED_NOT_ACTIVATED
```

Any failed frozen gate closes the family or the exact tested branch without
relaxing thresholds. Closed-family fingerprints are retained so later ranking
cannot silently reintroduce the same mechanism under a new label.
