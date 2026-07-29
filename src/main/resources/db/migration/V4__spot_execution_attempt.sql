-- Durable mechanical execution-attempt state for provider-first recovery.
-- This table does not enable a strategy, scheduler, order, fund movement, or
-- reconciliation job by itself.

CREATE TABLE IF NOT EXISTS `bt_spot_execution_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `live_signal_id` bigint NOT NULL,
  `strategy_contract` varchar(96) NOT NULL,
  `side` varchar(8) NOT NULL,
  `attempt_sequence` int NOT NULL,
  `signal_bar_open_time` datetime(6) NOT NULL,
  `trigger_bar_open_time` datetime(6) NOT NULL,
  `client_order_id` varchar(32) NOT NULL,
  `provider` varchar(16) NOT NULL,
  `provider_order_id` varchar(128) DEFAULT NULL,
  `state` varchar(32) NOT NULL,
  `requested_quote_amount` decimal(30,12) DEFAULT NULL,
  `requested_base_quantity` decimal(30,12) DEFAULT NULL,
  `average_price` decimal(30,12) DEFAULT NULL,
  `gross_fill_quantity` decimal(30,12) DEFAULT NULL,
  `net_fill_quantity` decimal(30,12) DEFAULT NULL,
  `gross_quote_amount` decimal(30,12) DEFAULT NULL,
  `signed_fee_amount` decimal(30,12) DEFAULT NULL,
  `fee_currency` varchar(20) DEFAULT NULL,
  `fee_usdt` decimal(30,12) DEFAULT NULL,
  `fee_reconciliation_status` varchar(24) NOT NULL,
  `applied_fill_quantity` decimal(30,12) NOT NULL DEFAULT 0,
  `applied_gross_quote_amount` decimal(30,12) NOT NULL DEFAULT 0,
  `applied_fee_usdt` decimal(30,12) NOT NULL DEFAULT 0,
  `remaining_lot_quantity` decimal(30,12) DEFAULT NULL,
  `submitted_at` datetime(6) DEFAULT NULL,
  `provider_accepted_at` datetime(6) DEFAULT NULL,
  `reconciled_at` datetime(6) DEFAULT NULL,
  `last_reconciliation_error` varchar(500) DEFAULT NULL,
  `provider_receipt_json` json DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT 0,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
      ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spot_exec_attempt_sequence`
      (`live_signal_id`,`side`,`attempt_sequence`),
  UNIQUE KEY `uk_spot_exec_attempt_client_order` (`client_order_id`),
  UNIQUE KEY `uk_spot_exec_attempt_provider_order`
      (`provider`,`provider_order_id`),
  KEY `idx_spot_exec_attempt_state_updated` (`state`,`updated_at`),
  KEY `idx_spot_exec_attempt_live_signal` (`live_signal_id`,`created_at`),
  CONSTRAINT `chk_spot_exec_attempt_side`
      CHECK (`side` IN ('BUY','SELL')),
  CONSTRAINT `chk_spot_exec_attempt_sequence`
      CHECK (`attempt_sequence` > 0),
  CONSTRAINT `chk_spot_exec_attempt_state`
      CHECK (`state` IN (
        'RESERVED',
        'SUBMITTING',
        'SUBMISSION_UNKNOWN',
        'PROVIDER_ACCEPTED',
        'RECONCILED_FILLED',
        'RECONCILED_PARTIAL',
        'REJECTED'
      )),
  CONSTRAINT `chk_spot_exec_attempt_fee_status`
      CHECK (`fee_reconciliation_status` IN (
        'NOT_APPLICABLE',
        'PENDING',
        'RECONCILED'
      )),
  CONSTRAINT `chk_spot_exec_attempt_request`
      CHECK (
        (`requested_quote_amount` IS NOT NULL
          AND `requested_quote_amount` > 0)
        OR
        (`requested_base_quantity` IS NOT NULL
          AND `requested_base_quantity` > 0)
      ),
  CONSTRAINT `chk_spot_exec_attempt_fill_values`
      CHECK (
        (`average_price` IS NULL OR `average_price` > 0)
        AND (`gross_fill_quantity` IS NULL
          OR `gross_fill_quantity` >= 0)
        AND (`net_fill_quantity` IS NULL
          OR `net_fill_quantity` >= 0)
        AND (`gross_quote_amount` IS NULL
          OR `gross_quote_amount` >= 0)
        AND `applied_fill_quantity` >= 0
        AND `applied_gross_quote_amount` >= 0
        AND `applied_fee_usdt` >= 0
        AND (`remaining_lot_quantity` IS NULL
          OR `remaining_lot_quantity` >= 0)
        AND (`gross_fill_quantity` IS NULL
          OR `applied_fill_quantity` <= `gross_fill_quantity`)
        AND (`gross_quote_amount` IS NULL
          OR `applied_gross_quote_amount` <= `gross_quote_amount`)
      )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Durable provider-first spot order attempt and fill application state';
