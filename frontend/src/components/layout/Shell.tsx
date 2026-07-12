import { NavLink, Link, Outlet, ScrollRestoration } from 'react-router'
import { TrainFront } from 'lucide-react'
import { cn } from '@/lib/cn'

function HeaderLink({ to, children }: { to: string; children: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          'rounded-lg px-3 py-2 text-sm font-semibold transition-colors',
          isActive ? 'bg-crimson/10 text-crimson' : 'text-ink-soft hover:text-crimson',
        )
      }
    >
      {children}
    </NavLink>
  )
}

export function Shell() {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="sticky top-0 z-40 border-b border-line bg-paper/92 backdrop-blur">
        <div className="mx-auto flex h-16 w-full max-w-6xl items-center justify-between px-4 sm:px-6">
          <Link to="/" className="flex items-center gap-2" aria-label="EuroTransit home">
            <TrainFront aria-hidden className="size-6 text-crimson" />
            <span className="display-type text-xl tracking-tight">
              Euro<span className="text-crimson">Transit</span>
            </span>
          </Link>
          <nav className="flex items-center gap-1">
            <HeaderLink to="/trains">Trains</HeaderLink>
            <HeaderLink to="/orders">My trips</HeaderLink>
          </nav>
        </div>
      </header>

      <main className="flex-1">
        <Outlet />
      </main>

      <footer className="border-t border-line bg-ink text-paper">
        <div className="mx-auto flex w-full max-w-6xl flex-col gap-2 px-4 py-8 sm:px-6">
          <p className="eyebrow text-paper/60">EuroTransit · est. 2026</p>
          <p className="max-w-xl text-sm text-paper/80">
            Cloud Programming and Operations capstone, Politecnico di Torino 2025-26. A demo
            marketplace running live on Kubernetes — not a real carrier, and no real payments.
          </p>
          <p className="font-mono text-xs text-paper/50">
            catalog · orders · inventory · payments · notifications
          </p>
        </div>
      </footer>
      <ScrollRestoration />
    </div>
  )
}
