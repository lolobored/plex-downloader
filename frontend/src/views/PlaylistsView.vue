<template>
  <div class="playlists-root">
    <div class="toolbar">
      <h2>Playlists</h2>
    </div>

    <div v-if="loading" class="loading">Loading…</div>
    <div v-else-if="playlists.length === 0" class="empty">No playlists found.</div>
    <div v-else class="grid">
      <div
        v-for="p in playlists"
        :key="p.id"
        class="poster-card"
        @click="router.push(`/playlists/${p.id}`)"
      >
        <div class="img-wrap">
          <div class="composite-grid">
            <div
              v-for="(plexId, idx) in p.posterPlexIds.slice(0, 4)"
              :key="idx"
              class="composite-tile"
            >
              <img :src="`/api/posters/${plexId}.jpg`" :alt="p.title" loading="lazy" />
            </div>
            <div
              v-for="i in Math.max(0, 4 - p.posterPlexIds.length)"
              :key="'ph-' + i"
              class="composite-tile placeholder"
            />
          </div>
          <div v-if="p.subscribed" class="subscribed-badge" title="Subscribed">●</div>
        </div>
        <div class="info">
          <p class="title">{{ p.title }}</p>
          <p class="subtitle">{{ p.leafCount }} items</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getPlaylists } from '@/api/playlists.js'

const router    = useRouter()
const playlists = ref([])
const loading   = ref(true)

onMounted(async () => {
  try {
    playlists.value = await getPlaylists()
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 24px; margin-bottom: 24px; }
h2 { font-size: 1.5rem; font-weight: 600; }

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 16px;
}

.poster-card { cursor: pointer; }
.poster-card:hover .img-wrap .composite-tile img { transform: scale(1.03); }

.img-wrap {
  position: relative;
  border-radius: 6px;
  overflow: hidden;
  background: var(--surface2);
  aspect-ratio: 2/3;
}

.composite-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: 1fr 1fr;
  height: 100%;
  gap: 2px;
}

.composite-tile { overflow: hidden; }
.composite-tile img {
  width: 100%; height: 100%;
  object-fit: cover;
  display: block;
  transition: transform .2s ease;
}
.composite-tile.placeholder { background: var(--surface); }

.subscribed-badge {
  position: absolute; top: 6px; right: 6px;
  width: 16px; height: 16px; border-radius: 50%;
  background: var(--accent); color: #fff;
  font-size: .55rem; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 1px 4px rgba(0,0,0,.5);
}

.info { padding: 6px 2px 0; }
.title { font-size: .9rem; font-weight: 500; white-space: nowrap;
         overflow: hidden; text-overflow: ellipsis; }
.subtitle { font-size: .8rem; color: var(--text-muted); margin-top: 2px; }
.loading, .empty { color: var(--text-muted); padding: 40px 0; text-align: center; }
</style>
