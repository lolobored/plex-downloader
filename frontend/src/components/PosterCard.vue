<template>
  <div class="poster-card" @click="$emit('click')">
    <div class="img-wrap">
      <img :src="`/api/posters/${plexId}.jpg`" :alt="title" />
      <div v-if="watched" class="watched-badge" title="Watched">✓</div>
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
  subtitle: { type: String, default: null },
  watched:  { type: Boolean, default: false }
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
.watched-badge {
  position: absolute; top: 6px; left: 6px;
  width: 22px; height: 22px; border-radius: 50%;
  background: var(--green); color: #fff;
  font-size: .75rem; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 1px 4px rgba(0,0,0,.5);
}
.info  { padding: 6px 2px 0; }
.title { font-size: .9rem; font-weight: 500; white-space: nowrap;
         overflow: hidden; text-overflow: ellipsis; }
.subtitle { font-size: .8rem; color: var(--text-muted); margin-top: 2px; }
</style>
