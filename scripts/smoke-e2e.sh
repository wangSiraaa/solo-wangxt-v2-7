#!/usr/bin/env bash
# End-to-end smoke test against http://localhost:8080 (default H2 profile).
set -uo pipefail
BASE=http://localhost:8080
PASS=0; FAIL=0
ok()   { echo "  ✅ $*"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ $*"; FAIL=$((FAIL+1)); }
check(){ if [ "$1" = "$2" ]; then ok "$3"; else bad "$3 (got '$1' want '$2')"; fi; }

jpost(){ # token path json [idemkey]
  local hdr=(-H "X-Auth-Token: $1" -H 'Content-Type: application/json')
  [ -n "${4:-}" ] && hdr+=(-H "Idempotency-Key: $4")
  curl -s -X POST "$BASE$2" "${hdr[@]}" -d "$3"
}
jget(){ curl -s "$BASE$2" -H "X-Auth-Token: $1"; }
# item qty from a /api/*/inventory response: invq "<json>" ITEM
invq(){ echo "$1" | jq --arg i "$2" '[.items[]|select(.itemCode==$i)|.qty][0] // 0'; }
login(){ curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
        -d "{\"username\":\"$1\",\"password\":\"$2\"}" | jq -r .token; }
uuid(){ cat /proc/sys/kernel/random/uuid; }

OPS=$(login ops_admin operator123)
P1=$(login player1 player123)
P2=$(login player2 player123)
P3=$(login player3 player123)   # app_user id = 4
P2_ID=3
P3_ID=4

echo "== 1. 登录样例账号 =="
{ [ -n "$OPS" ] && [ "$OPS" != null ] && [ -n "$P1" ] && [ -n "$P3" ] && ok "运营/玩家均可登录"; } || bad "登录失败"
BAD=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/auth/login \
      -H 'Content-Type: application/json' -d '{"username":"player1","password":"wrong"}')
check "$BAD" 401 "错误密码返回 401"
FORBID=$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/operator/recipes -H "X-Auth-Token: $P1")
check "$FORBID" 403 "玩家不能访问运营接口"

echo "== 2. 运营查看配方版本 =="
RECIPES=$(jget "$OPS" /api/operator/recipes)
echo "$RECIPES" | jq -e '.[] | select(.code=="FIRE_SWORD") | .versions[] | select(.versionNo==1 and .status=="PUBLISHED")' >/dev/null \
  && ok "烈焰之剑 v1 已发布" || bad "种子配方版本异常"

echo "== 3. 玩家合成预览（player1 恰好一份材料）=="
PRE=$(jget "$P1" /api/player/recipes/1/preview)
check "$(echo "$PRE" | jq -r .craftable)" "true" "服务端预览判定可合成"
check "$(echo "$PRE" | jq -r .versionNo)" "1" "预览展示当前版本 v1"

echo "== 4. 预占 + 同键重试（不重复扣材料）=="
KEY=$(uuid)
PREOCC=$(jpost "$P1" /api/player/crafts/preoccupy '{"recipeId":1}' "$KEY")
ORDER=$(echo "$PREOCC" | jq -r .orderNo)
check "$(echo "$PREOCC" | jq -r .boundVersionNo)" "1" "预占单绑定 v1"
REPLAY=$(jpost "$P1" /api/player/crafts/preoccupy '{"recipeId":1}' "$KEY")
check "$(echo "$REPLAY" | jq -r .orderNo)" "$ORDER" "同键重试回放同一单"
check "$(echo "$REPLAY" | jq -r .replayed)" "true" "回放标记 replayed=true"
INV=$(jget "$P1" /api/player/inventory)
check "$(echo "$INV" | jq '[.items[].qty] | add // 0')" "0" "材料只扣一次，余额全 0"

echo "== 5. 完成合成，逐笔流水可查；重复提交不发双份 =="
COMMIT=$(jpost "$P1" /api/player/crafts/commit "{\"orderNo\":\"$ORDER\"}" "$(uuid)")
check "$(echo "$COMMIT" | jq -r .status)" "COMMITTED" "合成完成"
DETAIL=$(jget "$P1" /api/player/crafts/$ORDER)
echo "$DETAIL" | jq -e '[.ledger[].entryType] | (index("CONSUME") != null) and (index("PRODUCE") != null)' >/dev/null \
  && ok "逐笔去向含 CONSUME/PRODUCE" || bad "流水不全: $(echo "$DETAIL" | jq -c '.ledger[].entryType')"
