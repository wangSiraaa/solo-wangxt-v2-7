# 限时道具合成系统（配方 + 玩家账本）

一个可直接运行的限时活动道具合成后端（Spring Boot 3 + MySQL 事务）与运营/玩家操作台（Vue 3）。
所有规则由**服务端**在数据库事务内判定，前端按钮是否可点只是展示，从不作为规则依据。

- 后端：`backend/`（Spring Boot 3.3 / Java 17 / Spring JDBC，MySQL 8 或内置 H2 双模式）
- 前端：`frontend/`（Vue 3 + Vite），构建产物已打进后端 `static/`，单端口即可访问
- 端到端冒烟脚本：`scripts/smoke-e2e.sh`

---

## 1. 快速开始

### 方式 A：零依赖（内置 H2，内存库，重启即回种子）

```bash
cd backend
mvn spring-boot:run
```

打开 http://localhost:8080/ 即为操作台（Vue 已托管在后端）。API 前缀 `http://localhost:8080/api`。

### 方式 B：MySQL（生产形态）

```bash
# 1) 起 MySQL（或自备 8.x）
docker compose -f deploy/docker-compose.yml up -d

# 2) 导入表结构与演示数据（脚本内含 CREATE DATABASE craft / USE craft）
mysql -h127.0.0.1 -uroot -proot < backend/src/main/resources/db/schema-mysql.sql
mysql -h127.0.0.1 -uroot -proot < backend/src/main/resources/db/data-seed-mysql.sql
# 若用 compose 首次启动，/docker-entrypoint-initdb.d 已自动执行这两个脚本，无需手工导入

# 3) 以 mysql profile 启动
java -jar backend/target/craft-service-1.0.0.jar --spring.profiles.active=mysql
# 或：mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=mysql
```

连接信息可用环境变量覆盖：`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`（见 `application-mysql.yml`）。

### 演示账号（可直接登录）

| 角色 | 账号 | 密码 | 说明 |
|------|------|------|------|
| 运营 | `ops_admin` | `operator123` | 配方版本、账本监控、撤销、异常清单 |
| 玩家 | `player1` | `player123` | 背包**恰好够 1 份**烈焰之剑材料（并发测试锚点）|
| 玩家 | `player2` | `player123` | 雷霆弓材料 |
| 玩家 | `player3` | `player123` | 多份材料，用于版本/撤销演示 |

> 注：`app_user.id` 中 ops=1、player1=2、player2=3、player3=4。运营接口里的 `playerId` 用数字 id。

### 跑测试

```bash
# 默认：H2 上 32 个用例（含“批量计划 32 同键并发”“两 worker 抢同一序号”
# “已扣未发/已发未回写 两崩溃窗口租约恢复”“取消与领取竞争”“执行中发新版本”
# “最后一份材料部分完成”“对账补偿重试不产生第二笔”等）
cd backend && mvn test

# 真实 InnoDB（需要本地 Docker，Testcontainers）：
mvn test -Pmysql-it
```

### 端到端冒烟（52 项断言，建议对“全新启动的 H2”跑）

```bash
bash scripts/smoke-e2e.sh
```

覆盖：登录鉴权 → 预览 → 预占/同键重试 → 完成/重复提交 → 取消退回 → **10s 超时自动释放** →
**新版本发布后在途单按旧版本完成** → 下架后强制提交被服务端拒绝 →
**撤销全额反向流水** / **奖励已耗用→异常清单** → **8 并发抢最后一份材料仅 1 单成功** →
**批量计划：固定版本快照执行 / 同键只产生一个计划 / 最后一份材料 PARTIAL /
逐序号 planNo·unitNo 可追溯 / 对账一致 / 补偿 NOOP 与重试幂等 / 取消抢占只跳未开始序号**。

---

## 2. 核心一致性设计（难点对应）

合成分两步：**预占（preoccupy）→ 完成（commit）**，可取消；预占带超时。

