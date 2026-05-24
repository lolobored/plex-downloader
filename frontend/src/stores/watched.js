import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  getWatched       as apiGetWatched,
  getSubscriptions as apiGetSubscriptions,
  subscribe        as apiSubscribe,
  unsubscribe      as apiUnsubscribe,
  syncNow          as apiSyncNow,
  enqueueUnwatched as apiEnqueueUnwatched
} from '@/api/watched.js'

export const useWatchedStore = defineStore('watched', () => {
  const watchedByShow = ref(new Map())   // Map<showId, Set<episodeId>>
  const subscriptions = ref(new Map())   // Map<showId, targetCount>

  async function fetchWatched(showId) {
    const data = await apiGetWatched(showId)
    watchedByShow.value.set(showId, new Set(data.watchedEpisodeIds))
  }

  async function fetchSubscriptions() {
    const list = await apiGetSubscriptions()
    subscriptions.value = new Map(list.map(s => [s.showId, s.targetCount]))
  }

  async function subscribe(showId, targetCount) {
    await apiSubscribe(showId, targetCount)
    subscriptions.value.set(showId, targetCount)
    await fetchWatched(showId)
  }

  async function unsubscribe(showId) {
    await apiUnsubscribe(showId)
    subscriptions.value.delete(showId)
  }

  async function syncNow(showId) {
    const data = await apiSyncNow(showId)
    watchedByShow.value.set(showId, new Set(data.watchedEpisodeIds))
  }

  async function enqueueUnwatched(showId, limit) {
    return apiEnqueueUnwatched(showId, limit)
  }

  function isWatched(showId, episodeId) {
    return watchedByShow.value.get(showId)?.has(episodeId) ?? false
  }

  function getSubscription(showId) {
    return subscriptions.value.get(showId) ?? null
  }

  return {
    watchedByShow, subscriptions,
    fetchWatched, fetchSubscriptions, subscribe, unsubscribe,
    syncNow, enqueueUnwatched, isWatched, getSubscription
  }
})
