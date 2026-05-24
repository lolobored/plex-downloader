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
        <label>Plex Token</label>
        <input name="plexToken" v-model="form.plexToken" type="password" placeholder="xxxxxxxxxxxxxxxxxxxx" />
      </div>
      <button class="btn-save" @click="save" :disabled="saving">
        {{ saving ? 'Saving…' : 'Save' }}
      </button>
      <p v-if="saveOk" class="ok">Saved.</p>
    </section>

    <section class="card-section">
      <h3>Path Mapping</h3>
      <div class="field">
        <label>Plex path prefix</label>
        <input name="plexPrefix" v-model="form.plexPathPrefixPlex" type="text" />
      </div>
      <div class="field">
        <label>App path prefix</label>
        <input name="appPrefix" v-model="form.plexPathPrefixApp" type="text" />
      </div>
      <div class="field">
        <label>Poster directory</label>
        <input name="posterDir" v-model="form.plexPosterDir" type="text" readonly class="readonly" />
      </div>
      <div class="field">
        <label>Conversion directory</label>
        <input name="conversionDir" v-model="form.plexConversionDir" type="text" readonly class="readonly" />
      </div>
      <button class="btn-save" @click="save" :disabled="saving">
        {{ saving ? 'Saving…' : 'Save' }}
      </button>
    </section>

    <section class="card-section">
      <h3>Library Sync</h3>
      <div class="field">
        <label>Sync cron expression</label>
        <input name="syncCron" v-model="form.syncCron" type="text" placeholder="0 0 */6 * * *" />
      </div>
      <div class="sync-status" v-if="syncStatus">
        <span :class="['state', syncStatus.state.toLowerCase()]">{{ syncStatus.state }}</span>
        <span v-if="syncStatus.lastSyncAt" class="last-sync">
          Last sync: {{ new Date(syncStatus.lastSyncAt).toLocaleString() }}
          ({{ syncStatus.itemsSynced }} items)
        </span>
        <span v-if="syncStatus.error" class="sync-error">{{ syncStatus.error }}</span>
      </div>
      <div class="sync-actions">
        <button class="btn-save" @click="save" :disabled="saving">Save cron</button>
        <button class="btn-sync" data-testid="sync-btn" @click="sync" :disabled="syncing">
          {{ syncing ? 'Syncing…' : '↻ Sync Now' }}
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth.js'
import { getSettings, putSettings, getSyncStatus, triggerSync } from '@/api/admin.js'

const authStore  = useAuthStore()
const saving     = ref(false)
const saveOk     = ref(false)
const syncing    = ref(false)
const syncStatus = ref(null)

const form = reactive({
  plexUrl:            '',
  plexToken:          '',
  plexPathPrefixPlex: '',
  plexPathPrefixApp:  '',
  plexPosterDir:      '',
  plexConversionDir:  '',
  syncCron:           ''
})

onMounted(async () => {
  const [s, ss] = await Promise.all([getSettings(), getSyncStatus()])
  form.plexUrl            = s['plex.server.url']        ?? ''
  form.plexToken          = ''  // never pre-fill tokens
  form.plexPathPrefixPlex = s['plex.path.prefix.plex']  ?? ''
  form.plexPathPrefixApp  = s['plex.path.prefix.app']   ?? ''
  form.plexPosterDir      = s['plex.poster.dir']        ?? ''
  form.plexConversionDir  = s['plex.conversion.dir']    ?? ''
  form.syncCron           = s['plex.sync.cron']         ?? ''
  syncStatus.value = ss
})

async function save() {
  saving.value = true
  saveOk.value = false
  const payload = {
    'plex.server.url':        form.plexUrl,
    'plex.path.prefix.plex':  form.plexPathPrefixPlex,
    'plex.path.prefix.app':   form.plexPathPrefixApp,
    'plex.sync.cron':         form.syncCron
  }
  if (form.plexToken) payload['plex.server.token'] = form.plexToken
  try {
    await putSettings(payload)
    saveOk.value = true
    setTimeout(() => { saveOk.value = false }, 2000)
  } finally {
    saving.value = false
  }
}

async function sync() {
  syncing.value = true
  try {
    await triggerSync()
    syncStatus.value = await getSyncStatus()
  } finally {
    syncing.value = false
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
.sync-actions { display: flex; gap: 12px; align-items: center; margin-top: 4px; }
.btn-sync { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
            border-radius: 6px; padding: 8px 16px; }
.btn-sync:hover:not(:disabled) { border-color: var(--accent-blue); }
.error { color: var(--red); padding: 40px; text-align: center; }
</style>
