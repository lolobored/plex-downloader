<template>
  <div class="movies-root">
    <div class="toolbar">
      <h2>Movies</h2>
      <SearchFilter v-model:search="search" v-model:year="year" />
    </div>

    <div class="content-wrap">
      <!-- Movie list -->
      <div class="movie-list">
        <div v-if="!allMovies.length && loading" class="loading">Loading…</div>
        <div v-else-if="!allMovies.length && !loading" class="empty">No movies found.</div>

        <template v-for="group in groupedMovies" :key="group.letter">
          <h3
            :id="'letter-' + group.letter"
            class="letter-header"
            :ref="el => { if (el) sectionRefs[group.letter] = el; else delete sectionRefs[group.letter] }"
          >{{ group.letter }}</h3>
          <div class="grid">
            <PosterCard
              v-for="m in group.movies"
              :key="m.id"
              :plexId="m.plexId"
              :title="m.title"
              :subtitle="m.year?.toString()"
              :watched="m.watched"
              @click="router.push(`/movies/${m.id}`)"
            >
              <template #badge>
                <DownloadButton type="MOVIE" :mediaId="m.id" small />
              </template>
            </PosterCard>
          </div>
        </template>

        <div ref="sentinel" class="sentinel" />
        <div v-if="loading && allMovies.length" class="loading-more">Loading more…</div>
      </div>

      <!-- Alphabet sidebar -->
      <nav v-if="allLetters.length" class="alpha-sidebar" aria-label="Jump to letter">
        <button
          v-for="letter in allLetters"
          :key="letter"
          :class="['alpha-btn', { active: letter === activeLetter }]"
          :title="letter"
          @click="scrollToLetter(letter)"
        >{{ letter }}</button>
      </nav>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { getMovies } from '@/api/library.js'
import PosterCard from '@/components/PosterCard.vue'
import SearchFilter from '@/components/SearchFilter.vue'
import DownloadButton from '@/components/DownloadButton.vue'

const router = useRouter()

const allMovies    = ref([])
const loading      = ref(false)
const page         = ref(0)
const hasMore      = ref(true)
const search       = ref('')
const year         = ref(null)
const sentinel     = ref(null)
const activeLetter = ref('')
const sectionRefs  = {}   // plain object — DOM refs only, no reactivity needed

// Strip leading articles for sort/grouping
function sortTitle(t) {
  return (t || '').replace(/^(the|a|an)\s+/i, '').trim()
}
function firstLetter(t) {
  const c = sortTitle(t).charAt(0).toUpperCase()
  return /[A-Z]/.test(c) ? c : '#'
}

const sortedMovies = computed(() =>
  [...allMovies.value].sort((a, b) =>
    sortTitle(a.title).localeCompare(sortTitle(b.title), undefined, { sensitivity: 'base' })
  )
)

const groupedMovies = computed(() => {
  const map = new Map()
  for (const m of sortedMovies.value) {
    const l = firstLetter(m.title)
    if (!map.has(l)) map.set(l, [])
    map.get(l).push(m)
  }
  return [...map.entries()]
    .sort(([a], [b]) => a === '#' ? 1 : b === '#' ? -1 : a.localeCompare(b))
    .map(([letter, movies]) => ({ letter, movies }))
})

const allLetters = computed(() => groupedMovies.value.map(g => g.letter))

async function loadMore() {
  if (loading.value || !hasMore.value) return
  loading.value = true
  try {
    const data = await getMovies({
      search: search.value || undefined,
      year: year.value || undefined,
      page: page.value,
      size: 100
    })
    allMovies.value.push(...data.content)
    hasMore.value = page.value < data.totalPages - 1
    page.value++
    await nextTick()
    updateActiveLetter()
  } finally {
    loading.value = false
  }
}

function reset() {
  allMovies.value = []
  page.value = 0
  hasMore.value = true
  activeLetter.value = ''
  Object.keys(sectionRefs).forEach(k => delete sectionRefs[k])
}

watch([search, year], () => { reset(); loadMore() })

// Infinite scroll via IntersectionObserver on sentinel
let scrollObserver = null
onMounted(() => {
  loadMore()
  scrollObserver = new IntersectionObserver(
    ([entry]) => { if (entry.isIntersecting) loadMore() },
    { rootMargin: '400px' }
  )
  if (sentinel.value) scrollObserver.observe(sentinel.value)
  window.addEventListener('scroll', updateActiveLetter, { passive: true })
})

onUnmounted(() => {
  scrollObserver?.disconnect()
  window.removeEventListener('scroll', updateActiveLetter)
})

// Track which letter section is near the top of viewport
// NavBar is sticky at 56px; use 70px as threshold
const SCROLL_OFFSET = 70

function updateActiveLetter() {
  let current = ''
  for (const letter of allLetters.value) {
    const el = sectionRefs[letter]
    if (!el) continue
    if (el.getBoundingClientRect().top <= SCROLL_OFFSET) current = letter
  }
  activeLetter.value = current || allLetters.value[0] || ''
}

function scrollToLetter(letter) {
  const el = sectionRefs[letter]
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<style scoped>
.movies-root { position: relative; }

.toolbar { display: flex; align-items: center; gap: 24px; margin-bottom: 24px; }
h2 { font-size: 1.5rem; font-weight: 600; }

.content-wrap {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.movie-list { flex: 1; min-width: 0; }

.letter-header {
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--accent);
  margin: 28px 0 12px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--border);
  scroll-margin-top: 70px;
}
.letter-header:first-child { margin-top: 0; }

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 16px;
  margin-bottom: 8px;
}

.loading, .empty { color: var(--text-muted); padding: 40px 0; text-align: center; }
.loading-more { color: var(--text-muted); text-align: center; padding: 20px 0; font-size: .85rem; }
.sentinel { height: 1px; }

/* Alphabet sidebar */
.alpha-sidebar {
  position: sticky;
  top: 70px;
  width: 24px;
  max-height: calc(100vh - 90px);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  flex-shrink: 0;
  overflow-y: auto;
  scrollbar-width: none;
  padding: 2px 0;
}
.alpha-sidebar::-webkit-scrollbar { display: none; }

.alpha-btn {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: .65rem;
  font-weight: 600;
  border-radius: 4px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 0;
  flex-shrink: 0;
  transition: color .1s, background .1s;
}
.alpha-btn:hover {
  color: var(--text);
  background: var(--surface2);
}
.alpha-btn.active {
  color: var(--accent);
  background: var(--surface2);
  border-radius: 4px;
}
</style>
