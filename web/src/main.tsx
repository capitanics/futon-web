import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter, RouterProvider, useRouteError } from 'react-router-dom'
import Home from './pages/Home'
import Browse from './pages/Browse'
import Favorites from './pages/Favorites'
import MangaPage from './pages/Manga'
import Reader from './pages/Reader'
import Layout from './Layout'
import '@fontsource/roboto/400.css'
import '@fontsource/roboto/500.css'
import '@fontsource/roboto/700.css'
import './styles.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 5 * 60_000, retry: 1, refetchOnWindowFocus: false },
  },
})

function ErrorPage() {
  const error = useRouteError()
  const message =
    error instanceof Error ? error.message : 'Something unexpected happened.'
  return (
    <div className="page crash-page">
      <h1>Something broke</h1>
      <p className="dim">{message}</p>
      <div className="action-row">
        <a className="button" href="/">
          Back to library
        </a>
        <button className="button ghost" onClick={() => location.reload()}>
          Reload
        </button>
      </div>
    </div>
  )
}

const router = createBrowserRouter([
  {
    element: <Layout />,
    errorElement: <ErrorPage />,
    children: [
      { path: '/', element: <Home /> },
      { path: '/browse', element: <Browse /> },
      { path: '/favorites', element: <Favorites /> },
      { path: '/manga/:mangaId', element: <MangaPage /> },
    ],
  },
  // reader is fullscreen, outside the chrome
  { path: '/read/:mangaId/:chapterId', element: <Reader />, errorElement: <ErrorPage /> },
])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
