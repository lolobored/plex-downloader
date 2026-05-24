<template>
  <div class="poster-card" @click="$emit('click')">
    <div class="img-wrap">
      <img :src="`/api/posters/${plexId}.jpg`" :alt="title" loading="lazy" />
      <div class="badge-slot"><slot name="badge" /></div>
    </div>
    <div class="info">
      <p class="title">{{ title }}</p>
      <p v-if="subtitle" class="subtitle">{{ subtitle }}</p>
    </div>
  </div>
</template>

<script setup>
defineProps({
  plexId:   { type: String, required: true },
  title:    { type: String, required: true },
  subtitle: { type: String, default: null }
})
defineEmits(['click'])
</script>

<style scoped>
.poster-card { cursor: pointer; }
.poster-card:hover .img-wrap img { transform: scale(1.03); }
.img-wrap  { position: relative; overflow: hidden; border-radius: 6px;
             background: var(--surface2); aspect-ratio: 2/3; }
.img-wrap img { width: 100%; height: 100%; object-fit: cover;
                transition: transform .2s ease; display: block; }
.badge-slot { position: absolute; top: 6px; right: 6px; }
.info  { padding: 6px 2px 0; }
.title { font-size: .9rem; font-weight: 500; white-space: nowrap;
         overflow: hidden; text-overflow: ellipsis; }
.subtitle { font-size: .8rem; color: var(--text-muted); margin-top: 2px; }
</style>
