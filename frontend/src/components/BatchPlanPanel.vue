<template>
  <div class="panel">
    <div class="row" style="justify-content:space-between">
      <h2 style="margin:0">批量合成计划（1–100 次，服务端 worker 执行）</h2>
      <button class="secondary" @click="reload">刷新</button>
    </div>
    <p class="muted small">
      一次提交多次合成，服务端创建计划时冻结配方版本与每次材料/产出快照；后台 worker 用数据库租约逐序号执行，
      前端<b>不</b>循环调用单次合成。同键并发只会产生一个计划。活动结束或材料不足时不再启动新序号，已预占序号仍按绑定版本完成。
    </p>

    <div class="row" style="gap:10px;align-items:flex-end;flex-wrap:wrap">
      <label class="small muted">
        配方
        <select v-model.number="form.recipeId" style="display:block;min-width:240px;margin-top:4px">
          <option v-for="r in recipes" :key="r.recipeId" :value="r.recipeId">
            {{ r.recipeName }}（{{ r.recipeCode }}）v{{ r.versionNo ?? '—' }}
          </option>
        </select>
      </label>
      <label class="small muted">
        次数（1–100）
        <input v-model.number="form.count" type="number" min="1" max="100"
               style="display:block;width:120px;margin-top:4px" />
      </label>
      <button :disabled="busy" @click="createPlan">创建批量计划</button>
      <span class="small muted">每次材料以创建时快照为准；结果见下方实时进度。</span>
    </div>

    <div v-for="p in plans" :key="p.planNo" class="panel"
         style="background:var(--panel2);margin-top:12px">
      <div class="row" style="justify-content:space-between">
        <div>
          <strong>{{ p.recipeName }}</strong>
          <span class="mono muted small" style="margin-left:6px">{{ short(p.planNo) }}</span>
          <span class="tag" :class="planStatusClass(p.status)" style="margin-left:8px">{{ p.status }}</span>
          <span v-if="p.stopFlag" class="tag warn" style="margin-left:6px">
            已停止新序号{{ p.stopReason ? '（' + p.stopReason + '）' : '' }}
          </span>
        </div>
        <div class="row">
          <button class="secondary" @click="toggle(p.planNo)">
            {{ expanded === p.planNo ? '收起' : '逐序号时间线' }}
          </button>
          <button class="danger" :disabled="busy || p.status !== 'RUNNING'"
                  @click="cancelPlan(p)">取消（仅未开始序号）</button>
        </div>
      </div>

      <div class="muted small" style="margin:6px 0">
        绑定版本 v{{ p.boundVersionNo }} · 共 {{ p.totalCount }} 次 ·
        创建 {{ fmt(p.createdAt) }}
        <template v-if="p.finishedAt"> · 结束 {{ fmt(p.finishedAt) }}</template>
      </div>

      <div style="margin:8px 0">
        <div class="progress">
          <div class="seg done" :style="{ width: pct(p.completedCount, p.totalCount) + '%' }"></div>
          <div class="seg skipped" :style="{ width: pct(p.skippedCount, p.totalCount) + '%' }"></div>
          <div class="seg failed" :style="{ width: pct(p.failedCount, p.totalCount) + '%' }"></div>
        </div>
        <div class="small muted" style="margin-top:4px">
          <span class="tag ok">已完成 {{ p.completedCount }}</span>
          <span class="tag warn" style="margin-left:6px">未执行/跳过 {{ p.skippedCount }}</span>
          <span class="tag bad" style="margin-left:6px">失败 {{ p.failedCount }}</span>
          <span class="tag info" style="margin-left:6px">进行中/待执行 {{ p.notStartedCount }}</span>
        </div>
      </div>

      <div v-if="expanded === p.planNo">
        <PlanTimeline :plan-no="p.planNo" :refresh-key="refreshKey" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, watch } from 'vue'
import { api, idemKey } from '../api'
import PlanTimeline from './PlanTimeline.vue'

const props = defineProps({ recipes: { type: Array, default: () => [] } })
const emit = defineEmits(['changed'])

const plans = ref([])
const form = reactive({ recipeId: null, count: 3 })
const busy = ref(false)
const expanded = ref('')
const refreshKey = ref(0)

watch(() => props.recipes, (list) => {
  if (form.recipeId == null && list.length) {
    const pick = list.find(r => r.craftable) || list[0]
    form.recipeId = pick.recipeId
  }
}, { immediate: true })

const short = (no) => no ? no.slice(0, 10) + '…' : ''
const fmt = (t) => t ? new Date(t).toISOString().replace('T', ' ').slice(0, 19) : '—'
const pct = (n, total) => (total ? Math.max(0, Math.min(100, (n / total) * 100)) : 0)

function planStatusClass(s) {
  return {
    COMPLETED: 'ok', RUNNING: 'info', PARTIAL: 'warn',
    CANCELLED: 'warn', FAILED: 'bad'
  }[s] || ''
}

async function reload() {
  plans.value = await api.myPlans()
  refreshKey.value++
}

function toggle(no) {
  expanded.value = expanded.value === no ? '' : no
}

async function createPlan() {
  busy.value = true
  try {
    const res = await api.createPlan(form.recipeId, form.count, idemKey())
    expanded.value = res.planNo
    await reload()
    emit('changed')
  } catch (e) {
    alert(`创建被服务端拒绝 [${e.code || e.status}]：${e.message}`)
  } finally {
    busy.value = false
  }
}

async function cancelPlan(p) {
  if (!confirm(`确认取消计划 ${p.planNo}？只有尚未开始的序号会被取消，已完成奖励不回滚。`)) return
  busy.value = true
  try {
    await api.cancelPlan(p.planNo, 'player cancelled from console')
    await reload()
    emit('changed')
  } catch (e) {
    alert('取消失败：' + e.message)
  } finally {
    busy.value = false
  }
}

defineExpose({ reload })
</script>

<style scoped>
.progress {
  display: flex;
  height: 14px;
  border-radius: 7px;
  overflow: hidden;
  background: #232a36;
}
.seg { height: 100%; transition: width .3s; }
.seg.done { background: #2f9e63; }
.seg.skipped { background: #c08b2d; }
.seg.failed { background: #c0473e; }
</style>
