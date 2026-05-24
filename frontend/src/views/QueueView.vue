<template>
  <div>
    <h2>Download Queue</h2>

    <div v-if="allEmpty" class="empty">Queue is empty.</div>

    <section v-if="inProgress.length" class="section">
      <h3>In Progress</h3>
      <div v-for="item in inProgress" :key="item.id" class="queue-item active">
        <span class="spinner">⏳</span>
        <div class="item-info">
          <span class="type">{{ item.mediaType }} #{{ item.mediaId }}</span>
          <span class="sub">Copying…</span>
        </div>
      </div>
    </section>

    <section v-if="pending.length" class="section">
      <h3>Pending</h3>
      <div v-for="item in pending" :key="item.id" class="queue-item">
        <span class="pos">#{{ item.queuePosition }}</span>
        <div class="item-info">
          <span class="type">{{ item.mediaType }} #{{ item.mediaId }}</span>
          <span class="sub">Queued {{ formatDate(item.requestedAt) }}</span>
        </div>
      </div>
    </section>

    <section v-if="done.length" class="section">
      <h3>Completed</h3>
      <div v-for="item in done" :key="item.id" class="queue-item done">
        <span class="done-icon">✓</span>
        <div class="item-info">
          <span class="type">{{ item.mediaType }} #{{ item.mediaId }}</span>
          <span class="sub">{{ formatDate(item.completedAt) }}</span>
          <span v-if="item.status === 'ERROR'" class="error-msg">{{ item.errorMessage }}</span>
        </div>
        <template v-if="item.status === 'DONE'">
          <span v-if="item.tdarrStatus === 'NONE'"        class="tdarr-badge none">Queued in Tdarr</span>
          <span v-else-if="item.tdarrStatus === 'PROCESSING'"  class="tdarr-badge processing">Transcoding…</span>
          <span v-else-if="item.tdarrStatus === 'TRANSCODED'"  class="tdarr-badge transcoded">Transcoded ✓</span>
          <span v-else-if="item.tdarrStatus === 'TDARR_ERROR'" class="tdarr-badge tdarr-error">
            Tdarr error<span v-if="item.tdarrError"> — {{ item.tdarrError }}</span>
          </span>
        </template>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { useDownloadStore } from '@/stores/download.js'

const dlStore = useDownloadStore()

const inProgress = computed(() => dlStore.queueItems.filter(i => i.status === 'IN_PROGRESS'))
const pending    = computed(() => dlStore.queueItems.filter(i => i.status === 'PENDING'))
const done       = computed(() => dlStore.queueItems.filter(i => i.status === 'DONE' || i.status === 'ERROR'))
const allEmpty   = computed(() => inProgress.value.length === 0 && pending.value.length === 0 && done.value.length === 0)

function formatDate(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString()
}

let pollTimer = null
onMounted(async () => {
  await dlStore.fetchQueue()
  pollTimer = setInterval(() => dlStore.fetchQueue(), 2000)
})
onUnmounted(() => clearInterval(pollTimer))
</script>

<style scoped>
h2 { font-size: 1.5rem; font-weight: 600; margin-bottom: 24px; }
.empty { color: var(--text-muted); padding: 40px 0; text-align: center; }
.section { margin-bottom: 32px; }
h3 { font-size: 1rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase;
     letter-spacing: .05em; margin-bottom: 12px; padding-bottom: 8px;
     border-bottom: 1px solid var(--border); }
.queue-item { display: flex; align-items: center; gap: 16px; padding: 12px 16px;
              background: var(--surface2); border-radius: 8px; margin-bottom: 8px; }
.queue-item.active { border-left: 3px solid var(--accent-blue); }
.queue-item.done   { opacity: 0.65; }
.spinner, .done-icon { font-size: 1.2rem; }
.pos { font-size: 1rem; color: var(--text-muted); min-width: 28px; }
.item-info { display: flex; flex-direction: column; gap: 2px; flex: 1; }
.type { font-weight: 500; }
.sub  { font-size: .8rem; color: var(--text-muted); }
.error-msg { font-size: .8rem; color: var(--red); }
.tdarr-badge { font-size: .75rem; font-weight: 600; padding: 2px 8px; border-radius: 10px;
               white-space: nowrap; }
.tdarr-badge.none       { background: var(--surface); color: var(--text-muted);
                           border: 1px solid var(--border); }
.tdarr-badge.processing { background: rgba(52,152,219,.15); color: var(--accent-blue); }
.tdarr-badge.transcoded { background: rgba(39,174,96,.15); color: var(--green); }
.tdarr-badge.tdarr-error { background: rgba(231,76,60,.15); color: var(--red); }
</style>
