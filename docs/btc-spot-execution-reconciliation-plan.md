# BTC Spot Execution Reconciliation Plan

Status date: 2026-07-29

Status:

```text
CURRENT_CODE_REVIEWED
FAILURE_MODES_CONFIRMED
LOCAL_FOUNDATION_IMPLEMENTED
LOCAL_DRA_SELL_WIRING_IMPLEMENTED
LOCAL_DRA_BUY_FEE_RECONCILIATION_IMPLEMENTED
LOCAL_CONTRACT_TESTS_PASS
DB_RACE_TEST_NOT_RUN
NOT_DEPLOYED
MIGRATION_FILE_NOT_EXECUTED
NO_PRODUCTION_WRITE
```

## Scope

This plan covers mechanical execution correctness shared by DRA and owner 509:

- delayed provider fee reconciliation;
- durable order-attempt identity;
- ambiguous submission recovery without blind retry;
- idempotent partial-fill application;
- exact provider receipt and residual-quantity accounting.

It does not change a strategy signal, notional, profit target, position
ownership, Grid/OCO behavior, fund movement, Telegram behavior, or runtime
mode.

## Confirmed current defects

### Delayed buy fee has no durable reconciliation state

`OkxTradingService.pollForFill` retries an empty fee currency for about 1.5
seconds. If the fee is still absent, it reduces the buy quantity by a
conservative buffer. `BtcDraLiveExecutionService` then stores that reduced
quantity and an effective entry price, but `bt_live_signal` has no durable
fields for:

- provider gross quantity;
- signed fee amount and currency;
- provider-net quantity;
- fee receipt status;
- final reconciliation time.

The evidence JSON may contain the first response, but it is append-only audit
evidence rather than authoritative mutable execution state. A later provider
fee cannot currently finalize the position quantity automatically.

### Partial DRA sell reuses its first client order ID

DRA derives the sell client order ID only from the original signal bar. A
partial fill leaves the lot `OPEN_PARTIAL`, so a later eligible closed bar
derives the same client order ID again. OKX duplicate-ID behavior then becomes
an ambiguous-submission path rather than a valid second sell attempt.

### Sell reservation is not cross-JVM unique

The service is synchronized only inside one JVM. During blue/green overlap,
two instances can observe the same closed bar. Buy submission has the
`bt_live_signal` unique bar reservation, but sell reservation only rewrites
`filter_reason`. There is no unique database row that elects exactly one
submitter for a lot, side, and attempt sequence.

### Provider success can precede database completion

If OKX fills an order and the later database update fails, the current path
records an error but has no durable attempt state that can safely re-read the
provider order and apply only the unapplied fill delta. This is the same
distributed transaction shape as the historical Grid orphan problem, but the
old Grid implementation referenced by the KB has since been removed. Only the
provider-first recovery principle remains applicable.

## Selected minimal design

Add one narrow platform-owned execution-attempt record. Do not restore the
deleted generic strategy, risk, Grid recovery, or AI orchestration systems.

Each attempt must contain at least:

- strategy contract and live-signal ID;
- side and durable attempt sequence;
- signal bar and trigger bar;
- deterministic client order ID;
- provider order ID;
- requested quote amount or base quantity;
- state;
- provider average price, gross fill quantity, net quantity;
- signed fee amount, fee currency, normalized fee in USDT;
- applied fill quantity and remaining lot quantity;
- submission, provider, reconciliation, and update timestamps;
- optimistic version.

Required uniqueness:

```text
UNIQUE(live_signal_id, side, attempt_sequence)
UNIQUE(client_order_id)
UNIQUE(provider, provider_order_id) when provider_order_id is present
```

The attempt state machine is mechanical:

```text
RESERVED
  -> SUBMITTING
  -> SUBMISSION_UNKNOWN
  -> PROVIDER_ACCEPTED
  -> RECONCILED_FILLED
  -> RECONCILED_PARTIAL
  -> REJECTED
```

No state transition may infer that an exchange order failed merely because a
local call timed out.

## Submission algorithm

