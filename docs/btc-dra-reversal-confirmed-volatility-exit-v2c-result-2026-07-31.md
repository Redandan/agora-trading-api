# BTC DRA Reversal-Confirmed Volatility Exit V2C Result

Date: 2026-07-31

Research identity: `BTC_DRA_REVERSAL_CONFIRMED_VOLATILITY_EXIT_V2C_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS
DESIGN_VALIDATION_COMPLETE
PRE_2025_FAIR_RESET_FOLDS_COMPLETE
NO_CANDIDATE
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## Frozen hypothesis tested

V2C retained V2A's causal `1.50 ATR` monotonic ratchet and added one of five
preregistered complete-candle reversal confirmations. A confirmation could
queue an earlier sell only when estimated liquidation after fee and adverse
slippage was strictly net positive. The study did not scan another ATR
multiplier and no V2C candidate used a fixed profit percentage.

The five factor ablations were:

1. EMA20 five-day slope turning nonpositive;
2. complete-day close below causal EMA5;
3. a lot-specific decline of at least one current ATR14;
4. close below the prior five complete-day lows with negative 24-hour
   momentum; and
5. confirmation by at least two of those four factors.

All entry, arm, expiry, cooldown, lot, capital, fee, slippage, next-open,
independent-lot, profit-only, and no-final-liquidation rules remained frozen.

## Data and reproducibility acceptance

The preselection query was physically capped at the sealed OOS boundary and
could not read a post-2024 bar.

- source: server-local Production database, read-only OKX `BTCUSDT` closed
  `1h` rows;
- first open: `2019-01-01T00:00:00`;
- last included close: `2025-01-01T00:00:00`;
- rows: `52,608`;
- gaps, duplicates, off-grid rows, non-one-hour durations, invalid numeric,
  OHLC, or volume rows: `0`;
- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- final runner SHA-256:
  `7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37`;
- two independent final preselection runs produced byte-for-byte identical
  `78,413`-byte JSON files with SHA-256:
  `102f0808883cc305023aca6f5100056d376151bdd5a09d0e12e69874e3161658`.

The runner intentionally returns process exit code `2` for `NO_CANDIDATE`.
Both runs completed all simulations and wrote the same fail-closed decision;
the nonzero code is not an execution failure.

## Exact checkpoint reproduction

Amounts are USDT. Every frozen field, including operational counts,
utilization, and turnover, matched exactly. The headline checkpoints were:

| Checkpoint | Realized | Unrealized | Total | Max DD | Median hold | P90 hold |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| V1 Design | `169.89846767` | `-79.12049441` | `90.77797326` | `29.530448%` | `126.0h` | `1,818.6h` |
| V1 Validation | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` |
| V2A ATR trail `1.50` Validation | `116.45914729` | `-3.20820121` | `113.25094608` | `8.945793%` | `401.0h` | `1,846.6h` |
| V2B `TURNOVER` Validation | `59.93271313` | `0.00000000` | `59.93271313` | `5.177908%` | `179.0h` | `891.0h` |
| V2B `BALANCED` Validation | `71.44693976` | `0.00000000` | `71.44693976` | `5.257247%` | `209.0h` | `1,263.0h` |
| V2B `TREND` Validation | `71.52625635` | `0.00000000` | `71.52625635` | `7.302341%` | `256.0h` | `1,451.0h` |

The V2B annual total/median-hold win counts also reproduced exactly:
`TURNOVER 2/1`, `BALANCED 1/0`, and `TREND 1/0` out of five folds.

## Validation results

All candidates had `51` buys, `50` sells, one open lot, zero blocked entries,
zero deferred exits, and the same ending unrealized PnL. The changed result
came from which profitable retracements were harvested, not from changing the
entry stream or liquidating the final losing lot.

| Reversal factor | Realized | Unrealized | Total | Max DD | Median hold | P90 hold |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| EMA20 slope nonpositive | `113.10322216` | `-3.20820121` | `109.89502095` | `8.978650%` | `384.0h` | `1,704.4h` |
| Close below EMA5 | `81.23826208` | `-3.20820121` | `78.03006087` | `5.648317%` | `288.0h` | `1,416.0h` |
| One-ATR reversal | `85.36169189` | `-3.20820121` | `82.15349068` | `7.192787%` | `336.0h` | `1,468.8h` |
| Donchian5 + negative momentum | `116.85354741` | `-3.20820121` | `113.64534620` | `8.936078%` | `392.5h` | `1,846.6h` |
| Two-of-four consensus | `87.71294693` | `-3.20820121` | `84.50474572` | `7.095375%` | `348.0h` | `1,487.0h` |

| Reversal factor | Avg utilization | Turnover | Base/factor queued exits | Annual total wins | Annual median-hold wins |
| --- | ---: | ---: | --- | ---: | ---: |
| EMA20 slope nonpositive | `26.770862%` | `1,613.10322216` | `45 / 5` | `4/5` | `0/5` |
| Close below EMA5 | `20.227086%` | `1,581.23826208` | `24 / 26` | `3/5` | `0/5` |
| One-ATR reversal | `24.397401%` | `1,585.36169189` | `24 / 26` | `2/5` | `0/5` |
| Donchian5 + negative momentum | `28.963748%` | `1,616.85354741` | `49 / 1` | `4/5` | `0/5` |
| Two-of-four consensus | `25.004788%` | `1,587.71294693` | `29 / 21` | `3/5` | `0/5` |