| 难点 | 机制 |
|------|------|
| 活动结束 / 背包扣减 / 请求重试同时发生 | 预占在**一个事务**内：`SELECT … FOR UPDATE` 锁配方头与当前发布版本 → 校验活动窗口 → 对材料行 `UPDATE … WHERE qty >= ?` 原子扣减 → 写 `CONSUME` 流水与占用行。任一步失败整体回滚。隔离级别 `READ COMMITTED`，材料行按 item_code 排序加锁避免死锁。 |
| 不多扣材料 | 扣减是条件更新 `qty = qty - ? WHERE qty >= ?`，并发只有一个事务影响行数=1；DB 层 `CHECK (qty >= 0)` 兜底。 |
| 不重复发奖 | 完成时对合成单加行锁并做 CAS：`PREOCCUPIED→COMMITTED` 只有一个赢家；`PRODUCE` 流水对 `(ref_no, player, item, type)` 有唯一键。 |
| 请求重试 | 客户端必带 `Idempotency-Key`。首次 `INSERT` 占位（唯一键），成功后固化响应；**同键重试原样回放**；业务失败释放键允许修正后重试；并发同键返回 `409 RETRY_IN_FLIGHT`。 |
| 已开始的合成按预占版本完成 | 下单时把 `recipe_version_id` 快照写入 `craft_order`，完成时读该版本快照产出；之后发新版本不影响在途单。 |
| 配方发布后不可改 | 只有 `DRAFT` 可编辑；修改=新建版本草稿→发布；发布在同一事务内把旧 `PUBLISHED` 置 `ARCHIVED`。 |
| 超时取消释放占用 | 定时清扫器（默认 2s）对到期单用独立事务 CAS `PREOCCUPIED→TIMEOUT`；占用行 `released` 标记做 compare-and-set，**提交/取消/超时三路竞争至多一路释放**，并写 `RELEASE` 回补流水。 |
| 规则不靠按钮 | 活动窗口、版本状态、材料余额全部在服务端事务内重算；前端只是回显服务端结论（如 `ACTIVITY_NOT_OPEN`、`MATERIAL_INSUFFICIENT`、`RECIPE_CLOSED`）。 |
| 运营撤销错误奖励 | 仅对 `COMMITTED` 单：产出全在库→生成与原产出**相反的负向 `REVOKE` 流水**并扣回，`related_ref` 指向原单号；任一产出不足→**不做部分扣减**，整笔落 `revoke_record(result=EXCEPTION)` 并写 `REVOKE_PENDING(PENDING)` 挂账，进异常清单。重复撤销被 `COMMITTED→REVOKED` CAS 拒绝。 |

### 账本即流水

`player_inventory.qty` 只在事务内被行锁 UPDATE 修改；每次变动在 `ledger_entry` 追加一条带方向的流水：

- `CONSUME`（预占扣减，负）、`PRODUCE`（完成产出，正）、`RELEASE`（取消/超时回补，正）
- `GRANT`（运营设置）、`REVOKE`（撤销冲销，负）、`REVOKE_PENDING`（撤销挂账，负，status=PENDING）

前端“逐笔材料去向”即按单号聚合这些流水（含时间、类型、关联单号、说明）。

---

## 2b. 批量合成计划（1–100 次，后台 worker 逐序号执行）

玩家一次提交 1–100 次合成；**服务端**创建一个计划与全部子序号，固定配方版本与每次
材料/产出快照，由后台 worker 从 MySQL 领取逐次执行。前端**不能**循环调用单次合成冒充批量。

- 表：`batch_plan`（计划头：版本快照、状态、计数、停止闸 `stop_new_units`、取消标记）、
  `plan_unit`（逐序号：`PENDING→RUNNING→DEDUCTED→DONE/SKIPPED/FAILED`，租约 `lease_owner/lease_expires_at`）、
  `repair_record`（修复幂等台账）。每个子序号仍写**原有 `craft_order`**（带 `plan_no/unit_no`）
  与**原有 `ledger_entry`**（同样追加 `plan_no/unit_no`），完全可追溯。
- 幂等创建：同一 `Idempotency-Key` 的并发创建都竞争 `idempotency_record` 的唯一 INSERT，
  只有一个产生计划；其余在途返回 `409 RETRY_IN_FLIGHT`，完成后回放同一份响应。

### 三个持久化检查点（每个独立事务），崩溃后凭持久化状态恢复

| 阶段 | 事务内动作 | 崩溃窗口恢复 |
|------|-----------|-------------|
| Tx1 扣料 | CAS `PENDING→RUNNING`(写租约+建 craft_order) → 行锁条件扣料 `qty>=need`、写 CONSUME/占用行 → CAS `RUNNING→DEDUCTED` | 无 CONSUME 行：租约过期后整序号重来，背包无任何变化 |
| Tx2 发奖 | 按计划快照发产出、写 PRODUCE（`(ref,player,item,type)` 唯一键）、合成单 `PREOCCUPIED→COMMITTED` | “**已扣料未发奖**”：补发；PRODUCE 已存在则跳过，**绝不发第二次** |
| Tx3 回写 | CAS `DEDUCTED→DONE`、清租约，计划行锁下重算计数与终态 | “**已发奖未回写状态**”：只补写 DONE，奖励不重发 |

worker 可多实例并行：领取是**行锁 + 租约 CAS**，状态迁移全是条件 UPDATE（CAS），
材料/产出唯一约束兜底；同一序号最多扣料、发奖各一次。租约（默认 30s）过期后任意 worker
可接管，正确性不依赖某个进程，也不依赖 JVM 锁/按钮禁用。

### 停止闸与取消（玩家取消与 worker 抢占同时发生）

活动结束、材料不足或玩家取消都只置 `stop_new_units=1`：**不再启动新序号**，
已预占（已领租约）的序号仍按**绑定版本**跑完。取消只把尚未开始的 `PENDING` 序号置 `SKIPPED`；
已扣料/已完成的奖励**不回滚**。计划终态明确为：
`COMPLETED`（全部完成）、`PARTIAL`（部分完成，含取消时已有完成）、`CANCELLED`（完成 0）、
`FAILED`（存在不可恢复失败），并记录已完成/失败/未执行数量。

### 对账与运营修复（只追加补偿流水，不改写历史）

