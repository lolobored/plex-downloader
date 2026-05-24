<template>
  <div class="search-filter">
    <input
      type="search"
      v-model="localSearch"
      placeholder="Search…"
      @input="emit('update:search', localSearch)"
      class="search-input"
    />
    <select v-model="localYear" @change="emit('update:year', localYear ? Number(localYear) : null)" class="year-select">
      <option value="">Any year</option>
      <option v-for="y in years" :key="y" :value="y">{{ y }}</option>
    </select>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  search: { type: String, default: '' },
  year:   { type: Number, default: null }
})
const emit = defineEmits(['update:search', 'update:year'])

const localSearch = ref(props.search)
const localYear   = ref(props.year ?? '')

const currentYear = new Date().getFullYear()
const years = computed(() => {
  const arr = []
  for (let y = currentYear; y >= 1970; y--) arr.push(y)
  return arr
})
</script>

<style scoped>
.search-filter { display: flex; gap: 12px; align-items: center; }
.search-input { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
                border-radius: 6px; padding: 8px 14px; font-size: .9rem; flex: 1; max-width: 320px; }
.search-input:focus { outline: none; border-color: var(--accent-blue); }
.year-select { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
               border-radius: 6px; padding: 8px 12px; font-size: .9rem; }
</style>
