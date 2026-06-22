<template>
  <span class="dl-btn-wrap">
    <select
      v-if="showPicker"
      v-model="selectedProfileId"
      class="profile-select"
      @click.stop
    >
      <option
        v-for="p in dlStore.profiles"
        :key="p.id"
        :value="p.id"
      >{{ p.name }}{{ p.isDefault ? ' ✓' : '' }}</option>
    </select>
    <button
      class="dl-btn"
      :class="[`status-${status ?? 'idle'}`, { small }]"
      :disabled="status === 'QUEUED' || status === 'TRANSCODING' || status === 'DONE'"
      @click.stop="handleClick"
      :title="label"
    >
      {{ icon }} <span v-if="!small">{{ label }}</span>
    </button>
  </span>
</template>

<script setup>
import { computed, ref, watch, onMounted } from 'vue'
import { useDownloadStore } from '@/stores/download.js'

const props = defineProps({
  type:    { type: String, required: true },
  mediaId: { type: Number, required: true },
  small:   { type: Boolean, default: false }
})

const dlStore = useDownloadStore()

const status = computed(() => dlStore.statusFor(props.type, props.mediaId))

const selectedProfileId = ref(null)

const showPicker = computed(() =>
  !props.small &&
  dlStore.profiles.length > 1 &&
  !status.value
)

// Initialize selectedProfileId to the default profile's id once profiles are available
function initSelectedProfile() {
  const def = dlStore.profiles.find(p => p.isDefault)
  selectedProfileId.value = def?.id ?? null
}

onMounted(async () => {
  await dlStore.fetchProfiles()
  initSelectedProfile()
})

// If profiles load after mount (e.g. already cached and reactive), keep in sync
watch(() => dlStore.profiles, () => {
  if (selectedProfileId.value === null) {
    initSelectedProfile()
  }
})

const icon = computed(() => {
  switch (status.value) {
    case 'QUEUED':      return '⏳'
    case 'TRANSCODING': return '⏳'
    case 'DONE':        return '✓'
    case 'ERROR':       return '✗'
    default:            return '⬇'
  }
})

const label = computed(() => {
  switch (status.value) {
    case 'QUEUED':      return 'In Queue'
    case 'TRANSCODING': return 'Transcoding…'
    case 'DONE':        return 'Done'
    case 'ERROR':       return 'Error'
    default:            return 'Download'
  }
})

async function handleClick() {
  if (!status.value) {
    try {
      const profileId = props.small ? null : (selectedProfileId.value ?? null)
      await dlStore.enqueue(props.type, props.mediaId, profileId)
    } catch (e) {
      console.error('Enqueue failed', e)
    }
  }
}
</script>

<style scoped>
.dl-btn-wrap { display: inline-flex; align-items: center; gap: 6px; }
.dl-btn { display: inline-flex; align-items: center; gap: 6px; border: none;
          border-radius: 6px; padding: 8px 16px; font-size: .9rem; font-weight: 600;
          transition: opacity .15s; }
.dl-btn.small { padding: 4px 8px; font-size: .8rem; border-radius: 4px; }
.dl-btn:disabled { cursor: default; }
.status-idle        { background: var(--accent); color: #000; }
.status-idle:hover  { opacity: .85; }
.status-QUEUED      { background: var(--accent-blue); color: #fff; }
.status-TRANSCODING { background: var(--accent-blue); color: #fff; }
.status-DONE        { background: var(--green); color: #fff; }
.status-ERROR       { background: var(--red); color: #fff; }
.profile-select { font-size: .85rem; padding: 4px 6px; border-radius: 4px;
                  border: 1px solid var(--border); background: var(--surface);
                  color: var(--text); cursor: pointer; }
</style>
