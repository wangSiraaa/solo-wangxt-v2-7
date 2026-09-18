-- H2 (MODE=MySQL) equivalent of schema-mysql.sql, used for local run & unit tests.
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS login_token (
    token      CHAR(48)    NOT NULL,
    user_id    BIGINT      NOT NULL,
    role       VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (token)
);
CREATE INDEX IF NOT EXISTS ix_token_user ON login_token(user_id);

CREATE TABLE IF NOT EXISTS recipe (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    created_by  BIGINT      NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL,
    updated_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_recipe_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS recipe_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    recipe_id       BIGINT       NOT NULL,
    version_no      INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    inputs_json     CLOB         NULL,
    outputs_json    CLOB         NULL,
    start_time      TIMESTAMP(3) NULL,
    end_time        TIMESTAMP(3) NULL,
    craft_timeout_s INT          NOT NULL,
    published_at    TIMESTAMP(3) NULL,
    published_by    BIGINT       NULL,
    created_at      TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_recipe_version UNIQUE (recipe_id, version_no)
);
CREATE INDEX IF NOT EXISTS ix_rv_published_lookup ON recipe_version(recipe_id, status);

CREATE TABLE IF NOT EXISTS player_inventory (
    player_id  BIGINT      NOT NULL,
    item_code  VARCHAR(64) NOT NULL,
    qty        BIGINT      NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (player_id, item_code),
    CONSTRAINT ck_inv_qty_nonneg CHECK (qty >= 0)
);

CREATE TABLE IF NOT EXISTS craft_order (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    order_no            CHAR(20)     NOT NULL,
    player_id           BIGINT       NOT NULL,
    recipe_id           BIGINT       NOT NULL,
    recipe_version_id   BIGINT       NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    status_reason       VARCHAR(255) NULL,
    preoccupy_deadline  TIMESTAMP(3) NOT NULL,
    committed_at        TIMESTAMP(3) NULL,
    closed_at           TIMESTAMP(3) NULL,
    revoke_ref_no       CHAR(20)     NULL,
    plan_no             CHAR(20)     NULL,
    unit_no             INT          NULL,
    created_at          TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_no UNIQUE (order_no)
);
CREATE INDEX IF NOT EXISTS ix_order_player ON craft_order(player_id, created_at);
CREATE INDEX IF NOT EXISTS ix_order_status_deadline ON craft_order(status, preoccupy_deadline);
CREATE INDEX IF NOT EXISTS ix_order_plan ON craft_order(plan_no, unit_no);

CREATE TABLE IF NOT EXISTS material_hold (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    order_id     BIGINT      NOT NULL,
    player_id    BIGINT      NOT NULL,
    item_code    VARCHAR(64) NOT NULL,
    qty          BIGINT      NOT NULL,
    released     TINYINT     NOT NULL DEFAULT 0,
    created_at   TIMESTAMP(3) NOT NULL,
    released_at  TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hold_order_item UNIQUE (order_id, item_code)
);
CREATE INDEX IF NOT EXISTS ix_hold_player ON material_hold(player_id, released);

CREATE TABLE IF NOT EXISTS ledger_entry (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    ref_no        CHAR(20)     NOT NULL,
    player_id     BIGINT       NOT NULL,
    item_code     VARCHAR(64)  NOT NULL,
    entry_type    VARCHAR(24)  NOT NULL,
    qty_delta     BIGINT      NOT NULL,
    related_ref   CHAR(20)     NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'POSTED',
    remark        VARCHAR(255) NULL,
    plan_no       CHAR(20)     NULL,
    unit_no       INT          NULL,
    created_at    TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ledger_ref_player_item_type UNIQUE (ref_no, player_id, item_code, entry_type)
);
CREATE INDEX IF NOT EXISTS ix_ledger_player ON ledger_entry(player_id, created_at);
CREATE INDEX IF NOT EXISTS ix_ledger_related ON ledger_entry(related_ref);
CREATE INDEX IF NOT EXISTS ix_ledger_plan ON ledger_entry(plan_no, unit_no);

