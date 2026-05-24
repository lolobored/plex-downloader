<template>
  <div v-if="!authStore.isAdmin" class="error">Access denied.</div>
  <div v-else>
    <h2>Settings</h2>

    <section class="card-section">
      <h3>Plex Connection</h3>
      <div class="field">
        <label>Server URL</label>
        <input name="plexUrl" v-model="form.plexUrl" type="url" placeholder="http://localhost:32400" />
      </div>
      <div class="field">
        <button class="btn-load" data-testid="load-libraries-btn" @click="loadLibraries" :disabled="loadingLibraries">
          {{ loadingLibraries ? 'Loading…' : '↻ Test connection & load libraries' }}
        </button>
        <p v-if="libraryError" class="error-inline">{{ libraryError }}</p>
      </div>
      <div v-if="availableLibraries.length" class="field">
        <label>Libraries to sync</label>
        <div v-for="lib in availableLibraries" :key="lib.key" class="checkbox-row">
          <input type="checkbox" :id="'lib-' + lib.key" :value="lib.key" v-model="selectedLibraryKeys" />
          <label :for="'lib-' + lib.key" class="checkbox-label">{{ lib.title }} <span class="lib-type">({{ lib.type }})</span></label>
        </div>
      </div>
      <button class="btn-save" @click="save" :disabled="saving">
        {{ saving ? 'Saving…' : 'Save' }}
      </button>
      <p v-if="saveOk" class="ok">Saved.</p>
    </section>

    <section class="card-section">
      <h3>Library Sync</h3>
      <div class="field">
        <label>Sync library every</label>
        <select name="syncCron" v-model="form.syncCron" class="select-field">
          <option v-for="o in SYNC_OPTIONS" :key="o.cron" :value="o.cron">{{ o.label }}</option>
        </select>
      </div>
<div class="sync-status" v-if="syncStatus">
        <span :class="['state', syncStatus.state.toLowerCase()]">{{ syncStatus.state }}</span>
        <span v-if="syncStatus.lastSyncAt" class="last-sync">
          Last sync: {{ new Date(syncStatus.lastSyncAt).toLocaleString() }}
          ({{ syncStatus.itemsSynced }} items)
        </span>
        <span v-if="syncStatus.error" class="sync-error">{{ syncStatus.error }}</span>
      </div>
      <div v-if="syncing || syncStatus?.state === 'RUNNING'" class="sync-progress">
        <!-- Global bar -->
        <div class="progress-bar">
          <div class="progress-fill" :style="progressStyle"></div>
        </div>
        <span class="progress-label">
          Total: {{ syncStatus?.itemsSynced ?? 0 }}
          <template v-if="syncStatus?.totalItems"> / {{ syncStatus.totalItems }}</template>
          items
        </span>
        <!-- Per-library bars -->
        <div v-for="lib in syncStatus?.libraries ?? []" :key="lib.key" class="lib-progress">
          <div class="lib-progress-header">
            <span class="lib-name">{{ lib.title }}</span>
            <span class="lib-count">{{ lib.itemsSynced }} / {{ lib.totalItems }}</span>
            <span v-if="lib.done" class="lib-done">✓</span>
          </div>
          <div class="progress-bar lib-bar">
            <div class="progress-fill" :style="{ width: lib.totalItems ? Math.round(lib.itemsSynced / lib.totalItems * 100) + '%' : '0%' }"></div>
          </div>
        </div>
      </div>
      <div class="sync-actions">
        <button class="btn-save" @click="save" :disabled="saving">Save</button>
        <button class="btn-sync" data-testid="sync-btn" @click="sync" :disabled="syncing">
          {{ syncing ? 'Syncing…' : '↻ Sync Now' }}
        </button>
      </div>
    </section>

    <section class="card-section">
      <h3>Tdarr</h3>
      <div class="field">
        <label>Tdarr server URL</label>
        <div class="url-row">
          <input name="tdarrUrl" v-model="form.tdarrUrl" type="url" placeholder="http://192.168.1.10:8265" />
          <button class="btn-load" data-testid="test-tdarr-btn" @click="testTdarrConnection" :disabled="testingTdarr">
            {{ testingTdarr ? 'Testing…' : '↻ Test' }}
          </button>
        </div>
        <p v-if="tdarrTestOk === true"  class="ok tdarr-status">✓ Connected</p>
        <p v-if="tdarrTestOk === false" class="error-inline tdarr-status">✗ {{ tdarrTestError }}</p>
      </div>
      <div class="field">
        <label>Sync Tdarr status every</label>
        <select name="tdarrSyncCron" v-model="form.tdarrSyncCron" class="select-field">
          <option v-for="o in TDARR_OPTIONS" :key="o.cron" :value="o.cron">{{ o.label }}</option>
        </select>
      </div>
      <button class="btn-save" @click="save" :disabled="saving">Save</button>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth.js'
