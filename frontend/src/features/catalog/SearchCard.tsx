import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { ArrowLeftRight, Search } from 'lucide-react'
import type { CatalogRoute } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Select } from '@/components/ui/select'
import { Stepper } from '@/components/ui/stepper'
import { clampPax, MAX_PASSENGERS } from './pax'

const ANY = ''

interface SearchCardProps {
  routes: CatalogRoute[]
  initial?: { from?: string; to?: string; pax?: number }
}

/**
 * The catalog API has no query parameters, so search is an honest client-side
 * filter over the full (small) route list. Options come from the live data.
 */
export function SearchCard({ routes, initial }: SearchCardProps) {
  const navigate = useNavigate()
  const [from, setFrom] = useState(initial?.from ?? ANY)
  const [to, setTo] = useState(initial?.to ?? ANY)
  const [pax, setPax] = useState(clampPax(initial?.pax ?? 1))

  const origins = [...new Set(routes.map((r) => r.origin))].sort()
  const destinations = [...new Set(routes.map((r) => r.destination))].sort()

  function swap() {
    // Only meaningful when both sides exist as counterparts; still harmless otherwise.
    setFrom(to)
    setTo(from)
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const params = new URLSearchParams()
    if (from) params.set('from', from)
    if (to) params.set('to', to)
    params.set('pax', String(pax))
    navigate(`/trains?${params.toString()}`)
  }

  return (
    <Card className="p-4 sm:p-5">
      <form onSubmit={submit} className="grid grid-cols-1 items-end gap-3 sm:grid-cols-[1fr_auto_1fr_auto_auto]">
        <label className="grid gap-1.5">
          <span className="eyebrow text-steel">From</span>
          <Select value={from} onChange={(e) => setFrom(e.target.value)} aria-label="Departure city">
            <option value={ANY}>Any city</option>
            {origins.map((city) => (
              <option key={city} value={city}>
                {city}
              </option>
            ))}
          </Select>
        </label>

        <Button
          intent="ghost"
          size="sm"
          onClick={swap}
          aria-label="Swap departure and arrival"
          className="mb-1 hidden self-end sm:inline-flex"
        >
          <ArrowLeftRight className="size-4" aria-hidden />
        </Button>

        <label className="grid gap-1.5">
          <span className="eyebrow text-steel">To</span>
          <Select value={to} onChange={(e) => setTo(e.target.value)} aria-label="Arrival city">
            <option value={ANY}>Any city</option>
            {destinations.map((city) => (
              <option key={city} value={city}>
                {city}
              </option>
            ))}
          </Select>
        </label>

        <div className="grid gap-1.5">
          <span className="eyebrow text-steel">Passengers</span>
          <Stepper label="passengers" value={pax} min={1} max={MAX_PASSENGERS} onChange={setPax} />
        </div>

        <Button type="submit" size="lg" className="sm:mb-0">
          <Search className="size-4" aria-hidden />
          Search trains
        </Button>
      </form>
    </Card>
  )
}
