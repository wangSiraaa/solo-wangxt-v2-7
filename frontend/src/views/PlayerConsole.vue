<template>
  <div>
    <div v-if="flash" class="flash" :class="flash.type">{{ flash.text }}</div>

    <div class="grid2">
      <!-- Left: recipes & craft -->
      <div>
        <div class="panel">
          <div class="row" style="justify-content:space-between">
            <h2 style="margin:0">配方与合成预览</h2>
            <button class="secondary" @click="refreshAll">刷新</button>
          </div>
          <p class="muted small">
            按钮是否可点不作为规则依据；即使前端显示不可合成，服务端仍会独立校验活动窗口、
            已发布版本、材料余额，拒绝的结果会原样回显。
          </p>

          <div v-for="r in recipes" :key="r.recipeId" class="panel"
               style="background:var(--panel2);margin-bottom:10px">
            <div class="row" style="justify-content:space-between">
              <div>
                <strong>{{ r.recipeName }}</strong>
                <span class="mono muted small" style="margin-left:6px">{{ r.recipeCode }}</span>
              </div>
              <span class="tag" :class="recipeTagClass(r)">{{ recipeTagText(r) }}</span>
            </div>
            <div class="muted small" style="margin:6px 0">
              当前版本 v{{ r.versionNo ?? '—' }} ·
              活动 {{ fmt(r.activityStart) }} ~ {{ fmt(r.activityEnd) }} ·
              预占超时 {{ r.craftTimeoutSeconds ?? '—' }}s
            </div>

            <table v-if="r.inputs && r.inputs.length">
              <thead><tr><th>材料</th><th class="right">需要</th><th class="right">持有</th><th class="right">其他单占用</th><th></th></tr></thead>
              <tbody>
                <tr v-for="line in r.inputs" :key="line.itemCode">
                  <td class="mono">{{ line.itemCode }}</td>
                  <td class="right">{{ line.need }}</td>
                  <td class="right">{{ line.balance }}</td>
                  <td class="right">{{ line.inOtherOrders }}</td>
                  <td class="right">
                    <span class="tag" :class="line.enough ? 'ok' : 'bad'">
                      {{ line.enough ? '够' : `缺${line.need - line.available}` }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
            <div class="muted small" style="margin:6px 0">
              产出：<span v-for="o in r.outputs" :key="o.itemCode" class="mono tag" style="margin-right:6px">
                {{ o.itemCode }} ×{{ o.qty }}
              </span>
            </div>
            <div class="row">
              <button @click="preoccupy(r)" :disabled="busy">预占材料（第1步）</button>
              <span v-if="!r.craftable" class="small error">{{ cannotReason(r) }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Right: active orders, inventory, trail -->
      <div>
        <div class="panel">
          <h2>进行中的合成（预占）</h2>
          <p class="muted small" v-if="!activeOrders.length">暂无预占中的合成单。</p>
          <table v-else>
            <thead><tr><th>单号</th><th>配方/版本</th><th>剩余时间</th><th></th></tr></thead>
            <tbody>
              <tr v-for="o in activeOrders" :key="o.orderNo">
                <td class="mono small">{{ short(o.orderNo) }}</td>
                <td>{{ o.recipeName }} v{{ o.boundVersionNo }}</td>
                <td><Countdown :deadline="o.preoccupyDeadline" @expired="refreshAll" /></td>
                <td class="right row" style="justify-content:flex-end">
                  <button @click="commit(o)" :disabled="busy">完成（第2步）</button>
                  <button class="secondary" @click="cancel(o)">取消释放</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="panel">
          <h2>我的背包</h2>
          <table>
            <thead><tr><th>道具</th><th class="right">数量</th></tr></thead>
            <tbody>
              <tr v-for="it in inventory.items" :key="it.itemCode">
                <td class="mono">{{ it.itemCode }}</td>
                <td class="right">{{ it.qty }}</td>
              </tr>
              <tr v-if="!inventory.items.length"><td colspan="2" class="muted">空空如也</td></tr>
            </tbody>
          </table>
          <p class="muted small" v-if="inventory.activeHolds?.length">
            预占占用（已从余额扣减）：
            <span v-for="h in inventory.activeHolds" :key="h.id" class="tag warn" style="margin-right:6px">
              {{ h.itemCode }} ×{{ h.qty }}
            </span>
          </p>
        </div>

        <div class="panel">
          <div class="row" style="justify-content:space-between">
            <h2 style="margin:0">合成单与逐笔材料去向</h2>
            <select v-model="selectedOrderNo" @change="loadDetail" style="width:240px">
              <option value="">选择合成单…</option>
              <option v-for="o in crafts" :key="o.orderNo" :value="o.orderNo">
                {{ short(o.orderNo) }} · {{ o.recipeName }} · {{ o.status }}
              </option>
            </select>
          </div>
          <div v-if="detail">
            <div class="muted small" style="margin:8px 0">
              单号 <span class="mono">{{ detail.orderNo }}</span> ·
              绑定版本 v{{ detail.boundVersionNo }} · 状态
              <span class="tag" :class="statusClass(detail.status)">{{ detail.status }}</span>
              <span v-if="detail.revokeRefNo"> · 撤销单 <span class="mono">{{ short(detail.revokeRefNo) }}</span></span>
            </div>
            <table>
              <thead><tr><th>时间(UTC)</th><th>流水类型</th><th>道具</th><th class="right">变动</th><th>说明</th></tr></thead>
              <tbody>
                <tr v-for="e in detail.ledger" :key="e.id">
                  <td class="small muted">{{ fmt(e.createdAt) }}</td>
                  <td><EntryType :type="e.entryType" :status="e.status" /></td>
                  <td class="mono">{{ e.itemCode }}</td>
                  <td class="right" :class="e.qtyDelta >= 0 ? 'delta-pos' : 'delta-neg'">
                    {{ e.qtyDelta > 0 ? '+' : '' }}{{ e.qtyDelta }}
                  </td>
                  <td class="small muted">{{ e.remark }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="panel">
          <h2>最近流水</h2>
          <table>
            <thead><tr><th>单号</th><th>类型</th><th>道具</th><th class="right">变动</th><th>时间</th></tr></thead>
            <tbody>
              <tr v-for="e in ledger" :key="e.id">
                <td class="mono small">{{ short(e.refNo) }}</td>
                <td><EntryType :type="e.entryType" :status="e.status" /></td>
                <td class="mono">{{ e.itemCode }}</td>
                <td class="right" :class="e.qtyDelta >= 0 ? 'delta-pos' : 'delta-neg'">
                  {{ e.qtyDelta > 0 ? '+' : '' }}{{ e.qtyDelta }}
                </td>
                <td class="small muted">{{ fmt(e.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { api, idemKey } from '../api'
import Countdown from '../components/Countdown.vue'
import EntryType from '../components/EntryType.vue'

const recipes = ref([])
const inventory = reactive({ items: [], activeHolds: [] })
const crafts = ref([])
const ledger = ref([])
const selectedOrderNo = ref('')
const detail = ref(null)
const busy = ref(false)
const flash = ref(null)
let timer

function notify(type, text) {
  flash.value = { type, text }
  setTimeout(() => (flash.value = null), 5000)
}
const short = (no) => no ? no.slice(0, 10) + '…' : ''
const fmt = (t) => t ? new Date(t).toISOString().replace('T', ' ').slice(0, 19) : '—'

async function refreshAll() {
  try {
    const [catalog, inv, myCrafts, myLedger] = await Promise.all([
      api.catalog(), api.inventory(), api.myCrafts(), api.myLedger()
    ])
    recipes.value = catalog
    inventory.items = inv.items
    inventory.activeHolds = inv.activeHolds
    crafts.value = myCrafts
    ledger.value = myLedger
    if (selectedOrderNo.value) await loadDetail()
  } catch (e) {
    notify('error', '刷新失败：' + e.message)
  }
}

const activeOrders = computed(() => crafts.value.filter(o => o.status === 'PREOCCUPIED'))

async function preoccupy(recipe) {
  busy.value = true
  try {
    // A fresh idempotency key per click; the same key replays on network retry.
    const res = await api.preoccupy(recipe.recipeId, idemKey())
    notify('ok', `已预占：${res.orderNo}（绑定 v${res.boundVersionNo}），请在超时前完成。`)
    selectedOrderNo.value = res.orderNo
    await refreshAll()
  } catch (e) {
    // Server verdict — e.g. ACTIVITY_NOT_OPEN / MATERIAL_INSUFFICIENT / RECIPE_CLOSED
    notify('error', `预占被服务端拒绝 [${e.code || e.status}]：${e.message}`)
  } finally {
    busy.value = false
  }
}

async function commit(order) {
  busy.value = true
  try {
    const res = await api.commit(order.orderNo, idemKey())
    notify('ok', `合成完成：${res.orderNo}，产出已发放（重试同键会回放，不会重复发奖）。`)
    selectedOrderNo.value = order.orderNo
    await refreshAll()
  } catch (e) {
    notify('error', `完成失败 [${e.code || e.status}]：${e.message}`)
  } finally {
    busy.value = false
  }
}

async function cancel(order) {
  try {
    await api.cancel(order.orderNo, 'player cancelled')
    notify('ok', '已取消，预占材料逐笔退回。')
    await refreshAll()
  } catch (e) {
    notify('error', '取消失败：' + e.message)
  }
}

async function loadDetail() {
  if (!selectedOrderNo.value) { detail.value = null; return }
  detail.value = await api.craftDetail(selectedOrderNo.value)
}

function recipeTagClass(r) {
  if (r.headerStatus === 'CLOSED') return 'bad'
  if (!r.versionNo) return 'warn'
  return r.activityOpen ? 'ok' : 'warn'
}
function recipeTagText(r) {
  if (r.headerStatus === 'CLOSED') return '已下架'
  if (!r.versionNo) return '未发布'
  return r.activityOpen ? '进行中' : '活动未开始/已结束'
}
function cannotReason(r) {
  if (r.headerStatus === 'CLOSED') return '配方已下架（服务端会拒绝）'
  if (!r.versionNo) return '无已发布版本'
  if (!r.activityOpen) return '不在活动有效期'
  return '材料不足（服务端会拒绝）'
}
function statusClass(s) {
  return { COMMITTED: 'ok', PREOCCUPIED: 'info', CANCELLED: 'warn', TIMEOUT: 'warn', REVOKED: 'bad' }[s] || ''
}

onMounted(async () => {
  await refreshAll()
  timer = setInterval(refreshAll, 3000)
})
onUnmounted(() => clearInterval(timer))
</script>
