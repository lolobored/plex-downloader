import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getQueue, enqueue as apiEnqueue, getQualityProfiles, getOutputStatus } from '@/api/download.js'

export const useDownloadStore = defineStore('download', () => {
  const queueItems       = ref([])
  const profiles         = ref([])
  const outputConfigured = ref(true)  // default true to avoid disabled flash before first fetch

  async function fetchQueue() {
    queueItems.value = await getQueue()
  }

  async function fetchProfiles() {
    if (profiles.value.length > 0) return
    profiles.value = await getQualityProfiles()
  }

  async function fetchOutputStatus() {
    const status = await getOutputStatus()
    outputConfigured.value = status.configured
  }

  function statusFor(mediaType, mediaId) {
    const item = queueItems.value.find(
      i => i.mediaType === mediaType && i.mediaId === mediaId
    )
    return item?.status ?? null
  }

  async function enqueue(type, id, qualityProfileId = null) {
    await apiEnqueue(type, id, qualityProfileId)
    await fetchQueue()
  }

  return { queueItems, profiles, outputConfigured, fetchQueue, fetchProfiles, fetchOutputStatus, statusFor, enqueue }
})