import { getSettings, putSettings, getSyncStatus, triggerSync, getPlexLibraries, testTdarr } from '@/api/admin.js'

const SYNC_OPTIONS = [
  { label: 'Every hour',     cron: '0 0 * * * *'    },
  { label: 'Every 6 hours',  cron: '0 0 */6 * * *'  },
  { label: 'Every 12 hours', cron: '0 0 */12 * * *' },
  { label: 'Every day',      cron: '0 0 0 * * *'    },
]

const TDARR_OPTIONS = [
  { label: 'Every 15 minutes', cron: '0 */15 * * * *' },
  { label: 'Every 30 minutes', cron: '0 */30 * * * *' },
  { label: 'Every hour',       cron: '0 0 * * * *'    },
  { label: 'Every 6 hours',    cron: '0 0 */6 * * *'  },
]

function matchCron(value, options) {
  const match = options.find(o => o.cron === value)
  return match ? match.cron : options[0].cron
}

const authStore  = useAuthStore()
const saving     = ref(false)
const saveOk     = ref(false)
const syncing    = ref(false)
const syncStatus = ref(null)
let saveOkTimer = null
let destroyed = false
onUnmounted(() => { clearTimeout(saveOkTimer); destroyed = true })

const progressStyle = computed(() => {
  const s = syncStatus.value
  if (!s || !s.totalItems) return { width: '0%' }
  const pct = Math.min(100, Math.round((s.itemsSynced / s.totalItems) * 100))
  return { width: pct + '%' }
})

const availableLibraries  = ref([])
const selectedLibraryKeys = ref([])
const loadingLibraries    = ref(false)
const libraryError        = ref(null)

const testingTdarr  = ref(false)
const tdarrTestOk   = ref(null)   // null=untested, true=ok, false=fail
const tdarrTestError = ref('')

const form = reactive({
  plexUrl:      '',
  syncCron:     '',
  tdarrUrl:     '',
  tdarrSyncCron: ''
})

onMounted(async () => {
  try {
    const [s, ss] = await Promise.all([getSettings(), getSyncStatus()])
    form.plexUrl      = s['plex.server.url']  ?? ''
    form.syncCron     = matchCron(s['plex.sync.cron'],    SYNC_OPTIONS)
    form.tdarrUrl           = s['tdarr.server.url']       ?? ''
    form.tdarrSyncCron      = matchCron(s['tdarr.sync.cron'],   TDARR_OPTIONS)
    const storedLibs = s['plex.sync.libraries'] ?? ''
    selectedLibraryKeys.value = storedLibs ? storedLibs.split(',').map(k => k.trim()).filter(Boolean) : []
    syncStatus.value = ss
    // Resume progress bar if sync was already running when page loaded
    if (ss.state === 'RUNNING') resumePolling()
    // Auto-load library checkboxes if URL + selections already saved
    if (form.plexUrl && selectedLibraryKeys.value.length > 0) loadLibraries()
  } catch (e) {
    console.error('Failed to load settings:', e)
  }
})

async function save() {
  saving.value = true
  saveOk.value = false
  const payload = {
    'plex.server.url':       form.plexUrl,
    'plex.sync.cron':        form.syncCron,
    'plex.sync.libraries':   selectedLibraryKeys.value.join(','),
    'tdarr.server.url':      form.tdarrUrl,
    'tdarr.sync.cron':       form.tdarrSyncCron
  }
  try {
    await putSettings(payload)
    saveOk.value = true
    clearTimeout(saveOkTimer)
    saveOkTimer = setTimeout(() => { saveOk.value = false }, 2000)
  } finally {
    saving.value = false
  }
}

async function testTdarrConnection() {
  testingTdarr.value = true
  tdarrTestOk.value = null
  tdarrTestError.value = ''
  try {
    const result = await testTdarr(form.tdarrUrl)
    tdarrTestOk.value = result.ok
    if (!result.ok) tdarrTestError.value = result.error ?? 'Connection failed'
  } catch {
    tdarrTestOk.value = false
    tdarrTestError.value = 'Request failed'
  } finally {
    testingTdarr.value = false
  }
}

