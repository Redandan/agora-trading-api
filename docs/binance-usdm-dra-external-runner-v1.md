# Binance USD-M DRA External Runner V1

## Status

This is an offline, research-only capability. It does not contain a frozen
hypothesis or experiment manifest instance, does not download a historical
corpus, does not run strategy economics by itself, and does not create a
candidate or open OOS. Immediate PnL and drawdown effects are zero; all alpha
and strategy evidence remains `MISSING_PROOF`.

It adds no timer, scheduler, Spring component, database access, Research MCP
write, canonical-state write, or `SHADOW` / `PAPER` / `LIVE` path.

## Official source boundary

The adapter accepts bytes supplied by a caller for one official Binance
`BTCUSDT` USD-M daily `metrics` ZIP and its matching `CHECKSUM` sidecar. The
only approved archive hosts are:

- `https://data.binance.vision`
- `https://s3-ap-northeast-1.amazonaws.com/data.binance.vision`

The public archive format is documented by Binance at
`https://github.com/binance/binance-public-data`. The adapter performs no
network access. It accepts only files named
`BTCUSDT-metrics-YYYY-MM-DD.zip`, refuses days after 2024-12-31, and makes no
persistent writes.

For every archive, `research_pipeline/binance_usdm_archive.py`:

1. verifies the ZIP SHA-256 against the sidecar before opening it;
2. rejects encrypted, linked, nested, traversal, multi-member, oversized, or
   excessive-compression ZIPs;
3. requires the frozen metrics header and one gap-free UTC day containing all
   288 five-minute observations;
4. retains exact decimal text, accepts only byte-identical duplicate timestamp
   rows, and rejects conflicting duplicates or invalid ratios; and
5. returns both the raw archive hash and the normalized payload hash without
   writing either payload.

## Exactly three supported research families

The manifest schema and runner expose only these independent joint mechanisms:

| Family | Admission state | Required joint inputs |
| --- | --- | --- |
| `dra-binance-usdm-deleveraging-flush-entry-admission` | A completed negative BTC day accompanied by OI-value contraction | price return and OI-value return |
| `dra-binance-usdm-positioning-divergence-entry-admission` | Material separation between top-trader and global long/short ratios | both participant ratios |
| `dra-binance-usdm-taker-flow-open-interest-confirmation-entry-admission` | Directional perpetual taker flow confirmed by non-contracting OI | taker long/short ratio and OI-value return |

Funding, premium, basis, carry, univariate OI, and univariate taker-imbalance
families are not supported. A future manifest is limited to one family and one
to three preregistered variants.

## Decision-time and economic contract

Each external observation is released only after its complete UTC source day
closes. It may gate the unchanged DRA signal at that close for the next hourly
fill. An observation from the fill day or any later day is a lookahead error.
Missing observations veto the affected parent signal and are counted as
unavailable interventions.

The runner subclasses the frozen equal-capital parent and keeps:

- initial equity: 250 USDT;
- slot capacity: 240 USDT;
- fee: 10 bps per fill side; and
- adverse slippage: 5 bps per fill side.

A future explicitly frozen historical experiment can call `run_manifest` and
must bind the H1 selection hash, every archive/checksum-derived evidence hash,
the source audit, one supported family, and no more than three threshold
variants. The result supplies matched parent/candidate ledgers for Design,
Validation, and annual folds, including realized/unrealized/total PnL,
drawdown, fee and slippage cost, holding distributions, inventory path,
interventions, annual breadth, and positive-year concentration.

## Capability acceptance and next action

Capability acceptance uses only synthetic data. It proves checksum, archive,
normalization, schema, joint-family, lookahead, unchanged-economics, and output
contract behavior. It does not prove prediction or performance.

After this capability is accepted, the Manager may select exactly one of the
three source-ready families, freeze its causal hypothesis, thresholds, data
hashes, economic gates, and stop conditions, then run one bounded pre-2025
Design/Validation experiment. OOS remains sealed unless that experiment passes
all frozen gates.
