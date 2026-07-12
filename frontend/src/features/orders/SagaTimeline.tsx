import type { ComponentType } from 'react'
import { Armchair, Check, CreditCard, MailCheck, ReceiptText, X } from 'lucide-react'
import type { OrderStatus } from '@/api/types'
import { cn } from '@/lib/cn'

/**
 * The signature element: the order's journey drawn as a rail line.
 * Each station is a real stage of the backend saga — orders → inventory →
 * payments → notifications — driven only by the polled order status.
 */

interface Station {
  title: string
  detail: string
  icon: ComponentType<{ className?: string }>
}

const STATIONS: Station[] = [
  {
    title: 'Order placed',
    detail: "we've received your booking request",
    icon: ReceiptText,
  },
  {
    title: 'Seats reserved',
    detail: 'your seats are being held on the train',
    icon: Armchair,
  },
  {
    title: 'Payment authorised',
    detail: 'your payment has gone through',
    icon: CreditCard,
  },
  {
    title: 'Booked — confirmation sent',
    detail: 'your confirmation is on its way',
    icon: MailCheck,
  },
]

/** How many stations are completed for a given status. */
function completedCount(status: OrderStatus): number {
  switch (status) {
    case 'DRAFT':
      return 1
    case 'RESERVED':
      return 2
    case 'CONFIRMED':
      return 4
    case 'FAILED':
      return 1 // the order existed; where it derailed isn't exposed by the API
  }
}

type NodeState = 'done' | 'active' | 'todo' | 'failed'

export function SagaTimeline({ status }: { status: OrderStatus }) {
  const done = completedCount(status)
  const failed = status === 'FAILED'

  return (
    <ol className="grid gap-0">
      {STATIONS.map((station, index) => {
        const state: NodeState = failed
          ? index < done
            ? 'done'
            : 'failed'
          : index < done
            ? 'done'
            : index === done
              ? 'active'
              : 'todo'
        const isLast = index === STATIONS.length - 1
        const Icon = station.icon

        return (
          <li key={station.title} className="grid grid-cols-[2.75rem_1fr] gap-x-4">
            <div className="flex flex-col items-center">
              <span
                className={cn(
                  'grid size-11 shrink-0 place-items-center rounded-full border-2 transition-colors',
                  state === 'done' && 'border-crimson bg-crimson text-white',
                  state === 'active' && 'animate-pulse-node border-crimson bg-white text-crimson',
                  state === 'todo' && 'border-line bg-white text-steel/60',
                  state === 'failed' && 'border-fail/40 bg-fail-soft text-fail/70',
                )}
              >
                {state === 'done' ? (
                  <Check className="size-5" aria-hidden />
                ) : state === 'failed' ? (
                  <X className="size-5" aria-hidden />
                ) : (
                  <Icon className="size-5" aria-hidden />
                )}
              </span>
              {!isLast && (
                <span
                  aria-hidden
                  className={cn(
                    'w-0.5 flex-1',
                    state === 'done' && !failed && 'bg-crimson',
                    state === 'active' &&
                      'animate-rail-dash bg-[linear-gradient(to_bottom,var(--color-crimson)_55%,transparent_55%)] bg-[length:2px_14px] bg-repeat-y',
                    (state === 'todo' || failed) && index >= done && 'bg-line',
                    failed && index < done && 'bg-fail/40',
                  )}
                  style={{ minHeight: '2rem' }}
                />
              )}
            </div>

            <div className={cn('pb-8', isLast && 'pb-0')}>
              <p
                className={cn(
                  'pt-2 font-semibold',
                  state === 'todo' && 'text-steel/70',
                  state === 'failed' && 'text-fail/70 line-through decoration-fail/40',
                )}
              >
                {station.title}
                <span className="sr-only">
                  {state === 'done' ? ' — completed' : state === 'active' ? ' — in progress' : state === 'failed' ? ' — not reached' : ' — pending'}
                </span>
              </p>
              <p className="mt-0.5 font-mono text-xs text-steel">{station.detail}</p>
            </div>
          </li>
        )
      })}
    </ol>
  )
}
