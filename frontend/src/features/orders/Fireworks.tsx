import { useEffect, useRef } from 'react'
import gsap from 'gsap'

/**
 * Celebration fireworks for a live payment confirmation, animated with GSAP.
 * Renders a full-screen non-interactive overlay, fires a few particle bursts,
 * then calls onDone so the parent unmounts it. Skipped entirely under
 * prefers-reduced-motion.
 */

const COLORS = ['#c8102e', '#e6173a', '#e6a817', '#1e7f4f', '#faf7f2']
const BURSTS = 5
const PARTICLES_PER_BURST = 28

export function Fireworks({ onDone }: { onDone: () => void }) {
  const rootRef = useRef<HTMLDivElement>(null)
  const onDoneRef = useRef(onDone)
  onDoneRef.current = onDone

  useEffect(() => {
    const root = rootRef.current
    if (!root) return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      onDoneRef.current()
      return
    }

    const ctx = gsap.context(() => {
      for (let b = 0; b < BURSTS; b++) {
        const originX = (12 + Math.random() * 76) / 100
        const originY = (12 + Math.random() * 38) / 100
        const delay = b * 0.32
        const hueOffset = Math.floor(Math.random() * COLORS.length)

        for (let i = 0; i < PARTICLES_PER_BURST; i++) {
          const particle = document.createElement('span')
          const size = i % 4 === 0 ? 9 : 5
          particle.style.position = 'absolute'
          particle.style.left = `${originX * 100}%`
          particle.style.top = `${originY * 100}%`
          particle.style.width = `${size}px`
          particle.style.height = `${size}px`
          particle.style.borderRadius = '50%'
          particle.style.background = COLORS[(hueOffset + i) % COLORS.length]
          particle.style.opacity = '0'
          root.appendChild(particle)

          const angle = (i / PARTICLES_PER_BURST) * Math.PI * 2 + Math.random() * 0.4
          const distance = 70 + Math.random() * 130
          const tl = gsap.timeline({ delay })
          tl.set(particle, { opacity: 1 })
            .to(particle, {
              x: Math.cos(angle) * distance,
              y: Math.sin(angle) * distance,
              duration: 0.65 + Math.random() * 0.3,
              ease: 'power3.out',
            })
            .to(
              particle,
              // Gravity tail: drift down while fading out.
              { y: `+=${55 + Math.random() * 45}`, opacity: 0, scale: 0.35, duration: 0.7, ease: 'power1.in' },
              '>-0.1',
            )
        }
      }
      gsap.delayedCall(BURSTS * 0.32 + 1.8, () => onDoneRef.current())
    }, root)

    return () => ctx.revert()
  }, [])

  return (
    <div
      ref={rootRef}
      aria-hidden
      data-testid="fireworks"
      className="pointer-events-none fixed inset-0 z-50 overflow-hidden"
    />
  )
}
