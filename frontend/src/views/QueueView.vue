<template>
  <div>
    <h2>Download Queue <span v-if="totalVisible > 0" class="count-badge" data-testid="count-badge">{{ totalVisible }}</span></h2>

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

    <div v-if="dlStore.queueItems.length === 0" class="empty">Queue is empty.</div>
    <div v-else-if="totalVisible === 0" class="empty">No items match filters.</div>

    <!-- ── Subscribed Playlists ── -->
    <section v-if="playlistGroups.length" data-testid="section-playlists" class="tree-section">
      <h3 class="section-label">SUBSCRIBED PLAYLISTS</h3>

      <div v-for="group in playlistGroups" :key="group.playlistId" class="group">
        <div class="group-header"
             :data-testid="'group-header-playlist-' + group.playlistId"
             @click="toggleGroup('playlist-' + group.playlistId)">
          <span class="chevron">{{ isOpen('playlist-' + group.playlistId) ? '▼' : '▶' }}</span>
          <span class="group-title">📋 {{ group.playlistTitle }}</span>
          <span class="group-count">{{ group.items.length }}</span>
          <button class="btn-unsub"
                  :data-testid="'unsub-btn-' + group.playlistId"
                  @click.stop="handleUnsubscribeClick(group.playlistId, group.playlistTitle)">
            Unsubscribe
          </button>
        </div>

        <div v-if="isOpen('playlist-' + group.playlistId)" class="group-body">
          <template v-for="bucket in [buckets(group.items)]" :key="'b'">
            <div v-if="bucket.inProgress.length" class="sub-section">
              <p class="sub-label" data-testid="sub-label-IN_PROGRESS">IN PROGRESS</p>
              <QueueItemRow v-for="item in bucket.inProgress" :key="item.id"
                :item="item" :removing="removing" :retrying="retrying"
                @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
            </div>
            <div v-if="bucket.pending.length" class="sub-section">
              <p class="sub-label" data-testid="sub-label-PENDING">PENDING</p>
              <QueueItemRow v-for="item in bucket.pending" :key="item.id"
                :item="item" :removing="removing" :retrying="retrying"
                @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
            </div>
            <div v-if="bucket.done.length" class="sub-section">
              <p class="sub-label" data-testid="sub-label-DONE">DONE</p>
              <QueueItemRow v-for="item in bucket.done" :key="item.id"
                :item="item" :removing="removing" :retrying="retrying"
                @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
            </div>
          </template>
        </div>
      </div>
    </section>

    <!-- ── Individual Downloads ── -->
    <section v-if="showGroups.length || soloMovies.length" data-testid="section-individual" class="tree-section">
      <h3 class="section-label">INDIVIDUAL DOWNLOADS</h3>

      <!-- Show groups -->
      <div v-for="sg in showGroups" :key="sg.showId" class="group">
        <div class="group-header"
             :data-testid="'group-header-show-' + sg.showId"
             @click="toggleGroup('show-' + sg.showId)">
          <span class="chevron">{{ isOpen('show-' + sg.showId) ? '▼' : '▶' }}</span>
          <span class="group-title">📺 {{ sg.showTitle }}</span>
          <span class="group-count">{{ sg.seasons.reduce((n, s) => n + s.items.length, 0) }}</span>
        </div>

        <div v-if="isOpen('show-' + sg.showId)" class="group-body">
          <div v-for="season in sg.seasons" :key="season.seasonId" class="sub-group">
            <div class="sub-group-header"
                 :data-testid="'group-header-season-' + season.seasonId"
                 @click.stop="toggleGroup('season-' + season.seasonId)">
              <span class="chevron">{{ isOpen('season-' + season.seasonId) ? '▼' : '▶' }}</span>
              <span>Season {{ season.seasonNumber }}</span>
              <span class="group-count">{{ season.items.length }}</span>
            </div>

            <div v-if="isOpen('season-' + season.seasonId)" class="sub-group-body">
              <template v-for="bucket in [buckets(season.items)]" :key="'b'">
                <div v-if="bucket.inProgress.length" class="sub-section">
                  <p class="sub-label" data-testid="sub-label-IN_PROGRESS">IN PROGRESS</p>
                  <QueueItemRow v-for="item in bucket.inProgress" :key="item.id"
                    :item="item" :removing="removing" :retrying="retrying"
                    @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
                </div>
                <div v-if="bucket.pending.length" class="sub-section">
                  <p class="sub-label" data-testid="sub-label-PENDING">PENDING</p>
                  <QueueItemRow v-for="item in bucket.pending" :key="item.id"
                    :item="item" :removing="removing" :retrying="retrying"
                    @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
                </div>
                <div v-if="bucket.done.length" class="sub-section">
                  <p class="sub-label" data-testid="sub-label-DONE">DONE</p>
                  <QueueItemRow v-for="item in bucket.done" :key="item.id"
                    :item="item" :removing="removing" :retrying="retrying"
                    @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
                </div>
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- Solo movies (always visible, no group wrapper) -->
      <QueueItemRow v-for="item in soloMovies" :key="item.id"
        :item="item" :removing="removing" :retrying="retrying"
        @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
    </section>

    <ConfirmModal
      v-if="confirmState"
      :message="confirmState.message"
      confirmLabel="Yes, unsubscribe"
      cancelLabel="Keep"
      @confirm="confirmUnsubscribe"
      @cancel="confirmState = null"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, defineComponent, h } from 'vue'
