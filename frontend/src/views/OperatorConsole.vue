<template>
  <div>
    <div v-if="flash" class="flash" :class="flash.type">{{ flash.text }}</div>

    <div class="tabs" style="margin-bottom:14px">
      <button :class="tab==='recipes' ? '' : 'secondary'" @click="tab='recipes'">配方版本</button>
      <button :class="tab==='ledger' ? '' : 'secondary'" @click="tab='ledger'; loadOpsData()">账本监控</button>
      <button :class="tab==='plans' ? '' : 'secondary'" @click="tab='plans'; loadPlans()">批量计划/对账</button>
      <button :class="tab==='revoke' ? '' : 'secondary'" @click="tab='revoke'; loadRevokes()">撤销与异常清单</button>
      <button :class="tab==='grant' ? '' : 'secondary'" @click="tab='grant'">库存工具</button>
    </div>

    <!-- ===================== 配方版本 ===================== -->
    <div v-if="tab==='recipes'">
      <div class="panel">
        <h2>新建配方</h2>
        <div class="row">
          <input v-model="newCode" placeholder="配方编码 CODE（如 ICE_SHIELD）" style="width:260px" />
          <input v-model="newName" placeholder="名称（如 寒冰之盾）" style="width:260px" />
          <button @click="createRecipe">创建（自带 v1 草稿）</button>
        </div>
      </div>

      <div v-for="r in recipes" :key="r.recipeId" class="panel">
        <div class="row" style="justify-content:space-between">
          <div>
            <strong>{{ r.name }}</strong>
            <span class="mono muted small" style="margin-left:6px">{{ r.code }}</span>
            <span class="tag" :class="r.status === 'ACTIVE' ? 'ok' : 'bad'" style="margin-left:8px">
              {{ r.status === 'ACTIVE' ? '上架中' : '已下架' }}
            </span>
          </div>
          <div class="row">
            <button class="secondary" @click="newVersion(r.recipeId)">创建新版本</button>
            <button class="ghost" :disabled="r.status !== 'ACTIVE'" @click="closeRecipe(r.recipeId)">
              活动结束/下架
            </button>
          </div>
        </div>

        <table style="margin-top:10px">
          <thead>
          <tr>
            <th>版本</th><th>状态</th><th>材料</th><th>产出</th>
            <th>活动窗口(UTC)</th><th>预占超时</th><th>发布时间</th><th></th>
          </tr>
          </thead>
          <tbody>
          <tr v-for="v in r.versions" :key="v.versionId">
            <td>v{{ v.versionNo }}</td>
            <td><span class="tag" :class="versionClass(v.status)">{{ versionText(v.status) }}</span></td>
            <td class="small">
              <span v-for="i in v.inputs" :key="i.itemCode" class="mono tag" style="margin:0 4px 4px 0;display:inline-block">
                {{ i.itemCode }}×{{ i.qty }}
              </span>
            </td>
            <td class="small">
              <span v-for="o in v.outputs" :key="o.itemCode" class="mono tag ok" style="margin:0 4px 4px 0;display:inline-block">
                {{ o.itemCode }}×{{ o.qty }}
              </span>
            </td>
            <td class="small muted">{{ fmt(v.startTime) }}<br/>~ {{ fmt(v.endTime) }}</td>
            <td>{{ v.craftTimeoutSeconds || '—' }}s</td>
            <td class="small muted">{{ fmt(v.publishedAt) }}</td>
            <td class="right">
              <button v-if="v.status==='DRAFT'" class="secondary" @click="edit(r, v)">编辑草稿</button>
              <button v-if="v.status==='DRAFT'" @click="publish(r, v)">发布</button>
              <span v-else class="muted small">已发布版本不可改，只能新建版本</span>
            </td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ===================== 账本监控 ===================== -->
    <div v-if="tab==='ledger'">
      <div class="grid2">
        <div class="panel">
          <h2>最近合成单</h2>
          <table>
            <thead><tr><th>单号</th><th>玩家</th><th>配方/版本</th><th>状态</th><th>时间</th></tr></thead>
            <tbody>
              <tr v-for="o in crafts" :key="o.orderNo" style="cursor:pointer" @click="queryRef(o.orderNo)">
                <td class="mono small">{{ o.orderNo }}</td>
                <td>#{{ o.playerId }}</td>
                <td>{{ o.recipeName }} v{{ o.boundVersionNo }}</td>
                <td><span class="tag" :class="orderClass(o.status)">{{ o.status }}</span></td>
                <td class="small muted">{{ fmt(o.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="panel">
          <h2>按单号/撤销单查流水</h2>
          <div class="row">
            <input v-model="refQuery" placeholder="CO… 或 RV…" />
            <button @click="queryRef(refQuery)">查询</button>
          </div>
          <table style="margin-top:10px">
            <thead><tr><th>时间</th><th>ref</th><th>类型</th><th>道具</th><th class="right">变动</th><th>关联</th></tr></thead>
            <tbody>
              <tr v-for="e in refLedger" :key="e.id">
                <td class="small muted">{{ fmt(e.createdAt) }}</td>
                <td class="mono small">{{ e.refNo }}</td>
                <td><EntryType :type="e.entryType" :status="e.status" /></td>
                <td class="mono">{{ e.itemCode }}</td>
                <td class="right" :class="e.qtyDelta>=0?'delta-pos':'delta-neg'">{{ e.qtyDelta }}</td>
                <td class="mono small muted">{{ e.relatedRef || '' }}</td>
              </tr>
              <tr v-if="!refLedger.length"><td colspan="6" class="muted">输入单号查询；点左侧合成单自动带出。</td></tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="panel">
        <h2>最近全局流水（消耗/产出/释放/撤销）</h2>
        <table>
          <thead><tr><th>时间</th><th>单号</th><th>玩家</th><th>类型</th><th>道具</th><th class="right">变动</th><th>说明</th></tr></thead>
          <tbody>
          <tr v-for="e in allLedger" :key="e.id">
            <td class="small muted">{{ fmt(e.createdAt) }}</td>
            <td class="mono small">{{ e.refNo }}</td>
            <td>#{{ e.playerId }}</td>
            <td><EntryType :type="e.entryType" :status="e.status" /></td>
            <td class="mono">{{ e.itemCode }}</td>
            <td class="right" :class="e.qtyDelta>=0?'delta-pos':'delta-neg'">{{ e.qtyDelta }}</td>
            <td class="small muted">{{ e.remark }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ===================== 批量计划 / 对账 / 补偿 ===================== -->
    <div v-if="tab==='plans'">
      <div class="panel">
        <div class="row" style="justify-content:space-between">
          <h2 style="margin:0">批量合成计划</h2>
          <div class="row">
            <label class="small muted">
              <input type="checkbox" v-model="showAnomalies" @change="loadPlans" /> 仅异常/非 COMPLETED
            </label>
            <button class="secondary" @click="loadPlans">刷新</button>
          </div>
        </div>
        <table>
          <thead><tr>
            <th>计划号</th><th>玩家</th><th>配方/版本</th><th>总数</th>
            <th class="right">完成</th><th class="right">跳过</th><th class="right">失败</th>
            <th>状态</th><th>停止原因</th><th>创建时间</th><th></th>
          </tr></thead>
          <tbody>
          <tr v-for="p in planList" :key="p.planNo">
            <td class="mono small">{{ p.planNo }}</td>
            <td>#{{ p.playerId }}</td>
            <td>{{ p.recipeName }} v{{ p.boundVersionNo }}</td>
            <td>{{ p.totalCount }}</td>
            <td class="right">{{ p.completedCount }}</td>
            <td class="right">{{ p.skippedCount }}</td>
            <td class="right">{{ p.failedCount }}</td>
            <td><span class="tag" :class="planClass(p.status)">{{ p.status }}</span></td>
            <td class="small muted">{{ p.stopReason || '' }}</td>
            <td class="small muted">{{ fmt(p.createdAt) }}</td>
            <td class="right">
              <button class="secondary" @click="openPlan(p.planNo)">对账/明细</button>
            </td>
          </tr>
          <tr v-if="!planList.length"><td colspan="11" class="muted">暂无计划</td></tr>
          </tbody>
        </table>
      </div>

      <div v-if="recon" class="panel">
        <div class="row" style="justify-content:space-between">
          <h2 style="margin:0">对账：{{ recon.planNo }}
            <span class="tag" :class="planClass(recon.status)" style="margin-left:8px">{{ recon.status }}</span>
            <span class="tag" :class="recon.consistent ? 'ok' : 'bad'" style="margin-left:8px">
              {{ recon.consistent ? '账实一致' : '存在差异' }}
            </span>
          </h2>
          <div class="row">
            <button class="secondary" @click="openPlan(recon.planNo)">重新对账</button>
            <button class="ghost" @click="recon=null">关闭</button>
          </div>
        </div>
        <p class="muted small">
          完成 {{ recon.completedCount }} / 跳过 {{ recon.skippedCount }} / 失败 {{ recon.failedCount }}
          / 未完成 {{ recon.notStartedCount }}。修复只能<b>追加</b>补偿流水，历史流水不改写、不删除；
          同一修复请求用同一 Idempotency-Key 可安全重试。
        </p>

        <h3>对账差异</h3>
        <table>
          <thead><tr><th>序号</th><th>类型</th><th>级别</th><th>道具</th><th class="right">应有</th><th class="right">实有</th><th>说明</th><th></th></tr></thead>
          <tbody>
          <tr v-for="(d, i) in recon.diffs" :key="i">
            <td>{{ d.unitNo || '—' }}</td>
            <td class="mono small">{{ d.type }}</td>
            <td><span class="tag" :class="d.severity === 'ERROR' ? 'bad' : 'warn'">{{ d.severity }}</span></td>
            <td class="mono">{{ d.itemCode || '' }}</td>
            <td class="right">{{ d.expected ?? '' }}</td>
            <td class="right">{{ d.actual ?? '' }}</td>
            <td class="small muted">{{ d.detail }}</td>
            <td class="right">
              <button v-if="canRepair(d)" class="secondary" @click="quickRepair(d)">追加补偿</button>
            </td>
          </tr>
          <tr v-if="!recon.diffs.length"><td colspan="8" class="muted">未发现差异。</td></tr>
          </tbody>
        </table>

        <h3>逐序号时间线</h3>
        <table>
          <thead><tr><th>#</th><th>状态</th><th>合成单号</th><th>扣料</th><th>发奖</th><th>回写</th><th>租约owner</th></tr></thead>
          <tbody>
          <tr v-for="u in recon.units" :key="u.unitNo">
            <td>{{ u.unitNo }}</td>
            <td><span class="tag" :class="unitClass(u.status)">{{ u.status }}</span></td>
            <td class="mono small">{{ u.orderNo || '—' }}</td>
            <td class="small muted">{{ fmt(u.consumedAt) }}</td>
            <td class="small muted">{{ fmt(u.rewardedAt) }}</td>
            <td class="small muted">{{ fmt(u.finishedAt) }}</td>
            <td class="small muted">{{ u.leaseOwner || '' }}</td>
          </tr>
          </tbody>
        </table>

        <h3>补偿结果</h3>
        <table>
          <thead><tr><th>修复键</th><th>序号</th><th>差异</th><th>道具</th><th class="right">补偿变动</th><th>补偿流水</th><th>运营</th><th>时间</th></tr></thead>
          <tbody>
          <tr v-for="r in repairList" :key="r.repairKey">
            <td class="mono small">{{ r.repairKey.slice(0,12) }}…</td>
            <td>{{ r.unitNo ?? '—' }}</td>
            <td class="mono small">{{ r.diffType }}</td>
            <td class="mono">{{ r.itemCode }}</td>
            <td class="right" :class="r.qtyDelta >= 0 ? 'delta-pos' : 'delta-neg'">{{ r.qtyDelta }}</td>
            <td class="mono small">{{ r.compRefNo }}</td>
            <td>#{{ r.operatorId }}</td>
            <td class="small muted">{{ fmt(r.createdAt) }}</td>
          </tr>
          <tr v-if="!repairList.length"><td colspan="8" class="muted">该计划暂无补偿。</td></tr>
          </tbody>
        </table>

        <div v-if="repairFlash" class="flash" :class="repairFlash.type" style="margin-top:10px">
          {{ repairFlash.text }}
        </div>
      </div>
    </div>

    <!-- ===================== 撤销与异常 ===================== -->
    <div v-if="tab==='revoke'">      <div class="grid2">
        <div class="panel">
          <h2>撤销错误奖励</h2>
          <p class="muted small">
            仅对 COMMITTED 的合成单生效：生成与原产出相反的 REVOKE 流水并扣回；
            若奖励/材料已被玩家用掉导致余额不足，不做部分扣减，整笔进入异常清单并挂 REVOKE_PENDING 流水。
          </p>
          <div class="row">
            <input v-model="revokeOrderNo" placeholder="要撤销的合成单号 CO…" />
            <button class="danger" @click="doRevoke">撤销奖励</button>
          </div>
          <div v-if="lastRevoke" style="margin-top:12px">
            <div class="flash" :class="lastRevoke.result === 'REVERSED' ? 'ok' : 'error'">
              撤销单 <span class="mono">{{ lastRevoke.revokeNo }}</span>：
              {{ lastRevoke.result === 'REVERSED' ? '已全额冲销' : '进入异常清单（材料已使用）' }}
            </div>
            <table v-if="lastRevoke.shortage.length">
              <thead><tr><th>道具</th><th class="right">应扣</th><th class="right">现存</th><th class="right">缺口</th></tr></thead>
              <tbody>
                <tr v-for="s in lastRevoke.shortage" :key="s.itemCode">
                  <td class="mono">{{ s.itemCode }}</td>
                  <td class="right">{{ s.need }}</td>
                  <td class="right">{{ s.have }}</td>
                  <td class="right error">{{ s.short }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="panel">
          <h2>异常清单 <span class="tag bad">{{ exceptions.length }}</span></h2>
          <table>
            <thead><tr><th>撤销单</th><th>原合成单</th><th>玩家</th><th>缺口明细</th><th>时间</th></tr></thead>
            <tbody>
              <tr v-for="x in exceptions" :key="x.revokeNo">
                <td class="mono small">{{ x.revokeNo }}</td>
                <td class="mono small">{{ x.orderNo }}</td>
                <td>#{{ x.playerId }}</td>
                <td class="small">
                  <span v-for="s in x.shortage" :key="s.itemCode" class="tag bad" style="margin-right:6px">
                    {{ s.itemCode }} 缺{{ s.short }}（存{{ s.have }}/需{{ s.need }}）
                  </span>
                </td>
                <td class="small muted">{{ fmt(x.createdAt) }}</td>
              </tr>
              <tr v-if="!exceptions.length"><td colspan="5" class="muted">暂无异常</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="panel">
        <h2>全部撤销记录</h2>
        <table>
          <thead><tr><th>撤销单</th><th>原合成单</th><th>玩家</th><th>结果</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="r in revokesAll" :key="r.revokeNo">
              <td class="mono small">{{ r.revokeNo }}</td>
              <td class="mono small">{{ r.orderNo }}</td>
              <td>#{{ r.playerId }}</td>
              <td><span class="tag" :class="r.result === 'REVERSED' ? 'ok' : 'bad'">{{ r.result }}</span></td>
              <td class="small muted">{{ fmt(r.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ===================== 库存工具 ===================== -->
    <div v-if="tab==='grant'">
      <div class="panel" style="max-width:560px">
        <h2>设置玩家库存（带 GRANT 审计流水）</h2>
        <div class="row"><span style="width:80px">玩家ID</span><input v-model.number="grantPlayerId" type="number" /></div>
        <div style="height:8px"></div>
        <div class="row"><span style="width:80px">道具编码</span><input v-model="grantItemCode" placeholder="MAT_IRON" /></div>
        <div style="height:8px"></div>
        <div class="row"><span style="width:80px">数量(绝对值)</span><input v-model.number="grantQty" type="number" /></div>
        <div style="height:12px"></div>
        <button @click="doGrant">写入库存</button>
      </div>
    </div>

    <!-- draft editor modal -->
    <div v-if="draft" class="panel" style="position:fixed;right:20px;top:20px;width:520px;max-height:94vh;overflow:auto;box-shadow:0 10px 40px rgba(0,0,0,.5);z-index:10">
      <div class="row" style="justify-content:space-between">
        <h2 style="margin:0">编辑 {{ draft.recipeCode }} v{{ draft.versionNo }} 草稿</h2>
        <button class="ghost" @click="draft=null">×</button>
      </div>
      <p class="muted small">保存后点“发布”才对玩家生效；发布瞬间旧版本归档，已开始的合成仍按旧版本完成。</p>

      <h3>材料</h3>
      <div v-for="(line, i) in draft.inputs" :key="'i'+i" class="row" style="margin-bottom:6px">
        <input v-model="line.itemCode" placeholder="MAT_X" />
        <input v-model.number="line.qty" type="number" style="width:110px" />
        <button class="ghost" @click="draft.inputs.splice(i,1)">删</button>
      </div>
      <button class="secondary" @click="draft.inputs.push({itemCode:'',qty:1})">+ 材料</button>

      <h3 style="margin-top:14px">产出</h3>
      <div v-for="(line, i) in draft.outputs" :key="'o'+i" class="row" style="margin-bottom:6px">
        <input v-model="line.itemCode" placeholder="EQP_X / GOLD" />
        <input v-model.number="line.qty" type="number" style="width:110px" />
        <button class="ghost" @click="draft.outputs.splice(i,1)">删</button>
      </div>
      <button class="secondary" @click="draft.outputs.push({itemCode:'',qty:1})">+ 产出</button>

      <h3 style="margin-top:14px">活动窗口（UTC，ISO 格式）</h3>
      <input v-model="draft.startTime" placeholder="2026-09-01T00:00:00Z" />
      <div style="height:6px"></div>
      <input v-model="draft.endTime" placeholder="2026-10-31T23:59:59Z" />
      <h3 style="margin-top:14px">预占超时秒数（≥10）</h3>
      <input v-model.number="draft.craftTimeoutSeconds" type="number" />
      <div class="row" style="margin-top:14px">
        <button @click="saveDraft">保存草稿</button>
        <button class="secondary" @click="draft=null">取消</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import EntryType from '../components/EntryType.vue'

const tab = ref('recipes')
const recipes = ref([])
const crafts = ref([])
const allLedger = ref([])
const refQuery = ref('')
const refLedger = ref([])
const revokeOrderNo = ref('')
const lastRevoke = ref(null)
const exceptions = ref([])
const revokesAll = ref([])
const flash = ref(null)

// batch plans / reconciliation
const planList = ref([])
const showAnomalies = ref(false)
const recon = ref(null)
const repairList = ref([])
const repairFlash = ref(null)

const newCode = ref('')
const newName = ref('')
const grantPlayerId = ref(2)
const grantItemCode = ref('MAT_IRON')
const grantQty = ref(10)
const draft = ref(null)

const fmt = (t) => t ? new Date(t).toISOString().replace('T', ' ').slice(0, 19) : '—'
function notify(type, text) { flash.value = { type, text }; setTimeout(() => flash.value = null, 6000) }

async function loadRecipes() { recipes.value = await api.opRecipes() }
async function loadOpsData() {
  crafts.value = await api.opCrafts()
  allLedger.value = await api.opLedger()
}
async function loadRevokes() {
  exceptions.value = await api.exceptions()
  revokesAll.value = await api.revokes()
}
async function queryRef(no) {
  if (!no) return
  refQuery.value = no
  refLedger.value = await api.opLedger(no)
}

async function createRecipe() {
  try {
    await api.createRecipe(newCode.value.trim(), newName.value.trim())
    notify('ok', '配方已创建并自带 v1 草稿，请编辑后发布。')
    newCode.value = ''; newName.value = ''
    await loadRecipes()
  } catch (e) { notify('error', e.message) }
}

async function newVersion(recipeId) {
  try {
    await api.newVersion(recipeId)
    notify('ok', '已创建新版本草稿（旧已发布版本仍在服务进行中的合成）。')
    await loadRecipes()
  } catch (e) { notify('error', e.message) }
}

function edit(recipe, v) {
  draft.value = {
    recipeId: recipe.recipeId,
    recipeCode: recipe.code,
    versionId: v.versionId,
    versionNo: v.versionNo,
    inputs: v.inputs.map(x => ({ ...x })),
    outputs: v.outputs.map(x => ({ ...x })),
    startTime: v.startTime || '',
    endTime: v.endTime || '',
    craftTimeoutSeconds: v.craftTimeoutSeconds || 120
  }
}

async function saveDraft() {
  try {
    await api.saveDraft(draft.value.versionId, {
      inputs: draft.value.inputs.filter(x => x.itemCode),
      outputs: draft.value.outputs.filter(x => x.itemCode),
      startTime: draft.value.startTime || null,
      endTime: draft.value.endTime || null,
      craftTimeoutSeconds: draft.value.craftTimeoutSeconds
    })
    notify('ok', '草稿已保存（尚未生效）。')
    await loadRecipes()
  } catch (e) { notify('error', `[${e.code}] ${e.message}`) }
}

async function publish(recipe, v) {
  try {
    await api.publish(recipe.recipeId, v.versionId)
    notify('ok', `v${v.versionNo} 已发布；旧版本已归档，进行中的单仍按旧版本结算。`)
    draft.value = null
    await loadRecipes()
  } catch (e) { notify('error', `[${e.code}] ${e.message}`) }
}

async function closeRecipe(recipeId) {
  try {
    await api.closeRecipe(recipeId, 'activity ended')
    notify('ok', '已下架：进行中的预占单仍可完成或超时释放，新合成被服务端拒绝。')
    await loadRecipes()
  } catch (e) { notify('error', e.message) }
}

async function doRevoke() {
  try {
    lastRevoke.value = await api.revoke(revokeOrderNo.value.trim())
    notify(lastRevoke.value.result === 'REVERSED' ? 'ok' : 'error',
      lastRevoke.value.result === 'REVERSED' ? '已生成反向流水并全额扣回。' : '材料已被使用，已进入异常清单。')
    await loadRevokes()
    await loadOpsData()
  } catch (e) { notify('error', `[${e.code}] ${e.message}`) }
}

async function doGrant() {
  try {
    const r = await api.grant(grantPlayerId.value, grantItemCode.value.trim(), grantQty.value)
    notify('ok', `已写入，GRANT 流水号 ${r.refNo}`)
  } catch (e) { notify('error', e.message) }
}

function versionClass(s) { return { PUBLISHED: 'ok', DRAFT: 'info', ARCHIVED: 'warn' }[s] || '' }
function versionText(s) { return { PUBLISHED: '已发布', DRAFT: '草稿', ARCHIVED: '已归档' }[s] || s }
function orderClass(s) { return { COMMITTED: 'ok', PREOCCUPIED: 'info', CANCELLED: 'warn', TIMEOUT: 'warn', REVOKED: 'bad' }[s] || '' }

// ---- batch plans / reconciliation / repair ----
function planClass(s) {
  return { COMPLETED: 'ok', RUNNING: 'info', PARTIAL: 'warn', CANCELLED: 'warn', FAILED: 'bad' }[s] || ''
}
function unitClass(s) {
  return { DONE: 'ok', PENDING: 'info', LEASED: 'info', SKIPPED: 'warn', FAILED: 'bad' }[s] || ''
}

async function loadPlans() {
  planList.value = await api.opPlans(showAnomalies.value ? 'anomalies' : 'all')
}

async function openPlan(planNo) {
  recon.value = await api.opReconcile(planNo)
  repairList.value = await api.opRepairs(planNo)
}

/** Only missing/extra balance discrepancies are append-only repairable. */
function canRepair(d) {
  return ['PRODUCE_MISSING', 'CONSUME_MISSING', 'PRODUCE_EXTRA'].includes(d.type) && !!d.itemCode
}

function repairDelta(d) {
  if (d.type === 'PRODUCE_MISSING') return (d.expected || 0) - (d.actual || 0)
  if (d.type === 'CONSUME_MISSING') return -((d.expected || 0) - (d.actual || 0))
  if (d.type === 'PRODUCE_EXTRA') return -((d.actual || 0) - (d.expected || 0))
  return 0
}

async function quickRepair(d) {
  const delta = repairDelta(d)
  if (!delta) { notify('error', '该差异数量为 0，无需补偿'); return }
  const msg = `确认对 ${d.itemCode} 追加一笔 ${delta > 0 ? '+' : ''}${delta} 的补偿流水？\n` +
    `历史流水不会被改写或删除。同一修复请求键可安全重试。`
  if (!confirm(msg)) return
  try {
    // Deterministic key per (plan, unit, type, item): clicking "补偿" repeatedly
    // for the SAME discrepancy replays the original and never double-posts.
    // After a successful repair the diff disappears (reconcile counts COMPENSATE).
    const key = 'fix-' + d.planNo + '-' + (d.unitNo || 0) + '-' + d.type + '-' + d.itemCode
    const r = await api.opRepair({
      planNo: d.planNo, unitNo: d.unitNo || null, diffType: d.type,
      itemCode: d.itemCode, qty: delta, remark: '运营台一键补偿'
    }, key)
    repairFlash.value = { type: 'ok', text: `已追加补偿流水 ${r.compRefNo}（${r.replayed ? '回放，未重复发' : '新增'}）` }
    await openPlan(d.planNo)
  } catch (e) {
    repairFlash.value = { type: 'error', text: `补偿失败 [${e.code}]：${e.message}` }
  }
}

onMounted(loadRecipes)
</script>