# A retry with a NEW key on an already-COMMITTED order is answered idempotently
# (same order, no extra reward); verify no second PRODUCE row exists.
AGAIN=$(jpost "$P1" /api/player/crafts/commit "{\"orderNo\":\"$ORDER\"}" "$(uuid)")
check "$(echo "$AGAIN" | jq -r .status)" "COMMITTED" "重复完成返回同一终态单"
PRODUCES=$(jget "$P1" /api/player/crafts/$ORDER | jq '[.ledger[]|select(.entryType=="PRODUCE")]|length')
check "$PRODUCES" "2" "产出流水仅一份（装备+金币），重复提交未发双份"

echo "== 6. 取消预占 -> 材料逐笔退回 =="
CO=$(jpost "$P2" /api/player/crafts/preoccupy '{"recipeId":2}' "$(uuid)" | jq -r .orderNo)
jpost "$P2" /api/player/crafts/cancel "{\"orderNo\":\"$CO\",\"reason\":\"smoke cancel\"}" >/dev/null
CD=$(jget "$P2" /api/player/crafts/$CO)
echo "$CD" | jq -e '[.ledger[].entryType] | index("RELEASE") != null' >/dev/null \
  && ok "取消生成 RELEASE 退回流水" || bad "缺少 RELEASE 流水"
W=$(jget "$P2" /api/player/inventory | jq -r '.items[]|select(.itemCode=="MAT_WOOD").qty')
check "$W" "5" "取消后木材回到 5"

echo "== 7. 超时自动释放（10s 短窗口配方，清扫器 2s 轮询）=="
NEW=$(jpost "$OPS" /api/operator/recipes '{"code":"SMOKE_QUICK","name":"冒烟速成品"}')
QID=$(echo "$NEW" | jq -r .recipeId); DRAFT=$(echo "$NEW" | jq -r .draftVersionId)
jpost "$OPS" /api/operator/recipes/draft \
  "{\"versionId\":$DRAFT,\"spec\":{\"inputs\":[{\"itemCode\":\"MAT_WOOD\",\"qty\":1}],\"outputs\":[{\"itemCode\":\"SMOKE_ITEM\",\"qty\":1}],\"startTime\":\"2026-09-01T00:00:00Z\",\"endTime\":\"2026-12-31T23:59:59Z\",\"craftTimeoutSeconds\":10}}" >/dev/null
jpost "$OPS" /api/operator/recipes/publish "{\"recipeId\":$QID,\"versionId\":$DRAFT}" >/dev/null
QO=$(jpost "$P2" /api/player/crafts/preoccupy "{\"recipeId\":$QID}" "$(uuid)" | jq -r .orderNo)
WBEFORE=$(jget "$P2" /api/player/inventory | jq -r '.items[]|select(.itemCode=="MAT_WOOD").qty')
check "$WBEFORE" "4" "预占后木材 5->4"
echo "   等待 14s 让超时清扫器处理…"; sleep 14
QD=$(jget "$P2" /api/player/crafts/$QO)
check "$(echo "$QD" | jq -r .status)" "TIMEOUT" "预占单超时关闭"
WAFTER=$(jget "$P2" /api/player/inventory | jq -r '.items[]|select(.itemCode=="MAT_WOOD").qty')
check "$WAFTER" "5" "超时后占用释放，木材回到 5"
LATE=$(jpost "$P2" /api/player/crafts/commit "{\"orderNo\":\"$QO\"}" "$(uuid)")
echo "$LATE" | grep -qE 'ORDER_NOT_OPEN|PREOCCUPY_EXPIRED' && ok "超时后迟到的完成不发奖" || bad "迟到完成异常: $LATE"

