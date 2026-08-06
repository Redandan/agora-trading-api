# Service Boundary

This repo is the trading service extracted from `AgoraMarketAPI`.

## Owns

- Versioned strategy catalog and fail-closed runtime strategy registry, owner
  509 LIVE execution, archived owner 508/V1 evidence, PAPER accounting,
  archived strategy inventory, Donchian SHADOW evidence, the default-OFF DRA
  switch with an explicitly authorized 30 USDT single-lot LIVE canary, spot
  OCO reconciliation, read-only OKX-native
  Grid monitoring, and the minimal trading MCP surface.
- Market data ingestion used by trading decisions. The versioned runtime
  catalog owns exact provider/symbol/interval subscriptions; database strategy
  `enabled` flags are research inventory and cannot start a stream.
- Trading execution-safety notifications and operator diagnostics.
- Trading database schema. Marketplace entities must not be read through shared JPA repositories.

## Does Not Own

- Commerce products, stores, carts, orders, delivery, disputes, reviews, promo codes, recharge, withdraw, and marketplace wallet tables.
- Marketplace frontend deployment/version management.
- Marketplace user profile screens, search logging, customer issues, product classification, and image audit workflows.

## Cross-Service Rule

`agora-trading-api` must not depend on `AgoraMarketAPI` entities, repositories, controllers, or service implementations.

Allowed dependency shapes:

- A small internal-client SDK jar published by `AgoraMarketAPI`.
- HTTP calls through SDK DTOs.
- Local fallback implementation for development or degraded mode.
- AgoraMarketAPI may call Trading's read-only internal report endpoints as the Telegram command gateway; it must not import Trading classes.

Direct database reads against the marketplace database are not allowed after the split.

## Offline Research Control Plane

`research_pipeline` and the repository skill under
`.agents/skills/autonomous-trading-research` are offline, research-only tooling
governed by `docs/autonomous-research-charter.md`. They may orchestrate sealed
research artifacts and approved read-only runner adapters, but they are not a
Trading runtime strategy-orchestration framework.

`com.agora.research.BtcDraResearchCli` and
`com.agora.research.BtcDraEconomicLedgerParityCli` are offline parity
entrypoints over the existing deterministic DRA engine. They may be launched
only as plain Java 21 classes with explicit local input/output; they must never
start Spring, access a repository/database/network, or become runtime beans or
application entrypoints.

The research control plane must not be imported by Spring runtime code,
registered as a Trading runtime scheduler, write into strategy/runtime tables,
place an order, or promote a result to SHADOW/PAPER/LIVE. A passing research
result ends at `REPORTED_NOT_ACTIVATED`. The only inbound exception is the
independent OAuth Research MCP below; it is not part of Trading Spring and can
enqueue only two fixed operations: the deterministic heartbeat and one bounded,
evidence-bound candidate bundle that may end only at an offline
`PREREGISTERED` experiment. Neither operation accepts a command or server path.

One Codex cloud Ops schedule may enqueue the deterministic research heartbeat
and, only after canonical `READY_FOR_HYPOTHESIS`, submit the one frozen candidate
bundle under `docs/server-research-worker-v2.md`. The OAuth MCP binds loopback behind a
fixed nginx route, writes only its request queue and OAuth store, and cannot
write canonical research state or inbox. A systemd path unit dispatches queued
requests; it is not a timer. The Worker has a separate Unix identity,
root-owned code, isolated durable state, no Production secrets, and no database
or exchange authority. It cannot invoke Spring or any Trading runtime action.

An optional local Codex research task may consume a schema-bound task package as
described in `docs/local-codex-research-node-v1.md`. It is a non-authoritative
execution node, not a scheduler or state writer. Its results return to the
Manager/Coach task and cannot call the Research MCP write surface, mutate
canonical state, open OOS, or activate any Trading path.

The same heartbeat call may automatically enqueue one deterministic companion
forward-evidence request only when canonical progress is `CAPTURE_DUE`. A
separate credential-free `agora-evidence-source` identity can call only the
fixed public OKX `BTC-USDT` 1-hour candle endpoint and write a hash-bound
one-way drop. It cannot read canonical state or Trading secrets. A second
network-denied `agora-research` path consumer independently reconstructs and
validates the day before extending the canonical evidence chain. These path
units are event consumers, not timers; all other external imports and every
backfill remain forbidden.

## First Internal API

The first required internal API is exchange rates:

- `GET /api/internal/exchange-rates/usdt`
- `GET /api/internal/exchange-rates/usdt/{currency}`

The response DTO should match the current `ExchangeRateInfo` shape:

- `fromCurrency`
- `toCurrency`
- `rate`
- `symbol`
- `currencyName`
- `lastUpdated`

## Identity Boundary

Trading does not share marketplace login, user profiles, or marketplace user tables.
Non-public HTTP routes default to deny-all; trading access is through explicit
public probes/docs and MCP API-key guarded tools, not role-based web login.
Trading MCP does not own Telegram callbacks or cross-service approval state.
Its callable tools require their declared Bearer API-key level; unannotated or
unknown tools fail closed.

## Telegram Report Gateway

AgoraMarketAPI owns the Telegram webhook and command dispatch for `/report`,
`/manager`, `/analysis`, and `/weekly`. This service owns the report content
and exposes only read-only internal endpoints under `/api/trading/internal/reports/**`,
protected by `X-Internal-Api-Key`.

For the current split, do not add identity internal APIs. If a future product
requirement explicitly needs shared identity, treat that as a separate design
change with a dedicated DTO-only SDK. Do not predefine user internal API
contracts in trading.

## Cleanup Boundary

Marketplace leftovers should be removed or replaced locally, not turned into
internal APIs. The automated local regression script was removed during the
strategy-first simplification, so changes touching these areas require direct
source review. Forbidden categories include:

- Flutter/AppVersion deployment, search logging, customer issue, address, image audit, and product-classification residue.
- OAuth2, WalletConnect/Web3 login, AuthService/AuthCode/2FA, JWT/member login, and marketplace account DTO residue.
- WebPush, chat/realtime, commerce order/cart/delivery/logistics, marketplace wallet enum, PWA/slot analytics, staking, and transaction DTO residue.

If a later trading feature really needs one of these capabilities, add a trading-owned model/service or an explicit SDK/HTTP DTO contract. Do not re-import marketplace entities, repositories, controllers, or service implementations.

Cleanup must preserve owner 509 LIVE semantics, archived owner 508/V1 evidence,
Donchian SHADOW evidence, exact catalog market streams, the isolated DRA
single-lot canary, mechanical OCO execution safety, read-only provider Grid
monitoring, outbound critical notifications, and deployment verification. The
completed cleanup chronology remains in `SPLIT_PROGRESS.md` and Git history.

Current maintenance debt and the scaling gate are defined in
`docs/current-design-debt-and-next-actions.md`. Deleted or retired AI/ML,
TQS/Autopilot, Guardian, generic strategy-risk, and strategy-specific
orchestration paths are historical designs. Do not restore them without a new
versioned requirement and causal performance evidence.
