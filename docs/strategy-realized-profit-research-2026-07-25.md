# 全策略已實現收益調研與接管建議

日期：2026-07-25

調研時間點：2026-07-25 15:55（Asia/Taipei）

本地程式基準：`2bfb6705cee9547cd0d7f725c2878a31c14e19f5`

Production runtime 程式基準：`6dae3fb`（owner 509 LIVE 實作）

## 1. 結論

目前沒有第二個歷史策略符合「像 owner 509 一樣，能用實際成交與完整費用證明已實現淨收益」的接管條件。

應維持：

1. `509`：唯一 `LIVE` 策略，繼續等待真實訊號與真實平倉；目前沒有 509 買入或平倉，不能宣稱已賺錢。
2. `BTC_DONCHIAN_20D_10D_V1`：繼續 `SHADOW`；歷史研究品質最好，但 forward 只有一個 entry、零個 completed trade、零已實現收益，不能轉 LIVE。
3. OKX Grid：作為獨立交易功能繼續運行，不納入策略排名，也不能把 fill 或 completed group 當成已證明的淨收益。
4. 其餘 30 個資料庫歷史策略：繼續視為 `ARCHIVED` 研究資料；資料庫的 `enabled=true` 不具有 runtime 執行權。

如果未來一定要接手一個歷史買入訊號，第一個研究對象是 `567 MEI`，但它現在只配稱「研究候選」，不配稱「部署候選」：

- 最新舊回測的真實資本變化為 `+809.49 USDT / +8.09%`，62 筆已平倉；
- 六次保存的回測都為正，但只有一次達五筆以上，且不是獨立 walk-forward 證據；
- 55/62 筆靠 `TIME` 強制平倉、6 筆靠 `SL`、1 筆靠 `END`，完全不是 509 的只賺錢才賣；
- 回測用 `binance`，資料庫目前宣告 `okx`，策略版本不一致；
- MEI 計算沒有鎖定 K 線 source，同一期間同時讀得到 4,331 根 Binance 與 4,331 根 OKX 1h K 線；
- MEI 是「波動方向將放大」而不是看多訊號，本身不能證明 LONG 方向；
- 指標最後資料停在 `2026-06-13 03:03:03 UTC`，截至本報告已超過 41 天沒有更新；
- CMI 策略執行器與指標收集 scheduler 已在簡化工程中移除。

因此，正確動作不是現在恢復 567，而是先讓 509 產生真實已實現淨收益證據。若之後仍要研究 567，只能以一個隔離、source-pinned、同 509 帳本的最小 replay 重新驗證，不恢復整套 CMI/AI/ML framework。

### 1.1 後續開發決定

使用者後續明確決定：509 的實際成交驗收需要時間，不等待它收斂，先平行開發下一個候選。

因此本地新增 `BTC_MEI_DIRECTIONAL_ACCUMULATION_V1@v1`，但沒有恢復舊 567 或 CMI framework：

- 固定只讀 OKX `BTCUSDT@1h` closed bars；
- `MEI>=60` 之外，強制 24h 正動能與 `close>EMA20`；
- 只在完整條件從 false 轉 true 時建立 10 USDT 虛擬 lot；
- 使用 0.10% 雙邊費用、0.05% 雙邊不利滑點、250 USDT 最大占用；
- 每個 lot 達 +5% 淨值才排隊，下一根開盤仍至少 +1% 淨利才虛擬賣出；
- 不用 SL、TIME 或 END 強制平倉；
- 預設 `OFF`，未部署、未啟用，沒有 exchange/OCO/Grid adapter。

這是新策略版本；舊 567 的 `+8.095%` 不算它的績效。完整契約見
`btc-mei-directional-shadow-candidate-v1.md`。

