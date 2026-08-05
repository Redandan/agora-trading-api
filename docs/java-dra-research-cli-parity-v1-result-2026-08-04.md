# Java DRA Offline Research CLI Parity V1 Result

- Result date: 2026-08-04
- Status: `JAVA_PARITY_PASS_RESEARCH_ONLY`
- Phase: `PHASE_A_EXACT_CHECKPOINTS`
- Mandatory research gate: `false`
- Authorization: `RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE`

## Decision

The existing Java `BtcDraShadowEngine` reproduces every frozen Python DRA V1
Design and Validation economic checkpoint exactly when driven by the same
canonical OKX hourly input.

Java may advance to Phase B cross-language event, fill, lot, and normalized
economic-state parity. It is not yet the mandatory confirmation gate for new
research candidates, and no Python runner is retired by this result.

## Exact checkpoint parity

| Window | Realized | Unrealized | Total | DD | Median/P90 hold | Buy/Sell/Open | Blocked | Avg utilization | Turnover |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Design actual | 169.89846767 | -79.12049441 | 90.77797326 | 29.530448% | 126.0 / 1818.6 h | 100 / 95 / 5 | 3 | 34.364819% | 3019.89846767 |
| Design Python | 169.89846767 | -79.12049441 | 90.77797326 | 29.530448% | 126.0 / 1818.6 h | 100 / 95 / 5 | 3 | 34.364819% | 3019.89846767 |
| Validation actual | 89.41118307 | -3.20820121 | 86.20298186 | 7.121498% | 182.5 / 1418.3 h | 51 / 50 / 1 | 0 | 21.632695% | 1589.41118307 |
| Validation Python | 89.41118307 | -3.20820121 | 86.20298186 | 7.121498% | 182.5 / 1418.3 h | 51 / 50 / 1 | 0 | 21.632695% | 1589.41118307 |

The parity comparison covers realized and unrealized valuation, total PnL,
drawdown, closed-lot holding distribution, fills, terminal lots, capacity
blocking, utilization, and net sell proceeds. No tolerance or approximate
comparison was used.

## Frozen input and engine evidence

- Input rows: `52,608`.
- Input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- Java: `openjdk 21.0.10`.
- Engine: `com.agora.service.trading.BtcDraShadowEngine`.
- Policy: `BTC_DAILY_REVERSAL_ACCUMULATION_V1`.
- Design ordered event-ledger SHA-256:
  `fc8b9c72dd512a0e13c40d86cb7cc39b00a039405c5f21110e52a21b3bc236eb`.
- Validation ordered event-ledger SHA-256:
  `6afa83a09cb7220b7d4f44fce4b70fbea9ab87ce41fba1b847e63b5935d394e1`.
- Design runtime-state SHA-256:
  `c6814ebb2ae6f18e03ffc45571fefd0a3f0282ea8bb6637ecefc612a1988eb80`.
- Validation runtime-state SHA-256:
  `6b7d40668abe3c6ca5d5d25a5f8a42e51e962e229c114d7a9ec8014517272ec7`.

The event and runtime-state hashes are Java evidence surfaces only in Phase A.
They are not yet cross-language parity claims.

## Launcher boundary learned during validation

The repository's configured Maven exec plugin retains
`TradingApiApplication` as its main class. Attempting to override it from the
command line started Spring Boot instead of the research CLI. Startup failed
during local datasource configuration before establishing a datasource;
no parity artifact, database write, order, or runtime mutation occurred.

The approved adapter therefore forbids Maven exec. It:

1. requires `JAVA_HOME` Java 21;
2. packages with Maven without running the application;
3. generates a dependency classpath;
4. launches only `com.agora.research.BtcDraResearchCli` with direct
   `java -cp`;
5. asserts from preserved Java CLI logs that no Spring, Tomcat, datasource, or
   application-context startup occurred.

## Sealed artifacts and source hashes

- Specification SHA-256:
  `5ea376408463b583670983e511764976405537a573af9478f173a18164428d4a`.
- Java CLI source SHA-256:
  `0175f65fc277160461423d382da55d694866741e670518c54da9338e2590338f`.
- Java engine source SHA-256:
  `a6b60d084cc6decb29e3640e851f7f2ef0579b92c05dd36ad70b2b41c2e62dde`.
- Java policy source SHA-256:
  `bdc100c84306ac64826b601d01ff2b86e2741763067bffe9014fc1eaf6241463`.
- Data exporter source SHA-256:
  `8b680c95bc98d5d4e0b532d8126c39a8f64de1b5490e37948ba3b4b91ec30c6c`.
- Approved adapter source SHA-256:
  `f587e6501dc17d61a2c0546b70a49c4c1cc7d31d3533d24ca8eba485b1c8b8e8`.
- Sealed diagnostic SHA-256:
  `383c38bf6edfa92c096090810da3c753dacd296bc8f611c391d75edf8d85d0db`.
- Sealed learning SHA-256:
  `877d5faf9b111ee3b3d8520b8cd8b11d7027792ef9d633d02509be5ecf7a988f`.

## Next gate

Phase B must define one language-neutral canonical ledger and prove:

- ordered entry/queue/fill/exit event parity;
- fill price, quantity, fee, and realized PnL parity;
- terminal open-lot parity;
- normalized economic-state hash parity;
- one representative complex overlay parity.

Only after all Phase B gates pass may Java become mandatory economic
confirmation for new candidates.

## Operational boundary

No Production runtime, configuration, database, scheduler, strategy catalog,
DRA LIVE state, position `263`, owner `509`, Grid/OCO, funds, orders, Telegram,
deployment, or external write changed.
