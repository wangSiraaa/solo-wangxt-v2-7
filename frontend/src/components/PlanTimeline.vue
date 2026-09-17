<template>
  <div>
    <div class="row" style="justify-content:space-between">
      <span class="small muted">逐序号时间线（实时）</span>
      <button class="ghost small" @click="load">立即刷新</button>
    </div>
    <table style="margin-top:6px">
      <thead>
      <tr>
        <th>#</th><th>状态</th><th>合成单号</th><th>执行节点</th><th>时间(UTC)</th>
      </tr>
      </thead>
      <tbody>
      <tr v-for="u in detail?.units || []" :key="u.unitNo">
        <td class="right">{{ u.unitNo }}</td>
        <td><span class="tag" :class="unitClass(u.status)">{{ unitText(u) }}</span></td>
        <td class="mono small">{{ u.orderNo ? short(u.orderNo) : '—' }}</td>
        <td class="small muted">{{ phase(u) }}</td>
        <td class="small muted">{{ timeline(u) }}</td>
      </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { api } from '../api'

const props = defineProps({ planNo: String, refreshKey: Number })
const detail = ref(null)
let timer

const short = (no) => no ? no.slice(0, 10) + '…' : ''
const fmt = (t) => t ? new Date(t).toISOString().replace('T', ' ').slice(0, 19) : ''

async function load() {
  if (!props.planNo) return
  detail.value = await api.planDetail(props.planNo)
}

function unitClass(s) {
  return { DONE: 'ok', PENDING: 'info', LEASED: 'info', SKIPPED: 'warn', FAILED: 'bad' }[s] || ''
}
function unitText(u) {
  if (u.status === 'SKIPPED' && u.skipReason) return `SKIPPED · ${u.skipReason}`
  if (u.status === 'FAILED' && u.failReason) return `FAILED · ${u.failReason}`
  if (u.status === 'LEASED') return `LEASED · ${u.leaseOwner || ''}`
  return u.status
}
function phase(u) {
  if (u.status === 'PENDING') return '等待领取'
  if (u.status === 'SKIPPED') return '未启动'
  const parts = []
  parts.push(u.consumedAt ? '已扣料' : '未扣料')
  parts.push(u.rewardedAt ? '已发奖' : '未发奖')
  if (u.status === 'DONE') parts.push('已回写')
  return parts.join(' → ')
}
function timeline(u) {
  const pts = []
  if (u.consumedAt) pts.push('扣料 ' + fmt(u.consumedAt))
  if (u.rewardedAt) pts.push('发奖 ' + fmt(u.rewardedAt))
  if (u.finishedAt) pts.push('结束 ' + fmt(u.finishedAt))
  return pts.join(' ｜ ')
}

watch(() => [props.planNo, props.refreshKey], load)
onMounted(async () => {
  await load()
  timer = setInterval(load, 1500)
})
onUnmounted(() => clearInterval(timer))
</script>
