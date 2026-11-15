import { lazy, Suspense } from 'react'
import { createBrowserRouter, Navigate } from 'react-router-dom'
import AppLayout  from '@/components/layout/AppLayout'
import PageLoader from '@/components/ui/PageLoader'

// App
const DashboardPage = lazy(() => import('@/pages/DashboardPage'))

const S = ({ children }) => <Suspense fallback={<PageLoader />}>{children}</Suspense>

export const router = createBrowserRouter([
  // Aplikacja jest dostępna bez logowania — brak tras/guardów auth
  {
    element: <AppLayout />,
    children: [
      { index: true,        element: <Navigate to="/dashboard" replace /> },
      { path: '/dashboard', element: <S><DashboardPage /></S> },
    ],
  },
  { path: '*', element: <Navigate to="/dashboard" replace /> },
])
