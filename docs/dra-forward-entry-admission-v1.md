# DRA Forward Entry Admission V1

Status: `IMPLEMENTED_RESEARCH_ONLY_AWAITING_FORWARD_EVIDENCE`

This is the first forward-evidence-eligible strategy adapter. It is an offline
research capability only. It cannot become a Spring bean, Trading scheduler,
strategy catalog entry, database writer, order path, or SHADOW/PAPER/LIVE
promotion path.

## Discovery contract

`research_pipeline/forward-diagnostic-contract.v1.json` preregisters two
mechanisms before the 90-day discovery result exists:

- `DRA_ENTRY_VOLUME_CONFIRMATION_20D`;
- `DRA_ENTRY_RANGE_CONFIRMATION_20D`.

Each mechanism uses fixed lower/primary/upper ratios `1.25 / 1.50 / 1.75`.
Only the primary ratio may seed a candidate; the neighbors are stability
checks. A mechanism must pass all frozen event-count, month-breadth, coverage,
next-day return, positive-share, concentration, and first/second-half gates.
At most one passing mechanism is exposed through canonical `candidate_context`.
If neither passes, the review closes as
`NO_CANDIDATE_FORWARD_DIAGNOSTIC`; no post-hoc threshold search is allowed.

## Candidate registration

Codex copies the exact canonical adapter configuration and replaces only the
mechanism placeholder with the sole passing mechanism. The server rehashes the
diagnostic contract, discovery review, evidence manifest, dataset, diagnostic,
retained pre-2025 corpus, hypothesis, and manifest. Registration is resumable:
an interruption between hypothesis, experiment, OOS trigger, and source
contract writes converges on the same identities and rejects changed content.

Registration creates a separate 90-day `CANDIDATE_OOS` trigger after the
manifest freeze. The discovery window is never OOS. Candidate OOS days expose
no market-path or performance summary before the complete window is sealed.

## Historical preselection

`research/btc_dra_forward_entry_admission_v1.py` reuses the offline DRA V1
economic engine and the exact retained 52,608-row pre-2025 corpus. It never
calls the legacy SSH/database fetch path. The baseline must reproduce exact
Design and Validation checkpoints. One primary candidate and two frozen
neighbors are evaluated under matching capital and accounting.

The primary must improve Design and Validation total PnL, keep realized and
unrealized Validation components non-worse, stay within `+0.25pp` Validation
drawdown, keep median/P90 holding non-worse, have at least four Validation
interventions, win at least three of five annual total-PnL folds, and keep
annual drawdown non-worse in at least four of five folds. Support and top-year
concentration gates must also pass. Both neighbors must improve Validation
total PnL, stay within the same drawdown tolerance, and have adequate support.

Failure closes the candidate and its OOS trigger unopened. Passing freezes the
candidate while OOS remains sealed.

## OOS and reporting

The existing heartbeat recovers a complete-but-unreviewed OOS window,
revalidates the candidate/manifest binding, opens the sealed dataset once, and
evaluates the same primary plus neighbors. OOS requires PnL, realized,
unrealized, drawdown, holding, intervention-support, and neighbor-stability
gates. `OUT_OF_SAMPLE_PASS` is still only `REPORTED_NOT_ACTIVATED`.

Coach reports show matched-parent PnL, drawdown, realized/unrealized, holding,
mechanism, and terminal status. A discovery `NO_CANDIDATE` sealed by source
ingest is emitted once on the next heartbeat, so a scientific rejection cannot
silently disappear as an idle queue.
