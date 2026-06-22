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
      <h3>Output Folders</h3>
      <div class="field">
        <label>Movies output directory</label>
        <div class="dir-field-row">
          <input name="moviesDir" v-model="form.moviesDir" type="text" placeholder="/plex-conversion/libraries/movies" />
          <button class="btn-browse" @click="openPicker('movies')" data-testid="browse-movies-btn">Browse</button>
        </div>
      </div>
      <div class="field">
        <label>TV shows output directory</label>
        <div class="dir-field-row">
          <input name="tvshowsDir" v-model="form.tvshowsDir" type="text" placeholder="/plex-conversion/libraries/tvshows" />
          <button class="btn-browse" @click="openPicker('tvshows')" data-testid="browse-tvshows-btn">Browse</button>
        </div>
      </div>
      <p v-if="outputDirError" class="error-inline">{{ outputDirError }}</p>
      <button class="btn-save" data-testid="save-output-dirs-btn" @click="saveOutputDirs" :disabled="saving">
        {{ saving ? 'Saving…' : 'Save' }}
      </button>
      <p v-if="saveOk" class="ok">Saved.</p>

      <FolderPicker
        v-if="pickerOpen"
        :initial-path="pickerInitialPath"
        @select="onPickerSelect"
        @close="pickerOpen = false"
      />
    </section>

    <section class="card-section">
      <h3>Transcoding</h3>

      <hr class="section-divider" />

      <h4 class="sub-heading">Quality Profiles</h4>

      <div v-if="profiles.length" class="profile-list">
        <div
          v-for="p in profiles"
          :key="p.id"
          :class="['profile-row', { 'profile-default': p.isDefault }]"
        >
          <span class="profile-name">{{ p.name }}<span v-if="p.isDefault" class="default-badge">default</span></span>
          <span class="profile-meta">{{ p.codec }} · {{ p.container }} · Q{{ p.qualityLevel }} · {{ p.resolutionCap }} · {{ p.audioMode }}</span>
          <div class="profile-actions">
            <button class="btn-sm" @click="editProfile(p)">Edit</button>
            <button class="btn-sm" @click="setDefault(p.id)" :disabled="p.isDefault">Set default</button>
            <button class="btn-sm btn-danger" @click="removeProfile(p.id)">Delete</button>
          </div>
        </div>
      </div>
      <p v-else class="no-profiles">No quality profiles yet.</p>

      <div class="profile-form">
        <h4 class="sub-heading">{{ editingProfile.id ? 'Edit Profile' : 'New Profile' }}</h4>
        <div class="field">
          <label>Name</label>
          <input name="profileName" v-model="editingProfile.name" type="text" placeholder="e.g. HD HEVC" />
        </div>
        <div class="field">
          <label>Codec</label>
          <select name="profileCodec" v-model="editingProfile.codec" class="select-field">
            <option value="HEVC_QSV">HEVC (QuickSync)</option>
            <option value="H264_QSV">H.264 (QuickSync)</option>
          </select>
        </div>
        <div class="field">
          <label>Container</label>
          <select name="profileContainer" v-model="editingProfile.container" class="select-field">
            <option value="MKV">MKV</option>
            <option value="MP4">MP4</option>
          </select>
        </div>
        <div class="field">
          <label>Quality level <span class="hint">(lower = better; e.g. 18–30)</span></label>
          <input name="profileQuality" v-model.number="editingProfile.qualityLevel" type="number" min="1" max="51" placeholder="23" />
        </div>
        <div class="field">
          <label>Resolution cap</label>
          <select name="profileResolution" v-model="editingProfile.resolutionCap" class="select-field">
            <option value="KEEP">Keep source</option>
            <option value="UHD_4K">4K UHD</option>
            <option value="P1080">1080p</option>
            <option value="P720">720p</option>
          </select>
        </div>
        <div class="field">
          <label>Audio mode</label>
          <select name="profileAudio" v-model="editingProfile.audioMode" class="select-field">
            <option value="COPY">Copy (passthrough)</option>
            <option value="AAC">AAC</option>
          </select>
        </div>
        <p v-if="profileError" class="error-inline">{{ profileError }}</p>
        <div class="profile-form-actions">
          <button class="btn-save" data-testid="save-profile-btn" @click="saveProfile" :disabled="savingProfile">
            {{ savingProfile ? 'Saving…' : (editingProfile.id ? 'Update Profile' : 'Create Profile') }}
          </button>
          <button v-if="editingProfile.id" class="btn-sm" @click="resetProfileForm">Cancel</button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth.js'