新版本其後以 66,296 根連續 OKX 1h Production bars 完成同引擎回放：
已實現淨利 `+563.44 USDT`、未實現 `-112.69 USDT`、總收益
`+180.30%`、CAGR `14.60%`、最大資本曲線回撤 `38.56%`。但 25 個
open lots 已占滿 250 USDT，2022、2023 與 2026 YTD 都沒有 realized
exit，且明顯落後同成本 BTC buy-and-hold 的 `+1,624.17%`。因此只接受
為 default-OFF/SHADOW 研究候選，不接受為已證明 alpha 或 LIVE 策略。
完整數據見 `btc-mei-directional-performance-report-2026-07-25.md`。

## 2. 調研範圍與證據

本次只讀核對：

- 現行 `StrategyRuntimeCatalog`；
- Production `bt_strategy` 的全部 30 個歷史策略；
- Production 最新 `bt_backtest_result` 與正規化 `bt_backtest_trade`；
- 各策略全部保存回測的正負分布、資料視窗、交易 ledger 重複度；
- Production `bt_live_signal` 的實際自動交易與未平倉；
- `market_indicator_history` 的資料新鮮度；
- `md_kline` 的 Binance/OKX source 覆蓋；
- 現行與刪除前的策略、指標及回測程式；
- 509 與 Donchian 的版本化規則、帳務和 forward 證據。

本次沒有：

- 新跑績效模擬；
- 建立或修改策略；
- 啟用 scheduler；
- 下單、賣出、修改 OCO/Grid 或移動資金；
- 修改 Production 或資料庫；
- commit、push 或 deploy。

知識庫唯讀授權通過後，以 `strategy 508`、`SCORE_BUY_V2`、`CMI MEI`、`BTC Donchian` 等關鍵字搜尋，沒有取得相關 topic；所以本報告不依賴 KB 舊結論，主要使用目前程式與 Production 資料。

## 3. 「只看實質收益」的統一口徑

### 3.1 可接受的已實現淨收益

一筆完整交易的已實現淨收益至少要能對帳：

```text
已實現淨收益
= 實際賣出成交價 × 實際賣出數量
- 賣出實際費用
- 實際買入總成本（包含買入費用）
- 借貸、funding 或其他實際成本
```

同時必須另外揭露：

- completed exit 數；
- open lot 數與成本；
- 未平倉平均成本；
- 最老持倉時間；
- 最大占用資金；
- 是否曾用虧損、時間到期或樣本結束強制平倉。

只顯示「已平倉都是正數」而不顯示未平倉資金，仍可能只是把虧損藏在庫存中。

### 3.2 證據分級

| 等級 | 定義 | 能否宣稱賺錢 |
| --- | --- | --- |
| A | 交易所實際 fills，含買賣費用，能與資產/訂單對帳 | 可以，但仍需足夠樣本 |
| B | 系統按實際 fill 保存完整費用與已實現淨收益 | 可以作為系統證據，最好再與交易所核對 |
| C | 實盤已平倉價差 PnL，但沒有費用 | 只能叫已實現毛損益 |
| D | 回測 `final_capital - initial_capital`，包含回測成本 | 只能作研究證據 |
| E | `net_pnl` 加總、未實現收益、回測總資產或 shadow | 不能當實盤獲利 |

509 的新 LIVE 路徑是目前唯一按 B 級設計的策略路徑：

- 買入後以 provider fill 計算含 fee 的 effective entry；
- 賣出分配 provider sell fee；
- `realized_pnl` 使用賣出淨 proceeds 減 effective cost；
- 每個 509 lot 獨立持有；
- 沒有自動虧損或時間退出。

但是 509 目前沒有任何 509-tagged lot，因此現在的實際收益仍是 `0`，證據樣本也是 `0`。

## 4. 兩個會改變舊排名的帳務問題

### 4.1 舊回測 `bt_backtest_trade.net_pnl` 漏掉買入費用

通用 `BacktestEngine` 的流程是：