echo "== 8. 新版本发布：已开始的单按预占版本完成 =="
T1O=$(jpost "$P3" /api/player/crafts/preoccupy '{"recipeId":2}' "$(uuid)" | jq -r .orderNo)
NV2=$(jpost "$OPS" /api/operator/recipes/new-version '{"recipeId":2}' | jq -r .draftVersionId)
jpost "$OPS" /api/operator/recipes/draft \
  "{\"versionId\":$NV2,\"spec\":{\"inputs\":[{\"itemCode\":\"MAT_WOOD\",\"qty\":4}],\"outputs\":[{\"itemCode\":\"EQP_THUNDER_BOW_PLUS\",\"qty\":1}],\"startTime\":\"2026-09-01T00:00:00Z\",\"endTime\":\"2026-12-31T23:59:59Z\",\"craftTimeoutSeconds\":120}}" >/dev/null
jpost "$OPS" /api/operator/recipes/publish "{\"recipeId\":2,\"versionId\":$NV2}" >/dev/null
TC=$(jpost "$P3" /api/player/crafts/commit "{\"orderNo\":\"$T1O\"}" "$(uuid)")
check "$(echo "$TC" | jq -r .boundVersionNo)" "1" "进行中的单仍按 v1 完成"
P3INV=$(jget "$P3" /api/player/inventory)
check "$(invq "$P3INV" EQP_THUNDER_BOW)" "1" "发放 v1 产出"
check "$(invq "$P3INV" EQP_THUNDER_BOW_PLUS)" "0" "未误发 v2 产出"

echo "== 9. 活动结束下架，服务端拒绝新合成（不靠按钮）=="
jpost "$OPS" /api/operator/recipes/close '{"recipeId":1,"reason":"activity ended"}' >/dev/null
CLOSED=$(jpost "$P3" /api/player/crafts/preoccupy '{"recipeId":1}' "$(uuid)")
echo "$CLOSED" | grep -q RECIPE_CLOSED && ok "下架后强制提交被服务端拒绝" || bad "下架后仍可合成: $CLOSED"

echo "== 10. 运营撤销：全额反向流水 / 已耗用 -> 异常清单 =="
# v2 雷霆弓只需木材 x4；player3 原有木材4（步骤6/7已回到5），一次设为 8 够两单
jpost "$OPS" /api/operator/inventory/grant "{\"playerId\":$P3_ID,\"itemCode\":\"MAT_WOOD\",\"qty\":8}" >/dev/null
# player3 在雷霆弓 v2（当前已发布）上做两单：第一单全额撤销，第二单把奖励用掉后撤销入异常
O1=$(jpost "$P3" /api/player/crafts/preoccupy '{"recipeId":2}' "$(uuid)" | jq -r .orderNo)
jpost "$P3" /api/player/crafts/commit "{\"orderNo\":\"$O1\"}" "$(uuid)" >/dev/null
RV1=$(jpost "$OPS" /api/operator/revokes "{\"orderNo\":\"$O1\"}")
check "$(echo "$RV1" | jq -r .result)" "REVERSED" "奖励在库 -> 全额冲销"
BOW=$(invq "$(jget "$P3" /api/player/inventory)" EQP_THUNDER_BOW_PLUS)
check "$BOW" "0" "PLUS 奖励已扣回（v2 产出）"
RV1NO=$(echo "$RV1" | jq -r .revokeNo)
REVLEDGER=$(jget "$OPS" "/api/operator/ledger?refNo=$RV1NO")
echo "$REVLEDGER" | jq -e 'type=="array" and length>0 and all(.[];.entryType=="REVOKE" and .qtyDelta<0 and .relatedRef!=null)' >/dev/null \
  && ok "生成负向 REVOKE 反向流水并关联原单" || bad "反向流水异常: $REVLEDGER"

