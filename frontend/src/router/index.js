import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth.js'

const routes = [
  { path: '/login',    component: () => import('@/views/LoginView.vue'),        meta: { public: true } },
  { path: '/movies',   component: () => import('@/views/MoviesView.vue') },
  { path: '/movies/:id', component: () => import('@/views/MovieDetailView.vue') },
  { path: '/playlists',     component: () => import('@/views/PlaylistsView.vue') },
  { path: '/playlists/:id', component: () => import('@/views/PlaylistDetailView.vue') },
  { path: '/tv',       component: () => import('@/views/TvView.vue') },
  { path: '/tv/:showId', component: () => import('@/views/TvShowDetailView.vue') },
  { path: '/tv/:showId/seasons/:seasonId', component: () => import('@/views/SeasonDetailView.vue') },
  { path: '/tv/:showId/seasons/:seasonId/episodes/:episodeId',
    component: () => import('@/views/EpisodeDetailView.vue') },
  { path: '/queue',    component: () => import('@/views/QueueView.vue') },
  { path: '/settings', component: () => import('@/views/SettingsView.vue') },
  { path: '/',         redirect: '/movies' },
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(to => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isLoggedIn) return '/login'
})

export default router
