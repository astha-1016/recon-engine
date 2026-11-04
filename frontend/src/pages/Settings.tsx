import { useCallback, useEffect, useState } from 'react'
import { api, type Session, type UserView } from '../api'

const ROLES: UserView['role'][] = ['ADMIN', 'ACCOUNTANT', 'VIEWER']

/** Everyone: change own password. Admins: manage staff accounts and reset the business data. */
export default function Settings({ session }: { session: Session }) {
  const isAdmin = session.role === 'ADMIN'
  return (
    <>
      <h2>Settings</h2>
      <ChangePassword />
      {isAdmin && <Users session={session} />}
      {isAdmin && <ResetData />}
    </>
  )
}

function ChangePassword() {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)

  async function submit() {
    try {
      await api('/auth/change-password', { method: 'POST', body: JSON.stringify({ currentPassword: current, newPassword: next }) })
      setCurrent(''); setNext('')
      setMsg({ ok: true, text: 'Password changed.' })
    } catch (e) {
      setMsg({ ok: false, text: (e as Error).message })
    }
  }

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <h3 style={{ marginTop: 0 }}>Change my password</h3>
      <div className="row">
        <input type="password" placeholder="Current password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        <input type="password" placeholder="New password (10+ characters)" value={next} onChange={(e) => setNext(e.target.value)} />
        <button className="btn" onClick={submit} disabled={!current || !next}>Change password</button>
      </div>
      {msg && <p className={`small ${msg.ok ? 'muted' : 'err'}`}>{msg.text}</p>}
    </div>
  )
}

function Users({ session }: { session: Session }) {
  const [users, setUsers] = useState<UserView[]>([])
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<UserView['role']>('ACCOUNTANT')
  const [msg, setMsg] = useState('')

  const load = useCallback(() => api<UserView[]>('/users').then(setUsers).catch((e) => setMsg(e.message)), [])
  useEffect(() => { load() }, [load])

  async function run(action: () => Promise<unknown>, done: string) {
    try { await action(); setMsg(done); load() } catch (e) { setMsg((e as Error).message) }
  }

  const add = () => run(async () => {
    await api('/users', { method: 'POST', body: JSON.stringify({ username, password, role }) })
    setUsername(''); setPassword('')
  }, 'User added.')

  const changeRole = (u: UserView, r: string) => run(() => api(`/users/${u.id}/role`, { method: 'PUT', body: JSON.stringify({ role: r }) }), `${u.username} is now ${r}.`)

  function resetPassword(u: UserView) {
    const p = window.prompt(`New password for ${u.username} (10+ characters):`)
    if (p) run(() => api(`/users/${u.id}/password`, { method: 'PUT', body: JSON.stringify({ password: p }) }), `Password for ${u.username} changed.`)
  }

  function remove(u: UserView) {
    if (window.confirm(`Delete the account ${u.username}?`)) run(() => api(`/users/${u.id}`, { method: 'DELETE' }), 'User deleted.')
  }

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <h3 style={{ marginTop: 0 }}>Users</h3>
      <p className="small muted">Admin: everything, including delete and reset. Accountant: upload, add and edit records, decide exceptions. Viewer: read only. A removed user’s current sign-in stays valid until it expires (up to 8 hours).</p>
      <table>
        <thead><tr><th>Username</th><th>Role</th><th /></tr></thead>
        <tbody>
          {users.map((u) => (
            <tr key={u.id}>
              <td>{u.username}{u.username === session.username && <span className="muted small"> (you)</span>}</td>
              <td>
                <select value={u.role} onChange={(e) => changeRole(u, e.target.value)}>
                  {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                </select>
              </td>
              <td>
                <div className="row" style={{ flexWrap: 'nowrap' }}>
                  <button className="btn secondary" onClick={() => resetPassword(u)}>Set password</button>
                  <button className="btn danger" onClick={() => remove(u)} disabled={u.username === session.username}>Delete</button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="row" style={{ marginTop: 12 }}>
        <input placeholder="New username" value={username} onChange={(e) => setUsername(e.target.value)} />
        <input type="password" placeholder="Password (10+ characters)" value={password} onChange={(e) => setPassword(e.target.value)} />
        <select value={role} onChange={(e) => setRole(e.target.value as UserView['role'])}>
          {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
        </select>
        <button className="btn" onClick={add} disabled={!username || !password}>Add user</button>
      </div>
      {msg && <p className="small muted">{msg}</p>}
    </div>
  )
}

function ResetData() {
  const [summary, setSummary] = useState<{ customers: number; invoices: number; transactions: number } | null>(null)
  const [confirm, setConfirm] = useState('')
  const [msg, setMsg] = useState('')

  const load = useCallback(() => api<{ customers: number; invoices: number; transactions: number }>('/admin/summary').then(setSummary).catch(() => setSummary(null)), [])
  useEffect(() => { load() }, [load])

  async function reset() {
    try {
      await api('/admin/reset', { method: 'POST', body: JSON.stringify({ confirm }) })
      setConfirm('')
      setMsg('All customers, invoices, payments, results and decisions were removed. You can now upload your own data.')
      load()
    } catch (e) {
      setMsg((e as Error).message)
    }
  }

  return (
    <div className="card danger-zone">
      <h3 style={{ marginTop: 0 }}>Remove demo data</h3>
      <p className="small muted">
        Use this once, before loading your real data, if you tried the sample files first. It deletes every customer, invoice, bank payment,
        match result, manual decision and learned payer name{summary ? ` (currently ${summary.customers} customers, ${summary.invoices} invoices, ${summary.transactions} payments)` : ''}.
        Users, the audit log and the trained model are kept. This cannot be undone: take a backup first (see docs/OPERATIONS.md).
      </p>
      <div className="row">
        <input placeholder="Type RESET to confirm" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        <button className="btn danger" onClick={reset} disabled={confirm !== 'RESET'}>Delete all business data</button>
      </div>
      {msg && <p className="small muted">{msg}</p>}
    </div>
  )
}
