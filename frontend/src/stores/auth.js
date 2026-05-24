import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const token    = ref(localStorage.getItem('jwt') ?? null)
  const username = ref(localStorage.getItem('jwt_username') ?? null)
  const role     = ref(localStorage.getItem('jwt_role') ?? null)

  // Sync cookie with existing localStorage token on store init
  if (token.value) {
    document.cookie = `plex-session=${token.value}; path=/; SameSite=Strict`
  }

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin    = computed(() => role.value === 'ADMIN')

  function saveToken({ token: t, username: u, role: r }) {
    token.value    = t
    username.value = u
    role.value     = r
    localStorage.setItem('jwt', t)
    localStorage.setItem('jwt_username', u)
    localStorage.setItem('jwt_role', r)
    document.cookie = `plex-session=${t}; path=/; SameSite=Strict`
  }

  function logout() {
    token.value    = null
    username.value = null
    role.value     = null
    localStorage.removeItem('jwt')
    localStorage.removeItem('jwt_username')
    localStorage.removeItem('jwt_role')
    document.cookie = 'plex-session=; path=/; max-age=0'
  }

  return { token, username, role, isLoggedIn, isAdmin, saveToken, logout }
})
