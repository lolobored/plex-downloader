import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  getWatched            as apiGetWatched,
  getSubscriptions      as apiGetSubscriptions,
  getSeasonSubscriptions as apiGetSeasonSubscriptions,
  subscribe             as apiSubscribe,
  unsubscribe           as apiUnsubscribe,
  subscribeSeason       as apiSubscribeSeason,
  unsubscribeSeasonSub  as apiUnsubscribeSeasonSub,
  syncNow               as apiSyncNow
} from '@/api/watched.js'

export const useWatchedStore = defineStore('watched', () => {
  const watchedByShow       = ref(new Map())   // Map<showId, Set<episodeId>>
  const subscriptions       = ref(new Map())   // Map<showId, targetCount>
  const seasonSubscriptions = ref(new Map())   // Map<seasonId, targetCount>

  async function fetchWatched(showId) {
    const data = await apiGetWatched(showId)
    watchedByShow.value.set(showId, new Set(data.watchedEpisodeIds))
  }

  async function fetchSubscriptions() {
    const [showSubs, seasonSubs] = await Promise.all([
      apiGetSubscriptions(),
      apiGetSeasonSubscriptions()
    ])
    subscriptions.value       = new Map(showSubs.map(s => [s.showId, s.targetCount]))
    seasonSubscriptions.value = new Map(seasonSubs.map(s => [s.seasonId, s.targetCount]))
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

  async function subscribeSeason(seasonId, targetCount) {
    await apiSubscribeSeason(seasonId, targetCount)
    seasonSubscriptions.value.set(seasonId, targetCount)
  }

  async function unsubscribeSeason(seasonId) {
    await apiUnsubscribeSeasonSub(seasonId)
    seasonSubscriptions.value.delete(seasonId)
  }

  async function syncNow(showId) {
    const data = await apiSyncNow(showId)
    watchedByShow.value.set(showId, new Set(data.watchedEpisodeIds))
  }

  function isWatched(showId, episodeId) {
    return watchedByShow.value.get(showId)?.has(episodeId) ?? false
  }

  function getSubscription(showId) {
    return subscriptions.value.get(showId) ?? null
  }

  function getSeasonSubscription(seasonId) {
    return seasonSubscriptions.value.get(seasonId) ?? null
  }

  return {
    watchedByShow, subscriptions, seasonSubscriptions,
    fetchWatched, fetchSubscriptions,
    subscribe, unsubscribe,
    subscribeSeason, unsubscribeSeason,
    syncNow, isWatched,
    getSubscription, getSeasonSubscription
  }
})
