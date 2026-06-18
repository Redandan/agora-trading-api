-- Flyway baseline for standalone Trading service.
-- Generated from shared database 'agora_market' on 2026-06-13T06:41:25Z.
-- Contains only tables mapped by agora-trading-api JPA entities.
-- Shared marketplace tables are intentionally excluded.
-- Reviewed shared-DB baseline; do not regenerate or change production env from
-- this migration. Future Trading schema changes should be V2__... migrations.


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_type` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `objective` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'QUEUED',
  `priority` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NORMAL',
  `requested_by` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `assignee_type` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `assignee_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `params_json` text COLLATE utf8mb4_unicode_ci,
  `result_summary` text COLLATE utf8mb4_unicode_ci,
  `error_message` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `started_at` datetime DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_task_status_assignee_created` (`status`,`assignee_type`,`created_at`),
  KEY `idx_ai_task_type_status_created` (`task_type`,`status`,`created_at`),
  KEY `idx_ai_task_requested_created` (`requested_by`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_task_artifact` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `artifact_type` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `uri_or_value` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `metadata_json` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_task_artifact_task_created` (`task_id`,`created_at`),
  CONSTRAINT `fk_ai_task_artifact_task` FOREIGN KEY (`task_id`) REFERENCES `ai_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=23 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_task_review` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `review_status` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `review_note` text COLLATE utf8mb4_unicode_ci,
  `reviewed_by` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_task_review_task_created` (`task_id`,`created_at`),
  CONSTRAINT `fk_ai_task_review_task` FOREIGN KEY (`task_id`) REFERENCES `ai_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_token_usage_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stat_date` date NOT NULL,
  `model` varchar(100) NOT NULL,
  `req_count` int NOT NULL DEFAULT '0',
  `prompt_tok` bigint NOT NULL DEFAULT '0',
  `complete_tok` bigint NOT NULL DEFAULT '0',
  `error_count` int NOT NULL DEFAULT '0',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_model` (`stat_date`,`model`)
) ENGINE=InnoDB AUTO_INCREMENT=7215 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `attention_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(120) NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `predicate_json` json NOT NULL COMMENT '{"symbol":"BTCUSDT","side":"LONG","fg_gt":80}(單層 AND)',
  `action` varchar(24) NOT NULL COMMENT 'Phase 1: LOG_ONLY / NOTIFY;Phase 2: REQUIRE_REVIEW / BLOCK / ESCALATE',
  `severity` varchar(16) NOT NULL DEFAULT 'INFO' COMMENT 'INFO / WARN / CRITICAL',
  `description` varchar(500) DEFAULT NULL,
  `created_by` varchar(64) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL COMMENT 'null=永久',
  `hit_count` int NOT NULL DEFAULT '0',
  `last_hit_at` datetime(6) DEFAULT NULL,
  `review_timeout_seconds` int DEFAULT NULL COMMENT 'Phase 2 REQUIRE_REVIEW 用',
  `review_channel` varchar(32) DEFAULT NULL COMMENT 'Phase 2: TG / SLACK / MCP_QUEUE',
  `fallback_action` varchar(24) DEFAULT NULL COMMENT 'Phase 2 超時 fallback',
  PRIMARY KEY (`id`),
  KEY `idx_ar_enabled` (`enabled`,`expires_at`)
) ENGINE=InnoDB AUTO_INCREMENT=88 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='注意力規則引擎(Phase 1 只 log/notify,Phase 2 可阻擋)';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_auto_exploration_rollout_transition` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `generated_at` datetime(6) NOT NULL,
  `symbol` varchar(20) NOT NULL,
  `strategy_id` bigint NOT NULL,
  `side` varchar(16) NOT NULL,
  `previous_stage` varchar(64) DEFAULT NULL,
  `current_stage` varchar(64) NOT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `blockers_json` json DEFAULT NULL,
  `warnings_json` json DEFAULT NULL,
  `tiny_live_execution_id` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_auto_rollout_generated` (`generated_at`),
  KEY `idx_auto_rollout_scope_generated` (`symbol`,`strategy_id`,`side`,`generated_at`),
  KEY `idx_auto_rollout_stage_generated` (`current_stage`,`generated_at`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_autonomous_exploration_loop_transition` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `generated_at` datetime(6) NOT NULL,
  `symbol` varchar(20) NOT NULL,
  `strategy_id` bigint NOT NULL,
  `side` varchar(16) NOT NULL,
  `state` varchar(64) NOT NULL,
  `previous_state` varchar(64) DEFAULT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `blockers_json` json DEFAULT NULL,
  `warnings_json` json DEFAULT NULL,
  `decision_id` bigint DEFAULT NULL,
  `tiny_live_execution_id` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_auto_explore_loop_generated` (`generated_at`),
  KEY `idx_auto_explore_loop_scope_generated` (`symbol`,`strategy_id`,`side`,`generated_at`),
  KEY `idx_auto_explore_loop_state_generated` (`state`,`generated_at`)
) ENGINE=InnoDB AUTO_INCREMENT=1861 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_backtest_result` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `strategy_id` bigint DEFAULT NULL,
  `symbol` varchar(30) NOT NULL,
  `interval_code` varchar(10) NOT NULL,
  `kline_source` varchar(16) DEFAULT NULL,
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `initial_capital` decimal(20,8) NOT NULL,
  `final_capital` decimal(20,8) NOT NULL,
  `total_return` decimal(12,6) NOT NULL,
  `max_drawdown` decimal(12,6) NOT NULL,
  `win_rate` decimal(12,6) NOT NULL,
  `trade_count` int NOT NULL,
  `fee_rate` decimal(12,6) NOT NULL,
  `trades_json` longtext,
  `config_snapshot_json` longtext,
  `diagnostic_logs_json` longtext,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `sharpe_ratio` decimal(12,6) DEFAULT NULL COMMENT '簡化夏普比率（mean(returnPct) / sampleStdDev(returnPct)）',
  `market_open_price` decimal(20,8) DEFAULT NULL COMMENT '回測期首根 K 開盤價',
  `market_close_price` decimal(20,8) DEFAULT NULL COMMENT '回測期末根 K 收盤價',
  `market_high_price` decimal(20,8) DEFAULT NULL COMMENT '回測期間最高價',
  `market_low_price` decimal(20,8) DEFAULT NULL COMMENT '回測期間最低價',
  `market_volatility_pct` decimal(12,6) DEFAULT NULL COMMENT '行情波動幅度 % = (high - low) / low',
  `market_price_change_pct` decimal(12,6) DEFAULT NULL COMMENT '行情漲跌幅 % = (close - open) / open',
  `market_trend` varchar(10) DEFAULT NULL COMMENT '行情走勢分類：BULLISH / BEARISH / SIDEWAYS',
  `benchmark_return` decimal(12,6) DEFAULT NULL COMMENT '買持報酬率（Buy & Hold），與 total_return 對比用',
  PRIMARY KEY (`id`),
  KEY `idx_bt_result_strategy_created` (`strategy_id`,`created_at`),
  KEY `idx_bt_result_symbol_interval_time` (`symbol`,`interval_code`,`start_time`,`end_time`),
  CONSTRAINT `fk_bt_result_strategy` FOREIGN KEY (`strategy_id`) REFERENCES `bt_strategy` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB AUTO_INCREMENT=2917 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci SECONDARY_ENGINE=RAPID;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_backtest_trade` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `backtest_id` bigint NOT NULL,
  `trade_idx` int NOT NULL COMMENT '0-based ordinal within the backtest run',
  `entry_time` datetime(6) NOT NULL,
  `exit_time` datetime(6) DEFAULT NULL,
  `entry_price` decimal(20,8) NOT NULL,
  `exit_price` decimal(20,8) DEFAULT NULL,
  `quantity` decimal(28,10) NOT NULL,
  `gross_pnl` decimal(20,8) DEFAULT NULL,
  `net_pnl` decimal(20,8) DEFAULT NULL,
  `return_pct` decimal(12,6) DEFAULT NULL,
  `exit_reason` varchar(32) DEFAULT NULL COMMENT 'SL / TP1 / TP2 / SIGNAL / END',
  `side` enum('LONG','SHORT') NOT NULL,
  `borrowing_cost` decimal(20,8) NOT NULL DEFAULT '0.00000000',
  `released_notional` decimal(20,8) DEFAULT NULL,
  `adx14` decimal(10,4) DEFAULT NULL COMMENT 'ADX 14-period at entry bar',
  `rsi14` decimal(10,4) DEFAULT NULL COMMENT 'RSI 14 at entry bar',
  `atr_pct` decimal(12,8) DEFAULT NULL COMMENT 'ATR / entry_price',
  `volume_ratio_ma20` decimal(12,6) DEFAULT NULL COMMENT 'entry_bar_volume / 20-bar MA',
  `close_vs_ema50_pct` decimal(12,8) DEFAULT NULL COMMENT '(close - EMA50) / EMA50 — relative to medium trend',
  `ema20_slope_pct` decimal(12,8) DEFAULT NULL COMMENT '(EMA20 - EMA20[-5]) / EMA20[-5] — 5-bar EMA slope',
  `bb_width_pct` decimal(12,8) DEFAULT NULL COMMENT '4σ Bollinger width / middle (20-period)',
  `dd_20bar_pct` decimal(10,6) DEFAULT NULL COMMENT 'drawdown from 20-bar high',
  `dd_50bar_pct` decimal(10,6) DEFAULT NULL COMMENT 'drawdown from 50-bar high',
  `momentum_50bar_pct` decimal(12,8) DEFAULT NULL COMMENT 'close vs close[idx-50]',
  `realized_vol_20bar` decimal(12,8) DEFAULT NULL COMMENT 'std of 20 bar log-returns',
  `dist_from_ema200_pct` decimal(12,8) DEFAULT NULL COMMENT 'close vs EMA200',
  `range_pct_50bar` decimal(10,6) DEFAULT NULL COMMENT '(high50-low50)/mid',
  `htf_momentum_50bar_pct` decimal(12,8) DEFAULT NULL COMMENT 'HTF (4h or 1d) 50-bar momentum',
  `htf_trend_up` tinyint DEFAULT NULL COMMENT '1 if HTF close > HTF EMA50, else 0',
  `htf_dist_ema50_pct` decimal(12,8) DEFAULT NULL COMMENT 'HTF (close - EMA50) / EMA50',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_backtest_idx` (`backtest_id`,`trade_idx`),
  KEY `idx_backtest` (`backtest_id`),
  KEY `idx_entry_time` (`entry_time`),
  KEY `idx_side_exit_reason` (`side`,`exit_reason`),
  KEY `idx_bt_trade_adx14` (`adx14`),
  KEY `idx_bt_trade_rsi14` (`rsi14`),
  CONSTRAINT `fk_bt_trade_backtest` FOREIGN KEY (`backtest_id`) REFERENCES `bt_backtest_result` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB AUTO_INCREMENT=29854 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci SECONDARY_ENGINE=RAPID;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_decision_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_time` datetime(6) NOT NULL,
  `strategy_id` bigint DEFAULT NULL COMMENT 'soft ref -> bt_strategy.id;null=system-level event',
  `symbol` varchar(20) DEFAULT NULL,
  `interval_code` varchar(10) DEFAULT NULL,
  `bar_open_time` datetime(6) DEFAULT NULL,
  `event_type` varchar(32) NOT NULL COMMENT 'SIGNAL_EVAL / SIGNAL_BUY / SIGNAL_SELL / FILTER_BLOCK / AUTOTRADE_OK / AUTOTRADE_FAIL / EXIT / OVERRIDE_APPLIED / ATTENTION_HIT',
  `outcome` varchar(16) NOT NULL COMMENT 'PASS / BLOCKED / ERROR / INFO',
  `blocker` varchar(64) DEFAULT NULL COMMENT 'LongAiFilter / ShortAiFilter / DailyLossGuard / StrategyOverride / HintDisable / AttentionRule',
  `reason` varchar(500) DEFAULT NULL,
  `context_json` json DEFAULT NULL COMMENT '純量快照:{score,nn,rsi,fg,whale,side};禁塞 array',
  `live_signal_id` bigint DEFAULT NULL COMMENT 'soft ref -> bt_live_signal.id',
  PRIMARY KEY (`id`,`event_time`),
  KEY `idx_audit_time` (`event_time`),
  KEY `idx_audit_strat_time` (`strategy_id`,`event_time`),
  KEY `idx_audit_symbol_time` (`symbol`,`event_time`),
  KEY `idx_audit_event_time` (`event_type`,`event_time`),
  KEY `idx_audit_live_signal` (`live_signal_id`),
  KEY `idx_audit_event_strategy_time_blocker` (`event_type`,`strategy_id`,`event_time`,`blocker`)
) ENGINE=InnoDB AUTO_INCREMENT=70231 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系統決策審計,每次 signal 評估/過濾/下單/平倉各寫一筆' SECONDARY_ENGINE=RAPID
/*!50100 PARTITION BY RANGE (to_days(`event_time`))
(PARTITION p202604 VALUES LESS THAN (740102) ENGINE = InnoDB,
 PARTITION p202605 VALUES LESS THAN (740133) ENGINE = InnoDB,
 PARTITION p202606 VALUES LESS THAN (740163) ENGINE = InnoDB,
 PARTITION p202607 VALUES LESS THAN (740194) ENGINE = InnoDB,
 PARTITION p202608 VALUES LESS THAN (740225) ENGINE = InnoDB,
 PARTITION p202609 VALUES LESS THAN (740255) ENGINE = InnoDB,
 PARTITION p202610 VALUES LESS THAN (740286) ENGINE = InnoDB,
 PARTITION p202611 VALUES LESS THAN (740316) ENGINE = InnoDB,
 PARTITION p202612 VALUES LESS THAN (740347) ENGINE = InnoDB,
 PARTITION p202701 VALUES LESS THAN (740378) ENGINE = InnoDB,
 PARTITION p202702 VALUES LESS THAN (740406) ENGINE = InnoDB,
 PARTITION p202703 VALUES LESS THAN (740437) ENGINE = InnoDB,
 PARTITION p202704 VALUES LESS THAN (740467) ENGINE = InnoDB,
 PARTITION p202705 VALUES LESS THAN (740498) ENGINE = InnoDB,
 PARTITION p202706 VALUES LESS THAN (740528) ENGINE = InnoDB,
 PARTITION p202707 VALUES LESS THAN (740559) ENGINE = InnoDB,
 PARTITION p202708 VALUES LESS THAN (740590) ENGINE = InnoDB,
 PARTITION p202709 VALUES LESS THAN (740620) ENGINE = InnoDB,
 PARTITION p202710 VALUES LESS THAN (740651) ENGINE = InnoDB,
 PARTITION p202711 VALUES LESS THAN (740681) ENGINE = InnoDB,
 PARTITION p202712 VALUES LESS THAN (740712) ENGINE = InnoDB,
 PARTITION p202801 VALUES LESS THAN (740743) ENGINE = InnoDB,
 PARTITION p202802 VALUES LESS THAN (740772) ENGINE = InnoDB,
 PARTITION p202803 VALUES LESS THAN (740803) ENGINE = InnoDB,
 PARTITION pmax VALUES LESS THAN MAXVALUE ENGINE = InnoDB) */;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_execution_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source` varchar(32) NOT NULL,
  `event_type` varchar(48) NOT NULL,
  `severity` varchar(16) NOT NULL,
  `recommendation` varchar(32) NOT NULL,
  `action_boundary` varchar(32) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `symbol` varchar(20) NOT NULL,
  `position_id` bigint DEFAULT NULL,
  `strategy_id` bigint DEFAULT NULL,
  `interval_code` varchar(10) DEFAULT NULL,
  `title` varchar(120) NOT NULL,
  `summary` varchar(1000) NOT NULL,
  `evidence_json` json DEFAULT NULL,
  `fingerprint` varchar(64) NOT NULL,
  `detected_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `expires_at` datetime DEFAULT NULL,
  `acknowledged_at` datetime DEFAULT NULL,
  `resolved_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bt_execution_event_fingerprint` (`fingerprint`),
  KEY `idx_bt_execution_event_active` (`status`,`symbol`,`detected_at`),
  KEY `idx_bt_execution_event_position` (`position_id`,`status`,`detected_at`),
  KEY `idx_bt_execution_event_type` (`event_type`,`status`,`detected_at`)
) ENGINE=InnoDB AUTO_INCREMENT=99 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_funding_arb` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `symbol` varchar(20) NOT NULL,
  `notional_usdt` decimal(20,8) NOT NULL COMMENT 'spot + perp 名目價值(USDT 估值)',
  `spot_qty` decimal(20,8) DEFAULT NULL,
  `spot_entry_price` decimal(20,8) DEFAULT NULL,
  `spot_exit_price` decimal(20,8) DEFAULT NULL,
  `spot_buy_order_id` varchar(50) DEFAULT NULL,
  `spot_sell_order_id` varchar(50) DEFAULT NULL,
  `perp_contract_qty` decimal(20,8) DEFAULT NULL COMMENT 'contract 張數(OKX 整數張)',
  `perp_entry_price` decimal(20,8) DEFAULT NULL,
  `perp_exit_price` decimal(20,8) DEFAULT NULL,
  `perp_open_order_id` varchar(50) DEFAULT NULL,
  `perp_close_order_id` varchar(50) DEFAULT NULL,
  `min_funding_rate` decimal(8,6) NOT NULL COMMENT '進場門檻(8h funding)',
  `exit_threshold` decimal(8,6) NOT NULL COMMENT '出場門檻(連續 2 期低於)',
  `target_profit_usdt` decimal(10,2) DEFAULT NULL COMMENT '累積 funding ≥ 此值即出場(null=不限)',
  `status` varchar(20) NOT NULL COMMENT 'PENDING / OPENING / OPEN / CLOSING / CLOSED / FAILED',
  `accumulated_funding` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '累積 funding USDT 收入',
  `funding_periods` int NOT NULL DEFAULT '0' COMMENT '已收取的 funding 結算次數',
  `realized_pnl` decimal(20,8) DEFAULT NULL COMMENT '平倉後淨 PnL(spot PnL + perp PnL + funding - fee)',
  `hint_gated` tinyint(1) NOT NULL DEFAULT '1',
  `regime_whitelist` varchar(200) NOT NULL DEFAULT 'TRENDING_UP,SIDEWAYS,RECOVERY' COMMENT 'hint_gated=true 時允許持倉的 Gemini regime(CSV)',
  `opened_at` datetime(6) DEFAULT NULL COMMENT '兩條腿全部下完(status=OPEN)時點',
  `closed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `close_reason` varchar(200) DEFAULT NULL COMMENT 'FUNDING_LOW / TARGET_HIT / REGIME_EXIT / DELTA_DRIFT / MANUAL / API_ERROR',
  PRIMARY KEY (`id`),
  KEY `idx_fa_status` (`status`,`symbol`),
  KEY `idx_fa_symbol_open` (`symbol`,`opened_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Funding Rate Arbitrage delta-neutral positions(spot 多 + perp 空配對)';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_grid` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `symbol` varchar(20) NOT NULL,
  `price_lower` decimal(20,8) NOT NULL,
  `price_upper` decimal(20,8) NOT NULL,
  `grid_count` int NOT NULL,
  `per_level_usdt` decimal(10,2) NOT NULL,
  `stop_out_pct` decimal(5,4) DEFAULT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `hint_gated` tinyint(1) NOT NULL DEFAULT '1',
  `regime_whitelist` varchar(200) NOT NULL DEFAULT 'SIDEWAYS,VOLATILE,RECOVERY',
  `total_realized_pnl` decimal(20,8) NOT NULL DEFAULT '0.00000000',
  `closed_pair_count` int NOT NULL DEFAULT '0',
  `paused_at` datetime(6) DEFAULT NULL,
  `paused_reason` varchar(200) DEFAULT NULL,
  `closed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `auto_rebalance` tinyint(1) NOT NULL DEFAULT '0' COMMENT '啟用後價格超出範圍觸發自動重建',
  `rebalance_trigger_pct` double NOT NULL DEFAULT '0.015' COMMENT '超出範圍多少比例觸發重建（預設 1.5%）',
  `rebalance_count` int NOT NULL DEFAULT '0' COMMENT '累計重建次數',
  `max_rebalance_count` int NOT NULL DEFAULT '5' COMMENT '最大重建次數上限，超過需人工確認',
  `min_hours_outside` int NOT NULL DEFAULT '4' COMMENT '價格需持續在範圍外 N 小時才觸發重建，避免短暫穿越',
  `outside_range_since` datetime DEFAULT NULL COMMENT '首次偵測到價格超出範圍的時間',
  `last_rebalance_at` datetime DEFAULT NULL COMMENT '上次自動重建的時間（限每日最多 1 次）',
  PRIMARY KEY (`id`),
  KEY `idx_bt_grid_enabled` (`enabled`,`closed_at`,`symbol`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Grid trading master';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_grid_level` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `grid_id` bigint NOT NULL,
  `level_index` int NOT NULL,
  `price` decimal(20,8) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `filled_qty` decimal(20,8) DEFAULT NULL,
  `filled_price` decimal(20,8) DEFAULT NULL,
  `paired_sell_price` decimal(20,8) DEFAULT NULL,
  `realized_pnl` decimal(20,8) DEFAULT NULL,
  `buy_order_id` varchar(50) DEFAULT NULL,
  `sell_order_id` varchar(50) DEFAULT NULL,
  `error_message` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `filled_at` datetime(6) DEFAULT NULL,
  `intent_at` datetime DEFAULT NULL COMMENT '#340 Phase 3 — 進入 PENDING_OKX 的時間，scanner 用此判斷 OKX 對齊延遲',
  `closed_at` datetime(6) DEFAULT NULL,
  `retry_count` int NOT NULL DEFAULT '0' COMMENT 'SELL_FAILED auto-retry 次數;>=3 停止自動 retry,等人工介入',
  PRIMARY KEY (`id`),
  KEY `idx_bt_grid_level_grid` (`grid_id`,`status`),
  KEY `idx_bt_grid_level_price` (`grid_id`,`price`),
  KEY `idx_bt_grid_level_status_intent` (`status`,`intent_at`)
) ENGINE=InnoDB AUTO_INCREMENT=68 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Grid level state';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_live_signal` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `strategy_id` bigint NOT NULL,
  `symbol` varchar(20) NOT NULL,
  `interval_code` varchar(10) NOT NULL,
  `bar_open_time` datetime(6) NOT NULL,
  `entry_price` decimal(20,8) NOT NULL,
  `suggested_sl` decimal(20,8) DEFAULT NULL,
  `suggested_tp` decimal(20,8) DEFAULT NULL,
  `score` decimal(6,4) NOT NULL,
  `nn_output` decimal(6,4) NOT NULL,
  `notified_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `exit_price` decimal(20,8) DEFAULT NULL COMMENT '實際出場價（NULL=仍持倉）',
  `exit_time` datetime(6) DEFAULT NULL COMMENT '出場時間（UTC）',
  `exit_reason` varchar(50) DEFAULT NULL COMMENT 'Exit reason: SELL_SIGNAL / SL / TP / OCO_FILLED / ORPHAN_CLOSED',
  `realized_pnl` decimal(20,8) DEFAULT NULL COMMENT '已實現損益 USDT（NULL=仍持倉或出場時無法計算）',
  `auto_traded` tinyint(1) DEFAULT NULL COMMENT '是否已自動下單',
  `exchange_order_id` varchar(50) DEFAULT NULL COMMENT 'Binance 買入訂單 ID',
  `actual_entry_price` decimal(20,8) DEFAULT NULL COMMENT '實際成交均價',
  `traded_qty` decimal(20,8) DEFAULT NULL COMMENT '實際買入數量',
  `oco_order_list_id` bigint DEFAULT NULL COMMENT 'OCO 訂單 ListId',
  `side` varchar(5) NOT NULL DEFAULT 'LONG' COMMENT '倉位方向 LONG/SHORT',
  `filter_reason` varchar(500) DEFAULT NULL COMMENT 'AiFilter 攔截原因（如 F&G=21 極度恐慌）；NULL 表示未被攔截',
  `oco_qty` decimal(20,8) DEFAULT NULL COMMENT 'Actual qty committed to OCO order. May differ from traded_qty when Grid HOLDING levels lock BTC. Used for accurate PnL calculation on close.',
  `last_aging_alert_at` datetime DEFAULT NULL,
  `trailing_state` varchar(20) NOT NULL DEFAULT 'ENTERED',
  `trailing_atr` decimal(12,6) DEFAULT NULL,
  `trailing_high` decimal(20,8) DEFAULT NULL,
  `trailing_last_transition_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_bt_live_signal_unique` (`strategy_id`,`symbol`,`interval_code`,`bar_open_time`),
  KEY `idx_bt_live_signal_notified_at` (`notified_at`),
  KEY `idx_bt_live_signal_exit` (`symbol`,`interval_code`,`exit_time`),
  KEY `idx_bt_live_signal_strategy_symbol_exit_time` (`strategy_id`,`symbol`,`exit_time` DESC)
) ENGINE=InnoDB AUTO_INCREMENT=256 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci SECONDARY_ENGINE=RAPID;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_oco_adjustment_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `live_signal_id` bigint NOT NULL,
  `strategy_id` bigint DEFAULT NULL,
  `symbol` varchar(20) NOT NULL,
  `side` varchar(5) NOT NULL DEFAULT 'LONG',
  `action` varchar(32) NOT NULL,
  `old_oco_order_list_id` bigint DEFAULT NULL,
  `new_oco_order_list_id` bigint DEFAULT NULL,
  `old_tp` decimal(20,8) DEFAULT NULL,
  `new_tp` decimal(20,8) DEFAULT NULL,
  `old_sl` decimal(20,8) DEFAULT NULL,
  `new_sl` decimal(20,8) DEFAULT NULL,
  `old_qty` decimal(20,8) DEFAULT NULL,
  `new_qty` decimal(20,8) DEFAULT NULL,
  `source` varchar(64) NOT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `effective_at` datetime NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_oco_audit_signal_time` (`live_signal_id`,`effective_at`),
  KEY `idx_oco_audit_symbol_time` (`symbol`,`effective_at`),
  KEY `idx_oco_audit_new_oco` (`new_oco_order_list_id`),
  KEY `idx_oco_audit_old_oco` (`old_oco_order_list_id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_runtime_decision_evidence` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `decision_id` bigint NOT NULL,
  `evidence_time` datetime NOT NULL,
  `symbol` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `side` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `strategy_id` bigint DEFAULT NULL,
  `interval_code` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `live_signal_id` bigint DEFAULT NULL,
  `signal_source` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `features_snapshot_json` json DEFAULT NULL,
  `freshness_state` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `blocker_reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `tqs_json` json DEFAULT NULL,
  `policy_mode` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `policy_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `policy_inputs_json` json DEFAULT NULL,
  `selected_action` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `exposure_snapshot_json` json DEFAULT NULL,
  `oco_order_list_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `final_outcome` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `score` double DEFAULT NULL,
  `threshold_value` double DEFAULT NULL,
  `decision` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `warnings_json` json DEFAULT NULL,
  `terminal_blocker` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `fear_greed_mode` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ev_result_json` json DEFAULT NULL,
  `tqs_result_json` json DEFAULT NULL,
  `risk_gate_result_json` json DEFAULT NULL,
  `execution_preview_json` json DEFAULT NULL,
  `execution_mode` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `order_sent` tinyint(1) DEFAULT NULL,
  `suppression_reason` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `intent_created` tinyint(1) DEFAULT NULL,
  `oco_plan_created` tinyint(1) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bt_runtime_decision_evidence_decision` (`decision_id`),
  KEY `idx_rt_decision_evidence_time` (`evidence_time`),
  KEY `idx_rt_decision_evidence_symbol_time` (`symbol`,`evidence_time`),
  KEY `idx_rt_decision_evidence_strategy_time` (`strategy_id`,`evidence_time`),
  KEY `idx_rt_decision_evidence_action_time` (`selected_action`,`evidence_time`),
  KEY `idx_rt_decision_evidence_exec_time` (`execution_mode`,`evidence_time`),
  KEY `idx_rt_decision_evidence_suppression_time` (`suppression_reason`,`evidence_time`)
) ENGINE=InnoDB AUTO_INCREMENT=24110 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci SECONDARY_ENGINE=RAPID;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_strategy` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `strategy_type` varchar(50) NOT NULL,
  `config_json` text NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `config_fingerprint` varchar(64) DEFAULT NULL COMMENT 'SHA-256 指紋（strategyType:configJson），用於 AI 策略重複偵測',
  `ai_generated` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'AI 自動探勘生成的策略標記',
  `discovery_batch` varchar(50) DEFAULT NULL COMMENT 'AI 探勘批次 ID（格式 yyyyMMdd-HHmmssSSS）',
  `symbols` varchar(200) DEFAULT NULL COMMENT '監控交易對，逗號分隔，NULL=全部',
  `kline_source` varchar(16) NOT NULL DEFAULT 'okx',
  `notes` varchar(1000) DEFAULT NULL COMMENT 'enableStrategy / disableStrategy 的說明備註(為何啟用/停用)',
  `alpha_source` varchar(100) DEFAULT NULL,
  `trigger_conditions` text,
  PRIMARY KEY (`id`),
  KEY `idx_bt_strategy_type` (`strategy_type`),
  KEY `idx_bt_strategy_ai_generated` (`ai_generated`),
  KEY `idx_bt_strategy_discovery_batch` (`discovery_batch`),
  KEY `idx_bt_strategy_ai_fingerprint` (`ai_generated`,`config_fingerprint`),
  KEY `idx_bt_strategy_alpha_source` (`alpha_source`)
) ENGINE=InnoDB AUTO_INCREMENT=584 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_tiny_live_event_risk_override_token` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) NOT NULL,
  `token_id` varchar(80) NOT NULL,
  `token_hash` varchar(128) NOT NULL,
  `symbol` varchar(20) NOT NULL,
  `strategy_id` bigint NOT NULL,
  `side` varchar(16) NOT NULL,
  `notional_usdt` decimal(20,8) NOT NULL,
  `preview_hash` varchar(128) NOT NULL,
  `status` varchar(32) NOT NULL,
  `reason` varchar(500) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_tiny_live_event_override_token_hash` (`token_hash`),
  KEY `idx_tiny_live_event_override_created` (`created_at`),
  KEY `idx_tiny_live_event_override_scope` (`symbol`,`strategy_id`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bt_tiny_live_execution_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6),
  `status` varchar(64) NOT NULL,
  `symbol` varchar(20) NOT NULL,
  `strategy_id` bigint NOT NULL,
  `side` varchar(16) NOT NULL,
  `preview_token_id` varchar(80) DEFAULT NULL,
  `preview_hash` varchar(128) NOT NULL,
  `approval_token_hash` varchar(128) NOT NULL,
  `approval_mode` varchar(64) DEFAULT NULL,
  `approval_token_id` varchar(100) DEFAULT NULL,
  `approval_token_type` varchar(40) DEFAULT NULL,
  `auto_approval_policy_version` varchar(64) DEFAULT NULL,
  `event_risk_override_used` tinyint(1) NOT NULL DEFAULT '0',
  `human_reason` varchar(500) DEFAULT NULL,
  `denial_reason` varchar(500) DEFAULT NULL,
  `order_id` varchar(100) DEFAULT NULL,
  `oco_algo_id` bigint DEFAULT NULL,
  `notional_usdt` decimal(20,8) DEFAULT NULL,
  `qty` decimal(20,8) DEFAULT NULL,
  `entry_price` decimal(20,8) DEFAULT NULL,
  `tp_price` decimal(20,8) DEFAULT NULL,
  `sl_price` decimal(20,8) DEFAULT NULL,
  `max_loss_usdt` decimal(20,8) DEFAULT NULL,
  `policy_mode` varchar(64) DEFAULT NULL,
  `tqs_band` varchar(64) DEFAULT NULL,
  `expected_r` decimal(20,8) DEFAULT NULL,
  `order_sent` tinyint(1) NOT NULL DEFAULT '0',
  `oco_attached` tinyint(1) NOT NULL DEFAULT '0',
  `live_signal_id` bigint DEFAULT NULL,
  `runtime_evidence_id` bigint DEFAULT NULL,
  `decision_audit_id` bigint DEFAULT NULL,
  `receipt_json` json DEFAULT NULL,
  `warnings_json` json DEFAULT NULL,
  `blockers_json` json DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tiny_live_approval_token_hash` (`approval_token_hash`),
  KEY `idx_tiny_live_exec_created` (`created_at`),
  KEY `idx_tiny_live_exec_symbol_strategy` (`symbol`,`strategy_id`,`created_at`),
  KEY `idx_tiny_live_exec_status` (`status`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `gemini_market_hint` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `symbol` varchar(20) NOT NULL,
  `timeframe` varchar(10) NOT NULL,
  `regime` varchar(30) NOT NULL,
  `style_hint` varchar(20) NOT NULL,
  `adx_adjust` decimal(5,2) NOT NULL DEFAULT '0.00',
  `sl_multiplier` decimal(6,3) NOT NULL DEFAULT '1.000',
  `tp_multiplier` decimal(6,3) NOT NULL DEFAULT '1.000',
  `allow_short` tinyint(1) NOT NULL DEFAULT '0',
  `confidence` decimal(3,2) NOT NULL,
  `persona_votes` json NOT NULL,
  `reasoning` text NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_gmh_symbol_tf_expires` (`symbol`,`timeframe`,`expires_at`)
) ENGINE=InnoDB AUTO_INCREMENT=743 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Gemini Market Advisor hints';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `hint_override` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `symbol` varchar(20) NOT NULL,
  `timeframe` varchar(10) NOT NULL,
  `style_hint` varchar(20) DEFAULT NULL COMMENT 'TREND / HIGH_FREQ / CONSERVATIVE / DISABLE / null(不覆蓋)',
  `regime` varchar(30) DEFAULT NULL,
  `adx_adjust` decimal(5,2) DEFAULT NULL,
  `sl_multiplier` decimal(6,3) DEFAULT NULL,
  `tp_multiplier` decimal(6,3) DEFAULT NULL,
  `allow_short` tinyint(1) DEFAULT NULL COMMENT 'null=不覆蓋',
  `priority` smallint NOT NULL DEFAULT '100' COMMENT '>gemini 的隱含 0',
  `reason` varchar(500) NOT NULL,
  `created_by` varchar(64) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL COMMENT 'Phase 1 硬上限 6h',
  `revoked_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ho_active` (`symbol`,`timeframe`,`expires_at`,`revoked_at`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Claude 注入的 hint,per-field 覆蓋 gemini_market_hint';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `indicator_alert_state` (
  `indicator_name` varchar(64) NOT NULL,
  `state` varchar(16) NOT NULL,
  `entered_at` datetime DEFAULT NULL,
  `last_fired_at` datetime DEFAULT NULL,
  `last_score` int DEFAULT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`indicator_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='#404 hysteresis state per CompositeIndicator — survives deploys';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `market_flip_ai_analysis` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `provider` varchar(64) NOT NULL COMMENT 'gemini-flash / groq-llama-70b / claude-sonnet',
  `decision` varchar(32) NOT NULL COMMENT 'DISMISS / ALERT / TUNE / CREATE_RULE',
  `confidence` decimal(3,2) NOT NULL COMMENT '0.00 ~ 1.00',
  `reasoning` text,
  `tokens_used` int DEFAULT NULL,
  `latency_ms` int DEFAULT NULL,
  `analyzed_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_mfaa_event` (`event_id`),
  KEY `idx_mfaa_provider_analyzed` (`provider`,`analyzed_at` DESC),
  CONSTRAINT `fk_mfaa_event` FOREIGN KEY (`event_id`) REFERENCES `market_flip_event` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=456 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `market_flip_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `symbol` varchar(20) NOT NULL,
  `indicator` varchar(32) NOT NULL,
  `threshold_lo` decimal(10,4) DEFAULT NULL COMMENT '低門檻(如 F&G 25 / whale 0.25)',
  `threshold_hi` decimal(10,4) DEFAULT NULL COMMENT '高門檻(如 F&G 75 / whale 0.65)',
  `delta_threshold` decimal(10,4) DEFAULT NULL COMMENT '絕對變化門檻(如 F&G 20 / whale 0.20)',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mfc_symbol_indicator` (`symbol`,`indicator`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `market_flip_decision` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `final_decision` varchar(32) NOT NULL,
  `consensus_type` varchar(32) NOT NULL COMMENT 'UNANIMOUS / MAJORITY / SPLIT / SINGLE_AI / HUMAN_OVERRIDE / AUTO_ESCALATED',
  `decider` varchar(128) NOT NULL COMMENT 'ai-consensus / session-xxx / human-via-tg / auto-escalate',
  `summary` text,
  `action_taken_json` text COMMENT 'JSON:實際執行什麼 (TG message / threshold change / rule created)',
  `decided_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `event_id` (`event_id`),
  KEY `idx_mfd_decided` (`decided_at` DESC),
  KEY `idx_mfd_decider` (`decider`),
  CONSTRAINT `fk_mfd_event` FOREIGN KEY (`event_id`) REFERENCES `market_flip_event` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=396 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `market_flip_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `symbol` varchar(20) NOT NULL,
  `indicator` varchar(32) NOT NULL COMMENT 'fear_greed / whale_buy_ratio / funding_rate 等',
  `prev_value` decimal(10,4) NOT NULL,
  `current_value` decimal(10,4) NOT NULL,
  `threshold_crossed` varchar(32) DEFAULT NULL COMMENT 'fg_25 / whale_65 / delta_20 等',
  `delta_value` decimal(10,4) NOT NULL COMMENT 'abs(current - prev)',
  `detected_at` datetime NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / IN_REVIEW / REVIEWED / AUTO_ESCALATED / HUMAN_OVERRIDE',
  `reviewed_at` datetime DEFAULT NULL,
  `context_json` json DEFAULT NULL COMMENT '事件發生時的 snapshot(其他指標值、當前策略狀態等)',
  PRIMARY KEY (`id`),
  KEY `idx_mfe_status_detected` (`status`,`detected_at`),
  KEY `idx_mfe_symbol_detected` (`symbol`,`detected_at` DESC),
  KEY `idx_mfe_indicator_detected` (`indicator`,`detected_at` DESC)
) ENGINE=InnoDB AUTO_INCREMENT=405 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `market_indicator_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `captured_at` datetime NOT NULL COMMENT '快照時間(UTC)',
  `symbol` varchar(20) NOT NULL COMMENT 'BTCUSDT / ETHUSDT 等',
  `indicator` varchar(32) NOT NULL COMMENT 'fear_greed / whale_buy_ratio / funding_rate / long_short_ratio / orderbook_imbalance',
  `value` decimal(30,8) NOT NULL,
  `metadata_json` json DEFAULT NULL COMMENT '額外資訊:聚合 periods、API 回應 latency、原始回應片段等',
  `error_flag` tinyint(1) NOT NULL DEFAULT '0',
  `error_reason` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mih_symbol_indicator_capturedat` (`symbol`,`indicator`,`captured_at`),
  KEY `idx_mih_sym_ind_captured` (`symbol`,`indicator`,`captured_at` DESC),
  KEY `idx_mih_captured` (`captured_at` DESC),
  KEY `idx_mih_error_flag` (`error_flag`),
  KEY `idx_mih_sym_ind_err_captured_value` (`symbol`,`indicator`,`error_flag`,`captured_at` DESC,`value`)
) ENGINE=InnoDB AUTO_INCREMENT=1896766 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每小時市場指標歷史(M-F flip 判斷 + 回測用)' SECONDARY_ENGINE=RAPID;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `md_kline` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `symbol` varchar(30) NOT NULL,
  `interval_code` varchar(10) NOT NULL,
  `open_time` datetime NOT NULL,
  `close_time` datetime NOT NULL,
  `open_price` decimal(20,8) NOT NULL,
  `high_price` decimal(20,8) NOT NULL,
  `low_price` decimal(20,8) NOT NULL,
  `close_price` decimal(20,8) NOT NULL,
  `volume` decimal(28,10) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `source` varchar(10) NOT NULL DEFAULT 'binance' COMMENT 'K 線資料源：binance（Binance.us）或 okx（OKX v5）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_md_kline_symbol_interval_open_time_source` (`symbol`,`interval_code`,`open_time`,`source`),
  KEY `idx_md_kline_symbol_interval_open_time` (`symbol`,`interval_code`,`open_time`),
  KEY `idx_md_kline_source_open_time` (`source`,`open_time`),
  KEY `idx_md_kline_sym_int_src_open` (`symbol`,`interval_code`,`source`,`open_time` DESC)
) ENGINE=InnoDB AUTO_INCREMENT=319210 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci SECONDARY_ENGINE=RAPID;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `meta_control_attribution` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `override_type` varchar(32) NOT NULL COMMENT 'STRATEGY_PAUSE / HINT_OVERRIDE(future)',
  `override_id` bigint NOT NULL COMMENT 'FK strategy_override.id 或 hint_override.id(由 override_type 決定)',
  `strategy_id` bigint NOT NULL,
  `symbol` varchar(32) NOT NULL,
  `interval_code` varchar(16) NOT NULL,
  `window_start` datetime(6) NOT NULL COMMENT 'UTC - override 生效起點(strategy_override.created_at)',
  `window_end` datetime(6) NOT NULL COMMENT 'UTC - override 結束點(COALESCE(revoked_at, expires_at))',
  `actual_pnl` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT 'window 內 auto_traded=true 的 bt_live_signal realized_pnl 總和;PAUSE 期間通常 0',
  `actual_trade_count` int NOT NULL DEFAULT '0',
  `counterfactual_pnl` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT 'backtest 若無 override 會有的 net_pnl 總和(所有 trades.netPnl 加總)',
  `counterfactual_trade_count` int NOT NULL DEFAULT '0',
  `alpha_contribution` decimal(20,6) NOT NULL COMMENT 'actual_pnl - counterfactual_pnl;正=override 加分,負=扣分',
  `computation_status` varchar(32) NOT NULL COMMENT 'SUCCESS / INSUFFICIENT_DATA / SCOPE_TOO_BROAD / BACKTEST_FAILED',
  `error_message` text COMMENT 'BACKTEST_FAILED 時存 exception message 前 500 字',
  `computed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_attr_override` (`override_type`,`override_id`),
  KEY `idx_attr_strategy_time` (`strategy_id`,`computed_at`),
  KEY `idx_attr_status` (`computation_status`,`computed_at`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Meta-Control override 的 counterfactual alpha 歸因';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `polymarket_alert_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `market_id` varchar(200) NOT NULL,
  `market_title` varchar(500) NOT NULL,
  `alert_type` varchar(30) NOT NULL,
  `signal_strength` varchar(10) NOT NULL,
  `prob_before` decimal(5,4) DEFAULT NULL,
  `prob_after` decimal(5,4) DEFAULT NULL,
  `prob_delta` decimal(5,4) DEFAULT NULL,
  `volume_spike_ratio` decimal(8,2) DEFAULT NULL,
  `largest_single_bet` decimal(18,2) DEFAULT NULL,
  `btc_price_at_alert` decimal(12,2) DEFAULT NULL,
  `btc_price_4h_later` decimal(12,2) DEFAULT NULL,
  `btc_pct_change_4h` decimal(8,4) DEFAULT NULL,
  `notified_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pal_notified` (`notified_at`),
  KEY `idx_pal_market` (`market_id`,`notified_at`)
) ENGINE=InnoDB AUTO_INCREMENT=3922 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `polymarket_historical_odds` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `market_id` varchar(200) NOT NULL,
  `token_id` varchar(200) DEFAULT NULL,
  `market_title` varchar(500) NOT NULL,
  `market_category` varchar(50) DEFAULT NULL,
  `event_time` datetime NOT NULL,
  `prob` decimal(5,4) NOT NULL,
  `prob_delta_1h` decimal(5,4) DEFAULT NULL,
  `btc_price` decimal(12,2) DEFAULT NULL,
  `btc_change_1h` decimal(8,4) DEFAULT NULL,
  `btc_change_4h` decimal(8,4) DEFAULT NULL,
  `btc_change_24h` decimal(8,4) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pho_market_event` (`market_id`,`event_time`),
  KEY `idx_pho_event_time` (`event_time`),
  KEY `idx_pho_delta` (`prob_delta_1h`)
) ENGINE=InnoDB AUTO_INCREMENT=11698 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `polymarket_odds_snapshot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `market_id` varchar(200) NOT NULL,
  `market_title` varchar(500) NOT NULL,
  `relevance_tag` varchar(10) NOT NULL DEFAULT 'MEDIUM',
  `prob` decimal(5,4) NOT NULL,
  `volume_total` decimal(18,2) NOT NULL,
  `volume_delta_15m` decimal(18,2) DEFAULT NULL,
  `rolling_avg_volume_15m` decimal(18,2) DEFAULT NULL,
  `volume_spike_ratio` decimal(8,2) DEFAULT NULL,
  `prob_1h_ago` decimal(5,4) DEFAULT NULL,
  `prob_delta_1h` decimal(5,4) DEFAULT NULL,
  `largest_single_bet_usdc` decimal(18,2) DEFAULT NULL,
  `btc_price` decimal(12,2) DEFAULT NULL,
  `is_resolved` tinyint(1) NOT NULL DEFAULT '0',
  `snapshotted_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pos_market_time` (`market_id`(100),`snapshotted_at`),
  KEY `idx_pos_market_time` (`market_id`,`snapshotted_at`),
  KEY `idx_pos_snapshotted` (`snapshotted_at`),
  KEY `idx_pos_vol_delta` (`market_id`(100),`snapshotted_at`,`volume_delta_15m`)
) ENGINE=InnoDB AUTO_INCREMENT=137269 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `position_annotation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `live_signal_id` bigint NOT NULL COMMENT 'soft ref -> bt_live_signal.id',
  `tag` varchar(32) DEFAULT NULL COMMENT 'WIN_STRUCTURAL / LOSS_CHOP / FALSE_BREAKOUT / REGIME_MISMATCH ...',
  `note` text NOT NULL,
  `created_by` varchar(64) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pa_live` (`live_signal_id`),
  KEY `idx_pa_tag` (`tag`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Claude 對已平倉 bt_live_signal 的事後分析筆記';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `server_startup_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `started_at` datetime NOT NULL COMMENT 'Spring ApplicationReady 時間',
  `ws_ready_at` datetime DEFAULT NULL COMMENT 'WS 訂閱全部送出完成時間',
  `first_eval_at` datetime DEFAULT NULL COMMENT '策略首次評估（暖機）完成時間',
  `note` varchar(500) DEFAULT NULL COMMENT '備注（如訂閱失敗原因）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1352 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='伺服器啟動時序紀錄，每次啟動一筆，用於排查策略空窗期';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `signal_outcome_verification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `live_signal_id` bigint NOT NULL COMMENT 'FK → bt_live_signal.id',
  `symbol` varchar(20) NOT NULL,
  `interval_code` varchar(10) NOT NULL,
  `side` varchar(10) NOT NULL COMMENT 'LONG / SHORT',
  `decision` varchar(20) NOT NULL COMMENT 'PASS / BLOCK / ENSEMBLE_BLOCK',
  `decision_layer` varchar(64) NOT NULL COMMENT 'EnsembleGate / ShortAiFilter / PASS etc.',
  `entry_price` decimal(20,8) NOT NULL,
  `sl_price` decimal(20,8) DEFAULT NULL COMMENT 'suggested_sl at signal time',
  `tp_price` decimal(20,8) DEFAULT NULL COMMENT 'suggested_tp at signal time',
  `outcome` varchar(16) NOT NULL DEFAULT 'WATCHING' COMMENT 'WATCHING / CORRECT / WRONG / EXPIRED',
  `last_price` decimal(20,8) DEFAULT NULL COMMENT '最後一次檢查的現價',
  `last_checked_at` datetime(6) DEFAULT NULL,
  `finalized_at` datetime(6) DEFAULT NULL COMMENT 'CORRECT/WRONG 確定時間',
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_live_signal` (`live_signal_id`),
  KEY `idx_verify` (`outcome`,`last_checked_at`),
  KEY `idx_accuracy` (`decision_layer`,`finalized_at`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=140 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci SECONDARY_ENGINE=RAPID;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `strategy_override` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `strategy_id` bigint NOT NULL,
  `symbol` varchar(20) DEFAULT NULL COMMENT 'null=此策略所有 symbols',
  `interval_code` varchar(10) DEFAULT NULL COMMENT 'null=所有週期',
  `action` varchar(16) NOT NULL COMMENT 'PAUSE / TWEAK(Phase 2: QUARANTINE / RISK_DOWN)',
  `config_patch` json DEFAULT NULL COMMENT 'TWEAK 時的 config 覆蓋(deep merge 入 strategy config)',
  `reason` varchar(500) NOT NULL,
  `created_by` varchar(64) NOT NULL COMMENT 'claude / ops / manual',
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL COMMENT 'Phase 1 硬上限 24h',
  `revoked_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ov_active` (`strategy_id`,`expires_at`,`revoked_at`),
  KEY `idx_ov_symbol` (`symbol`,`expires_at`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Claude 對策略的暫停/調參覆蓋(硬性 TTL)';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_reminder` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `fire_at` datetime(6) NOT NULL COMMENT '預定發送時間(UTC)',
  `message` varchar(2000) NOT NULL COMMENT 'TG 訊息內容(支援 HTML)',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING(待發) / FIRED(已發) / CANCELLED(取消) / FAILED(發送失敗)',
  `tag` varchar(64) DEFAULT NULL COMMENT '可選分類標籤(snapshot/review/etc.)',
  `created_by` varchar(64) NOT NULL COMMENT 'claude / ops / api',
  `created_at` datetime(6) NOT NULL,
  `fired_at` datetime(6) DEFAULT NULL COMMENT '實際發送時間',
  `error` varchar(500) DEFAULT NULL COMMENT '若 status=FAILED,記錄錯誤原因',
  PRIMARY KEY (`id`),
  KEY `idx_reminder_due` (`status`,`fire_at`),
  KEY `idx_reminder_tag` (`tag`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=33 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系統預約提醒(到時發 TG 通知)';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tg_notification_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `message` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `level` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'INFO',
  `source` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `symbol` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `rule_id` bigint DEFAULT NULL,
  `use_html` tinyint(1) DEFAULT '1',
  `sent_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_sent_at` (`sent_at`),
  KEY `idx_source` (`source`),
  KEY `idx_level` (`level`),
  KEY `idx_rule_id` (`rule_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4692 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='TG 通知歷史，30天自動清除';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
