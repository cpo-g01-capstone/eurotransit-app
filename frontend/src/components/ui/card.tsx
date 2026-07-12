import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('rounded-2xl border border-line bg-white shadow-[0_1px_3px_rgb(26_27_32/0.07)]', className)}
      {...props}
    />
  )
}
