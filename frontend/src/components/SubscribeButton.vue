<template>
  <div class="subscribe-wrap" :class="{ small }">
    <button class="sub-btn" :class="statusClass" @click.stop="toggle" :disabled="loading">
      <template v-if="loading">⏳</template>
      <template v-else-if="current">📥 Next {{ current }}</template>
      <template v-else>⬇ Download</template>
    </button>

    <div v-if="open" class="picker" @click.stop>
      <div class="picker-section">
        <p class="picker-label">{{ current ? 'Change subscription' : 'Subscribe: keep ahead' }}</p>
        <button v-for="n in COUNTS" :key="`sub-${n}`"
                class="picker-opt" :class="{ active: current === n }"
                @click="doSubscribe(n)">
          📥 Next {{ n }}
        </button>
      </div>
      <div class="picker-section">
        <p class="picker-label">One-time download</p>
        <button v-for="n in COUNTS" :key="`once-${n}`"
                class="picker-opt"
                @click="doOneTime(n)">
          ⬇ Download {{ n }}
        </button>
      </div>
      <div v-if="current" class="picker-section">
        <button class="picker-opt cancel-opt" @click="doCancel">✕ Cancel subscription</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useWatchedStore } from '@/stores/watched.js'

const COUNTS = [5, 10, 15, 20]

const props = defineProps({
  showId: { type: Number, required: true },
  small:  { type: Boolean, default: false }
})

const watchedStore = useWatchedStore()
const open    = ref(false)
const loading = ref(false)

const current    = computed(() => watchedStore.getSubscription(props.showId))
const statusClass = computed(() => current.value ? 'active-sub' : 'idle')

function toggle() { open.value = !open.value }

async function doSubscribe(n) {
  open.value = false
  loading.value = true
  try { await watchedStore.subscribe(props.showId, n) }
  finally { loading.value = false }
}

async function doOneTime(n) {
  open.value = false
  loading.value = true
  try { await watchedStore.enqueueUnwatched(props.showId, n) }
  finally { loading.value = false }
}

async function doCancel() {
  open.value = false
  loading.value = true
  try { await watchedStore.unsubscribe(props.showId) }
  finally { loading.value = false }
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
</style>