1. 進場時先從 cash 扣 `openFee = cash × feeRate`；
2. 剩餘資金才成為 position notional；
3. 平倉的 `TradeRecord.netPnl` 只扣 sell fee 與 borrowing cost；
4. entry fee 沒寫進該筆 `net_pnl`。

所以：

```text
真正回測淨收益
= final_capital - initial_capital
= Σ bt_backtest_trade.net_pnl - Σ entry fees
```

部分代表性差異：

| 策略 | 最新回測逐筆 `net_pnl` 加總 | 真正資本淨變化 | 被漏掉的進場費 |
| ---: | ---: | ---: | ---: |
| 27 | `+259.0497` | `+239.2493` | `19.8004` |
| 567 | `+1,466.0042` | `+809.4914` | `656.5128` |
| 577 | `+0.7169` | `+0.6656` | `0.0513` |
| 579 | `+139.7102` | `+129.7102` | `10.0000` |
| 583 | `+79.2352` | `-17.6596` | `96.8948` |

583 甚至從逐筆加總的正數變成真正資本結果的負數。舊報告若直接使用 `SUM(net_pnl)`，會高估所有頻繁交易策略。

本報告所有回測排名改用：

```text
(final_capital - initial_capital) / initial_capital
```

### 4.2 舊實盤 `bt_live_signal.realized_pnl` 沒有扣費

舊 OCO poller 的 `realized_pnl` 是：

```text
LONG  = (exit_price - entry_price) × quantity
SHORT = (entry_price - exit_price) × quantity
```

它沒有扣買入、賣出、借貸或 funding 費用，所以是 C 級「已實現毛損益」，不是淨收益。

截至本次查詢，舊 auto-traded 實盤資料：

| 策略 | 已平倉 | 未平倉 | DB 已實現毛損益 | 判斷 |
| ---: | ---: | ---: | ---: | --- |
| 28 | 1 | 0 | `-4.4950` | 費前已虧損 |
| 485 legacy | 34 | 0 | `-18.3030` | 費前已虧損；不是新 509 lot |
| 508 legacy | 1 | 3 | `+0.8908` | 最多只是費前正數，另有約 30 USDT 未平倉 |
| 566 | 1 | 0 | `-1.2515` | 費前已虧損 |
| 574 | 3 | 0 | `-6.9347` | 費前已虧損 |

508 的三筆舊未平倉是：

| ID | 週期 | 進場時間 UTC | 進場價 | 約占用成本 |
| ---: | --- | --- | ---: | ---: |
| 260 | 4h | 2026-07-09 08:00 | `62,762.0` | `9.9999 USDT` |
| 261 | 4h | 2026-07-10 04:00 | `63,979.3` | `10.0000 USDT` |
| 262 | 1h | 2026-07-10 09:00 | `64,400.2` | `9.9994 USDT` |

它們屬於 archived 508 legacy position，不是 509。保留其 ownership 與執行安全是必要的，但不能用一筆 `+0.8908` 毛收益宣稱 508 已證明賺錢。

## 5. 現行 Runtime 真相

| Runtime key | 模式 | 實際功能 | 已實現證據 |
| --- | --- | --- | --- |
| `TV_BTC_DAILY_SCORE_BUY_AUTO_EXIT_V2@v2` / alias 509 | `LIVE` | Binance BTCUSDT 日線收盤判斷；OKX spot 買入；每 lot +5% net-profit 自動賣出 | 0 lot、0 exit、0 realized |
| `BTC_DONCHIAN_20D_10D_V1@v1` | `SHADOW` | OKX 1h price-only Donchian virtual execution | forward 1 entry、0 completed trade、0 realized |
| `TV_BTC_DAILY_ACCUMULATION_V1@v1` / alias 508 | `ARCHIVED` | 凍結舊 TradingView entry/accumulation 證據 | 不再評估 |
| 其他 `bt_strategy` rows | `ARCHIVED` | 研究/回測歷史 | DB enabled flag 不會執行 |

509 的執行規則：

