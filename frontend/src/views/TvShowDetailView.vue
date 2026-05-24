<template>
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="show">
    <button class="back" @click="router.back()">← Back</button>

    <div class="hero">
      <img class="poster" :src="`/api/posters/${show.plexId}.jpg`" :alt="show.title" />
      <div class="meta">
        <h1>{{ show.title }}</h1>
        <p class="year-seasons">{{ show.year }} · {{ show.totalSeasons }} season{{ show.totalSeasons === 1 ? '' : 's' }}</p>
        <p class="genres">{{ show.genres?.join(', ') }}</p>
        <p class="rating">★ {{ show.rating?.toFixed(1) }}</p>
        <p class="summary">{{ show.summary }}</p>
        <DownloadButton type="SHOW" :mediaId="show.id" />
      </div>
    </div>

    <div v-if="seasons.length" class="seasons-section">
      <h3>Seasons</h3>
      <div class="seasons-grid">
        <PosterCard
          v-for="s in seasons"
          :key="s.id"
          :plexId="s.plexId"
          :title="s.title || `Season ${s.seasonNumber}`"
          :subtitle="`${s.episodeCount} episodes`"
          @click="router.push(`/tv/${show.id}/seasons/${s.id}`)"
        >
          <template #badge>
            <DownloadButton type="SEASON" :mediaId="s.id" small />
          </template>
        </PosterCard>
      </div>
    </div>

    <div v-if="show.actors?.length" class="cast-section">
      <h3>Cast</h3>
      <div class="cast-list">
        <span v-for="a in show.actors" :key="a.id" class="actor">{{ a.name }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getShow, getSeasons } from '@/api/library.js'
import PosterCard from '@/components/PosterCard.vue'
import DownloadButton from '@/components/DownloadButton.vue'

const route   = useRoute()
const router  = useRouter()
const show    = ref(null)
const seasons = ref([])
const loading = ref(true)

async function load() {
  const [s, ss] = await Promise.all([
    getShow(route.params.showId),
    getSeasons(route.params.showId)
  ])
  show.value    = s
  seasons.value = ss
  loading.value = false
}
load()
</script>

<style scoped>
.back { background: transparent; border: 1px solid var(--border); color: var(--text-muted);
        border-radius: 6px; padding: 6px 14px; margin-bottom: 24px; }
.hero { display: grid; grid-template-columns: 220px 1fr; gap: 32px; align-items: start; }
.poster { width: 220px; border-radius: 8px; object-fit: cover; aspect-ratio: 2/3; }
h1 { font-size: 2rem; font-weight: 700; margin-bottom: 8px; }
.year-seasons { color: var(--text-muted); }
.genres { color: var(--accent-blue); font-size: .9rem; margin: 6px 0; }
.rating { color: var(--accent); font-size: .9rem; margin-bottom: 6px; }
.summary { line-height: 1.6; color: var(--text-muted); max-width: 680px; margin: 16px 0 24px; }
.seasons-section { margin-top: 32px; }
h3 { font-size: 1.1rem; margin-bottom: 12px; }
.seasons-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 16px; }
.cast-section { margin-top: 32px; }
.cast-list { display: flex; flex-wrap: wrap; gap: 8px; }
.actor { background: var(--surface2); border-radius: 16px; padding: 4px 14px; font-size: .85rem; }
.loading { color: var(--text-muted); padding: 40px; text-align: center; }
</style>
