# BTC DRA V7 Profit-Peak Delayed Failed-Reclaim Exit V12 Result

Date: 2026-08-03

Research identity:
`BTC_DRA_V7_PROFIT_PEAK_DELAYED_FAILED_RECLAIM_EXIT_V12_RESEARCH`

Final status: `NO_CANDIDATE_KEEP_V7_AND_DRA_V1`.

## Outcome first

Delayed confirmation solved most of V9's premature-exit damage, but it did not
produce a complete replacement for V7.

The 12-hour candidate was the economically useful result. In Validation it
raised realized/total PnL from V7's
`96.02789691 / 95.38625667 USDT` to
`96.72332272 / 96.08168248 USDT`, retained the same
`-0.64164024 USDT` ending unrealized PnL, and reduced maximum drawdown from
`6.832349%` to `6.776181%`. Its two managed Validation exits both realized
more than the same V7 lots.

It nevertheless did not improve the portfolio holding distribution: median
and P90 remained exactly `192h / 836h`. It produced only two Validation
manager exits, won total PnL in only `2/5` annual folds, and won annual median
holding in only `1/5`. Four frozen gates therefore failed.

The 24-hour candidate produced three Validation exits and reduced median to
`187h`, but realized/total fell to
`95.28605340 / 94.64441316 USDT`. One additional 2024 exit surrendered
`1.40155461 USDT` versus V7, so waiting longer did not monotonically improve
exit quality.

No threshold was relaxed. No candidate was frozen, and the OOS guard rejected
access before any 2025+ row was fetched. The independent one-slot overlay did
not run.

## Frozen architecture

V7 remained the parent. A manager could observe only an initial full-V2A lot
or an unconditionally promoted V7 lot after its first causal `1R` arm. It
could not change entries, cooldown, routes, promotion, size, partial behavior,
or quota, and the parent V2A ratchet always had same-hour precedence.

For every armed lot, V12 opened an observation only when a complete hourly
close broke below the lows of the prior 24 complete hours:

```text
breakLevel = min(low[b-24 ... b-1])
freshBreak = close[b] < breakLevel
```

The two candidates waited exactly 12 or 24 complete hours. A close above the
frozen break level cancelled the attempt immediately. A decision was valid
only when every intervening close stayed at or below the break level and the
decision close was no higher than the break close. A full-lot exit queued only
when the lot remained estimated-net-positive and filled next open only when
actual modeled PnL remained strictly positive.

## Data and baseline parity

- Server-local read-only OKX `BTCUSDT` complete `1h` bars.
- Preselection rows: `52,608`.
- First open / last close:
  `2019-01-01T00:00:00 / 2025-01-01T00:00:00`.
- Data SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- Design: 2019-2022; Validation: 2023-2024.
- Fair-reset folds: 2020-2024.

The runner reproduced V1, V2A, and V7 exactly before evaluating V12.

| Validation strategy | Realized | Unrealized | Total | Max DD | Median | P90 |
|---|---:|---:|---:|---:|---:|---:|
| DRA V1 | 89.41118307 | -3.20820121 | 86.20298186 | 7.121498% | 182.5h | 1,418.3h |
| V2A 1.50 ATR | 116.45914729 | -3.20820121 | 113.25094608 | 8.945793% | 401h | 1,846.6h |
| V7 parent | 96.02789691 | -0.64164024 | 95.38625667 | 6.832349% | 192h | 836h |

V7 Design total reproduced at `94.90277533 USDT`; its routing, eight
Validation promotions, and annual `2/5` total plus `3/5` median-hold wins
also matched the frozen checkpoint.

## Candidate results

| Candidate | Design total | Validation realized | Validation total | DD | Median / P90 | Fills D/V | Annual total / hold wins | Result |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| 12h failed reclaim | 117.67380942 | 96.72332272 | 96.08168248 | 6.776181% | 192 / 836h | 4 / 2 | 2/5 / 1/5 | Reject |
| 24h failed reclaim | 117.55557825 | 95.28605340 | 94.64441316 | 6.832349% | 187 / 836h | 4 / 3 | 2/5 / 1/5 | Reject |

Both candidates preserved V7's `-0.64164024 USDT` Validation ending
unrealized PnL. Both were much more selective than V9's 27/23 Design/
Validation one-hour giveback exits.

### Failed frozen gates

The 12-hour candidate failed:

1. Validation median no higher than DRA V1 and strictly below V7;
2. at least three Validation manager exits;
3. at least `3/5` annual total wins versus V7;
4. at least `3/5` annual median-hold wins versus V7.

The 24-hour candidate failed:

1. Validation realized strictly greater than V7;
2. Validation total strictly greater than V7;
3. Validation median no higher than DRA V1 and strictly below V7;
4. at least `3/5` annual total wins versus V7;
5. at least `3/5` annual median-hold wins versus V7.

All other frozen economic, selectivity, and audit gates passed.

## What the delay filtered

| Candidate / window | Attempts | Reclaimed | No-down cancel | Confirmed positive | Confirmed nonpositive | Parent precedence | Manager fills |
|---|---:|---:|---:|---:|---:|---:|---:|
| 12h Design | 92 | 60 | 7 | 4 | 9 | 12 | 4 |
| 12h Validation | 78 | 63 | 3 | 2 | 4 | 6 | 2 |
| 24h Design | 94 | 67 | 3 | 4 | 8 | 12 | 4 |
| 24h Validation | 73 | 61 | 0 | 3 | 3 | 6 | 3 |

