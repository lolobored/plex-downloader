<template>
  <span :class="['subtitle-badge', badgeClass]" :title="badgeTitle ?? undefined">{{ badgeText }}</span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  langs: {
    type: String,
    default: null
  },
  scanned: {
    type: Boolean,
    default: false
  }
})

/**
 * Parse a subtitle CSV value into an array of language codes.
 * Input like ",eng,fra," → ["eng", "fra"]
 * Strips surrounding commas, splits on ",", drops empties, lowercases.
 */
function parseLangs(langs) {
  if (!langs) return []
  return langs
    .replace(/^,+|,+$/g, '')  // strip leading/trailing commas
    .split(',')
    .map(s => s.trim().toLowerCase())
    .filter(s => s.length > 0)
}

const badge = computed(() => {
  if (!props.scanned) {
    return { text: 'sub?', cls: 'badge-subtitle-unknown', title: null }
  }
  const codes = parseLangs(props.langs)
  if (codes.length === 0) {
    return { text: 'no sub', cls: 'badge-subtitle-none', title: null }
  }
  return {
    text: `SUB·${codes.length}`,
    cls: 'badge-subtitle-has',
    title: codes.join(', ')
  }
})

const badgeText = computed(() => badge.value.text)
const badgeClass = computed(() => badge.value.cls)
const badgeTitle = computed(() => badge.value.title)
</script>

<style scoped>
.subtitle-badge {
  flex-shrink: 0;
  font-size: .7rem;
  font-weight: 600;
  border-radius: 10px;
  padding: 2px 7px;
  display: inline-block;
}

.badge-subtitle-unknown {
  background: rgba(120, 120, 140, .12);
  color: var(--text-muted);
  border: 1px solid rgba(120, 120, 140, .2);
}

.badge-subtitle-none {
  background: rgba(120, 120, 140, .18);
  color: var(--text-muted);
  border: 1px solid rgba(120, 120, 140, .3);
}

.badge-subtitle-has {
  background: rgba(52, 152, 219, .18);
  color: #5dade2;
  border: 1px solid rgba(52, 152, 219, .3);
}
</style>
