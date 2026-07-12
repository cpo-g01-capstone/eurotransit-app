import type { ButtonHTMLAttributes } from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/cn'

const buttonVariants = cva(
  'inline-flex cursor-pointer items-center justify-center gap-2 rounded-lg font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-45',
  {
    variants: {
      intent: {
        primary: 'bg-crimson text-white hover:bg-crimson-deep',
        dark: 'bg-ink text-paper hover:bg-ink-soft',
        outline: 'border border-ink/25 bg-white text-ink hover:border-crimson hover:text-crimson',
        ghost: 'text-crimson hover:bg-crimson/10',
      },
      size: {
        sm: 'h-8 px-3 text-xs',
        md: 'h-10 px-4 text-sm',
        lg: 'h-12 px-6 text-base',
      },
    },
    defaultVariants: { intent: 'primary', size: 'md' },
  },
)

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {}

export function Button({ className, intent, size, type = 'button', ...props }: ButtonProps) {
  return <button type={type} className={cn(buttonVariants({ intent, size }), className)} {...props} />
}
