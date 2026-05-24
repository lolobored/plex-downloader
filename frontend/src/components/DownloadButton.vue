<template>
  <button class="download-button" :class="{ small }">
    <!-- stub — full implementation in Task 9 -->
    Download
  </button>
</template>

<script setup>
defineProps({
  type: {
    type: String,
    required: true
  },
  mediaId: {
    type: Number,
    required: true
  },
  small: {
    type: Boolean,
    default: false
  }
})
</script>

<style scoped>
.download-button {
  padding: 8px 16px;
  border: none;
  border-radius: 4px;
  background: var(--primary);
  color: white;
  cursor: pointer;
}

.download-button.small {
  padding: 4px 8px;
  font-size: 0.875rem;
}
</style>
