<template>
  <div class="login">
    <div class="card">
      <h1>🎬 PlexDL</h1>
      <p class="sub">Sign in to browse and download your Plex library</p>

      <button v-if="!polling" class="btn-plex" @click="startLogin" :disabled="loading">
        {{ loading ? 'Connecting…' : 'Sign in with Plex' }}
      </button>

      <div v-if="polling" class="polling">
        <p>Waiting for Plex authorization…</p>
        <p class="hint">Complete sign-in in the Plex tab, then return here.</p>
        <button class="btn-cancel" @click="cancelLogin">Cancel</button>
      </div>

      <p v-if="error" class="error">{{ error }}</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { initPin, checkPin } from '@/api/auth.js'
import { useAuthStore } from '@/stores/auth.js'

const router   = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const polling = ref(false)
const error   = ref(null)
let pollInterval = null

async function startLogin() {
  error.value = null
  loading.value = true
  try {
    const { pinId, code } = await initPin()
    const authUrl = `https://app.plex.tv/auth#?clientID=plexdl&code=${code}` +
                    `&forwardUrl=${encodeURIComponent(window.location.origin + '/login')}`
    window.open(authUrl, '_blank')
    polling.value = true
    loading.value = false
    pollInterval = setInterval(async () => {
      try {
        const jwt = await checkPin(pinId)
        if (jwt) {
          clearInterval(pollInterval)
          authStore.saveToken(jwt)
          router.push('/movies')
        }
      } catch {
        clearInterval(pollInterval)
        error.value = 'Authentication failed. Please try again.'
        polling.value = false
      }
    }, 2000)
  } catch {
    error.value = 'Could not reach the server. Please try again.'
    loading.value = false
  }
}

function cancelLogin() {
  clearInterval(pollInterval)
  polling.value = false
}
</script>

<style scoped>
.login { display: flex; align-items: center; justify-content: center; min-height: 100vh; }
.card  { background: var(--surface); border: 1px solid var(--border); border-radius: 12px;
         padding: 40px; text-align: center; max-width: 380px; width: 100%; }
h1     { font-size: 2rem; color: var(--accent); margin-bottom: 8px; }
.sub   { color: var(--text-muted); margin-bottom: 28px; }
.btn-plex { background: var(--accent); color: #000; border: none; border-radius: 8px;
            padding: 12px 28px; font-size: 1rem; font-weight: 600; width: 100%; }
.btn-plex:hover:not(:disabled) { background: #f0b429; }
.btn-plex:disabled { opacity: 0.6; }
.polling { margin-top: 16px; }
.polling p { color: var(--text-muted); margin-bottom: 8px; }
.hint  { font-size: .85rem; }
.btn-cancel { background: transparent; border: 1px solid var(--border); color: var(--text-muted);
              border-radius: 6px; padding: 8px 20px; margin-top: 12px; }
.error { color: var(--red); margin-top: 12px; font-size: .9rem; }
</style>
