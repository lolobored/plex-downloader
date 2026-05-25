<template>
  <Teleport to="body">
    <div class="modal-backdrop" @click.self="$emit('cancel')">
      <div class="modal-box" role="dialog" aria-modal="true">
        <p class="modal-message">{{ message }}</p>
        <div class="modal-actions">
          <button class="btn-cancel" @click="$emit('cancel')">{{ cancelLabel }}</button>
          <button class="btn-confirm" data-testid="confirm-btn" @click="$emit('confirm')">{{ confirmLabel }}</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
defineProps({
  message:      { type: String, required: true },
  confirmLabel: { type: String, default: 'Confirm' },
  cancelLabel:  { type: String, default: 'Cancel' }
})
defineEmits(['confirm', 'cancel'])
</script>

<style scoped>
.modal-backdrop {
  position: fixed; inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex; align-items: center; justify-content: center;
  z-index: 1000;
}
.modal-box {
  background: var(--surface, #1e1e2e);
  border: 1px solid var(--border, #333);
  border-radius: 12px;
  padding: 24px;
  max-width: 400px;
  width: 90%;
}
.modal-message { margin: 0 0 20px; font-size: 1rem; color: var(--text, #cdd6f4); }
.modal-actions  { display: flex; gap: 12px; justify-content: flex-end; }
.btn-cancel  { padding: 8px 16px; border-radius: 6px; border: 1px solid var(--border, #333);
               background: transparent; color: var(--text, #cdd6f4); cursor: pointer; }
.btn-confirm { padding: 8px 16px; border-radius: 6px; border: none;
               background: #e74c3c; color: white; cursor: pointer; }
</style>
