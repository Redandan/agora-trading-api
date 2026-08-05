# Java/Python DRA Cross-Language Economic Ledger V2 Result

Status: `JAVA_LEDGER_PARITY_PASS_RESEARCH_ONLY`

## Decision

Java and Python reproduced the same DRA V1 economic history in the frozen
Design and Validation windows. All Phase A checkpoints and every Phase B
event, fill, hourly economic-state, and terminal-lot hash matched exactly.

This establishes the existing Java `BtcDraShadowEngine` as a viable candidate
shared economic kernel for DRA V1. It does not make Java mandatory for all
research and does not authorize SHADOW, PAPER, LIVE, deployment, or runtime
changes. Phase C must separately reproduce one representative complex
lot-management overlay before Java becomes a required candidate gate.

## Frozen evidence

- input rows: `52,608`;
- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- Java: `openjdk 21.0.10`;
- engine: `com.agora.service.trading.BtcDraShadowEngine`;
- policy: `BTC_DAILY_REVERSAL_ACCUMULATION_V1`;
- mismatches: `0`;
- formal diagnostic SHA-256:
  `6e9b2b9d8146c64b704d24020e1437c1147bca012c9b571441b645e0be47728c`;
- formal learning SHA-256:
  `ffbfad3f1b6cd46b2f4ddc1aa9236a57a382d06cbb164f9b5ed55f40b6201b1a`.

## Design parity

| Ledger | Rows | Shared SHA-256 |
| --- | ---: | --- |
| Ordered events | 515 | `99e02716634b09ebfe7fce9b80be7a10f17f936e3042d4e239100d3fcdef84d8` |
| Buy/sell fills | 195 | `52b018baa2987b9b701c57395cfad1a59923d1b86e7085e5c6e00d7e822a9d5e` |
| Hourly economic states | 35,064 | `8d1ebbd34959f03dd6c45adfc43e4de5e9be381767cc8143b761f3e3f8a42233` |
| Terminal lots | 5 | `b9a55b1bfd4578985f028afdf62166e9e6949bbb569e135d0910e39ba0a7a46f` |

## Validation parity

| Ledger | Rows | Shared SHA-256 |
| --- | ---: | --- |
| Ordered events | 264 | `d52e28afe51df93425f96fc4d2e78bc5483a8de8625ffd31442a99821569735e` |
| Buy/sell fills | 101 | `28485fd5227fe778276dcfd982b20141bc3b9dfb2abe5566fd710d08e620977a` |
| Hourly economic states | 17,544 | `5b82e11abfe2a5475a7882bd0714eacb8917b19e67dc86f391498aa683845923` |
| Terminal lots | 1 | `af1820fef464a8cc8bb156ce1c102ce08cee67fb6ad64ba1dda8bd439b54b644` |

## Boundary proof

The formal adapter launched Java 21 directly with an explicit classpath. Its
Java logs contain no `Spring Boot`, `TradingApiApplication`, `ApplicationContext`,
`DataSource`, or `Tomcat` startup marker. The Java CLI has no Spring annotation,
repository, database, network, order, scheduler, or deployment dependency.

## Next gate

Freeze Phase C around one representative complex overlay. The preferred first
candidate is an interpretable independent-lot exit overlay with partial or
ratcheted de-risking because it exercises quantity allocation, remaining cost,
multiple fills, lot identity, and realized/unrealized accounting. Preserve the
V1 Phase A and Phase B sources and hashes unchanged; add a new versioned CLI
and adapter rather than editing the sealed baseline proof.
