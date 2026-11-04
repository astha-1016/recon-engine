import { useState } from 'react'
import { getSession, setSession, type Session } from './api'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Exceptions from './pages/Exceptions'
import Upload from './pages/Upload'
import Invoices from './pages/Invoices'
import Reports from './pages/Reports'
import Credits from './pages/Credits'
import Decisions from './pages/Decisions'
import Payments from './pages/Payments'
import Settings from './pages/Settings'

const TABS = ['Dashboard', 'Exceptions', 'Credits', 'Decisions', 'Upload', 'Invoices', 'Payments', 'Reports', 'Settings'] as const
type Tab = (typeof TABS)[number]

export default function App() {
  const [session, setLocal] = useState<Session | null>(getSession())
  const [tab, setTab] = useState<Tab>('Dashboard')

  if (!session) return <Login onLogin={(s) => { setSession(s); setLocal(s) }} />

  return (
    <>
      <header className="top">
        <h1>Reconciliation Engine</h1>
        <nav>
          {TABS.map((t) => (
            <button key={t} className={t === tab ? 'active' : ''} onClick={() => setTab(t)}>{t}</button>
          ))}
        </nav>
        <span className="muted small">{session.username} · {session.role}</span>
        <button className="btn secondary" onClick={() => { setSession(null); setLocal(null) }}>Sign out</button>
      </header>
      <main>
        {tab === 'Dashboard' && <Dashboard session={session} goTo={() => setTab('Exceptions')} />}
        {tab === 'Exceptions' && <Exceptions session={session} />}
        {tab === 'Credits' && <Credits session={session} />}
        {tab === 'Decisions' && <Decisions session={session} />}
        {tab === 'Upload' && <Upload session={session} />}
        {tab === 'Invoices' && <Invoices session={session} />}
        {tab === 'Payments' && <Payments session={session} />}
        {tab === 'Reports' && <Reports session={session} />}
        {tab === 'Settings' && <Settings session={session} />}
      </main>
    </>
  )
}
