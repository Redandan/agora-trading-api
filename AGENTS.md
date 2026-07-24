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