## Design results

| Reversal factor | Realized | Unrealized | Total | Max DD | Median hold | P90 hold | Avg utilization | Blocked |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| EMA20 slope nonpositive | `270.25976819` | `-101.42144167` | `168.83832652` | `22.420205%` | `346.0h` | `1,561.8h` | `42.295688%` | `7` |
| Close below EMA5 | `249.71058124` | `-79.12049441` | `170.59008683` | `27.099685%` | `261.0h` | `1,643.6h` | `38.318617%` | `3` |
| One-ATR reversal | `244.77321775` | `-79.12049441` | `165.65272334` | `27.068629%` | `274.0h` | `1,598.4h` | `38.290212%` | `3` |
| Donchian5 + negative momentum | `278.07664544` | `-101.42144167` | `176.65520377` | `22.420205%` | `371.0h` | `1,561.8h` | `42.945243%` | `7` |
| Two-of-four consensus | `252.99790583` | `-79.12049441` | `173.87741142` | `27.108254%` | `302.0h` | `1,643.6h` | `39.071526%` | `3` |

For reference, V2A Design total was `176.40466034`, drawdown
`22.420205%`, median `371.0h`, and P90 `1,561.8h`. The more active reversal
factors increased realized PnL but did not remove the regime-dependent ending
inventory loss.

## Fair-reset annual folds

Annual total PnL:

| Exit | 2020 | 2021 | 2022 | 2023 | 2024 |
| --- | ---: | ---: | ---: | ---: | ---: |
| V1 | `56.19904224` | `31.45159316` | `-15.77563722` | `44.20017391` | `38.78866787` |
| EMA20 slope nonpositive | `95.17043739` | `55.00641759` | `-30.55185662` | `45.13403827` | `63.26521032` |
| Close below EMA5 | `71.18425079` | `62.07906777` | `-25.32669852` | `37.35275894` | `39.18152957` |
| One-ATR reversal | `70.55353661` | `63.89453070` | `-28.11349979` | `43.38802070` | `37.61126164` |
| Donchian5 + negative momentum | `100.51750894` | `55.00641759` | `-28.08205092` | `49.08444655` | `63.06512729` |
| Two-of-four consensus | `74.16094194` | `63.48078510` | `-26.18239988` | `47.10677840` | `35.90219496` |

Annual median holding hours:

| Exit | 2020 | 2021 | 2022 | 2023 | 2024 |
| --- | ---: | ---: | ---: | ---: | ---: |
| V1 | `113.0` | `103.5` | `126.0` | `204.0` | `160.0` |
| EMA20 slope nonpositive | `355.5` | `419.0` | `314.0` | `392.5` | `338.0` |
| Close below EMA5 | `234.0` | `367.0` | `240.0` | `317.5` | `261.0` |
| One-ATR reversal | `264.0` | `425.5` | `192.0` | `349.5` | `286.0` |
| Donchian5 + negative momentum | `395.5` | `419.0` | `314.0` | `392.5` | `379.0` |
| Two-of-four consensus | `264.0` | `425.5` | `255.0` | `371.5` | `286.0` |

Every factor improved annual median holding in `0/5` fair-reset folds. This is
not an aggregate-window artifact: the V2A trend-capture mechanism remained
systematically slower than V1 in every calendar regime tested.

## Why no candidate passed

- `DONCHIAN5_NEGATIVE_MOMENTUM` preserved the most trend PnL. Its Validation
  total `113.64534620` slightly exceeded V2A, and it beat V1 annual total in
  four folds. It confirmed only one early exit, so median holding remained
  `392.5h` and P90 remained `1,846.6h`; both holding gates and the annual
  median-hold gate failed.
- `EMA20_SLOPE_NONPOSITIVE` retained `96.90%` of V2A Validation total and beat
  annual V1 total in four folds, but `384.0h / 1,704.4h` still failed both V1
  holding gates and all five annual median comparisons.
- `CLOSE_BELOW_EMA5`, `ATR1_REVERSAL`, and `CONSENSUS_2_OF_4` triggered many
  more early exits and reduced drawdown or P90, but their Validation total PnL
  fell below V1. None retained the preregistered `90%` of V2A total.
- All candidates passed the ending-unrealized and two-percentage-point
  drawdown gates. Those partial improvements cannot override failed total-PnL,
  holding, or annual-stability gates.

The result reproduces the same frontier seen in V2A and V2B: weak reversal
confirmation preserves trends but does not recycle capital quickly enough;
strong confirmation recycles earlier but harvests the trend before sufficient
profit develops. Changing the factor definitions or holding gates now would be
post-hoc selection.

## Sealed outputs and operational boundary

No factor passed all gates, so the formal freeze is `NO_CANDIDATE`:

- `2025-01-01` onward was not queried or evaluated;
- no one-slot overlay was calculated, as preregistered;
- no owner-509 promotion comparison was opened;
- no runtime implementation, strategy catalog entry, SHADOW, LIVE, deployment,
  order, position, Grid/OCO, fund, scheduler, Telegram, database, or Production
  change was made;
- DRA V1, position `263`, owner `509`, and the existing Donchian lane remain
  unchanged.

The checked-in research specification and runner are the reproducible
artifacts. A future study, if authorized, must define a genuinely new causal
hypothesis before reading results; this V2C candidate set and its gates are
closed.