import { useRouter } from 'vue-router'
import { useDownloadStore } from '@/stores/download.js'
import { removeQueueItem, retryQueueItem } from '@/api/download.js'
import { unsubscribe, getPlaylistQueueCount } from '@/api/playlists.js'
import ConfirmModal from '@/components/ConfirmModal.vue'

const router  = useRouter()
const dlStore = useDownloadStore()
const removing  = ref(new Set())
const retrying  = ref(new Set())
const openGroups = ref(new Set())
const confirmState = ref(null) // { playlistId, message } | null

// ── Inline QueueItemRow component ────────────────────────────────────────────
const QueueItemRow = defineComponent({
  props: ['item', 'removing', 'retrying'],
  emits: ['remove', 'retry', 'navigate'],
  setup(props, { emit }) {
    return () => {
      const item = props.item
      const isRemoving = props.removing.has(item.id)
      const isRetrying = props.retrying.has(item.id)
      const isInProgress = item.status === 'IN_PROGRESS'
      const isTdarrError = item.tdarrStatus === 'TDARR_ERROR'

      let statusLabel = 'pending'
      if (item.status === 'IN_PROGRESS') statusLabel = 'copying…'
      else if (item.status === 'ERROR' || isTdarrError) statusLabel = 'error'
      else if (item.status === 'DONE') statusLabel = 'done'

      return h('div', {
        class: ['queue-item', 'clickable', isInProgress ? 'active' : '', (item.status === 'DONE' || item.status === 'ERROR') ? 'done' : ''].filter(Boolean).join(' '),
        'data-testid': 'queue-item-row',
        onClick: () => emit('navigate', item)
      }, [
        h('div', { class: 'item-info' }, [
          h('span', { class: 'type' }, item.title || `${item.mediaType} #${item.mediaId}`),
          h('span', { class: 'sub' }, statusLabel),
          item.status === 'ERROR' && item.errorMessage
            ? h('span', { class: 'error-msg' }, item.errorMessage)
            : null,
          isTdarrError && item.tdarrError
            ? h('span', { class: 'error-msg' }, item.tdarrError)
            : null,
        ].filter(Boolean)),
        isTdarrError
          ? h('button', {
              class: 'btn-retry',
              'data-testid': 'retry-btn',
              disabled: isRetrying,
              onClick: (e) => { e.stopPropagation(); emit('retry', item.id) }
            }, isRetrying ? '…' : '⟳ Retry')
          : null,
        h('button', {
          class: 'btn-remove',
          'data-testid': `remove-btn-${item.id}`,
          disabled: isRemoving || isInProgress,
          title: isInProgress ? 'Wait for copy to finish' : 'Remove',
          onClick: (e) => { e.stopPropagation(); emit('remove', item.id) }
        }, isRemoving ? '…' : '✕'),
      ].filter(Boolean))
    }
  }
})

// ── Filter state ──────────────────────────────────────────────────────────────
const typeFilter   = ref('ALL')
const statusFilter = ref(new Set())
const textFilter   = ref('')

