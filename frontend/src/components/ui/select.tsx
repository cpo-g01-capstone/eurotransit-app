import type { SelectHTMLAttributes } from 'react'
import { ChevronDown } from 'lucide-react'
import { cn } from '@/lib/cn'

/**
 * Styled native <select>: keyboard/screen-reader behaviour for free and zero
 * extra dependencies (07b: small dependency footprint for the supply chain).
 */
export function Select({ className, children, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <span className={cn('relative block', className)}>
      <select
        className="h-11 w-full cursor-pointer appearance-none rounded-lg border border-ink/20 bg-white pr-9 pl-3 text-sm font-medium text-ink"
        {...props}
      >
        {children}
      </select>
      <ChevronDown aria-hidden className="pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-steel" />
    </span>
  )
}