# 第二单：上一单扣 4 后还剩 4，直接合成；随后把装备“用掉”（库存置 0）再撤销
O2=$(jpost "$P3" /api/player/crafts/preoccupy '{"recipeId":2}' "$(uuid)" | jq -r .orderNo)
jpost "$P3" /api/player/crafts/commit "{\"orderNo\":\"$O2\"}" "$(uuid)" >/dev/null
jpost "$OPS" /api/operator/inventory/grant "{\"playerId\":$P3_ID,\"itemCode\":\"EQP_THUNDER_BOW_PLUS\",\"qty\":0}" >/dev/null
RV2=$(jpost "$OPS" /api/operator/revokes "{\"orderNo\":\"$O2\"}")
check "$(echo "$RV2" | jq -r .result)" "EXCEPTION" "奖励已耗用 -> 进入异常清单"
check "$(echo "$RV2" | jq -r '.shortage[0].itemCode')" "EQP_THUNDER_BOW_PLUS" "异常明细标注缺口道具"
EXC=$(jget "$OPS" /api/operator/exceptions)
echo "$EXC" | jq -e --arg o "$O2" 'any(.[]; .orderNo==$o)' >/dev/null && ok "异常清单可查询到该单" || bad "异常清单缺失"
PEND=$(jget "$OPS" "/api/operator/ledger?refNo=$(echo "$RV2"|jq -r .revokeNo)")
echo "$PEND" | jq -e 'all(.[];.status=="PENDING" and .entryType=="REVOKE_PENDING")' >/dev/null \
  && ok "挂账 REVOKE_PENDING 流水存在，余额未被部分扣减" || bad "挂账流水异常"
DBL=$(jpost "$OPS" /api/operator/revokes "{\"orderNo\":\"$O1\"}")
echo "$DBL" | grep -q ORDER_NOT_REVOKABLE && ok "重复撤销被拒绝" || bad "重复撤销: $DBL"

echo "== 11. 最后一份材料并发合成（8 并发，恰够 1 份）=="
# 重新发布烈焰之剑（步骤9已下架），并把 player1 重置为恰好一份
NV3=$(jpost "$OPS" /api/operator/recipes/new-version '{"recipeId":1}' | jq -r .draftVersionId)
jpost "$OPS" /api/operator/recipes/draft \
  "{\"versionId\":$NV3,\"spec\":{\"inputs\":[{\"itemCode\":\"MAT_IRON\",\"qty\":3},{\"itemCode\":\"MAT_MAGIC_CORE\",\"qty\":2},{\"itemCode\":\"MAT_FIRE_SHARD\",\"qty\":1}],\"outputs\":[{\"itemCode\":\"EQP_FIRE_SWORD\",\"qty\":1},{\"itemCode\":\"GOLD\",\"qty\":100}],\"startTime\":\"2026-09-01T00:00:00Z\",\"endTime\":\"2026-12-31T23:59:59Z\",\"craftTimeoutSeconds\":120}}" >/dev/null
jpost "$OPS" /api/operator/recipes/publish "{\"recipeId\":1,\"versionId\":$NV3}" >/dev/null
for it in 'MAT_IRON 3' 'MAT_MAGIC_CORE 2' 'MAT_FIRE_SHARD 1'; do
  set -- $it
  jpost "$OPS" /api/operator/inventory/grant "{\"playerId\":2,\"itemCode\":\"$1\",\"qty\":$2}" >/dev/null
done
TMP=$(mktemp -d)
for i in $(seq 1 8); do
  ( curl -s -o $TMP/r$i -w '%{http_code}' -X POST $BASE/api/player/crafts/preoccupy \
      -H "X-Auth-Token: $P1" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" \
      -d '{"recipeId":1}' > $TMP/c$i ) &
done
wait
SUCCESS=$(grep -l '^200$' $TMP/c* 2>/dev/null | wc -l | tr -d ' ')
INSUF=0
for f in $TMP/r*; do grep -q MATERIAL_INSUFFICIENT "$f" && INSUF=$((INSUF+1)); done
check "$SUCCESS" "1" "8 个并发仅 1 个预占成功"
check "$INSUF" "7" "其余 7 个收到 MATERIAL_INSUFFICIENT"
FINAL=$(jget "$P1" /api/player/inventory)
check "$(echo "$FINAL" | jq -r '[.items[]|select(.itemCode|startswith("MAT_"))|.qty] | add // 0')" "0" "材料总量恰好扣一份，无多扣"
rm -rf $TMP

echo
echo "结果：PASS=$PASS FAIL=$FAIL"
[ $FAIL -eq 0 ]