- signal：Binance `BTCUSDT` 日線，只在完整 K 線收盤判斷；
- execution：OKX spot `BTC-USDT`；
- weight `1/2/5` → `10/20/50 USDT`；
- 同一根 K 線最多 `80 USDT`；
- 509 open cost 上限 `250 USDT`；
- 每個 lot 預估淨報酬達 `+5%` 才進入賣出；
- 真實賣出若不足 `+1%` net profit，延後；
- 無 SL、無 TIME exit、無 OCO、無 AI/ML gate。

Production 在本次調研時還沒走到部署後第一個 genuine daily-close event；驗收不能製造交易。因此「509 真的有執行能力」已由 runtime/設定/連線驗收證明，但「509 能產生績效」尚未被任何真實交易證明。

## 6. 全部 30 個資料庫策略

下表的「最新回測」一律採真正資本淨變化，而不是 `SUM(net_pnl)`。所有非 catalog 策略即使資料庫 `enabled=true`，也仍是 runtime `ARCHIVED`。

| ID | 策略/類型 | 最新真正回測 | Production 實際 | 結論 |
| ---: | --- | --- | --- | --- |
| 27 | BTC SOP 1h | `+2.392% / 3` | 無 | 樣本太少；結果 source=Binance、目前宣告 OKX；不恢復 |
| 28 | ETH SOP 1h | `-8.701% / 6` | 1 筆，毛損益 `-4.495` | 非 BTC 目標且實盤虧損；淘汰 |
| 269 | Mean Reversion | `-3.769% / 20` | 無 | 淘汰 |
| 482 | MR-FG | `-12.473% / 6` | 無 | 淘汰 |
| 485 | ScoreBuy V2 DB row | `0% / 0` | legacy 34 筆，毛損益 `-18.303` | 僅保留作 509 DB mapping；legacy 績效不代表 509 |
| 488 | AI SOP trend | `0% / 0` | 無 | 無近期訊號，impl 已刪；封存 |
| 496 | ScoreBuy V3 | `-21.528% / 36` | 無 | 淘汰 |
| 508 | OI/Funding | `-3.599% / 7` | 1 closed `+0.891` gross、3 open | provider 複雜、回測負；封存，只維護舊 lot |
| 548 | AI trend short | `0% / 0` | 無 | 無近期訊號，impl 已刪；封存 |
| 563 | SQI long | `-7.924% / 12` | 無 auto-traded | SQI runtime 已刪、資料 stale；淘汰 |
| 564 | ShortBuild long | `-22.573% / 16` | 無 | 淘汰 |
| 565 | SDI long | `-2.695% / 1` | 無 | 原本已標 invalid；淘汰 |
| 566 | ETF long | `-1.860% / 2` | 1 筆，毛損益 `-1.251` | 費前實盤也虧；淘汰 |
| 567 | MEI breakout | `+8.095% / 62` | 無 | 第一研究候選；不是部署候選 |
| 568 | VDI undervalued | `-12.056% / 3` | 無 | 原本已標 invalid；淘汰 |
| 569 | AI SOP | `0% / 0` | 無 | 無證據；封存 |
| 570 | AI SOP | `0% / 0` | 無 | 無證據；封存 |
| 571 | AI SOP | `0% / 0` | 無 | 無證據；封存 |
| 572 | ETH ScoreBuy V2 | `0% / 0` | 無 | 非 BTC 目標且無訊號；封存 |
| 573 | Fade short LSR | `-14.241% / 25` | 無 | 淘汰 |
| 574 | MEI long v2 | `-7.095% / 18` | 3 筆，毛損益 `-6.935` | 費前實盤也虧；淘汰 |
| 575 | SQI no-SL POC | `-23.109% / 3` | 無 auto-traded | TIME exit 造成巨大虧損；淘汰 |
| 576 | SQI tier30 | `-23.109% / 3` | 無 auto-traded | 與 575 相同近期結果；淘汰 |
| 577 | SQI tier50 | `+6.656% / 5` | 無 | 僅 10 USDT、同期間重跑；研究備選，不恢復 |
| 578 | SDI no-SL | `+0.078% / 1` | 無 | 只有一筆且為 END；不足 |
| 579 | ETF no-SL | `+1.297% / 1` | 無 | 最新只有一筆；研究備選，不恢復 |
| 580 | ShortBuild no-SL | `+2.306% / 3` | 無 | 樣本太少；研究備選，不恢復 |
| 581 | AI SOP | `-14.270% / 75` | 無 | 淘汰 |
| 582 | AI SOP | `-1.400% / 1` | 無 | 淘汰 |
| 583 | AI SOP | `-0.177% / 13` | 無 | `SUM(net_pnl)` 假正、真正資本為負；淘汰 |

