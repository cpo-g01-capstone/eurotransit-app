import { createBrowserRouter, type LoaderFunctionArgs } from 'react-router'
import { getRoute, getRoutes } from '@/api/catalog'
import { Shell } from '@/components/layout/Shell'
import { isUuid } from '@/lib/uuid'
import { NotFoundPage, RouteErrorPanel } from '@/pages/ErrorPages'
import { HomePage } from '@/pages/HomePage'
import { OrderPage } from '@/pages/OrderPage'
import { RouteDetailPage } from '@/pages/RouteDetailPage'
import { TrainsPage } from '@/pages/TrainsPage'
import { TripsPage } from '@/pages/TripsPage'

/**
 * Route guards live in loaders (07b's protected-route pattern; adapted to
 * this backend's threat model: there is no auth, so the guards validate
 * untrusted URL params instead of sessions).
 */

function catalogLoader() {
  return getRoutes()
}

function routeDetailLoader({ params }: LoaderFunctionArgs) {
  const id = params.routeId ?? ''
  if (!isUuid(id)) throw new Response('Not a route id', { status: 404 })
  return getRoute(id)
}

function orderLoader({ params }: LoaderFunctionArgs) {
  const id = params.orderId ?? ''
  if (!isUuid(id)) throw new Response('Not an order id', { status: 404 })
  return { orderId: id.toLowerCase() }
}

export const router = createBrowserRouter([
  {
    path: '/',
    element: <Shell />,
    children: [
      { index: true, loader: catalogLoader, element: <HomePage />, errorElement: <RouteErrorPanel /> },
      { path: 'trains', loader: catalogLoader, element: <TrainsPage />, errorElement: <RouteErrorPanel /> },
      {
        path: 'routes/:routeId',
        loader: routeDetailLoader,
        element: <RouteDetailPage />,
        errorElement: <RouteErrorPanel />,
      },
      { path: 'orders', element: <TripsPage /> },
      { path: 'orders/:orderId', loader: orderLoader, element: <OrderPage />, errorElement: <RouteErrorPanel /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