- `GET /api/operator/reconcile/{planNo}`：只读比对**计划/序号状态、合成单状态、背包净变动、
  流水**（如 `MISSING_PRODUCE`、`LEDGER_NET_MISMATCH`、`PLAN_STATUS_MISMATCH`）；
  `GET /api/operator/reconcile` 给出全部异常计划队列。
- `POST /api/operator/repairs`：修复**只能新增补偿流水** `COMPENSATE` 补齐缺失一侧
  （补产出：补背包+PRODUCE；补扣料：`qty>=need` 条件扣减+CONSUME），状态类问题只做 CAS 修正；
  **从不 UPDATE/DELETE 历史流水**。修复请求的 `Idempotency-Key` 是唯一键（先“占键”后“补偿”
  两阶段事务），同一请求并发或重试都只产生一笔补偿，安全重试。

保留既有单次合成、版本发布、超时释放、奖励撤销；批量子任务的占用行不参与单次超时清扫。

---


## 3. HTTP 接口（节选）

鉴权：登录返回 token，后续请求带头 `X-Auth-Token: <token>`；写请求带头 `Idempotency-Key: <uuid>`。

玩家（`/api/player/**`，仅 PLAYER）
- `GET  /recipes`、`GET /recipes/{id}/preview`
- `POST /crafts/preoccupy` `{recipeId}`
- `POST /crafts/commit` `{orderNo}`
- `POST /crafts/cancel` `{orderNo,reason}`
- `GET  /crafts`、`GET /crafts/{orderNo}`（含 holds + 全量流水）
- `GET  /inventory`、`GET /ledger`

- `POST /plans` `{recipeId,totalUnits:1..100}`（带 `Idempotency-Key`；同键只产生一个计划）
- `POST /plans/cancel` `{planNo,reason}`（只取消尚未开始的序号）
- `GET  /plans`、`GET  /plans/{planNo}`（实时进度 + 逐序号时间线 + planNo/unitNo 流水）

运营（`/api/operator/**`，仅 OPERATOR）
- `GET/POST /recipes`、`POST /recipes/new-version`、`POST /recipes/draft`、`POST /recipes/publish`、`POST /recipes/close`
- `GET  /crafts`、`GET  /ledger?refNo=`
- `POST /revokes` `{orderNo}`、`GET /revokes`、`GET /exceptions`
- `POST /inventory/grant`（测试/补库存，附 GRANT 审计流水）

- `GET  /plans`、`GET  /plans/{planNo}`（批量计划监控与逐序号/租约视图）
- `GET  /reconcile`（异常计划队列）、`GET  /reconcile/{planNo}`（只读对账差异）
- `POST /repairs` `{idempotencyKey,planNo,unitNo,issueType,itemCode}`（追加补偿流水，可安全重试）
- `GET  /repairs?planNo=`（补偿结果台账）

公共：`POST /api/auth/login` `{username,password}`

错误形如 `{"error":"MATERIAL_INSUFFICIENT","message":"材料不足：MAT_IRON 需要 3"}`，常见码：
`RECIPE_CLOSED` / `ACTIVITY_NOT_OPEN` / `RECIPE_NOT_PUBLISHED` / `MATERIAL_INSUFFICIENT` /
`PREOCCUPY_EXPIRED` / `ORDER_COMMIT_RACE` / `RETRY_IN_FLIGHT` / `ORDER_NOT_REVOKABLE`。

---

## 4. 前端开发

```bash
cd frontend
npm install
npm run dev        # 5173，/api 已代理到 8080
npm run build      # 产物 dist/
```

发布到后端单端口：把 `dist/*` 拷到 `backend/src/main/resources/static/`（已内置一份）。

操作台包含：
- 玩家：配方版本与**合成预览**、预占倒计时、背包、按合成单逐笔材料去向、最近流水，以及**批量合成计划**面板（提交 1–100 次、取消未开始序号、实时进度条与**逐序号时间线**，3s 自动刷新）。
- 运营：配方多版本管理、账本监控、撤销与异常清单、库存工具，以及**批量计划/对账**页（异常计划队列、只读对账差异、一键追加补偿修复、补偿结果台账、逐序号租约时间线）。

---

## 5. 目录

```
backend/
  src/main/resources/db/schema-mysql.sql     # InnoDB 表结构
  src/main/resources/db/data-seed-mysql.sql  # MySQL 演示数据
  src/main/resources/schema-h2.sql           # H2 等价表（本地/单测）
  src/main/resources/data-seed.sql           # H2 演示数据（同一份账号与配方）
  src/main/java/com/gameops/craft/
    common/      时钟、错误码、单号生成、JSON
    domain/      值对象
    repo/        JdbcTemplate 仓储（FOR UPDATE / 条件更新 / CAS）
    service/     CraftTxService(事务核心) CraftService(幂等外观) RecipeAdminService TimeoutSweeper …
    web/         控制器、鉴权拦截器、全局异常
  src/test/      H2 并发/版本/超时/撤销/HTTP 全流程 + MySQL Testcontainers IT
frontend/        Vue 3 操作台源码
deploy/          docker-compose（MySQL）
scripts/smoke-e2e.sh
```