## 7. 為什麼「很多次正收益」不能直接採信

多個舊策略看起來有很高的 positive-run ratio，但大部分是相鄰時間重跑高度重疊的 30–90 天視窗，不是數百個獨立市場實驗。

| 策略 | 正/總 runs | runs ≥ 5 trades | distinct trade ledgers | start/end 日期數 | 最新結果 |
| ---: | ---: | ---: | ---: | --- | ---: |
| 566 ETF | `187/208` | 177 | 110 | 36 / 24 | `-1.860%`，實盤毛損 `-1.251` |
| 567 MEI | `6/6` | 1 | 5 | 5 / 4 | `+8.095%` |
| 574 MEI v2 | `167/187` | 187 | 104 | 31 / 21 | `-7.095%`，實盤毛損 `-6.935` |
| 575 SQI | `171/205` | 195 | 117 | 32 / 22 | `-23.109%` |
| 576 SQI | `168/191` | 183 | 108 | 31 / 22 | `-23.109%` |
| 577 SQI tier50 | `2/2` | 2 | 2 | 1 / 1 | `+6.656%` |
| 579 ETF no-SL | `179/193` | 175 | 110 | 31 / 22 | `+1.297% / 1 trade` |
| 580 ShortBuild | `2/2` | 1 | 2 | 1 / 1 | `+2.306% / 3 trades` |

這些數字揭露三件事：

1. 同一個市場時段被重複計數，positive-run ratio 不是獨立成功率。
2. 近期 source-aligned 結果比過去滾動平均更差。
3. 567/577/579/580 的正結果仍使用 SL、TIME、TP 或 END 出場，不能回答「改成 509 只在盈利時賣後，會占用多少資金」。

舊通用回測會在最後一根 K 線用 `END` 強制平倉，因此最新 runs 全部沒有 open lot。它天生無法提供 509 最重要的未平倉成本、平均成本、最老 lot 與最大資金占用。

## 8. 候選深度分析

### 8.1 509：先觀察，不改規則

優點：

- 唯一具有完整 fee-aware 實盤帳本；
- frozen TradingView entry，避免 AI/ML/風控反覆改寫策略；
- 日線收盤判斷，低頻且維護面小；
- profit-only per-lot exit，直接降低人工賣出依賴；
- 不會誤賣 Grid、manual 或 legacy 508 BTC。

缺口：

- 0 真實買入、0 真實平倉；
- 歷史 replay 不是實盤收益；
- profit-only 可能長期占用 250 USDT 上限，需要真實 open-lot age 才能判斷。

判斷：保持 LIVE，但績效狀態必須標為 `UNPROVEN / NO_REALIZED_SAMPLE`。

### 8.2 Donchian：保留 SHADOW，不改成 509

優點：

- 價格資料 only，來源鎖定 OKX；
- 66,009 根 1h K 線、完整 hash 與 deterministic ledger；
- 歷史 normal `+171.89%`、stress `+163.25%`；
- 41 個完整 round trips；
- normal/stress max drawdown 都約 `15.10%`；
- 4/5 folds positive。

限制：

