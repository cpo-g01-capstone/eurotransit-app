import { Link, isRouteErrorResponse, useRouteError } from 'react-router'
import { ApiError } from '@/api/client'
import { Card } from '@/components/ui/card'

/** Shared error boundary for data-loading routes; renders inside the Shell. */
export function RouteErrorPanel() {
  const error = useRouteError()
  const notFound =
    (isRouteErrorResponse(error) && error.status === 404) ||
    (error instanceof ApiError && error.status === 404)

  return (
    <div className="mx-auto w-full max-w-3xl px-4 py-16 sm:px-6">
      <Card className="p-8 text-center">
        <p className="display-type text-3xl">{notFound ? 'Nothing at this address' : 'Something went wrong'}</p>
        <p className="mx-auto mt-3 max-w-md text-sm text-steel">
          {notFound
            ? 'That page or train does not exist (or no longer does).'
            : "We couldn't load this page right now. Please try again in a moment."}
        </p>
        <Link to="/" className="mt-5 inline-block font-semibold text-crimson hover:underline">
          Back to departures
        </Link>
      </Card>
    </div>
  )
}

export function NotFoundPage() {
  return (
    <div className="mx-auto w-full max-w-3xl px-4 py-16 sm:px-6">
      <Card className="p-8 text-center">
        <p className="font-mono text-sm text-steel">404</p>
        <p className="display-type mt-2 text-3xl">This train doesn't stop here</p>
        <Link to="/" className="mt-5 inline-block font-semibold text-crimson hover:underline">
          Back to departures
        </Link>
      </Card>
    </div>
  )
}
