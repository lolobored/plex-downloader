import api from './axios.js'

export const getWatched              = (showId)                => api.get(`/api/tv/${showId}/watched`).then(r => r.data)
export const getSubscriptions        = ()                      => api.get('/api/subscriptions').then(r => r.data)
export const getSeasonSubscriptions  = ()                      => api.get('/api/subscriptions/seasons').then(r => r.data)
export const subscribe               = (showId, targetCount)   => api.post('/api/subscriptions', { showId, targetCount }).then(r => r.data)
export const unsubscribe             = (showId)                => api.delete(`/api/subscriptions/${showId}`)
export const subscribeSeason         = (seasonId, targetCount) => api.post('/api/subscriptions/seasons', { seasonId, targetCount }).then(r => r.data)
export const unsubscribeSeasonSub    = (seasonId)              => api.delete(`/api/subscriptions/seasons/${seasonId}`)
export const syncNow                 = (showId)                => api.post(`/api/subscriptions/${showId}/sync`).then(r => r.data)
export const getShowQueueCount       = (showId)                => api.get(`/api/subscriptions/${showId}/queue-count`).then(r => r.data.count)
export const getSeasonQueueCount     = (seasonId)              => api.get(`/api/subscriptions/seasons/${seasonId}/queue-count`).then(r => r.data.count)
