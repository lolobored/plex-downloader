import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SubtitleBadge from '../SubtitleBadge.vue'

describe('SubtitleBadge', () => {
  it('shows "sub?" when scanned is false (unknown state)', () => {
    const w = mount(SubtitleBadge, { props: { langs: null, scanned: false } })
    expect(w.text()).toContain('sub?')
  })

  it('shows "sub?" when scanned is not provided (defaults to unknown)', () => {
    const w = mount(SubtitleBadge, { props: { langs: null } })
    expect(w.text()).toContain('sub?')
  })

  it('shows "no sub" when scanned=true and langs is null', () => {
    const w = mount(SubtitleBadge, { props: { langs: null, scanned: true } })
    expect(w.text()).toContain('no sub')
  })

  it('shows "no sub" when scanned=true and langs is ","', () => {
    const w = mount(SubtitleBadge, { props: { langs: ',', scanned: true } })
    expect(w.text()).toContain('no sub')
  })

  it('shows "no sub" when scanned=true and langs is empty string', () => {
    const w = mount(SubtitleBadge, { props: { langs: '', scanned: true } })
    expect(w.text()).toContain('no sub')
  })

  it('shows "SUB·2" when langs has two language codes', () => {
    const w = mount(SubtitleBadge, { props: { langs: ',eng,fra,', scanned: true } })
    expect(w.text()).toContain('SUB·2')
  })

  it('sets title attribute to joined language codes for hover', () => {
    const w = mount(SubtitleBadge, { props: { langs: ',eng,fra,', scanned: true } })
    const badge = w.find('[title]')
    expect(badge.attributes('title')).toBe('eng, fra')
  })

  it('shows "SUB·1" when langs has a single language code', () => {
    const w = mount(SubtitleBadge, { props: { langs: ',eng,', scanned: true } })
    expect(w.text()).toContain('SUB·1')
  })

  it('handles langs without surrounding commas', () => {
    const w = mount(SubtitleBadge, { props: { langs: 'eng,fra', scanned: true } })
    expect(w.text()).toContain('SUB·2')
  })

  it('reactivity: badge updates when props change between states', async () => {
    const w = mount(SubtitleBadge, { props: { langs: null, scanned: false } })
    expect(w.text()).toContain('sub?')

    await w.setProps({ scanned: true, langs: ',eng,fra,' })
    expect(w.text()).toContain('SUB·2')
    expect(w.find('[title]').attributes('title')).toContain('eng')
    expect(w.find('[title]').attributes('title')).toContain('fra')
  })
})
