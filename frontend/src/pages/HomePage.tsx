import { useLoaderData } from 'react-router'
import type { CatalogRoute } from '@/api/types'
import { RouteCard } from '@/features/catalog/RouteCard'
import { SearchCard } from '@/features/catalog/SearchCard'

export function HomePage() {
  const routes = useLoaderData() as CatalogRoute[]

  return (
    <>
      <section className="bg-[linear-gradient(135deg,var(--color-crimson-deep),var(--color-crimson)_55%,var(--color-crimson-bright))] text-white">
        <div className="mx-auto w-full max-w-6xl px-4 pt-16 pb-28 sm:px-6 sm:pt-24 sm:pb-36">
          <p className="eyebrow text-white/70">EuroTransit high-speed network</p>
          <h1 className="display-type mt-4 max-w-3xl text-5xl sm:text-7xl">
            Next stop,
            <br />
            wherever.
          </h1>
          <p className="mt-5 max-w-xl text-base text-white/85 sm:text-lg">
            High-speed comfort between Italy's great cities, at fares that make sense. Pick your
            train, choose your seats, and you're ready to go.
          </p>
        </div>
      </section>

      <section className="mx-auto w-full max-w-6xl px-4 sm:px-6">
        <div className="-mt-16 sm:-mt-20">
          <SearchCard routes={routes} />
        </div>
      </section>

      <section className="mx-auto w-full max-w-6xl px-4 py-14 sm:px-6">
        <p className="eyebrow text-crimson">Departures</p>
        <h2 className="display-type mt-2 text-3xl">Today on the network</h2>
        <div className="mt-6 grid gap-4">
          {routes.map((route) => (
            <RouteCard key={route.id} route={route} />
          ))}
        </div>
        <p className="mt-4 font-mono text-xs text-steel">
          {routes.length} route{routes.length === 1 ? '' : 's'} on sale today · availability may
          change until checkout
        </p>
      </section>
    </>
  )
}
