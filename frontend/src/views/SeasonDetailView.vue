<template>
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="season && show">
    <button class="back" @click="router.back()">← Back</button>

    <div class="hero">
      <img class="poster" :src="`/api/posters/${season.plexId}.jpg`" :alt="season.title" />
      <div class="meta">
        <h1>{{ show.title }}</h1>
        <h2>{{ season.title || `Season ${season.seasonNumber}` }}</h2>
        <p class="ep-count">{{ season.episodeCount }} episodes</p>
        <DownloadButton type="SEASON" :mediaId="season.id" />
      </div>
    </div>

    <div class="episodes-section">
      <h3>Episodes</h3>
      <div class="episodes-grid">
        <div v-for="ep in episodes" :key="ep.id" class="ep-card"
             @click="router.push(`/tv/${show.id}/seasons/${season.id}/episodes/${ep.id}`)">
          <div class="ep-thumb">
            <img v-if="ep.thumbnailUrl" :src="`/api/posters/${ep.plexId}.jpg`" :alt="ep.title" />
            <div v-else class="ep-thumb-placeholder">▶</div>
          </div>
          <div class="ep-info">
            <p class="ep-num">E{{ ep.episodeNumber }}</p>
            <p class="ep-title">{{ ep.title }}</p>
            <p class="ep-air">{{ ep.airDate }}</p>
          </div>
          <DownloadButton type="EPISODE" :mediaId="ep.id" small />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getShow, getSeason, getEpisodes } from '@/api/library.js'
import DownloadButton from '@/components/DownloadButton.vue'

const route   = useRoute()
const router  = useRouter()
const show    = ref(null)
const season  = ref(null)
const episodes = ref([])
const loading = ref(true)

async function load() {
  const { showId, seasonId } = route.params
  const [sh, se, eps] = await Promise.all([
    getShow(showId),
    getSeason(showId, seasonId),
    getEpisodes(showId, seasonId)
  ])
  show.value     = sh
  season.value   = se
  episodes.value = eps
  loading.value  = false
}
load()
</script>

<style scoped>
.back { background: transparent; border: 1px solid var(--border); color: var(--text-muted);
        border-radius: 6px; padding: 6px 14px; margin-bottom: 24px; }
.hero { display: grid; grid-template-columns: 160px 1fr; gap: 24px; align-items: start; margin-bottom: 32px; }
.poster { width: 160px; border-radius: 8px; aspect-ratio: 2/3; object-fit: cover; }
h1 { font-size: 1.6rem; font-weight: 700; }
h2 { font-size: 1.2rem; color: var(--text-muted); margin: 4px 0 8px; }
.ep-count { color: var(--text-muted); font-size: .9rem; margin-bottom: 16px; }
h3 { font-size: 1.1rem; margin-bottom: 12px; }
.episodes-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.ep-card { display: flex; align-items: center; gap: 12px; background: var(--surface2);
           border-radius: 8px; padding: 10px; cursor: pointer; }
.ep-card:hover { background: var(--border); }
.ep-thumb { width: 80px; min-width: 80px; aspect-ratio: 16/9; border-radius: 4px;
            background: var(--border); overflow: hidden; }
.ep-thumb img { width: 100%; height: 100%; object-fit: cover; }
.ep-thumb-placeholder { display: flex; align-items: center; justify-content: center;
                        height: 100%; color: var(--text-muted); font-size: 1.2rem; }
.ep-info { flex: 1; min-width: 0; }
.ep-num  { font-size: .75rem; color: var(--text-muted); }
.ep-title { font-size: .9rem; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ep-air  { font-size: .75rem; color: var(--text-muted); margin-top: 2px; }
.loading { color: var(--text-muted); padding: 40px; text-align: center; }
</style>
