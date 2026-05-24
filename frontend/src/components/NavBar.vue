<template>
  <nav class="nav">
    <RouterLink to="/movies" class="logo">🎬 PlexDL</RouterLink>

    <div class="links">
      <RouterLink to="/movies">Movies</RouterLink>
      <RouterLink to="/tv">TV Shows</RouterLink>
      <RouterLink to="/queue" class="queue-link">
        Queue
        <span v-if="pendingCount > 0" class="badge">{{ pendingCount }}</span>
      </RouterLink>
      <RouterLink v-if="auth.isAdmin" to="/settings">Settings</RouterLink>
    </div>

    <div class="user">
      <span class="username">{{ auth.username }}</span>
      <button class="btn-logout" @click="logout">Sign out</button>
    </div>
  </nav>
</template>

<script setup>
import { computed } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth.js'
import { useDownloadStore } from '@/stores/download.js'

const router   = useRouter()
const auth     = useAuthStore()
const dlStore  = useDownloadStore()

const pendingCount = computed(() =>
  dlStore.queueItems.filter(i => i.status === 'PENDING' || i.status === 'IN_PROGRESS').length
)

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.nav { display: flex; align-items: center; gap: 24px; padding: 0 24px;
       height: 56px; background: var(--surface); border-bottom: 1px solid var(--border);
       position: sticky; top: 0; z-index: 100; }
.logo { font-weight: 700; font-size: 1.1rem; color: var(--accent); }
.links { display: flex; gap: 20px; flex: 1; }
.links a { color: var(--text-muted); font-size: .9rem; }
.links a.router-link-active { color: var(--text); }
.queue-link { position: relative; }
.badge { background: var(--accent); color: #000; font-size: .7rem; font-weight: 700;
         border-radius: 10px; padding: 1px 6px; margin-left: 4px; }
.user  { display: flex; align-items: center; gap: 12px; margin-left: auto; }
.username { font-size: .9rem; color: var(--text-muted); }
.btn-logout { background: transparent; border: 1px solid var(--border); color: var(--text-muted);
              border-radius: 6px; padding: 4px 12px; font-size: .85rem; }
.btn-logout:hover { border-color: var(--text-muted); color: var(--text); }
</style>
