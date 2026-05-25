import { mount } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import ConfirmModal from '../ConfirmModal.vue'

// With Teleport, content is rendered into document.body.
// We attach the wrapper to document.body so @vue/test-utils can resolve the teleport target,
// then query via document.body for the teleported nodes.
const mountModal = (props = {}) => mount(ConfirmModal, {
  props: { message: 'Are you sure?', ...props },
  attachTo: document.body
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('ConfirmModal', () => {
  it('renders message', () => {
    mountModal({ message: 'Delete 3 items?' })
    expect(document.body.textContent).toContain('Delete 3 items?')
  })

  it('uses default button labels', () => {
    mountModal()
    expect(document.body.textContent).toContain('Confirm')
    expect(document.body.textContent).toContain('Cancel')
  })

  it('uses custom button labels', () => {
    mountModal({ confirmLabel: 'Yes, delete', cancelLabel: 'Keep' })
    expect(document.body.textContent).toContain('Yes, delete')
    expect(document.body.textContent).toContain('Keep')
  })

  it('emits confirm on confirm button click', async () => {
    const wrapper = mountModal()
    const btn = document.body.querySelector('[data-testid="confirm-btn"]')
    btn.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('confirm')).toBeTruthy()
  })

  it('emits cancel on cancel button click', async () => {
    const wrapper = mountModal()
    const btn = document.body.querySelector('.btn-cancel')
    btn.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('cancel')).toBeTruthy()
  })

  it('emits cancel on backdrop click', async () => {
    const wrapper = mountModal()
    const backdrop = document.body.querySelector('.modal-backdrop')
    backdrop.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('cancel')).toBeTruthy()
  })
})
