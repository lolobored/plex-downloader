import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useDownloadStore = defineStore('download', () => {
  const queueItems = ref([])
  return { queueItems }
})
