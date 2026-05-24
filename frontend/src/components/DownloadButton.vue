<template>
  <button
    class="dl-btn"
    :class="[`status-${status ?? 'idle'}`, { small }]"
    :disabled="status === 'PENDING' || status === 'IN_PROGRESS' || status === 'DONE'"
    @click.stop="handleClick"
    :title="label"
  >
    {{ icon }} <span v-if="!small">{{ label }}</span>
  </button>
</template>

<script setup>
import { computed } from 'vue'
import { useDownloadStore } from '@/stores/download.js'

const props = defineProps({
  type:    { type: String, required: true },
  mediaId: { type: Number, required: true },
  small:   { type: Boolean, default: false }
})

const dlStore = useDownloadStore()

const status = computed(() => dlStore.statusFor(props.type, props.mediaId))

const icon = computed(() => {
  switch (status.value) {
    case 'PENDING':     return '⏳'
    case 'IN_PROGRESS': return '⏳'
    case 'DONE':        return '✓'
    case 'ERROR':       return '✗'
    default:            return '⬇'
  }
})

const label = computed(() => {
  switch (status.value) {
    case 'PENDING':     return 'In Queue'
    case 'IN_PROGRESS': return 'Copying…'
    case 'DONE':        return 'Done'
    case 'ERROR':       return 'Error'
    default:            return 'Download'
  }
})

async function handleClick() {
  if (!status.value) {
    try {
      await dlStore.enqueue(props.type, props.mediaId)
    } catch (e) {
      console.error('Enqueue failed', e)
    }
  }
}
</script>

<style scoped>
.dl-btn { display: inline-flex; align-items: center; gap: 6px; border: none;
          border-radius: 6px; padding: 8px 16px; font-size: .9rem; font-weight: 600;
          transition: opacity .15s; }
.dl-btn.small { padding: 4px 8px; font-size: .8rem; border-radius: 4px; }
.dl-btn:disabled { cursor: default; }
.status-idle        { background: var(--accent); color: #000; }
.status-idle:hover  { opacity: .85; }
.status-PENDING     { background: var(--accent-blue); color: #fff; }
.status-IN_PROGRESS { background: var(--accent-blue); color: #fff; }
.status-DONE        { background: var(--green); color: #fff; }
.status-ERROR       { background: var(--red); color: #fff; }
</style>