- 最近 fold 是負數；
- 現在 forward 只有 1 entry、0 completed trade；
- forward 已實現淨收益是 0；
- 它是趨勢策略，退出邏輯是 10 日突破/ATR stop，不適合硬套 509 profit-only exit；
- 沒有 LIVE exchange adapter。

判斷：它是證據品質最高的第二條策略 lane，但現在只應繼續收集 SHADOW realized evidence。不要為了「像 509」而破壞其 frozen policy。

### 8.3 567 MEI：第一研究候選，但暫不恢復

正面證據：

- 最新真正資本收益 `+8.095%`；
- 最新 62 筆，34 勝、28 負；
- 六個保存 runs 都為正，真正收益範圍 `+3.953%` 至 `+13.888%`。

關鍵反證：

- 最新 62 筆的退出：55 `TIME`、6 `SL`、1 `END`；
- `SUM(net_pnl)` 高估 `656.51 USDT`；
- 沒有實盤 auto-traded realized sample；
- MEI 高只代表 entropy 高，不代表價格向上；
- 回測 source=Binance，策略 current source=OKX；
- MEI 的 K 線 repository query 沒有限定 source；
- 同一回測期間同時存在 4,331 Binance 和 4,331 OKX bars，時間戳完全重疊；
- current CMI strategy implementation 已移除；
- 指標資料 stale 超過 41 天；
- 改成 509 後，舊 TIME/SL 已實現收益分布不再適用。

判斷：只允許未來做「source-pinned MEI entry + 509 ledger」的隔離研究。未完成前不得恢復 CMI runtime、不得建立 LIVE 策略。

### 8.4 577 SQI tier50：小樣本正數，不值得恢復供應鏈

正面證據：

- 最新真正收益 `+6.656%`；
- 5/5 trades 為正；
- 只使用 10 USDT capital 的小型 POC。

反證：

- 兩次回測是同一個 30 日窗口內相隔約 45 分鐘重跑，不是兩個獨立 fold；
- SQI class 已刪除；
- SQI 需要 funding、long/short ratio、liquidation、價格確認與 liquidation WS；
- 原歷史計算曾修過 live-data/time alignment 問題；
- 指標資料 stale 超過 41 天；
- 30 天 cap 仍會把虧損用 TIME 變成 realized loss，不是 509。

判斷：目前不要恢復 SQI。只有在 509 與 Donchian 都有實際結果後，且使用者明確願意承擔外部資料供應鏈成本，才重新評估。

### 8.5 579 ETF no-SL：舊 rolling ratio 看似漂亮，最新只有一筆

正面證據：

- 最新真正收益 `+1.297%`；
- 193 runs 中 179 個資本結果為正。

反證：

- 最新只有 1 筆交易；
- 193 runs 只對應 31 個 start dates、22 個 end dates、110 個 distinct ledgers；
- 真正 run 範圍從 `-24.454%` 到 `+41.274%`，並不穩定；
- 舊 566 ETF 實盤唯一一筆就是費前 `-1.251`；
- ETF indicator 使用 Yahoo Finance 價格與成交量方向代理，不是精確 net flow；
- refresh 預設關閉；
- indicator collector 已刪；
- 指標資料 stale 超過 41 天。

判斷：不要恢復。若未來要 ETF alpha，應先取得可審計的 daily net-flow source，而不是復活舊 proxy framework。

### 8.6 580 ShortBuild：概念合理，證據太少

正面證據：

- 兩次保存 runs 都為正；
- 最新真正收益 `+2.306%`。

反證：

- 最新只有 3 筆，另一 run 10 筆，且兩次是同一歷史視窗；
- 依賴 OI、long/short ratio、funding 與 Kraken price；
- collectors 已刪，指標 stale；
- 不具 Production auto-traded sample。

判斷：保留策略文字與舊資料，不恢復程式。

### 8.7 27 SOP：可維護但幾乎不交易

正面證據：

