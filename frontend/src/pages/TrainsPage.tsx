import { Link, useLoaderData, useSearchParams } from 'react-router'
import type { CatalogRoute } from '@/api/types'
import { RouteCard } from '@/features/catalog/RouteCard'
import { SearchCard } from '@/features/catalog/SearchCard'
import { clampPax } from '@/features/catalog/pax'

export function TrainsPage() {
  const routes = useLoaderData() as CatalogRoute[]
  const [searchParams] = useSearchParams()
  const from = searchParams.get('from') ?? ''
  const to = searchParams.get('to') ?? ''
  const pax = clampPax(Number(searchParams.get('pax') ?? '1'))

  const results = routes.filter(
    (r) => (from === '' || r.origin === from) && (to === '' || r.destination === to),
  )

  return (
    <div className="mx-auto w-full max-w-6xl px-4 py-10 sm:px-6">
      <p className="eyebrow text-crimson">Trains</p>
      <h1 className="display-type mt-2 text-4xl">
        {from || to ? (
          <>
            {from || 'Anywhere'} <span className="text-crimson">→</span> {to || 'Anywhere'}
          </>
        ) : (
          'All connections'
        )}
      </h1>

      <div className="mt-6">
        <SearchCard routes={routes} initial={{ from, to, pax }} />
      </div>

      <p className="mt-8 font-mono text-xs tracking-wide text-steel uppercase">
        {results.length} of {routes.length} connections
        {pax > 1 ? ` · ${pax} passengers` : ''}
      </p>

      {results.length > 0 ? (
        <div className="mt-4 grid gap-4">
          {results.map((route) => (
            <RouteCard key={route.id} route={route} pax={pax} />
          ))}
        </div>
      ) : (
        <div className="mt-4 rounded-2xl border border-dashed border-line bg-white p-10 text-center">
          <p className="display-type text-2xl">No trains on this connection</p>
          <p className="mx-auto mt-2 max-w-md text-sm text-steel">
            The network currently runs {routes.length} route{routes.length === 1 ? '' : 's'}. Try a
            different pair of cities, or browse everything.
          </p>
          <Link to="/trains" className="mt-5 inline-block font-semibold text-crimson hover:underline">
            Show all connections
          </Link>
        </div>
      )}
    </div>
  )
}
