<template>
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="playlist">
    <button class="back" @click="router.back()">← Back</button>

    <div class="detail-header">
      <div>
        <h1>{{ playlist.title }} <span class="count-badge" data-testid="count-badge">{{ playlist.items.length }}</span></h1>
        <p class="meta">{{ playlist.playlistType }}</p>
      </div>
      <button
        class="btn-subscribe"
        :class="{ subscribed: subscribed }"
        data-testid="subscribe-btn"
        :disabled="subscribing"
        @click="subscribed ? handleUnsubscribeClick() : doSubscribe()"
      >
        {{ subscribed ? '✓ Unsubscribe' : 'Subscribe' }}
      </button>
    </div>

    <div class="item-list">
      <div v-for="item in playlist.items" :key="item.id"
           class="item-row"
           :class="{ clickable: item.mediaId != null }"
           :tabindex="item.mediaId != null ? 0 : undefined"
           @click="navigateToItem(item)"
           @keydown.enter="navigateToItem(item)">
        <img
          class="item-thumb"
          :src="`/api/posters/${item.plexId}.jpg`"
          :alt="item.title || item.plexId"
          loading="lazy"
        />
        <div class="item-info">
          <div class="item-title">{{ item.title || item.plexId }}</div>
          <div class="item-sub">
            {{ item.mediaType.toLowerCase() }}
            <template v-if="item.year">· {{ item.year }}</template>
          </div>
        </div>
        <span class="status-badge" :class="statusClass(item)">{{ statusLabel(item) }}</span>
      </div>
    </div>

    <ConfirmModal
      v-if="showConfirm"
      :message="confirmMessage"
      confirmLabel="Yes, cancel downloads"
      cancelLabel="Keep"
      @confirm="confirmUnsubscribe"
      @cancel="cancelUnsubscribe"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getPlaylist, subscribe, unsubscribe, getPlaylistQueueCount } from '@/api/playlists.js'
import ConfirmModal from '@/components/ConfirmModal.vue'

const route  = useRoute()
const router = useRouter()

const playlist    = ref(null)
const loading     = ref(true)
const subscribed  = ref(false)
const subscribing = ref(false)

const showConfirm    = ref(false)
const confirmMessage = ref('')

onMounted(async () => {
  try {
    const data = await getPlaylist(Number(route.params.id))
    playlist.value   = data
    subscribed.value = data.subscribed
  } finally {
    loading.value = false
  }
})

async function doSubscribe() {
  subscribing.value = true
  try {
    await subscribe(Number(route.params.id))
    subscribed.value = true
  } finally {
    subscribing.value = false
  }
}

async function handleUnsubscribeClick() {
  const count = await getPlaylistQueueCount(Number(route.params.id))
  confirmMessage.value = count > 0
    ? `This will remove your subscription and cancel ${count} queued download${count === 1 ? '' : 's'}. Continue?`
    : 'Remove subscription for this playlist?'
  showConfirm.value = true
}

async function confirmUnsubscribe() {
  showConfirm.value = false
  subscribing.value = true
  try {
    await unsubscribe(Number(route.params.id))
    subscribed.value = false
  } finally {
    subscribing.value = false
  }
}

function cancelUnsubscribe() {
  showConfirm.value = false
}

function navigateToItem(item) {
  if (item.mediaId == null) return
  if (item.mediaType === 'MOVIE') {
    router.push(`/movies/${item.mediaId}`)
  } else if (item.mediaType === 'EPISODE') {
    router.push(`/tv/${item.showId}/seasons/${item.seasonId}/episodes/${item.mediaId}`)
  }
}

function statusClass(item) {
  if (!item.queueStatus) return 'status-none'
  if (item.queueStatus === 'DONE') return 'status-done'
  if (item.queueStatus === 'TRANSCODING') return 'status-processing'
  if (item.queueStatus === 'ERROR') return 'status-error'
  return 'status-queued'
}

function statusLabel(item) {
  if (!item.queueStatus) return 'not queued'
  if (item.queueStatus === 'DONE') return 'transcoded'
  if (item.queueStatus === 'TRANSCODING') return 'transcoding'
  if (item.queueStatus === 'ERROR') return 'error'
  return 'queued'
}
</script>

<style scoped>
.back { background: transparent; border: 1px solid var(--border); color: var(--text-muted);
        border-radius: 6px; padding: 6px 14px; margin-bottom: 24px; cursor: pointer; }

.detail-header {
  display: flex; align-items: flex-start; justify-content: space-between;
  margin-bottom: 24px; gap: 16px;
}
h1 { font-size: 1.6rem; font-weight: 700; margin-bottom: 4px; }
.meta { color: var(--text-muted); font-size: .9rem; }
.count-badge { background: var(--surface2); border: 1px solid var(--border); color: var(--text-muted);
               font-size: .75rem; font-weight: 600; border-radius: 10px; padding: 2px 8px;
               margin-left: 8px; vertical-align: middle; }

.btn-subscribe {
  padding: 8px 20px; border-radius: 6px; font-size: .9rem; font-weight: 600;
  border: 1px solid var(--accent); color: var(--accent); background: transparent;
  cursor: pointer; white-space: nowrap; flex-shrink: 0;
  transition: background .15s, color .15s;
}
.btn-subscribe.subscribed { background: var(--accent); color: #fff; }
.btn-subscribe:disabled { opacity: .5; cursor: default; }

.item-list { display: flex; flex-direction: column; gap: 4px; }

.item-row {
  display: flex; align-items: center; gap: 12px;
  padding: 8px 12px; background: var(--surface2); border-radius: 6px;
}
.item-row.clickable { cursor: pointer; }
.item-row.clickable:hover { background: color-mix(in srgb, var(--surface2) 85%, var(--text) 15%); }
.item-thumb {
  width: 32px; height: 48px; object-fit: cover;
  border-radius: 3px; flex-shrink: 0; background: var(--surface);
}
.item-info { flex: 1; min-width: 0; }
.item-title { font-size: .85rem; font-weight: 500; white-space: nowrap;
              overflow: hidden; text-overflow: ellipsis; }
.item-sub { font-size: .75rem; color: var(--text-muted); margin-top: 2px; }

.status-badge {
  border-radius: 4px; padding: 3px 8px; font-size: .7rem;
  white-space: nowrap; flex-shrink: 0; font-weight: 600;
}
.status-done       { background: var(--green, #22c55e); color: #fff; }
.status-processing { background: #f59e0b; color: #fff; }
.status-error      { background: var(--red, #ef4444); color: #fff; }
.status-queued     { background: var(--surface); color: var(--text-muted); border: 1px solid var(--border); }
.status-none       { background: var(--surface); color: var(--text-muted); border: 1px solid var(--border); }

.loading { color: var(--text-muted); padding: 40px; text-align: center; }
</style>
