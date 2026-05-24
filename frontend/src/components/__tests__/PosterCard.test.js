import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PosterCard from '../PosterCard.vue'

describe('PosterCard', () => {
  it('renders title', () => {
    const w = mount(PosterCard, { props: { title: 'Inception', plexId: 'abc123' } })
    expect(w.text()).toContain('Inception')
  })

  it('uses /api/posters/{plexId}.jpg as img src', () => {
    const w = mount(PosterCard, { props: { title: 'Test', plexId: 'abc123' } })
    const img = w.find('img')
    expect(img.attributes('src')).toBe('/api/posters/abc123.jpg')
  })

  it('renders subtitle when provided', () => {
    const w = mount(PosterCard, { props: { title: 'Sopranos', plexId: 'x', subtitle: '6 seasons' } })
    expect(w.text()).toContain('6 seasons')
  })

  it('renders badge slot when provided', () => {
    const w = mount(PosterCard, {
      props: { title: 'Test', plexId: 'x' },
      slots: { badge: '<span class="test-badge">✓</span>' }
    })
    expect(w.find('.test-badge').exists()).toBe(true)
  })

  it('emits click when card clicked', async () => {
    const w = mount(PosterCard, { props: { title: 'Test', plexId: 'x' } })
    await w.find('.poster-card').trigger('click')
    expect(w.emitted('click')).toBeTruthy()
  })
})
