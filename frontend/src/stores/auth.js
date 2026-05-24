// stub — full implementation in Task 3
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
export const useAuthStore = defineStore('auth', () => {
  const token = ref(null)
  const isLoggedIn = computed(() => !!token.value)
  return { token, isLoggedIn }
})
