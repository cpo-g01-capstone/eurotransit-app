import { Minus, Plus } from 'lucide-react'

interface StepperProps {
  label: string
  value: number
  min: number
  max: number
  onChange: (value: number) => void
}

/** Integer stepper clamped to [min, max] — client-side validation as UX (07b); the server re-checks. */
export function Stepper({ label, value, min, max, onChange }: StepperProps) {
  const clamp = (n: number) => Math.min(max, Math.max(min, Math.trunc(n)))
  return (
    <div className="inline-flex h-11 items-center rounded-lg border border-ink/20 bg-white">
      <button
        type="button"
        aria-label={`Fewer ${label}`}
        disabled={value <= min}
        onClick={() => onChange(clamp(value - 1))}
        className="grid h-full w-10 cursor-pointer place-items-center text-ink transition-colors hover:text-crimson disabled:cursor-not-allowed disabled:opacity-30"
      >
        <Minus className="size-4" />
      </button>
      <output aria-live="polite" className="w-8 text-center font-mono text-sm font-semibold">
        {value}
      </output>
      <button
        type="button"
        aria-label={`More ${label}`}
        disabled={value >= max}
        onClick={() => onChange(clamp(value + 1))}
        className="grid h-full w-10 cursor-pointer place-items-center text-ink transition-colors hover:text-crimson disabled:cursor-not-allowed disabled:opacity-30"
      >
        <Plus className="size-4" />
      </button>
    </div>
  )
}