- 價格型訊號，不需要複雜外部指標；
- 最新真正收益 `+2.392%`。

反證：

- 730 天只有 3 筆；
- 最新 source=Binance，但 current DB source=OKX；
- 31 runs 只有 15 正、12 負、4 零；
- strategy implementation 已刪；
- 低頻到無法在合理時間取得 Production realized sample。

判斷：可作為 archive 中的低維護研究資料，但不值得現在接手。

## 9. 資料來源與維護成本

| Lane | 主要資料 | Current 狀態 | 可重建性 | 維護成本 |
| --- | --- | --- | --- | --- |
| 509 | Binance closed daily bars + OKX fills | 現行 LIVE | 高；版本化且 source 清楚 | 低至中 |
| Donchian | OKX confirmed 1h OHLC | 現行 SHADOW，golden parity | 很高 | 中 |
| 567 MEI | BTC 1h OHLC entropy | code 尚在，但 query 未鎖 source、collector 已刪 | 修正後可高 | 中 |
| 577 SQI | funding/LSR/liquidation/price/WS | implementation 與 liquidation runtime 已刪 | 低至中 | 高 |
| 579 ETF | Yahoo ETF price/volume proxy | refresh 預設 false、collector 已刪 | 中，但不等於 net flow | 高 |
| 580 ShortBuild | OI/LSR/funding/Kraken | collector 已刪 | 取決於多個 provider | 高 |
| 508 OI/Funding | OI/funding/volume/SMA/其他 gate | implementation 已刪 | provider parity 困難 | 很高 |
| AI/SOP/ML ensemble | price + features + model/gates | runtime 已移除 | 低；版本與資料漂移多 | 很高 |

目前 repository 仍有 5 個 `CompositeIndicator` implementation，但沒有原本驅動它們的 `CompositeIndicatorScheduler` 與 `MarketIndicatorHistoryCollector`。這些 class 會增加理解與依賴面，卻不會產生現行策略輸入。

若下一輪目標是再降低維護成本，建議先做只讀 dependency closure，確認 diagnostic tools 沒有需要即時計算後，再考慮移除：

- `EtfPressureIndicator`
- `MarketEntropyIndicator`
- `ShortBuildIndicator`
- `StablecoinDemandIndicator`
- `VdiIndicator`
- 僅服務上述 dormant indicator 的外部 market client

但若決定研究 567，應只保留/重寫 MEI 的最小 source-pinned 計算，不要恢復整個 CMI scheduler。

## 10. 候選排序

### 10.1 實際獲利準備度

| 排名 | Lane | 狀態 | 原因 |
| ---: | --- | --- | --- |
| 1 | 509 | `UNPROVEN_LIVE` | 帳務正確、已可執行，但 0 realized sample |
| 2 | Donchian | `UNPROVEN_FORWARD_SHADOW` | 歷史證據最佳，但 forward 0 completed trade |
| 3 | 508 legacy | `ARCHIVED_OPEN_POSITION_ONLY` | 1 筆費前正、3 open；不能證明淨收益 |
| — | 其他 | `NOT_PROFIT_READY` | 負收益、無實盤、費用不完整、source 不一致或資料 stale |

沒有任何策略達到「已證明可賺錢」。

### 10.2 若日後要做下一個最小研究

| 順序 | 候選 | 建議 |
| ---: | --- | --- |
| 1 | 567 MEI | 只做 isolated, source-pinned, owner-509-style ledger replay；不恢復 CMI framework |
| 2 | 27 SOP | 只有在接受極低頻時才研究；source 必須重鎖 |
| 3 | 579 ETF | 先取得可審計 net-flow source，否則不做 |
| 4 | 577 SQI | 只有願意恢復 liquidation/provider supply chain 才做 |
| 5 | 580 ShortBuild | 樣本不足且 provider 多，最後考慮 |

這是「研究順序」，不是 LIVE promotion 排名。

## 11. 建議的後續目標

