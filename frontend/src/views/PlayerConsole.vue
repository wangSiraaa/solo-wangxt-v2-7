<template>
  <div>
    <div v-if="flash" class="flash" :class="flash.type">{{ flash.text }}</div>

    <!-- ===================== 批量合成计划 ===================== -->
    <div class="panel" style="margin-bottom:14px">
      <div class="row" style="justify-content:space-between">
        <h2 style="margin:0">批量合成计划（1–100 次，后台逐序号执行）</h2>
        <button class="secondary" @click="refreshAll">刷新进度</button>
      </div>
      <p class="muted small">
        一次提交固定配方版本与每次材料/产出快照，由服务端后台 worker 执行，<b>不是</b>前端循环单次合成。
        同键并发只会创建一个计划；活动结束/材料不足后未开始序号不再启动，已预占序号仍按绑定版本完成；
        取消只影响尚未开始的序号，已完成奖励不回滚。
      </p>
      <div class="row">
        <select v-model="planRecipeId" style="width:300px">
          <option v-for="r in recipes" :key="r.recipeId" :value="r.recipeId">
            {{ r.recipeName }}（{{ r.recipeCode }}）v{{ r.versionNo ?? '—' }}
          </option>
        </select>
        <input v-model.number="planUnits" type="number" min="1" max="100" placeholder="次数 1-100" style="width:130px" />
        <button @click="createPlan" :disabled="busy">提交批量计划</button>
      </div>

      <table style="margin-top:12px">
        <thead>
          <tr><th>计划号</th><th>配方/版本</th><th>进度（完成/失败/未执行/总数）</th>
          <th>状态</th><th>创建时间</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="p in plans" :key="p.planNo"
              :style="selectedPlanNo === p.planNo ? 'background:rgba(64,158,255,.08)' : ''">
            <td class="mono small">{{ short(p.planNo) }}</td>
            <td>v{{ p.boundVersionNo || '—' }}</td>
            <td>
              <span class="tag ok">{{ p.completedCount }}</span> /
              <span class="tag bad">{{ p.failedCount }}</span> /
              <span class="tag warn">{{ p.notRunCount }}</span> /
              <span>{{ p.totalUnits }}</span>
              <div class="bar" style="margin-top:4px;max-width:220px">
                <div class="bar-ok" :style="{ width: progressPct(p, 'completedCount') }"></div>
                <div class="bar-bad" :style="{ width: progressPct(p, 'failedCount') }"></div>
                <div class="bar-warn" :style="{ width: progressPct(p, 'notRunCount') }"></div>
              </div>
            </td>
            <td>
              <span class="tag" :class="planStatusClass(p.status)">{{ p.status }}</span>
              <div v-if="p.stopNewUnits" class="small muted">已停止新序号</div>
            </td>
            <td class="small muted">{{ fmt(p.createdAt) }}</td>
            <td class="right row" style="justify-content:flex-end">
              <button class="secondary" @click="selectPlan(p.planNo)">进度/时间线</button>
              <button v-if="!terminalPlan(p)" class="ghost" @click="cancelPlan(p)">取消未开始</button>
            </td>
          </tr>
          <tr v-if="!plans.length"><td colspan="6" class="muted">还没有批量计划。</td></tr>
        </tbody>
      </table>

      <div v-if="planDetail" class="panel" style="background:var(--panel2);margin-top:12px">
        <div class="row" style="justify-content:space-between">
          <strong>计划 {{ short(planDetail.planNo) }} · 绑定版本 v{{ planDetail.boundVersionNo }} ·
            <span class="tag" :class="planStatusClass(planDetail.status)">{{ planDetail.status }}</span>
          </strong>
          <button class="ghost" @click="selectedPlanNo=''; planDetail=null">×</button>
        </div>
        <p class="muted small" v-if="planDetail.statusReason">原因：{{ planDetail.statusReason }}</p>
        <p class="small muted" style="margin:4px 0">
          完成 {{ planDetail.completedCount }} · 失败 {{ planDetail.failedCount }} ·
          跳过/未执行 {{ planDetail.skippedCount }} · 合计 {{ planDetail.totalUnits }}
        </p>
        <table>
          <thead>
            <tr><th>序号</th><th>状态</th><th>合成单</th><th>开始(UTC)</th><th>扣料(UTC)</th>
            <th>完成(UTC)</th><th>尝试</th><th>说明</th></tr>
          </thead>
          <tbody>
            <tr v-for="u in planDetail.units" :key="u.unitNo">
              <td class="mono">{{ u.unitNo }}</td>
              <td><span class="tag" :class="unitStatusClass(u.status)">{{ u.status }}</span></td>
              <td class="mono small">{{ u.orderNo ? short(u.orderNo) : '—' }}</td>
              <td class="small muted">{{ fmt(u.startedAt) }}</td>
              <td class="small muted">{{ fmt(u.deductedAt) }}</td>
              <td class="small muted">{{ fmt(u.completedAt) }}</td>
              <td>{{ u.attempts }}</td>
              <td class="small muted">{{ u.statusReason || '' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

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

// ---- batch plan state ----
const plans = ref([])
const planRecipeId = ref(null)
const planUnits = ref(5)
const selectedPlanNo = ref('')
const planDetail = ref(null)

function notify(type, text) {
  flash.value = { type, text }
  setTimeout(() => (flash.value = null), 5000)
}
const short = (no) => no ? no.slice(0, 10) + '…' : ''
const fmt = (t) => t ? new Date(t).toISOString().replace('T', ' ').slice(0, 19) : '—'

async function refreshAll() {
  try {
    const [catalog, inv, myCrafts, myLedger, myPlans] = await Promise.all([
      api.catalog(), api.inventory(), api.myCrafts(), api.myLedger(), api.myPlans()
    ])
    recipes.value = catalog
    if (planRecipeId.value == null && catalog.length) planRecipeId.value = catalog[0].recipeId
    inventory.items = inv.items
    inventory.activeHolds = inv.activeHolds
    crafts.value = myCrafts
    ledger.value = myLedger
    plans.value = myPlans
    if (selectedOrderNo.value) await loadDetail()
    if (selectedPlanNo.value) await loadPlanDetail()
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

// ---- batch plan actions ----
const PLAN_TERMINAL = ['COMPLETED', 'PARTIAL', 'CANCELLED', 'FAILED']
function terminalPlan(p) { return PLAN_TERMINAL.includes(p.status) }
function progressPct(p, key) {
  if (!p.totalUnits) return '0%'
  return ((p[key] || 0) / p.totalUnits * 100).toFixed(1) + '%'
}
function planStatusClass(s) {
  return { COMPLETED: 'ok', RUNNING: 'info', PENDING: 'info', PARTIAL: 'warn', CANCELLED: 'warn', FAILED: 'bad' }[s] || ''
}
function unitStatusClass(s) {
  return { DONE: 'ok', PENDING: 'muted', RUNNING: 'info', DEDUCTED: 'info', SKIPPED: 'warn', FAILED: 'bad' }[s] || ''
}

async function createPlan() {
  if (!planRecipeId.value) { notify('error', '请选择配方'); return }
  const n = Number(planUnits.value)
  if (!Number.isInteger(n) || n < 1 || n > 100) { notify('error', '次数必须是 1–100 的整数'); return }
  busy.value = true
  try {
    const res = await api.createPlan(planRecipeId.value, n, idemKey())
    selectedPlanNo.value = res.planNo
    notify('ok', `计划已创建：${res.planNo}（${n} 次，绑定 v${res.boundVersionNo}），后台开始执行。`)
    await refreshAll()
  } catch (e) {
    notify('error', `批量计划被拒绝 [${e.code || e.status}]：${e.message}`)
  } finally {
    busy.value = false
  }
}

async function selectPlan(no) {
  selectedPlanNo.value = no
  await loadPlanDetail()
}

async function loadPlanDetail() {
  if (!selectedPlanNo.value) { planDetail.value = null; return }
  try {
    planDetail.value = await api.planDetail(selectedPlanNo.value)
  } catch (e) {
    notify('error', '计划详情加载失败：' + e.message)
  }
}

async function cancelPlan(p) {
  if (!window.confirm(`确认取消计划 ${p.planNo}？只取消尚未开始的序号，已完成奖励不回滚。`)) return
  try {
    await api.cancelPlan(p.planNo, 'player cancelled')
    notify('ok', '已请求取消：未开始序号将跳过，已扣料序号继续完成。')
    await refreshAll()
  } catch (e) {
    notify('error', '取消失败：' + e.message)
  }
}

onMounted(async () => {
  await refreshAll()
  timer = setInterval(refreshAll, 3000)
})
onUnmounted(() => clearInterval(timer))
</script>
