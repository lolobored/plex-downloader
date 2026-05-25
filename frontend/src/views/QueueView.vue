<template>
  <div>
    <h2>Download Queue</h2>

    <div class="filter-bar">
      <div class="filter-group">
        <button type="button" data-testid="chip-type-ALL"
                class="chip" :class="{ active: typeFilter === 'ALL' }"
                @click="setTypeFilter('ALL')">All</button>
        <button type="button" data-testid="chip-type-MOVIE"
                class="chip" :class="{ active: typeFilter === 'MOVIE' }"
                @click="setTypeFilter('MOVIE')">Movie</button>
        <button type="button" data-testid="chip-type-TV"
                class="chip" :class="{ active: typeFilter === 'TV' }"
                @click="setTypeFilter('TV')">TV</button>
      </div>
      <div class="filter-group">
        <button v-for="s in STATUS_CHIPS" :key="s.key"
                type="button"
                :data-testid="'chip-status-' + s.key"
                class="chip" :class="{ active: statusFilter.has(s.key) }"
                @click="toggleStatusFilter(s.key)">{{ s.label }}</button>
      </div>
      <input data-testid="filter-search" v-model="textFilter"
             type="search" class="filter-search" placeholder="Search…" />
    </div>

    <div v-if="allEmpty" class="empty">Queue is empty.</div>

    <section v-if="inProgress.length" class="section">
      <h3>In Progress</h3>
      <div v-for="item in inProgress" :key="item.id" class="queue-item active">
        <span class="spinner">⏳</span>
        <div class="item-info">
          <span class="type">{{ item.title || (item.mediaType + ' #' + item.mediaId) }}</span>
          <span class="sub">Copying…</span>
        </div>
        <button :data-testid="'remove-btn-' + item.id" class="btn-remove"
          :disabled="true" title="Wait for copy to finish">✕</button>
      </div>
    </section>

    <section v-if="pending.length" class="section">
      <h3>Pending</h3>
      <div v-for="item in pending" :key="item.id" class="queue-item">
        <span class="pos">#{{ item.queuePosition }}</span>
        <div class="item-info">
          <span class="type">{{ item.title || (item.mediaType + ' #' + item.mediaId) }}</span>
          <span class="sub">Queued {{ formatDate(item.requestedAt) }}</span>
        </div>
        <button :data-testid="'remove-btn-' + item.id" class="btn-remove"
          :disabled="removing.has(item.id)" title="Remove"
          @click="remove(item.id)">{{ removing.has(item.id) ? '…' : '✕' }}</button>
      </div>
    </section>

    <section v-if="done.length" class="section">
      <h3>Completed</h3>
      <div v-for="item in done" :key="item.id" class="queue-item done">
        <span class="done-icon">✓</span>
        <div class="item-info">
          <span class="type">{{ item.title || (item.mediaType + ' #' + item.mediaId) }}</span>
          <span class="sub">{{ formatDate(item.completedAt) }}</span>
          <span v-if="item.status === 'ERROR'" class="error-msg">{{ item.errorMessage }}</span>
        </div>
        <template v-if="item.status === 'DONE'">
          <span v-if="!item.tdarrStatus || item.tdarrStatus === 'NONE'" class="tdarr-badge none">Queued in Tdarr</span>
          <span v-else-if="item.tdarrStatus === 'PROCESSING'"  class="tdarr-badge processing">Transcoding…</span>
          <span v-else-if="item.tdarrStatus === 'TRANSCODED'"  class="tdarr-badge transcoded">Transcoded ✓</span>
          <button :data-testid="'tdarr-refresh-btn-' + item.id"
                  class="btn-tdarr-refresh"
                  :disabled="refreshing.has(item.id)"
                  :title="refreshing.has(item.id) ? 'Refreshing…' : 'Check Tdarr status'"
                  @click="refreshTdarr(item.id)">
            {{ refreshing.has(item.id) ? '…' : '↻' }}
          </button>
        </template>
        <span v-if="item.status === 'ERROR' && item.tdarrStatus === 'TDARR_ERROR'" class="tdarr-badge tdarr-error">
          Tdarr error<span v-if="item.tdarrError"> — {{ item.tdarrError }}</span>
        </span>
        <button v-if="item.status === 'ERROR' && item.tdarrStatus === 'TDARR_ERROR'"
                type="button"
                class="btn-retry"
                data-testid="retry-btn"
                :disabled="retrying.has(item.id)"
                @click="retryItem(item.id)">
          {{ retrying.has(item.id) ? '…' : '⟳ Retry' }}
        </button>
        <button :data-testid="'remove-btn-' + item.id" class="btn-remove"
          :disabled="removing.has(item.id)" title="Remove"
          @click="remove(item.id)">{{ removing.has(item.id) ? '…' : '✕' }}</button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useDownloadStore } from '@/stores/download.js'
import { removeQueueItem, refreshTdarrStatus, retryQueueItem } from '@/api/download.js'

const dlStore = useDownloadStore()
const removing    = ref(new Set())
const refreshing  = ref(new Set())
const retrying    = ref(new Set())

// ── Filter state ─────────────────────────────────────────────────────────────
const typeFilter   = ref('ALL')      // 'ALL' | 'MOVIE' | 'TV'
const statusFilter = ref(new Set())  // Set<string>
const textFilter   = ref('')

const STATUS_CHIPS = [
  { key: 'PENDING',     label: 'Pending' },
  { key: 'COPYING',     label: 'Copying' },
  { key: 'DONE',        label: 'Done' },
  { key: 'TRANSCODING', label: 'Transcoding' },
  { key: 'TRANSCODED',  label: 'Transcoded' },
  { key: 'ERROR',       label: 'Error' },
]

