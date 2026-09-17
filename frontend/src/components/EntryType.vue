<template>
  <span class="tag" :class="cls">{{ type }}{{ status === 'PENDING' ? '（挂账）' : '' }}</span>
</template>

<script setup>
import { computed } from 'vue'
const props = defineProps({ type: String, status: String })
const cls = computed(() => {
  if (props.status === 'PENDING') return 'warn'
  return {
    CONSUME: 'bad',
    PRODUCE: 'ok',
    RELEASE: 'info',
    GRANT: 'info',
    REVOKE: 'bad',
    REVOKE_PENDING: 'warn'
  }[props.type] || ''
})
</script>