import { getSettings, putSettings, getSyncStatus, triggerSync, getPlexLibraries,
         createQualityProfile, updateQualityProfile, deleteQualityProfile, setDefaultQualityProfile } from '@/api/admin.js'
import { getQualityProfiles } from '@/api/download.js'
import FolderPicker from '@/components/FolderPicker.vue'

const SYNC_OPTIONS = [
  { label: 'Every hour',     cron: '0 0 * * * *'    },
  { label: 'Every 6 hours',  cron: '0 0 */6 * * *'  },
  { label: 'Every 12 hours', cron: '0 0 */12 * * *' },
  { label: 'Every day',      cron: '0 0 0 * * *'    },
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

// Quality profiles state
const profiles      = ref([])
const savingProfile = ref(false)
const profileError  = ref(null)

function blankProfileForm() {
  return { id: null, name: '', codec: 'HEVC_QSV', container: 'MKV', qualityLevel: 23, resolutionCap: 'KEEP', audioMode: 'COPY' }
}
const editingProfile = reactive(blankProfileForm())

function resetProfileForm() {
  Object.assign(editingProfile, blankProfileForm())
  profileError.value = null
}

function editProfile(p) {
  Object.assign(editingProfile, { id: p.id, name: p.name, codec: p.codec, container: p.container,
    qualityLevel: p.qualityLevel, resolutionCap: p.resolutionCap, audioMode: p.audioMode })
  profileError.value = null
}

async function fetchProfiles() {
  profiles.value = await getQualityProfiles()
}

async function saveProfile() {
  if (!editingProfile.name.trim()) { profileError.value = 'Name is required.'; return }
  profileError.value = null
  savingProfile.value = true
  try {
    const payload = {
      name: editingProfile.name.trim(),
      codec: editingProfile.codec,
      container: editingProfile.container,
      qualityLevel: editingProfile.qualityLevel,
      resolutionCap: editingProfile.resolutionCap,
      audioMode: editingProfile.audioMode
    }
    if (editingProfile.id) {
      await updateQualityProfile(editingProfile.id, payload)
    } else {
      await createQualityProfile(payload)
    }
    resetProfileForm()
    await fetchProfiles()
  } catch (e) {
    profileError.value = e?.response?.data?.message ?? 'Save failed.'
  } finally {
    savingProfile.value = false
  }
}

async function setDefault(id) {
  try {
    await setDefaultQualityProfile(id)
    await fetchProfiles()
  } catch (e) {
    profileError.value = 'Could not set default.'
  }
}

async function removeProfile(id) {
  try {
    await deleteQualityProfile(id)
    await fetchProfiles()
  } catch (e) {
    profileError.value = 'Could not delete profile.'
  }
}

const form = reactive({
  plexUrl:    '',
  syncCron:   '',
  moviesDir:  '',
  tvshowsDir: ''
})

const outputDirError = ref(null)
const pickerOpen = ref(false)
const pickerField = ref(null)
const pickerInitialPath = ref(null)

onMounted(async () => {
  try {
    const [s, ss] = await Promise.all([getSettings(), getSyncStatus()])
    form.plexUrl      = s['plex.server.url']  ?? ''
    form.syncCron     = matchCron(s['plex.sync.cron'],    SYNC_OPTIONS)
    const storedLibs = s['plex.sync.libraries'] ?? ''
    selectedLibraryKeys.value = storedLibs ? storedLibs.split(',').map(k => k.trim()).filter(Boolean) : []
    form.moviesDir  = s['output.movies.dir']  ?? '/plex-conversion/libraries/movies'
    form.tvshowsDir = s['output.tvshows.dir'] ?? '/plex-conversion/libraries/tvshows'
    syncStatus.value = ss
    // Resume progress bar if sync was already running when page loaded
    if (ss.state === 'RUNNING') resumePolling()
    // Auto-load library checkboxes if URL + selections already saved
    if (form.plexUrl && selectedLibraryKeys.value.length > 0) loadLibraries()
  } catch (e) {
    console.error('Failed to load settings:', e)
  }
  await fetchProfiles()
})

async function save() {
  saving.value = true
  saveOk.value = false
  const payload = {
    'plex.server.url':           form.plexUrl,
    'plex.sync.cron':            form.syncCron,
    'plex.sync.libraries':       selectedLibraryKeys.value.join(',')
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

function openPicker(field) {
  pickerField.value = field
  pickerInitialPath.value = field === 'movies' ? form.moviesDir : form.tvshowsDir
  pickerOpen.value = true
}

function onPickerSelect(path) {
  if (pickerField.value === 'movies') {
    form.moviesDir = path
  } else {
    form.tvshowsDir = path
  }
  pickerOpen.value = false
}

async function saveOutputDirs() {
  saving.value = true
  saveOk.value = false
  outputDirError.value = null
  const payload = {
    'output.movies.dir':  form.moviesDir,
    'output.tvshows.dir': form.tvshowsDir,
  }
  try {
    await putSettings(payload)
    saveOk.value = true
    clearTimeout(saveOkTimer)
    saveOkTimer = setTimeout(() => { saveOk.value = false }, 2000)
  } catch (e) {
    outputDirError.value = e?.response?.data?.message ?? 'Save failed.'
  } finally {
    saving.value = false
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
.hint           { font-size: .75rem; color: var(--text-muted); font-weight: 400; }
.section-divider { border: none; border-top: 1px solid var(--border); margin: 20px 0; }
.sub-heading { font-size: .9rem; font-weight: 600; margin-bottom: 12px; color: var(--text); }
.profile-list { margin-bottom: 16px; }
.profile-row { display: flex; flex-direction: column; gap: 4px; padding: 10px 12px;
               background: var(--surface2); border-radius: 6px; margin-bottom: 8px; }
.profile-default { border-left: 3px solid var(--green); }
.profile-name { font-size: .9rem; font-weight: 600; color: var(--text); display: flex; align-items: center; gap: 8px; }
.default-badge { background: var(--green); color: #fff; font-size: .7rem; font-weight: 700;
                 padding: 2px 7px; border-radius: 10px; }
.profile-meta { font-size: .8rem; color: var(--text-muted); }
.profile-actions { display: flex; gap: 8px; margin-top: 6px; }
.btn-sm { background: var(--surface); border: 1px solid var(--border); color: var(--text);
          border-radius: 5px; padding: 4px 12px; font-size: .8rem; cursor: pointer; }
.btn-sm:hover:not(:disabled) { border-color: var(--accent-blue); }
.btn-sm:disabled { opacity: 0.5; cursor: default; }
.btn-danger { border-color: var(--red); color: var(--red); }
.btn-danger:hover:not(:disabled) { background: var(--red); color: #fff; }
.no-profiles { font-size: .85rem; color: var(--text-muted); margin-bottom: 16px; }
.profile-form { margin-top: 8px; padding-top: 16px; border-top: 1px solid var(--border); }
.profile-form-actions { display: flex; gap: 12px; align-items: center; }
.dir-field-row { display: flex; gap: 8px; align-items: center; }
.dir-field-row input { flex: 1; }
.btn-browse { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
              border-radius: 6px; padding: 8px 14px; font-size: .85rem; cursor: pointer; white-space: nowrap; }
.btn-browse:hover { border-color: var(--accent-blue); }
</style>
