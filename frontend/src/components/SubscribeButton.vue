<template>
  <div class="subscribe-wrap" :class="{ small }">
    <button class="sub-btn" :class="statusClass" @click.stop="toggle" :disabled="loading || !dlStore.outputConfigured" :title="!dlStore.outputConfigured ? 'Set the movies and TV output folders in Settings before downloading.' : undefined">
      <template v-if="loading">⏳</template>
      <template v-else-if="current">📥 Next {{ current }}</template>
      <template v-else>⬇ Download</template>
    </button>

    <div v-if="open" class="picker" @click.stop>
      <div v-if="dlStore.profiles.length > 1" class="picker-section">
        <p class="picker-label">Quality profile</p>
        <select v-model="selectedProfileId" class="profile-select">
          <option v-for="p in dlStore.profiles" :key="p.id" :value="p.id">
            {{ p.name }}{{ p.isDefault ? ' ✓' : '' }}
          </option>
        </select>
      </div>
      <div class="picker-section">
        <p class="picker-label">{{ current ? 'Change subscription' : 'Subscribe: keep ahead' }}</p>
        <button v-for="n in COUNTS" :key="`sub-${n}`"
                class="picker-opt" :class="{ active: current === n }"
                @click="doSubscribe(n)">
          📥 Next {{ n }}
        </button>
      </div>
      <div class="picker-section">
        <p class="picker-label">Download</p>
        <button class="picker-opt" data-testid="download-all-btn" @click="doDownloadAll">
          ⬇ Download all
        </button>
      </div>
      <div v-if="current" class="picker-section">
        <button class="picker-opt cancel-opt" @click="handleUnsubscribeClick">✕ Cancel subscription</button>
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
import { ref, computed, watch, onMounted } from 'vue'
import { useWatchedStore } from '@/stores/watched.js'
import { useDownloadStore } from '@/stores/download.js'
import { getShowQueueCount, getSeasonQueueCount } from '@/api/watched.js'
import { enqueue } from '@/api/download.js'
import ConfirmModal from '@/components/ConfirmModal.vue'

const COUNTS = [5, 10, 15, 20]

const props = defineProps({
  showId:   { type: Number, required: true },
  seasonId: { type: Number, default: null },
  small:    { type: Boolean, default: false }
})

const watchedStore = useWatchedStore()
const dlStore      = useDownloadStore()
const open         = ref(false)
const loading      = ref(false)
const selectedProfileId = ref(null)

const showConfirm    = ref(false)
const confirmMessage = ref('')

const current = computed(() =>
  props.seasonId
    ? watchedStore.getSeasonSubscription(props.seasonId)
    : watchedStore.getSubscription(props.showId)
)
const statusClass = computed(() => current.value ? 'active-sub' : 'idle')

function initSelectedProfile() {
  const def = dlStore.profiles.find(p => p.isDefault)
  selectedProfileId.value = def?.id ?? null
}

onMounted(async () => {
  await dlStore.fetchProfiles()
  initSelectedProfile()
})

watch(() => dlStore.profiles, () => {
  if (selectedProfileId.value === null) {
    initSelectedProfile()
  }
})

function toggle() { open.value = !open.value }

async function doSubscribe(n) {
  open.value = false
  loading.value = true
  try {
    if (props.seasonId) {
      await watchedStore.subscribeSeason(props.seasonId, n)
    } else {
      await watchedStore.subscribe(props.showId, n)
    }
  } finally { loading.value = false }
}

async function doDownloadAll() {
  open.value = false
  loading.value = true
  const profileId = dlStore.profiles.length > 1 ? (selectedProfileId.value ?? null) : null
  try {
    if (props.seasonId) {
      await enqueue('SEASON', props.seasonId, profileId)
    } else {
      await enqueue('SHOW', props.showId, profileId)
    }
  } finally { loading.value = false }
}

async function handleUnsubscribeClick() {
  open.value = false
  const count = props.seasonId
    ? await getSeasonQueueCount(props.seasonId)
    : await getShowQueueCount(props.showId)
  confirmMessage.value = count > 0
    ? `This will remove your subscription and cancel ${count} queued download${count === 1 ? '' : 's'}. Continue?`
    : props.seasonId
      ? 'Remove subscription for this season?'
      : 'Remove subscription for this show?'
  showConfirm.value = true
}

async function confirmUnsubscribe() {
  showConfirm.value = false
  loading.value = true
  try {
    if (props.seasonId) {
      await watchedStore.unsubscribeSeason(props.seasonId)
    } else {
      await watchedStore.unsubscribe(props.showId)
    }
  } finally { loading.value = false }
}

function cancelUnsubscribe() {
  showConfirm.value = false
}
</script>

<style scoped>
.subscribe-wrap { position: relative; display: inline-block; }
.sub-btn { display: inline-flex; align-items: center; gap: 6px; border: none;
           border-radius: 6px; padding: 8px 16px; font-size: .9rem; font-weight: 600;
           transition: opacity .15s; cursor: pointer; }
.subscribe-wrap.small .sub-btn { padding: 4px 8px; font-size: .8rem; border-radius: 4px; }
.sub-btn:disabled { cursor: default; opacity: .6; }
.idle      { background: var(--accent); color: #000; }
.idle:hover { opacity: .85; }
.active-sub { background: var(--accent-blue); color: #fff; }

.picker { position: absolute; top: calc(100% + 6px); left: 0; z-index: 100;
          background: var(--surface); border: 1px solid var(--border); border-radius: 8px;
          padding: 12px; min-width: 220px; box-shadow: 0 4px 16px rgba(0,0,0,.4); }
.picker-section { margin-bottom: 12px; }
.picker-section:last-child { margin-bottom: 0; }
.picker-label { font-size: .75rem; color: var(--text-muted); text-transform: uppercase;
                letter-spacing: .05em; margin-bottom: 6px; }
.picker-opt { display: block; width: 100%; text-align: left; background: transparent;
              border: 1px solid var(--border); border-radius: 6px; padding: 6px 10px;
              color: var(--text); font-size: .85rem; margin-bottom: 4px; cursor: pointer; }
.picker-opt:hover { background: var(--surface2); }
.picker-opt.active { border-color: var(--accent-blue); color: var(--accent-blue); }
.cancel-opt { color: var(--red); border-color: var(--red); }
.cancel-opt:hover { background: rgba(231,76,60,.1); }
.profile-select { width: 100%; font-size: .85rem; padding: 5px 8px; border-radius: 4px;
                  border: 1px solid var(--border); background: var(--surface);
                  color: var(--text); cursor: pointer; }
</style>
