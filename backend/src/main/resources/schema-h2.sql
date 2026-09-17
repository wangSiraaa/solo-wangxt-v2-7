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
    created_at          TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_no UNIQUE (order_no)
);
CREATE INDEX IF NOT EXISTS ix_order_player ON craft_order(player_id, created_at);
CREATE INDEX IF NOT EXISTS ix_order_status_deadline ON craft_order(status, preoccupy_deadline);

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
    created_at    TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ledger_ref_player_item_type UNIQUE (ref_no, player_id, item_code, entry_type)
);
CREATE INDEX IF NOT EXISTS ix_ledger_player ON ledger_entry(player_id, created_at);
CREATE INDEX IF NOT EXISTS ix_ledger_related ON ledger_entry(related_ref);

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
