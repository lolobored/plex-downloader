<template>
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="episode">
    <button class="back" @click="router.back()">← Back</button>

    <div class="hero">
      <div class="thumb-wrap">
        <img v-if="episode.thumbnailUrl"
             :src="`/api/posters/${episode.plexId}.jpg`"
             :alt="episode.title"
             class="thumb" />
        <div v-else class="thumb-placeholder">▶</div>
      </div>
      <div class="meta">
        <p class="show-title" v-if="show">{{ show.title }}</p>
        <h1>{{ episode.title }}</h1>
        <p class="ep-meta">
          S{{ seasonNumber }}E{{ episode.episodeNumber }}
          <span v-if="episode.airDate"> · {{ episode.airDate }}</span>
          <span v-if="episode.durationMs"> · {{ formatDuration(episode.durationMs) }}</span>
        </p>
        <p class="ep-meta" v-if="episode.director">Dir: {{ episode.director }}</p>
        <p class="ep-meta" v-if="episode.videoResolution">{{ episode.videoResolution }}</p>
        <p class="summary">{{ episode.summary }}</p>
        <DownloadButton type="EPISODE" :mediaId="episode.id" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getEpisode, getShow, getSeason } from '@/api/library.js'
import DownloadButton from '@/components/DownloadButton.vue'

const route  = useRoute()
const router = useRouter()
const episode      = ref(null)
const show         = ref(null)
const seasonNumber = ref(null)
const loading      = ref(true)

function formatDuration(ms) {
  const m = Math.floor(ms / 60000)
  return `${m}m`
}

async function load() {
  const { showId, seasonId, episodeId } = route.params
  const [ep, sh, se] = await Promise.all([
    getEpisode(showId, seasonId, episodeId),
    getShow(showId),
    getSeason(showId, seasonId)
  ])
  episode.value      = ep
  show.value         = sh
  seasonNumber.value = se.seasonNumber
  loading.value      = false
}
load()
</script>

<style scoped>
.back { background: transparent; border: 1px solid var(--border); color: var(--text-muted);
        border-radius: 6px; padding: 6px 14px; margin-bottom: 24px; }
.hero { display: grid; grid-template-columns: 320px 1fr; gap: 32px; align-items: start; }
.thumb-wrap { border-radius: 8px; overflow: hidden; aspect-ratio: 16/9; background: var(--surface2); }
.thumb { width: 100%; height: 100%; object-fit: cover; display: block; }
.thumb-placeholder { display: flex; align-items: center; justify-content: center;
                     height: 100%; color: var(--text-muted); font-size: 3rem; }
.show-title { color: var(--text-muted); font-size: .95rem; margin-bottom: 4px; }
h1 { font-size: 1.8rem; font-weight: 700; margin-bottom: 8px; }
.ep-meta { color: var(--text-muted); font-size: .85rem; margin-bottom: 4px; }
.summary { line-height: 1.6; color: var(--text-muted); max-width: 620px; margin: 16px 0 24px; }
.loading { color: var(--text-muted); padding: 40px; text-align: center; }
</style>
