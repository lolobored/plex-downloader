<template>
  <div>
    <div class="toolbar">
      <h2>TV Shows</h2>
      <SearchFilter v-model:search="search" v-model:year="year" />
    </div>

    <div v-if="loading" class="loading">Loading…</div>
    <div v-else-if="shows.length === 0" class="empty">No TV shows found.</div>
    <div v-else class="grid">
      <PosterCard
        v-for="s in shows"
        :key="s.id"
        :plexId="s.plexId"
        :title="s.title"
        :subtitle="`${s.totalSeasons} season${s.totalSeasons === 1 ? '' : 's'}`"
        :watched="s.watched"
        @click="router.push(`/tv/${s.id}`)"
      />
    </div>

    <div v-if="totalPages > 1" class="pagination">
      <button :disabled="page === 0" @click="page--">‹ Prev</button>
      <span>{{ page + 1 }} / {{ totalPages }}</span>
      <button :disabled="page >= totalPages - 1" @click="page++">Next ›</button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getShows } from '@/api/library.js'
import PosterCard from '@/components/PosterCard.vue'
import SearchFilter from '@/components/SearchFilter.vue'

const router     = useRouter()
const shows      = ref([])
const loading    = ref(false)
const page       = ref(0)
const totalPages = ref(0)
const search     = ref('')
const year       = ref(null)

async function load() {
  loading.value = true
  try {
    const data = await getShows({ search: search.value || undefined, year: year.value || undefined, page: page.value })
    shows.value      = data.content
    totalPages.value = data.totalPages
  } finally {
    loading.value = false
  }
}

watch([search, year], () => { page.value = 0; load() })
watch(page, load)
load()
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 24px; margin-bottom: 24px; }
h2 { font-size: 1.5rem; font-weight: 600; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 16px; }
.loading, .empty { color: var(--text-muted); padding: 40px 0; text-align: center; }
.pagination { display: flex; align-items: center; gap: 16px; margin-top: 32px; justify-content: center; }
.pagination button { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
                     border-radius: 6px; padding: 6px 16px; }
.pagination button:disabled { opacity: 0.4; }
</style>
