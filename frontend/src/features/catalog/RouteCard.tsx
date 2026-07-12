import { Link } from 'react-router'
import { ArrowRight } from 'lucide-react'
import type { CatalogRoute } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { formatDate, formatEUR, formatTime } from '@/lib/format'

export function AvailabilityBadge({ route, pax = 1 }: { route: CatalogRoute; pax?: number }) {
  if (route.availableSeats <= 0) return <Badge tone="fail">Sold out</Badge>
  if (route.availableSeats < pax) return <Badge tone="wait">Only {route.availableSeats} left — not enough for {pax}</Badge>
  if (route.availableSeats <= 5) return <Badge tone="wait">Only {route.availableSeats} left</Badge>
  return <Badge tone="go">{route.availableSeats} seats</Badge>
}

interface RouteCardProps {
  route: CatalogRoute
  pax?: number
}

/** Boarding-pass style result card — every value on it comes from GET /api/catalog. */
export function RouteCard({ route, pax = 1 }: RouteCardProps) {
  const bookable = route.availableSeats > 0
  return (
    <Card className="grid grid-cols-1 gap-4 p-5 sm:grid-cols-[auto_1fr_auto] sm:items-center">
      <div className="border-line sm:border-r sm:pr-5">
        <p className="font-mono text-2xl font-semibold">{formatTime(route.departureTime)}</p>
        <p className="text-sm text-steel">{formatDate(route.departureTime)}</p>
      </div>

      <div className="min-w-0">
        <p className="display-type flex flex-wrap items-center gap-x-3 text-2xl sm:text-3xl">
          {route.origin}
          <ArrowRight aria-hidden className="size-6 shrink-0 text-crimson" />
          {route.destination}
        </p>
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <AvailabilityBadge route={route} pax={pax} />
          <span className="text-xs text-steel">Seats sell fast — availability may change until checkout.</span>
        </div>
      </div>

      <div className="flex items-center justify-between gap-4 border-line sm:flex-col sm:items-end sm:border-l sm:pl-5">
        <p className="font-mono text-2xl font-semibold">
          {formatEUR(route.price)}
          <span className="ml-1 text-xs font-normal text-steel">/seat</span>
        </p>
        {bookable ? (
          <Link
            to={`/routes/${route.id}?pax=${pax}`}
            className="inline-flex h-10 items-center rounded-lg bg-crimson px-5 text-sm font-semibold text-white transition-colors hover:bg-crimson-deep"
          >
            Select
          </Link>
        ) : (
          <span className="inline-flex h-10 cursor-not-allowed items-center rounded-lg bg-paper-dim px-5 text-sm font-semibold text-steel">
            Sold out
          </span>
        )}
      </div>
    </Card>
  )
}
