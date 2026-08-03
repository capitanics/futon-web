import { Link, NavLink, Outlet } from 'react-router-dom'

const tabs = [
  { to: '/', label: 'History', icon: '◷' },
  { to: '/favorites', label: 'Favorites', icon: '♥' },
  { to: '/browse', label: 'Explore', icon: '◉' },
]

export default function Layout() {
  return (
    <div className="layout">
      <header className="topbar">
        <Link to="/" className="brand">
          futon<span>web</span>
        </Link>
        <nav>
          {tabs.map((t) => (
            <NavLink key={t.to} to={t.to} end={t.to === '/'}>
              {t.label}
            </NavLink>
          ))}
        </nav>
      </header>
      <main>
        <Outlet />
      </main>
      <nav className="bottomnav">
        {tabs.map((t) => (
          <NavLink key={t.to} to={t.to} end={t.to === '/'}>
            <span aria-hidden="true" className="bottomnav-icon">
              {t.icon}
            </span>
            {t.label}
          </NavLink>
        ))}
      </nav>
    </div>
  )
}
