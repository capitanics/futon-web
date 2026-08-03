import { Link, NavLink, Outlet } from 'react-router-dom'

export default function Layout() {
  return (
    <div className="layout">
      <header className="topbar">
        <Link to="/" className="brand">
          futon<span>web</span>
        </Link>
        <nav>
          <NavLink to="/">Library</NavLink>
          <NavLink to="/browse">Browse</NavLink>
        </nav>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  )
}
