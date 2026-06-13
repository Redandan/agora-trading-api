# agora-trading-api Codex Guidance

This repository is production-sensitive and owns the split Trading runtime.
Read these files before non-trivial analysis or edits:

- `SERVICE_BOUNDARY.md` for service ownership and cross-service dependency
  rules.
- `SPLIT_PROGRESS.md` for current split status, completed cleanup, and
  acceptance history.
- `docs/split-acceptance-status.md` for the current deploy/acceptance handoff.
- `docs/deploy-runbook.md` for local, server, and deployment verification.

Default workflow:

1. Start with `git status --short --branch`.
2. Classify production, trading strategy, OCO/grid, order, fund, Earn, MCP
   write, scheduler, and database risk before editing.
3. Keep unrelated local or server-side changes intact.
4. For Java, config, script, or runtime-behavior changes, run
   `.\scripts\verify_local.ps1`. Add
   `.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180` when the
   change can affect startup, HTTP routing, MCP registration, scheduler
   gating, external-provider guards, or split acceptance.
5. For split-boundary-only cleanup, run
   `.\scripts\verify_split_boundaries.ps1`.
6. For docs-only changes, `git diff --check` is normally enough unless the docs
   change claims new runtime, deploy, or acceptance evidence.
7. Deploy and run server verification only when runtime/API behavior changed
   and the requested scope permits it.

Standing boundaries:

- Do not import AgoraMarketAPI marketplace entities, repositories, controllers,
  or service implementations.
- Keep marketplace access behind explicit internal-client SDK or HTTP DTO
  contracts. The current required internal API is AgoraMarket exchange rates.
- Do not add shared marketplace login/user/profile dependencies unless a future
  product requirement explicitly authorizes a DTO-only design.
- Do not run DB migrations, baseline regeneration, extra-table cleanup, or table
  drops unless explicitly authorized for the shared-DB split state.
- Pause before live strategy, OCO/grid, order, fund, Earn, scheduler enablement,
  Telegram-send, external-backfill/import, or production mutation changes unless
  explicitly authorized.
- Treat extra marketplace/shared tables in `agora_market` as expected while DB
  remains shared; do not clean them up from this service.