function matchesType(item) {
  if (typeFilter.value === 'ALL') return true
  if (typeFilter.value === 'MOVIE') return item.mediaType === 'MOVIE'
  if (typeFilter.value === 'TV')    return ['EPISODE', 'SEASON', 'SHOW'].includes(item.mediaType)
  return true
}

function matchesStatus(item) {
  if (statusFilter.value.size === 0) return true
  const a = statusFilter.value
  if (a.has('PENDING')     && item.status === 'PENDING') return true
  if (a.has('COPYING')     && item.status === 'IN_PROGRESS') return true
  if (a.has('DONE')        && item.status === 'DONE' && (!item.tdarrStatus || item.tdarrStatus === 'NONE')) return true
  if (a.has('TRANSCODING') && item.tdarrStatus === 'PROCESSING') return true
  if (a.has('TRANSCODED')  && item.tdarrStatus === 'TRANSCODED') return true
  if (a.has('ERROR')       && (item.status === 'ERROR' || item.tdarrStatus === 'TDARR_ERROR')) return true
  return false
}

function matchesText(item) {
  const q = textFilter.value.trim().toLowerCase()
  if (!q) return true
  const displayTitle = item.title || `${item.mediaType} #${item.mediaId}`
  return [displayTitle, item.mediaType, item.errorMessage, item.tdarrError]
    .filter(Boolean)
    .some(s => s.toLowerCase().includes(q))
}

function setTypeFilter(type) {
  typeFilter.value = typeFilter.value === type ? 'ALL' : type
}

function toggleStatusFilter(status) {
  const next = new Set(statusFilter.value)
  if (next.has(status)) next.delete(status)
  else next.add(status)
  statusFilter.value = next
}

// ── Actions ───────────────────────────────────────────────────────────────────
async function remove(id) {
  removing.value = new Set([...removing.value, id])
  try {
    await removeQueueItem(id)
    await dlStore.fetchQueue()
  } finally {
    const next = new Set(removing.value)
    next.delete(id)
    removing.value = next
  }
}

async function refreshTdarr(id) {
  refreshing.value = new Set([...refreshing.value, id])
  try {
    await refreshTdarrStatus(id)
    await dlStore.fetchQueue()
  } finally {
    const next = new Set(refreshing.value)
    next.delete(id)
    refreshing.value = next
  }
}

async function retryItem(id) {
  retrying.value = new Set([...retrying.value, id])
  try {
    await retryQueueItem(id)
    await dlStore.fetchQueue()
  } catch (e) {
    console.error('Retry failed', e)
  } finally {
    const next = new Set(retrying.value)
    next.delete(id)
    retrying.value = next
  }
}

// ── Filtered section lists ────────────────────────────────────────────────────
const inProgress = computed(() =>
  dlStore.queueItems
    .filter(i => i.status === 'IN_PROGRESS')
    .filter(matchesType)
    .filter(matchesText)
    .filter(matchesStatus)
)
const pending = computed(() =>
  dlStore.queueItems
    .filter(i => i.status === 'PENDING')
    .filter(matchesType)
    .filter(matchesText)
    .filter(matchesStatus)
)
const done = computed(() =>
  dlStore.queueItems
    .filter(i => i.status === 'DONE' || i.status === 'ERROR')
    .filter(matchesType)
    .filter(matchesText)
    .filter(matchesStatus)
)
const allEmpty = computed(() =>
  inProgress.value.length === 0 && pending.value.length === 0 && done.value.length === 0
)

function formatDate(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString()
}

let pollTimer = null
onMounted(async () => {
  try {
    await dlStore.fetchQueue()
  } catch (e) {
    console.error('Initial queue fetch failed:', e)
  }
  pollTimer = setInterval(() => dlStore.fetchQueue(), 2000)
})
onUnmounted(() => clearInterval(pollTimer))
</script>

<style scoped>
h2 { font-size: 1.5rem; font-weight: 600; margin-bottom: 24px; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 24px; }
.filter-group { display: flex; gap: 6px; flex-wrap: wrap; }
.chip { background: var(--surface2); border: 1px solid var(--border); color: var(--text-muted);
        border-radius: 16px; padding: 4px 12px; font-size: .8rem; cursor: pointer;
        transition: background .15s, color .15s, border-color .15s; }
.chip:hover:not(.active) { border-color: var(--accent-blue); color: var(--text); }
.chip.active { background: var(--accent-blue); border-color: var(--accent-blue); color: #fff; }
.filter-search { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
                 border-radius: 16px; padding: 4px 12px; font-size: .8rem; outline: none;
                 min-width: 160px; }
.filter-search:focus { border-color: var(--accent-blue); }
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
.btn-remove { background: none; border: none; color: var(--text-muted); cursor: pointer;
              font-size: 1rem; padding: 4px 8px; border-radius: 4px; margin-left: auto; }
.btn-remove:hover:not(:disabled) { color: var(--red); background: rgba(231,76,60,.1); }
.btn-remove:disabled { opacity: 0.3; cursor: not-allowed; }
.btn-tdarr-refresh { background: none; border: none; color: var(--text-muted); cursor: pointer;
                     font-size: .85rem; padding: 2px 6px; border-radius: 4px; }
.btn-tdarr-refresh:hover:not(:disabled) { color: var(--accent-blue); background: rgba(52,152,219,.1); }
.btn-tdarr-refresh:disabled { opacity: 0.3; cursor: not-allowed; }
.btn-retry { background: none; border: 1px solid var(--red); color: var(--red); cursor: pointer;
             font-size: .8rem; padding: 2px 8px; border-radius: 4px; }
.btn-retry:hover { background: rgba(231,76,60,.1); }
</style>
