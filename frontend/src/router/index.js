import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login',    component: () => import('@/views/LoginView.vue'),        meta: { public: true } },
  { path: '/movies',   component: () => import('@/views/MoviesView.vue') },
  { path: '/movies/:id', component: () => import('@/views/MovieDetailView.vue') },
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
  const token = localStorage.getItem('jwt')
  if (!to.meta.public && !token) return '/login'
})

export default router
