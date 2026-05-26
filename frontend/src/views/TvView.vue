<template>
  <div class="tv-root">
    <div class="toolbar">
      <h2>TV Shows <span v-if="totalElements > 0" class="count-badge" data-testid="count-badge">{{ totalElements }}</span></h2>
      <SearchFilter v-model:search="search" v-model:year="year" />
    </div>

    <div class="content-wrap">
      <!-- Show list -->
      <div class="show-list">
        <div v-if="!allShows.length && loading" class="loading">Loading…</div>
        <div v-else-if="!allShows.length && !loading" class="empty">No TV shows found.</div>

        <div class="grid">
          <PosterCard
            v-for="s in sortedShows"
            :key="s.id"
            :id="isFirstOfLetter(s) ? 'letter-' + firstLetter(s.title) : undefined"
            :class="isFirstOfLetter(s) ? 'letter-start' : undefined"
            :ref="el => {
              const l = firstLetter(s.title)
              if (firstShowOfLetter.get(l) !== s.id) return
              if (el) sectionRefs[l] = el.$el ?? el
              else delete sectionRefs[l]
            }"
            :plexId="s.plexId"
            :title="s.title"
            :subtitle="`${s.totalSeasons} season${s.totalSeasons === 1 ? '' : 's'}`"
            :watched="s.watched"
            @click="router.push(`/tv/${s.id}`)"
          />
        </div>

        <div ref="sentinel" class="sentinel" />
        <div v-if="loading && allShows.length" class="loading-more">Loading more…</div>
      </div>

      <!-- Alphabet sidebar -->
      <nav class="alpha-sidebar" aria-label="Jump to letter">
        <button
          v-for="letter in ALPHABET"
          :key="letter"
          :class="['alpha-btn', { active: letter === activeLetter, empty: !loadedLetters.has(letter) }]"
          :title="letter"
          @click="scrollToLetter(letter)"
        >{{ letter }}</button>
      </nav>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, onActivated, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { getShows } from '@/api/library.js'
import PosterCard from '@/components/PosterCard.vue'
import SearchFilter from '@/components/SearchFilter.vue'

defineOptions({ name: 'TvView' })

const router   = useRouter()
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('')

const allShows      = ref([])
const loading       = ref(false)
const page          = ref(0)
const hasMore       = ref(true)
const totalElements = ref(0)
const search        = ref('')
const year          = ref(null)
const sentinel      = ref(null)
const activeLetter  = ref('')
const sectionRefs   = {}

function sortTitle(t) {
  return (t || '').replace(/^(the|a|an)\s+/i, '').trim()
}
function firstLetter(t) {
  const c = sortTitle(t).charAt(0).toUpperCase()
  return /[A-Z]/.test(c) ? c : '#'
}

const sortedShows = computed(() =>
  [...allShows.value].sort((a, b) =>
    sortTitle(a.title).localeCompare(sortTitle(b.title), undefined, { sensitivity: 'base' })
  )
)

const firstShowOfLetter = computed(() => {
  const map = new Map()
  for (const s of sortedShows.value) {
    const l = firstLetter(s.title)
    if (!map.has(l)) map.set(l, s.id)
  }
  return map
})

function isFirstOfLetter(s) {
  return firstShowOfLetter.value.get(firstLetter(s.title)) === s.id
}

const allLetters = computed(() => {
  const letters = [...firstShowOfLetter.value.keys()]
  return letters.sort((a, b) => a === '#' ? 1 : b === '#' ? -1 : a.localeCompare(b))
})
const loadedLetters = computed(() => new Set(allLetters.value))

async function loadMore() {
  if (loading.value || !hasMore.value) return
  loading.value = true
  try {
    const data = await getShows({
      search: search.value || undefined,
      year: year.value || undefined,
      page: page.value,
      size: 100
    })
    allShows.value.push(...data.content)
    hasMore.value = page.value < data.totalPages - 1
    totalElements.value = data.totalElements ?? 0
    page.value++
    await nextTick()
    updateActiveLetter()
  } finally {
    loading.value = false
  }
  if (hasMore.value && sentinel.value) {
    const rect = sentinel.value.getBoundingClientRect()
    if (rect.top < window.innerHeight + 1200) loadMore()
  }
}

function reset() {
  allShows.value = []
  page.value = 0
  hasMore.value = true
  totalElements.value = 0
  activeLetter.value = ''
  Object.keys(sectionRefs).forEach(k => delete sectionRefs[k])
}

watch([search, year], () => { reset(); loadMore() })

let scrollObserver = null
onMounted(() => {
  loadMore()
  scrollObserver = new IntersectionObserver(
    ([entry]) => { if (entry.isIntersecting) loadMore() },
    { rootMargin: '1200px' }
  )
  if (sentinel.value) scrollObserver.observe(sentinel.value)
  window.addEventListener('scroll', updateActiveLetter, { passive: true })
})

onActivated(() => {
  updateActiveLetter()
})

onUnmounted(() => {
  scrollObserver?.disconnect()
  window.removeEventListener('scroll', updateActiveLetter)
})

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
  if (!loadedLetters.value.has(letter)) return
  const el = sectionRefs[letter]
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<style scoped>
.tv-root { position: relative; }

.toolbar { display: flex; align-items: center; gap: 24px; margin-bottom: 24px; }
h2 { font-size: 1.5rem; font-weight: 600; }

.content-wrap {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.show-list { flex: 1; min-width: 0; }

.letter-start { scroll-margin-top: 70px; }

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 16px;
}

.loading, .empty { color: var(--text-muted); padding: 40px 0; text-align: center; }
.loading-more { color: var(--text-muted); text-align: center; padding: 20px 0; font-size: .85rem; }
.sentinel { height: 1px; }

.count-badge { background: var(--surface2); border: 1px solid var(--border); color: var(--text-muted);
               font-size: .75rem; font-weight: 600; border-radius: 10px; padding: 2px 8px;
               margin-left: 8px; vertical-align: middle; }

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
.alpha-btn:hover:not(.empty) {
  color: var(--text);
  background: var(--surface2);
}
.alpha-btn.active {
  color: var(--accent);
  background: var(--surface2);
  border-radius: 4px;
}
.alpha-btn.empty {
  color: var(--border);
  cursor: default;
}
</style>