-- ============================================================================
-- Batch synthesis plans: one plan = N plan_units executed by background workers.
-- Version + per-craft materials/outputs are snapshotted at creation.
-- ============================================================================
CREATE TABLE IF NOT EXISTS batch_plan (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    plan_no           CHAR(20)     NOT NULL,
    player_id         BIGINT       NOT NULL,
    recipe_id         BIGINT       NOT NULL,
    recipe_version_id BIGINT       NOT NULL,
    total_units       INT          NOT NULL,
    inputs_json       CLOB         NOT NULL COMMENT 'per-unit inputs snapshot',
    outputs_json      CLOB         NOT NULL COMMENT 'per-unit outputs snapshot',
    status            VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/COMPLETED/PARTIAL/CANCELLED/FAILED',
    status_reason     VARCHAR(255) NULL,
    stop_new_units    TINYINT      NOT NULL DEFAULT 0 COMMENT 'workers stop claiming new unit numbers once 1',
    cancel_requested  TINYINT      NOT NULL DEFAULT 0,
    completed_count   INT          NOT NULL DEFAULT 0,
    failed_count      INT          NOT NULL DEFAULT 0,
    skipped_count     INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMP(3) NOT NULL,
    finished_at       TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_batch_plan_no UNIQUE (plan_no)
);
CREATE INDEX IF NOT EXISTS ix_plan_player ON batch_plan(player_id, created_at);
CREATE INDEX IF NOT EXISTS ix_plan_status ON batch_plan(status);

CREATE TABLE IF NOT EXISTS plan_unit (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    plan_id           BIGINT       NOT NULL,
    plan_no           CHAR(20)     NOT NULL,
    unit_no           INT          NOT NULL COMMENT '1-based sequence within the plan',
    player_id         BIGINT       NOT NULL,
    recipe_id         BIGINT       NOT NULL,
    recipe_version_id BIGINT       NOT NULL,
    order_no          CHAR(20)     NULL COMMENT 'craft_order created for this unit',
    status            VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/DEDUCTED/DONE/FAILED/SKIPPED',
    status_reason     VARCHAR(255) NULL,
    lease_owner       VARCHAR(64)  NULL,
    lease_expires_at  TIMESTAMP(3) NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    started_at        TIMESTAMP(3) NULL,
    deducted_at       TIMESTAMP(3) NULL,
    completed_at      TIMESTAMP(3) NULL,
    created_at        TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_plan_unit_no UNIQUE (plan_id, unit_no),
    CONSTRAINT uk_plan_unit_order UNIQUE (order_no)
);
CREATE INDEX IF NOT EXISTS ix_unit_claim ON plan_unit(status, lease_expires_at);
CREATE INDEX IF NOT EXISTS ix_unit_plan ON plan_unit(plan_id, unit_no);

-- Operator reconciliation repairs. Idempotent: one repair key -> at most one compensation.
CREATE TABLE IF NOT EXISTS repair_record (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    repair_no          CHAR(20)     NOT NULL,
    idempotency_key    VARCHAR(80)  NOT NULL,
    plan_no            CHAR(20)     NULL,
    unit_no            INT          NULL,
    order_no           CHAR(20)     NULL,
    issue_type         VARCHAR(32)  NOT NULL COMMENT 'MISSING_PRODUCE/MISSING_CONSUME/STATUS_MISMATCH/...',
    result             VARCHAR(16)  NOT NULL COMMENT 'APPLIED/NOOP',
    detail_json        CLOB         NULL,
    operator_id        BIGINT       NOT NULL,
    created_at         TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_repair_no UNIQUE (repair_no),
    CONSTRAINT uk_repair_idem UNIQUE (idempotency_key)
);
CREATE INDEX IF NOT EXISTS ix_repair_plan ON repair_record(plan_no);

CREATE TABLE IF NOT EXISTS idempotency_record (
    idempotency_key VARCHAR(80)  NOT NULL,
    player_id       BIGINT       NOT NULL,
    scope           VARCHAR(32)  NOT NULL,
    ref_no          CHAR(20)     NOT NULL,
    response_json   CLOB         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMP(3) NOT NULL,
    updated_at      TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (idempotency_key)
);
CREATE INDEX IF NOT EXISTS ix_idem_player ON idempotency_record(player_id);

CREATE TABLE IF NOT EXISTS revoke_record (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    revoke_no        CHAR(20)     NOT NULL,
    order_id         BIGINT       NOT NULL,
    order_no         CHAR(20)     NOT NULL,
    player_id        BIGINT       NOT NULL,
    result           VARCHAR(16)  NOT NULL,
    shortage_json    CLOB         NULL,
    operator_id      BIGINT       NOT NULL,
    created_at       TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_revoke_order UNIQUE (order_id),
    CONSTRAINT uk_revoke_no UNIQUE (revoke_no)
);
CREATE INDEX IF NOT EXISTS ix_revoke_result ON revoke_record(result);