1. Insert the unique attempt before any provider mutation.
2. Only the transaction that atomically changes `RESERVED` to `SUBMITTING` may
   call OKX.
3. Before submission, query OKX by the deterministic client order ID.
4. If the order exists, reconcile it; do not submit.
5. If the provider call outcome is unclear, persist `SUBMISSION_UNKNOWN`.
6. A later closed bar may query and reconcile that client order ID, but may
   not blindly submit it again.
7. Apply provider cumulative filled quantity as a delta:

```text
newly_applied = provider_cumulative_filled - already_applied
```

8. Update the owned lot and attempt in one database transaction.

The first DRA sell keeps the accepted ID:

```text
DRA1S<original-signal-time>
```

Only a proven partial fill may allocate sequence 2 or later. Follow-up IDs add
the durable sequence while remaining deterministic and within the OKX
alphanumeric length limit.

## Fee reconciliation

- The provider order and fill receipts are authoritative.
- Fee amounts remain signed and retain their original currency.
- Account balance differences must not be used to attribute fee quantity to a
  specific order.
- A buy may temporarily use the existing conservative sellable quantity while
  fee status is pending.
- Once all fills and fees are complete, future lots may derive exact
  strategy-owned net quantity from those receipts.
- Existing DRA position 263 is grandfathered for the current acceptance cycle:
  no migration, backfill, or quantity change is allowed without a separate
  explicit authorization.

## Partial and residual handling

- A partial fill must never be applied twice.
- A follow-up attempt is allowed only after the preceding attempt is
  reconciled as partial and the remaining quantity still meets instrument
  minimums on a later genuine closed bar.
- Profit eligibility is recalculated before every new sequence.
- A residual below exchange minimum or lot size is recorded separately as
  strategy-owned residual asset. It is not silently called sold and is not
  counted twice in realized cash profit.

## Focused contract tests

Before implementation can be deployed, tests must cover:

1. two service instances racing to reserve the same sell;
2. provider order already found by client order ID;
3. timeout after provider acceptance with no second submission;
4. partial fill applied exactly once;
5. deterministic sequence-2 allocation after a proven partial;
6. delayed base-currency fee changing provider-net quantity;
7. delayed quote-currency fee changing cash cost but not base quantity;
8. dust/minimum-size residual handling;
9. database failure after provider fill followed by provider-first recovery;
10. position 263 compatibility without mutation or backfill.

## Promotion boundary

Implementation, migration, deployment, and Production reconciliation are
separate approvals. Before any runtime change:

1. finish the DRA bootstrap-continuity acceptance;
2. review the exact schema and state-machine diff;
3. run the narrow execution contract tests and full package;
4. prove no strategy, notional, exit, Grid/OCO, or fund change;
5. deploy without a manual or test order;
6. accept only through natural provider events and read-only reconciliation.

## Current local implementation boundary

The local foundation now contains:

- a forward-only `bt_spot_execution_attempt` migration file;
- database uniqueness for lot/side/sequence, client order ID, and provider
  order ID;
- an optimistic version plus atomic `RESERVED -> SUBMITTING` election;
- a provider-first state model with explicit ambiguous submission;
- deterministic DRA partial-sell sequence IDs;
- idempotent cumulative-fill delta calculation and focused pure tests.

The DRA sell path is now wired locally to reserve before provider mutation,
atomically elect one submitter, look up OKX by client order ID before submit,
and apply cumulative provider fills as idempotent quantity/quote/fee deltas.
Ambiguous submission remains unresolved and cannot return to `SUBMITTING`.
The first sell retains `DRA1S<signal-time>`; only a reconciled partial may
allocate a later sequence.

The buy path is also wired locally to reserve and claim before submission.
Provider-net quantity remains conservative while fee currency is absent; a
later provider lookup can finalize base-fee quantity or quote-fee cash cost
without a second order or duplicate success audit.

The migration has not been run and none of this local wiring is deployed.
Position 263 has not been backfilled or changed. Database-backed concurrency
validation and a final staged-diff review remain required before promotion.