For the 12-hour Validation candidate, `63/78` attempts reclaimed the broken
level before confirmation. The delayed state machine therefore filtered a
large amount of the ordinary BTC pullback noise that V9 had sold. Another
four attempts completed the bearish confirmation while the lot was no longer
net-positive, so the profit-only boundary correctly prevented a sale.

This filtering explains the economic recovery relative to V9. V9's best
one-hour giveback candidate had Validation total `59.17439047 USDT` and 23
managed exits. V12 12h retained `96.08168248 USDT` with only two managed
exits. The trade-off is that two exits are too few to alter portfolio-wide
holding time or establish annual robustness.

## Annual fair-reset evidence

| Year | V7 total / median | 12h total / median | 24h total / median |
|---|---:|---:|---:|
| 2020 | 51.28045134 / 103.5h | 51.28045134 / 103.5h | 51.28045134 / 103.5h |
| 2021 | 14.60479908 / 26h | 23.51248709 / 26h | 23.25096435 / 26h |
| 2022 | -2.14051706 / 63h | -2.23144086 / 57h | -1.48307572 / 63h |
| 2023 | 39.87706746 / 302.5h | 39.87706746 / 302.5h | 39.87706746 / 302.5h |
| 2024 | 54.01341685 / 190h | 54.70884266 / 190h | 53.27157334 / 187h |

The 12-hour candidate's gains came from 2021 and 2024; it did not improve
2020 or 2023 and slightly reduced 2022 total. The 24-hour candidate improved
2021 and 2022 but lost in 2024. Neither supplied the required breadth.

## Validation lot attribution

The 12-hour candidate managed two lots and improved both:

| V7 lot fill | V12 realized | V7 realized | Delta |
|---|---:|---:|---:|
| 2024-05-18 | 0.22974466 | 0.03819288 | +0.19155178 |
| 2024-09-14 | 1.58091796 | 1.07704393 | +0.50387403 |

The 24-hour candidate managed three lots:

| V7 lot fill | V12 realized | V7 realized | Delta |
|---|---:|---:|---:|
| 2024-08-24 | 1.28670887 | 0.92436848 | +0.36234039 |
| 2024-09-14 | 1.37441464 | 1.07704393 | +0.29737071 |
| 2024-10-15 | 0.32017583 | 1.72173044 | -1.40155461 |

This shows why confirmation latency alone is not a monotonic quality control:
24 hours admitted one later break that was still only a temporary correction.

## Causal and invariance acceptance

Every Design, Validation, and annual-fold audit passed for both candidates:

- exact prior-24 lows excluded the current bar;
- every attempt began strictly after the first causal `1R` manager arm;
- at most one attempt was active per lot, with no same-bar restart;
- all decision classifications occurred at exactly 12/24 hours;
- confirmed attempts never reclaimed and closed no higher than the break bar;
- queues were confirmed and estimated-net-positive;
- every actual fill was strictly net-positive after frozen costs;
- V7 buys, blocked entries, routes, promotions, partial decisions, quantities,
  cost allocation, cooldown, and parent precedence remained unchanged.

The result is therefore a strategy finding, not an implementation or timing
artifact.

## Decision

`NO_CANDIDATE_KEEP_V7_AND_DRA_V1`.

Late failed-reclaim confirmation is a better direction than one-hour
giveback/EMA deterioration. It can identify a very small number of profitable
early exits without destroying runner convexity. Under the frozen gate it is
still too sparse and too year-dependent to replace V7 or DRA V1. The 12-hour
formula is retained only as research evidence, not as a selected candidate.

- Qualified candidates: `0`.
- Selected candidate: `null`.
- `2025+` OOS opened: `false`.
- One-slot overlay opened: `false`.
- Authorization: `RESEARCH_ONLY_NOT_SHADOW_OR_LIVE`.

No Production/runtime/config/database, DRA V1, V7, position `263`, owner
`509`, Grid/OCO, funds, schedules, Telegram, or orders changed. No commit,
push, or deployment was performed.

## Reproducibility

- Specification SHA-256:
  `5facd8e6f9f4d2cc177e4a53cb911f3b20cdf0cdb74fc1ffa64e93174120a168`.
- Formula manifest SHA-256:
  `12dfaa2adf69513f516737e3a85076a25a3b66dd2f34b3713dacb8be3c8ae8ff`.
- Runner SHA-256:
  `678cebc22e65f30685367c42e53f79743f6ca37735ae5b9c6c5750a138997380`.
- Both preselection JSONs are byte-identical, `3,640,637` bytes, SHA-256:
  `b3d5856510710c2ce9a0db3fbaadc960c2f55f054a81d3cb239b17e3dcbb5935`.
- OOS seal guard SHA-256:
  `6ce4dcde87655b9feaeeab684f4a67a4ba08ecbf1ab87fa3bb23e5c1da0eee5a`.

Artifacts:

- `docs/btc-dra-v7-profit-peak-delayed-failed-reclaim-exit-v12-research.md`;
- `research/btc_dra_v7_profit_peak_delayed_failed_reclaim_exit_v12_manifest.json`;
- `research/btc_dra_v7_profit_peak_delayed_failed_reclaim_exit_v12.py`;
- `btc-dra-v7-profit-peak-delayed-failed-reclaim-exit-v12-preselection-2026-08-03-run1.json`;
- `btc-dra-v7-profit-peak-delayed-failed-reclaim-exit-v12-preselection-2026-08-03-run2.json`;
- `btc-dra-v7-profit-peak-delayed-failed-reclaim-exit-v12-oos-seal-guard-2026-08-03.json`.
