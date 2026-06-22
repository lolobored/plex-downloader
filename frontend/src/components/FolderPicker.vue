<template>
  <div class="folder-picker-overlay" @click.self="$emit('close')">
    <div class="folder-picker-modal" role="dialog" aria-modal="true" aria-label="Pick a folder">
      <div class="fp-header">
        <h4 class="fp-title">Select Folder</h4>
        <button class="fp-close" @click="$emit('close')" aria-label="Close">✕</button>
      </div>

      <div class="fp-path-bar">
        <span class="fp-path-label">{{ currentPath }}</span>
      </div>

      <div v-if="error" class="fp-error">{{ error }}</div>

      <div class="fp-actions">
        <button class="fp-btn" @click="goUp" :disabled="!parent">↑ Up</button>
        <button class="fp-btn" @click="promptNewFolder" :disabled="creatingFolder">+ New Folder</button>
      </div>

      <div v-if="creatingFolder" class="fp-new-folder-row">
        <input
          ref="newFolderInput"
          v-model="newFolderName"
          type="text"
          class="fp-new-folder-input"
          placeholder="Folder name"
          @keyup.enter="createFolder"
          @keyup.escape="cancelNewFolder"
          data-testid="new-folder-input"
        />
        <button class="fp-btn fp-btn-primary" @click="createFolder" :disabled="!newFolderName.trim()">Create</button>
        <button class="fp-btn" @click="cancelNewFolder">Cancel</button>
      </div>

      <ul class="fp-list">
        <li
          v-for="entry in entries"
          :key="entry.path"
          class="fp-entry"
          @click="navigate(entry.path)"
          :data-testid="`fp-entry-${entry.name}`"
        >
          📁 {{ entry.name }}
        </li>
        <li v-if="entries.length === 0 && !loading" class="fp-empty">
          (no subfolders)
        </li>
        <li v-if="loading" class="fp-empty">Loading…</li>
      </ul>

      <div class="fp-footer">
        <button class="fp-btn fp-btn-primary" @click="select" data-testid="fp-select-btn">
          Select this folder
        </button>
        <button class="fp-btn" @click="$emit('close')">Cancel</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { fsList, fsMkdir } from '@/api/admin.js'

const props = defineProps({
  initialPath: { type: String, default: null }
})
const emit = defineEmits(['select', 'close'])

const currentPath = ref('')
const parent = ref(null)
const entries = ref([])
const loading = ref(false)
const error = ref(null)

const creatingFolder = ref(false)
const newFolderName = ref('')
const newFolderInput = ref(null)

async function loadPath(path) {
  loading.value = true
  error.value = null
  try {
    const result = await fsList(path)
    currentPath.value = result.path
    parent.value = result.parent
    entries.value = result.entries
  } catch (e) {
    error.value = e?.response?.data?.message ?? 'Failed to load directory.'
  } finally {
    loading.value = false
  }
}

function navigate(path) {
  loadPath(path)
}

function goUp() {
  if (parent.value) loadPath(parent.value)
}

function select() {
  emit('select', currentPath.value)
}

function promptNewFolder() {
  creatingFolder.value = true
  newFolderName.value = ''
  nextTick(() => newFolderInput.value?.focus())
}

function cancelNewFolder() {
  creatingFolder.value = false
  newFolderName.value = ''
}

async function createFolder() {
  const name = newFolderName.value.trim()
  if (!name) return
  error.value = null
  try {
    const newPath = currentPath.value.replace(/\/$/, '') + '/' + name
    await fsMkdir(newPath)
    cancelNewFolder()
    await loadPath(currentPath.value)
  } catch (e) {
    error.value = e?.response?.data?.message ?? 'Failed to create folder.'
  }
}

onMounted(() => {
  loadPath(props.initialPath)
})
</script>

<style scoped>
.folder-picker-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,.6);
  display: flex; align-items: center; justify-content: center; z-index: 1000;
}
.folder-picker-modal {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: 10px; padding: 20px; width: 480px; max-width: 95vw;
  max-height: 70vh; display: flex; flex-direction: column; gap: 12px;
}
.fp-header { display: flex; justify-content: space-between; align-items: center; }
.fp-title { font-size: .95rem; font-weight: 600; margin: 0; }
.fp-close { background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: 1rem; }
.fp-path-bar { background: var(--surface2); border: 1px solid var(--border);
  border-radius: 5px; padding: 6px 10px; font-size: .8rem; font-family: monospace;
  color: var(--text); word-break: break-all; }
.fp-path-label { color: var(--text-muted); }
.fp-actions { display: flex; gap: 8px; }
.fp-new-folder-row { display: flex; gap: 6px; align-items: center; }
.fp-new-folder-input { flex: 1; background: var(--surface2); border: 1px solid var(--border);
  color: var(--text); border-radius: 5px; padding: 5px 8px; font-size: .85rem; }
.fp-list { list-style: none; margin: 0; padding: 0; overflow-y: auto; flex: 1;
  border: 1px solid var(--border); border-radius: 6px; max-height: 280px; }
.fp-entry { padding: 8px 12px; cursor: pointer; font-size: .9rem; border-bottom: 1px solid var(--border); }
.fp-entry:last-child { border-bottom: none; }
.fp-entry:hover { background: var(--surface2); }
.fp-empty { padding: 10px 12px; font-size: .85rem; color: var(--text-muted); }
.fp-footer { display: flex; gap: 8px; justify-content: flex-end; }
.fp-btn { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
  border-radius: 5px; padding: 5px 12px; font-size: .85rem; cursor: pointer; }
.fp-btn:hover:not(:disabled) { border-color: var(--accent-blue); }
.fp-btn:disabled { opacity: 0.5; cursor: default; }
.fp-btn-primary { background: var(--accent); color: #000; border-color: var(--accent); font-weight: 600; }
.fp-btn-primary:hover:not(:disabled) { opacity: .9; }
.fp-error { color: var(--red); font-size: .85rem; }
</style>
