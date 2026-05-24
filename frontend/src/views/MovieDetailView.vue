<template>
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="movie" class="detail">
    <button class="back" @click="router.back()">← Back</button>

    <div class="hero">
      <img class="poster" :src="`/api/posters/${movie.plexId}.jpg`" :alt="movie.title" />
      <div class="meta">
        <h1>{{ movie.title }}</h1>
        <p class="year-studio">{{ movie.year }}{{ movie.studio ? ` · ${movie.studio}` : '' }}</p>
        <p class="genres">{{ movie.genres?.join(', ') }}</p>
        <p class="rating">★ {{ movie.rating?.toFixed(1) }} · {{ formatDuration(movie.durationMs) }}</p>
        <p class="directors" v-if="movie.directors?.length">Dir: {{ movie.directors.join(', ') }}</p>
        <p class="summary">{{ movie.summary }}</p>
        <DownloadButton type="MOVIE" :mediaId="movie.id" />
      </div>
    </div>

    <div v-if="movie.actors?.length" class="cast-section">
      <h3>Cast</h3>
      <div class="cast-list">
        <span v-for="a in movie.actors" :key="a.id" class="actor">{{ a.name }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getMovie } from '@/api/library.js'
import DownloadButton from '@/components/DownloadButton.vue'

const route  = useRoute()
const router = useRouter()
const movie  = ref(null)
const loading = ref(true)

function formatDuration(ms) {
  if (!ms) return ''
  const h = Math.floor(ms / 3600000)
  const m = Math.floor((ms % 3600000) / 60000)
  return h > 0 ? `${h}h ${m}m` : `${m}m`
}

getMovie(route.params.id).then(d => { movie.value = d }).finally(() => { loading.value = false })
</script>

<style scoped>
.back { background: transparent; border: 1px solid var(--border); color: var(--text-muted);
        border-radius: 6px; padding: 6px 14px; margin-bottom: 24px; }
.hero { display: grid; grid-template-columns: 220px 1fr; gap: 32px; align-items: start; }
.poster { width: 220px; border-radius: 8px; object-fit: cover; aspect-ratio: 2/3; }
h1 { font-size: 2rem; font-weight: 700; margin-bottom: 8px; }
.year-studio { color: var(--text-muted); }
.genres { color: var(--accent-blue); font-size: .9rem; margin: 6px 0; }
.rating { color: var(--accent); font-size: .9rem; margin-bottom: 6px; }
.directors { color: var(--text-muted); font-size: .85rem; margin-bottom: 10px; }
.summary { line-height: 1.6; color: var(--text-muted); max-width: 680px; margin: 16px 0 24px; }
.cast-section { margin-top: 32px; }
h3 { font-size: 1.1rem; margin-bottom: 12px; }
.cast-list { display: flex; flex-wrap: wrap; gap: 8px; }
.actor { background: var(--surface2); border-radius: 16px; padding: 4px 14px; font-size: .85rem; }
.loading { color: var(--text-muted); padding: 40px; text-align: center; }
</style>
