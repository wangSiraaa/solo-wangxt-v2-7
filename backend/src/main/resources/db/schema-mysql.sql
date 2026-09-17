-- ============================================================================
-- craft-service MySQL schema (InnoDB, READ COMMITTED by app requirement)
-- All money/material movements are append-only rows in ledger_entry.
-- ============================================================================
CREATE DATABASE IF NOT EXISTS craft
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE craft;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS app_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL COMMENT 'PLAYER / OPERATOR',
    display_name  VARCHAR(64)  NOT NULL,
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS login_token (
    token      CHAR(48)    NOT NULL,
    user_id    BIGINT      NOT NULL,
    role       VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (token),
    KEY ix_token_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Recipe header; only lifecycle flags live here (versions hold content).
CREATE TABLE IF NOT EXISTS recipe (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    status      VARCHAR(16) NOT NULL COMMENT 'ACTIVE / CLOSED',
    created_by  BIGINT      NOT NULL,
    created_at  DATETIME(3) NOT NULL,
    updated_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recipe_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS recipe_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    recipe_id       BIGINT       NOT NULL,
    version_no      INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL COMMENT 'DRAFT / PUBLISHED / ARCHIVED',
    -- Snapshot of rules at publish time; JSON for readability/audit
    inputs_json     TEXT         NULL,
    outputs_json    TEXT         NULL,
    start_time      DATETIME(3)  NULL COMMENT 'activity window start (inclusive, UTC)',
    end_time        DATETIME(3)  NULL COMMENT 'activity window end (inclusive, UTC)',
    craft_timeout_s INT          NOT NULL COMMENT 'seconds to finish before holds auto-release',
    published_at    DATETIME(3)  NULL,
    published_by    BIGINT       NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recipe_version (recipe_id, version_no),
    KEY ix_rv_published_lookup (recipe_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Per (player, item) row. Balance is mutated ONLY by row-locked UPDATE inside tx.
CREATE TABLE IF NOT EXISTS player_inventory (
    player_id  BIGINT      NOT NULL,
    item_code  VARCHAR(64) NOT NULL,
    qty        BIGINT      NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (player_id, item_code),
    CONSTRAINT ck_inv_qty_nonneg CHECK (qty >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS craft_order (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    order_no            CHAR(20)     NOT NULL,
    player_id           BIGINT       NOT NULL,
    recipe_id           BIGINT       NOT NULL,
    recipe_version_id   BIGINT       NOT NULL COMMENT 'snapshot: order always finishes on this version',
    status              VARCHAR(16)  NOT NULL COMMENT 'PREOCCUPIED / COMMITTED / CANCELLED / TIMEOUT / REVOKED',
    status_reason       VARCHAR(255) NULL,
    preoccupy_deadline  DATETIME(3)  NOT NULL COMMENT 'holds released by sweeper after this time',
    committed_at        DATETIME(3)  NULL,
    closed_at           DATETIME(3)  NULL,
    revoke_ref_no       CHAR(20)     NULL COMMENT 'set when order is revoked',
    created_at          DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY ix_order_player (player_id, created_at),
    KEY ix_order_status_deadline (status, preoccupy_deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Material held by a PREOCCUPIED order; released back on commit/cancel/timeout.
CREATE TABLE IF NOT EXISTS material_hold (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    order_id     BIGINT      NOT NULL,
    player_id    BIGINT      NOT NULL,
    item_code    VARCHAR(64) NOT NULL,
    qty          BIGINT      NOT NULL,
    released     TINYINT(1)  NOT NULL DEFAULT 0,
    created_at   DATETIME(3) NOT NULL,
    released_at  DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hold_order_item (order_id, item_code),
    KEY ix_hold_player (player_id, released)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Append-only ledger. The unique ref is the idempotency/anti-double-issue anchor.
-- BALANCE delta = CREDIT - DEBIT; every balance movement has exactly one row.
CREATE TABLE IF NOT EXISTS ledger_entry (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    ref_no        CHAR(20)     NOT NULL COMMENT 'business document no (order/revoke/grant)',
    player_id     BIGINT       NOT NULL,
    item_code     VARCHAR(64)  NOT NULL,
    entry_type    VARCHAR(24)  NOT NULL
                  COMMENT 'CONSUME / PRODUCE / RELEASE / GRANT / REVOKE / REVOKE_PENDING',
    qty_delta     BIGINT       NOT NULL COMMENT 'signed; +credit, -debit',
    related_ref   CHAR(20)     NULL COMMENT 'original order_no for a REVOKE reversal row',
    status        VARCHAR(16)  NOT NULL DEFAULT 'POSTED' COMMENT 'POSTED / PENDING',
    remark        VARCHAR(255) NULL,
    created_at    DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_ref_player_item_type (ref_no, player_id, item_code, entry_type),
    KEY ix_ledger_player (player_id, created_at),
    KEY ix_ledger_related (related_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Idempotency for request retries: one key -> one outcome (replayed on repeat).
CREATE TABLE IF NOT EXISTS idempotency_record (
    idempotency_key VARCHAR(80)  NOT NULL,
    player_id       BIGINT       NOT NULL,
    scope           VARCHAR(32)  NOT NULL COMMENT 'PREOCCUPY / COMMIT',
    ref_no          CHAR(20)     NOT NULL COMMENT 'order_no the first call produced/used',
    response_json   TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL COMMENT 'IN_FLIGHT / DONE',
    created_at      DATETIME(3) NOT NULL,
    updated_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (idempotency_key),
    KEY ix_idem_player (player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Operator revocation results, incl. materials already used -> exception list.
CREATE TABLE IF NOT EXISTS revoke_record (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    revoke_no        CHAR(20)     NOT NULL,
    order_id         BIGINT       NOT NULL,
    order_no         CHAR(20)     NOT NULL,
    player_id        BIGINT       NOT NULL,
    result           VARCHAR(16)  NOT NULL COMMENT 'REVERSED / EXCEPTION',
    shortage_json    TEXT         NULL COMMENT 'items that could not be fully clawed back',
    operator_id      BIGINT       NOT NULL,
    created_at       DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_revoke_order (order_id),
    UNIQUE KEY uk_revoke_no (revoke_no),
    KEY ix_revoke_result (result)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
