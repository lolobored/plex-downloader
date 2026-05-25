<template>
  <div id="root">
    <NavBar v-if="authStore.isLoggedIn" />
    <main>
      <keep-alive include="MoviesView,TvView">
        <router-view />
      </keep-alive>
    </main>
  </div>
</template>

<script setup>
import NavBar from '@/components/NavBar.vue'
import { useAuthStore } from '@/stores/auth.js'
const authStore = useAuthStore()
</script>

<style>
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

:root {
  --bg: #0f0f0f;
  --surface: #1a1a2e;
  --surface2: #222;
  --border: #333;
  --text: #e1e1e1;
  --text-muted: #888;
  --accent: #e5a00d;
  --accent-blue: #4a90d9;
  --green: #27ae60;
  --red: #e74c3c;
}

body { background: var(--bg); color: var(--text); font-family: system-ui, sans-serif; }
a { color: inherit; text-decoration: none; }
button { cursor: pointer; font-family: inherit; }

#root { min-height: 100vh; display: flex; flex-direction: column; }
main { flex: 1; padding: 24px; max-width: 1400px; margin: 0 auto; width: 100%; }
</style>
