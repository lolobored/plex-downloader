import api from './axios.js'

export const getWatched        = (showId)              => api.get(`/api/tv/${showId}/watched`).then(r => r.data)
export const getSubscriptions  = ()                    => api.get('/api/subscriptions').then(r => r.data)
export const subscribe         = (showId, targetCount) => api.post('/api/subscriptions', { showId, targetCount }).then(r => r.data)
export const unsubscribe       = (showId)              => api.delete(`/api/subscriptions/${showId}`)
export const syncNow           = (showId)              => api.post(`/api/subscriptions/${showId}/sync`).then(r => r.data)
export const enqueueUnwatched  = (showId, limit)       => api.post(`/api/download/show/${showId}/unwatched`, { limit }).then(r => r.data)
