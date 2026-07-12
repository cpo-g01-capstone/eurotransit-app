import { Link, useLoaderData, useSearchParams } from 'react-router'
import { ArrowLeft, ArrowRight } from 'lucide-react'
import type { CatalogRoute } from '@/api/types'
import { Card } from '@/components/ui/card'
import { AvailabilityBadge } from '@/features/catalog/RouteCard'
import { clampPax } from '@/features/catalog/pax'
import { CheckoutPanel } from '@/features/checkout/CheckoutPanel'
import { formatDate, formatEUR, formatTime } from '@/lib/format'

export function RouteDetailPage() {
  const route = useLoaderData() as CatalogRoute
  const [searchParams] = useSearchParams()
  const pax = clampPax(Number(searchParams.get('pax') ?? '1'))

  return (
    <div className="mx-auto w-full max-w-6xl px-4 py-10 sm:px-6">
      <Link
        to="/trains"
        className="inline-flex items-center gap-1.5 text-sm font-semibold text-steel transition-colors hover:text-crimson"
      >
        <ArrowLeft className="size-4" aria-hidden /> All trains
      </Link>

      <div className="mt-6 grid items-start gap-6 lg:grid-cols-[1.5fr_1fr]">
        <Card className="p-6 sm:p-8">
          <p className="eyebrow text-crimson">Your journey</p>
          <h1 className="display-type mt-3 flex flex-wrap items-center gap-x-4 text-4xl sm:text-5xl">
            {route.origin}
            <ArrowRight aria-hidden className="size-9 shrink-0 text-crimson" />
            {route.destination}
          </h1>

          <dl className="mt-8 grid grid-cols-2 gap-6 sm:grid-cols-3">
            <div>
              <dt className="eyebrow text-steel">Departure</dt>
              <dd className="mt-1 font-mono text-xl font-semibold">{formatTime(route.departureTime)}</dd>
              <dd className="text-sm text-steel">{formatDate(route.departureTime)}</dd>
            </div>
            <div>
              <dt className="eyebrow text-steel">Price per seat</dt>
              <dd className="mt-1 font-mono text-xl font-semibold">{formatEUR(route.price)}</dd>
            </div>
            <div>
              <dt className="eyebrow text-steel">Availability</dt>
              <dd className="mt-1.5">
                <AvailabilityBadge route={route} pax={pax} />
              </dd>
              <dd className="mt-1 text-xs text-steel">
                {route.availableSeats} of {route.totalSeats} seats still free — availability may
                change until checkout.
              </dd>
            </div>
          </dl>

        </Card>

        <CheckoutPanel route={route} initialSeats={pax} />
      </div>
    </div>
  )
}