const STATUS_CHIPS = [
  { key: 'PENDING', label: 'Pending' },
  { key: 'COPYING', label: 'Copying' },
  { key: 'DONE',    label: 'Done'    },
  { key: 'ERROR',   label: 'Error'   },
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
  if (a.has('PENDING') && item.status === 'PENDING') return true
  if (a.has('COPYING') && item.status === 'IN_PROGRESS') return true
  if (a.has('DONE') && item.status === 'DONE' && item.tdarrStatus !== 'TDARR_ERROR') return true
  if (a.has('ERROR') && (item.status === 'ERROR' || item.tdarrStatus === 'TDARR_ERROR')) return true
  return false
}
function matchesText(item) {
  const q = textFilter.value.trim().toLowerCase()
  if (!q) return true
  const display = item.title || `${item.mediaType} #${item.mediaId}`
  return [display, item.mediaType, item.errorMessage, item.tdarrError, item.showTitle, item.playlistTitle]
    .filter(Boolean).some(s => s.toLowerCase().includes(q))
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

// ── Tree computation ──────────────────────────────────────────────────────────
const filteredItems = computed(() =>
  dlStore.queueItems.filter(matchesType).filter(matchesStatus).filter(matchesText)
)

const totalVisible = computed(() => filteredItems.value.length)

const playlistGroups = computed(() => {
  const map = new Map()
  for (const item of filteredItems.value) {
    if (item.playlistId == null) continue
    if (!map.has(item.playlistId)) {
      map.set(item.playlistId, {
        playlistId: item.playlistId,
        playlistTitle: item.playlistTitle || `Playlist ${item.playlistId}`,
        items: []
      })
    }
    map.get(item.playlistId).items.push(item)
  }
  return [...map.values()].sort((a, b) =>
    (a.playlistTitle || '').localeCompare(b.playlistTitle || ''))
})

const individualItems = computed(() =>
  filteredItems.value.filter(i => i.playlistId == null)
)

// Note: only EPISODE mediaType goes into show groups. MOVIE goes to soloMovies.
// SEASON and SHOW mediaTypes are not individually enqueued by the backend (bulk only),
// so no catch-all bucket is needed here.
const showGroups = computed(() => {
  const map = new Map()
  for (const item of individualItems.value) {
    if (item.mediaType !== 'EPISODE') continue
    if (!map.has(item.showId)) {
      map.set(item.showId, {
        showId: item.showId,
        showTitle: item.showTitle || `Show ${item.showId}`,
        seasonMap: new Map()
      })
    }
    const sg = map.get(item.showId)
    if (!sg.seasonMap.has(item.seasonId)) {
      sg.seasonMap.set(item.seasonId, {
        seasonId: item.seasonId,
        seasonNumber: item.seasonNumber,
        items: []
      })
    }
    sg.seasonMap.get(item.seasonId).items.push(item)
  }
  return [...map.values()]
    .map(sg => ({
      ...sg,
      seasons: [...sg.seasonMap.values()]
        .sort((a, b) => (a.seasonNumber || 0) - (b.seasonNumber || 0))
    }))
    .sort((a, b) => (a.showTitle || '').localeCompare(b.showTitle || ''))
})

const soloMovies = computed(() =>
  individualItems.value
    .filter(i => i.mediaType === 'MOVIE')
    .sort((a, b) => (a.title || '').localeCompare(b.title || ''))
)

// ── Bucket helper ─────────────────────────────────────────────────────────────
function buckets(items) {
  return {
    inProgress: items.filter(i => i.status === 'IN_PROGRESS'),
    pending:    items.filter(i => i.status === 'PENDING'),
    done:       items.filter(i => i.status === 'DONE' || i.status === 'ERROR'),
  }
}

// ── Collapse / expand ─────────────────────────────────────────────────────────
function toggleGroup(key) {
  const next = new Set(openGroups.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  openGroups.value = next
}
function isOpen(key) { return openGroups.value.has(key) }

// ── Actions ───────────────────────────────────────────────────────────────────
async function remove(id) {
  removing.value = new Set([...removing.value, id])
  try {
    await removeQueueItem(id)
    await dlStore.fetchQueue()
  } finally {
    const next = new Set(removing.value); next.delete(id); removing.value = next
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
    const next = new Set(retrying.value); next.delete(id); retrying.value = next
  }
}

function navigateToItem(item) {
  if (item.mediaType === 'MOVIE') {
    router.push('/movies/' + item.mediaId)
  } else if (item.mediaType === 'EPISODE' && item.showId != null) {
    router.push('/tv/' + item.showId + '/seasons/' + item.seasonId + '/episodes/' + item.mediaId)
  }
}

// ── Unsubscribe ───────────────────────────────────────────────────────────────
async function handleUnsubscribeClick(playlistId, playlistTitle) {
  const count = await getPlaylistQueueCount(playlistId)
  const message = count > 0
    ? `Unsubscribe from "${playlistTitle}" and cancel ${count} queued download${count === 1 ? '' : 's'}?`
    : `Unsubscribe from "${playlistTitle}"?`
  confirmState.value = { playlistId, message }
}

async function confirmUnsubscribe() {
  const { playlistId } = confirmState.value
  confirmState.value = null
  await unsubscribe(playlistId)
  await dlStore.fetchQueue()
}

let pollTimer = null
onMounted(async () => {
  try { await dlStore.fetchQueue() } catch (e) { console.error('Initial queue fetch failed:', e) }
  pollTimer = setInterval(() => dlStore.fetchQueue(), 2000)
})
onUnmounted(() => clearInterval(pollTimer))
</script>

<style scoped>
h2 { font-size: 1.5rem; font-weight: 600; margin-bottom: 24px; }
.count-badge { background: var(--surface2); border: 1px solid var(--border); color: var(--text-muted);
               font-size: .75rem; font-weight: 600; border-radius: 10px; padding: 2px 8px;
               margin-left: 8px; vertical-align: middle; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 24px; }
.filter-group { display: flex; gap: 6px; flex-wrap: wrap; }
.chip { background: var(--surface2); border: 1px solid var(--border); color: var(--text-muted);
        border-radius: 16px; padding: 4px 12px; font-size: .8rem; cursor: pointer;
        transition: background .15s, color .15s, border-color .15s; }
.chip:hover:not(.active) { border-color: var(--accent-blue); color: var(--text); }
.chip.active { background: var(--accent-blue); border-color: var(--accent-blue); color: #fff; }
.filter-search { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
                 border-radius: 16px; padding: 4px 12px; font-size: .8rem; outline: none; min-width: 160px; }
.filter-search:focus { border-color: var(--accent-blue); }
.empty { color: var(--text-muted); padding: 40px 0; text-align: center; }

.tree-section { margin-bottom: 32px; }
.section-label { font-size: .75rem; font-weight: 700; color: var(--text-muted); letter-spacing: .08em;
                 text-transform: uppercase; margin-bottom: 8px; padding-bottom: 6px;
                 border-bottom: 1px solid var(--border); }
.group { margin-bottom: 4px; }
.group-header { display: flex; align-items: center; gap: 10px; padding: 10px 12px;
                background: var(--surface2); border-radius: 8px; cursor: pointer;
                user-select: none; }
.group-header:hover { background: color-mix(in srgb, var(--surface2) 85%, var(--text) 15%); }
.chevron { font-size: .75rem; color: var(--text-muted); width: 12px; }
.group-title { font-weight: 600; flex: 1; }
.group-count { font-size: .75rem; color: var(--text-muted); background: var(--surface);
               border: 1px solid var(--border); border-radius: 10px; padding: 1px 7px; }
.group-body { padding-left: 20px; margin-top: 2px; }

.sub-group { margin-bottom: 2px; }
.sub-group-header { display: flex; align-items: center; gap: 8px; padding: 7px 12px;
                    background: var(--surface); border-radius: 6px; cursor: pointer;
                    font-size: .9rem; color: var(--text-muted); user-select: none; }
.sub-group-header:hover { color: var(--text); }
.sub-group-body { padding-left: 16px; margin-top: 2px; }

.sub-section { margin-bottom: 8px; }
.sub-label { font-size: .68rem; font-weight: 700; color: var(--text-muted); letter-spacing: .07em;
             text-transform: uppercase; margin: 6px 0 4px 0; padding-left: 4px; }

.queue-item { display: flex; align-items: center; gap: 12px; padding: 9px 12px;
              background: var(--surface2); border-radius: 6px; margin-bottom: 4px; }
.queue-item.active { border-left: 3px solid var(--accent-blue); }
.queue-item.done   { opacity: 0.65; }
.queue-item.clickable { cursor: pointer; }
.queue-item.clickable:hover { background: color-mix(in srgb, var(--surface2) 85%, var(--text) 15%); }
.item-info { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.type { font-weight: 500; font-size: .9rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sub  { font-size: .78rem; color: var(--text-muted); }
.error-msg { font-size: .78rem; color: var(--red); }
.btn-remove { background: none; border: none; color: var(--text-muted); cursor: pointer;
              font-size: 1rem; padding: 4px 8px; border-radius: 4px; }
.btn-remove:hover:not(:disabled) { color: var(--red); background: rgba(231,76,60,.1); }
.btn-remove:disabled { opacity: 0.3; cursor: not-allowed; }
.btn-retry { background: none; border: 1px solid var(--red); color: var(--red); cursor: pointer;
             font-size: .8rem; padding: 2px 8px; border-radius: 4px; }
.btn-retry:hover { background: rgba(231,76,60,.1); }
.btn-unsub { background: none; border: 1px solid var(--accent); color: var(--accent); cursor: pointer;
             font-size: .75rem; padding: 2px 10px; border-radius: 4px; white-space: nowrap; }
.btn-unsub:hover { background: rgba(var(--accent-rgb, 52,152,219),.1); }
</style>