### Phase A：先讓目前系統產生可判斷的事實

1. 完成 509 第一個 genuine daily-close runtime event 驗收。
2. 等待 509 真實 buy/exit，不製造訂單。
3. 每次 509 close 都核對：
   - provider buy/sell order IDs；
   - gross/net quantities；
   - buy/sell fees 與 fee currency；
   - effective entry cost；
   - realized net PnL；
   - account balance delta。
4. 509 報表固定顯示：
   - realized net；
   - completed exits；
   - open cost / count / average / oldest；
   - 最大 250 USDT 占用比例。
5. Donchian 繼續 SHADOW，直到至少 30 天、5 entries、5 completed trades，且 normal/stress forward economics 通過。

### Phase B：停止讓舊帳務誤導決策

1. 所有舊 backtest 報表改標：
   - `bt_backtest_trade.net_pnl` 不含 entry fee；
   - 策略總淨收益只採 `final_capital - initial_capital`。
2. 所有舊 `bt_live_signal.realized_pnl` 改標為 legacy price PnL，不稱 net profit。
3. 不用 DB `enabled` 判斷是否在運行，只用 `StrategyRuntimeCatalog`。
4. 不把 rolling overlapping runs 的 positive ratio 當獨立成功率。

### Phase C：平行開發下一個策略，但不提前宣稱績效

使用者已授權在等待 509 實際樣本期間平行開發下一個 entry，因此：

1. 以 567 概念建立新的 `BTC_MEI_DIRECTIONAL_ACCUMULATION_V1`，不沿用舊策略身分；
2. signal source 凍結為 OKX；
3. MEI 只讀該 source 的 closed 1h bars；
4. 加入 24h 正動能與 EMA20 多頭方向確認；
5. 使用與 509 同口徑的 fee/slippage、profit-only、open-lot accounting；
6. 輸出 realized net 與未平倉占用，不用 END/TIME 強平美化結果；
7. 本地實作預設 OFF；部署與 SHADOW 啟用需另行授權；
8. forward 通過後仍不得直接轉 LIVE。

本決定仍維持最小化原則：不恢復舊 CMI/AI/ML 系統，不增加外部 provider，
不新增 MCP tool，也不碰 509、Donchian、Grid、OCO 或帳戶資金。

## 12. 最終判斷

這個系統過去長期無法證明績效，並不是因為策略數量不夠，而是：

1. 回測逐筆 PnL 與資本帳沒有同一口徑；
2. 實盤 legacy PnL 沒有完整費用；
3. 大量重疊回測被當成很多次成功；
4. signal source 與 current strategy source 漂移；
5. 外部指標停止更新後，歷史策略仍留在 DB enabled 清單；
6. TIME/SL/END 出場結果被拿來推論 profit-only 策略；
7. 實際已實現收益、未平倉資金占用與交易所對帳沒有被放在第一順位；
8. AI/ML/CMI/多 provider framework 的維護成本大於已證明的收益。

目前最合理的系統形態不是再恢復更多策略，而是：

```text
509 LIVE
  └─ frozen daily score-buy entry
  └─ exact OKX spot execution
  └─ fee-aware per-lot profit exit
  └─ explicit open-capital accounting

Donchian SHADOW
  └─ price-only, source-pinned
  └─ deterministic forward evidence

MEI Directional candidate (local OFF)
  └─ source-pinned OKX 1h entropy
  └─ momentum + EMA direction confirmation
  └─ fee-aware profit-only virtual lots

Grid
  └─ separate execution product
  └─ separate realized-net acceptance

All legacy strategies
  └─ ARCHIVED research inventory
  └─ no runtime execution
```

先證明這三條 lane 的真實收益與資金占用，再決定是否需要第四條。現在恢復 SQI、ETF、OI/Funding、AI/ML 或 CMI ensemble，都會先把維護複雜度加回來，卻沒有足夠實際淨收益證據支持。