async function loadLibraries() {
  loadingLibraries.value = true
  libraryError.value = null
  try {
    availableLibraries.value = await getPlexLibraries(form.plexUrl)
  } catch {
    libraryError.value = 'Could not load libraries. Check server URL and ensure you are logged in.'
  } finally {
    loadingLibraries.value = false
  }
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)) }

async function resumePolling() {
  syncing.value = true
  try {
    while (!destroyed) {
      const s = await getSyncStatus()
      if (destroyed) break
      syncStatus.value = s
      if (s.state !== 'RUNNING') break
      await sleep(1500)
    }
  } finally {
    if (!destroyed) syncing.value = false
  }
}

async function sync() {
  syncing.value = true
  try {
    await triggerSync()
    await sleep(600)   // give backend a moment to flip to RUNNING
    await resumePolling()
  } catch (e) {
    if (!destroyed) syncing.value = false
  }
}
</script>

<style scoped>
h2 { font-size: 1.5rem; font-weight: 600; margin-bottom: 24px; }
h3 { font-size: 1rem; font-weight: 600; margin-bottom: 16px; }
.card-section { background: var(--surface); border: 1px solid var(--border); border-radius: 10px;
                padding: 24px; max-width: 600px; margin-bottom: 24px; }
.field { display: flex; flex-direction: column; gap: 6px; margin-bottom: 16px; }
label { font-size: .85rem; color: var(--text-muted); }
input { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
        border-radius: 6px; padding: 8px 12px; font-size: .9rem; }
input:focus { outline: none; border-color: var(--accent-blue); }
input.readonly { opacity: 0.6; cursor: default; }
.select-field { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
                border-radius: 6px; padding: 8px 12px; font-size: .9rem; cursor: pointer; }
.select-field:focus { outline: none; border-color: var(--accent-blue); }
.btn-save { background: var(--accent); color: #000; border: none; border-radius: 6px;
            padding: 8px 20px; font-weight: 600; }
.btn-save:disabled { opacity: 0.6; }
.ok { color: var(--green); font-size: .85rem; margin-top: 8px; }
.sync-status { margin: 12px 0; display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.state { padding: 3px 10px; border-radius: 12px; font-size: .8rem; font-weight: 600; text-transform: uppercase; }
.state.idle    { background: var(--surface2); color: var(--text-muted); }
.state.running { background: var(--accent-blue); color: #fff; }
.state.done    { background: var(--green); color: #fff; }
.state.error   { background: var(--red); color: #fff; }
.last-sync  { font-size: .85rem; color: var(--text-muted); }
.sync-error { font-size: .85rem; color: var(--red); }
.sync-progress { margin: 10px 0 4px; }
.progress-bar { height: 6px; border-radius: 3px; background: var(--surface2); overflow: hidden; }
.progress-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--accent-blue);
  transition: width .6s ease;
  min-width: 2px;
}
.progress-label { font-size: .8rem; color: var(--text-muted); display: block; margin-top: 5px; }
.lib-progress { margin-top: 10px; }
.lib-progress-header { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.lib-name  { font-size: .85rem; color: var(--text); flex: 1; }
.lib-count { font-size: .75rem; color: var(--text-muted); }
.lib-done  { color: var(--green); font-size: .85rem; font-weight: 700; }
.lib-bar   { height: 4px; }
.sync-actions { display: flex; gap: 12px; align-items: center; margin-top: 4px; }
.btn-sync { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
            border-radius: 6px; padding: 8px 16px; }
.btn-sync:hover:not(:disabled) { border-color: var(--accent-blue); }
.error { color: var(--red); padding: 40px; text-align: center; }
.checkbox-row   { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.checkbox-label { font-size: .9rem; cursor: pointer; color: var(--text); }
.lib-type       { color: var(--text-muted); font-size: .8rem; }
.btn-load       { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
                  border-radius: 6px; padding: 8px 16px; font-size: .9rem; }
.btn-load:hover:not(:disabled) { border-color: var(--accent-blue); }
.error-inline   { color: var(--red); font-size: .85rem; margin-top: 6px; }
.url-row        { display: flex; gap: 8px; align-items: center; }
.url-row input  { flex: 1; }
.tdarr-status   { margin-top: 4px; }
</style>
