<template>
  <span :class="cls">{{ text }}</span>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'

const props = defineProps({ deadline: String })
const emit = defineEmits(['expired'])
const now = ref(Date.now())
let t

const remainMs = computed(() => new Date(props.deadline).getTime() - now.value)
const expired = computed(() => remainMs.value <= 0)
const text = computed(() => {
  const s = Math.max(0, Math.floor(remainMs.value / 1000))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
})
const cls = computed(() => expired.value ? 'tag bad' : remainMs.value < 30000 ? 'tag warn' : 'tag info')

onMounted(() => {
  t = setInterval(() => {
    now.value = Date.now()
    if (expired.value) emit('expired')
  }, 1000)
})
onUnmounted(() => clearInterval(t))
</script>
