# agora-trading-api Codex Guidance

This repository is production-sensitive and owns the split Trading runtime.
Read these files before non-trivial analysis or edits:

- `SERVICE_BOUNDARY.md` for service ownership and cross-service dependency
  rules.
- `docs/strategy-driven-minimal-runtime.md` for the active strategy-first
  runtime contract.
- `docs/deploy-runbook.md` for the retained deployment workflow.

Default workflow:

1. Start with `git status --short --branch`.
2. Classify production, trading strategy, OCO/grid, order, fund, Earn, MCP
   write, scheduler, and database risk before editing.
3. Keep unrelated local or server-side changes intact.
4. The automated test tree and non-deployment verification scripts were
   intentionally removed during the strategy-first simplification. For Java
   or config changes, compile with `mvn -DskipTests package`.
5. For deployment-script changes, syntax-check the retained scripts and review
   their exact dependency closure before deploying.
6. For docs-only changes, `git diff --check` is normally enough.
7. Deploy and run `scripts/verify_server.sh` only when runtime/API behavior changed
   and the requested scope permits it.

Autonomous research workflow:

- Read `docs/autonomous-research-charter.md` before changing or advancing the
  research control plane.
- Use the repository skill at
  `.agents/skills/autonomous-trading-research/SKILL.md` and the
  `python -m research_pipeline` CLI for registered experiments.
- Keep mutable runs, artifacts, registry state, and generated reports under
  `.research-state/`; never overwrite a sealed artifact.
- Launch the offline Java DRA research CLIs only through the approved
  `java-dra-v1-parity` / `java-dra-v1-economic-ledger` adapters or an
  equivalent direct Java 21 classpath invocation. Do not use the repository
  Maven exec main-class configuration; it targets `TradingApiApplication` and
  would start Spring.
- Treat the control plane as offline research tooling. It must not become a
  Spring bean, Trading runtime scheduler, strategy catalog entry, database
  writer, or SHADOW/PAPER/LIVE promotion path. The sole control-plane exception
  is the independent OAuth Research Worker defined in
  `docs/server-research-worker-v2.md`; one Codex cloud Ops schedule may enqueue
  its fixed deterministic heartbeat and one bounded evidence-ready candidate
  registration while the server path unit dispatches those fixed operations.
- Use `research_pipeline/policy.v3.json` for new control-plane work. Preserve
  prior policy hashes embedded in already sealed historical experiments.
- For local Codex research-node work, read
  `docs/local-codex-research-node-v1.md` and validate the exact task package
  before execution. The local node is message-dispatched, non-authoritative,
  and must not add a timer, call Research MCP writes, or write canonical state.

Standing boundaries:

- Do not import AgoraMarketAPI marketplace entities, repositories, controllers,
  or service implementations.
- Keep marketplace access behind explicit internal-client SDK or HTTP DTO
  contracts. The current required internal API is AgoraMarket exchange rates.
- Do not add shared marketplace login/user/profile dependencies unless a future
  product requirement explicitly authorizes a DTO-only design.
- Do not run DB migrations, baseline regeneration, extra-table cleanup, or table
  drops unless explicitly authorized for the shared-DB split state.
- Pause before live strategy, OCO/grid, order, fund, Earn, Trading scheduler enablement,
  Telegram-send, external-backfill/import, or production mutation changes unless
  explicitly authorized.
- Treat extra marketplace/shared tables in `agora_market` as expected while DB
  remains shared; do not clean them up from this service.
