import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getQueue, enqueue as apiEnqueue } from '@/api/download.js'

export const useDownloadStore = defineStore('download', () => {
  const queueItems = ref([])

  async function fetchQueue() {
    queueItems.value = await getQueue()
  }

  function statusFor(mediaType, mediaId) {
    const item = queueItems.value.find(
      i => i.mediaType === mediaType && i.mediaId === mediaId
    )
    return item?.status ?? null
  }

  async function enqueue(type, id) {
    await apiEnqueue(type, id)
    await fetchQueue()
  }

  return { queueItems, fetchQueue, statusFor, enqueue }
})
