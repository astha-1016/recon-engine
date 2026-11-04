import { useCallback, useEffect, useState } from 'react'
import { api, getSession, setSession, type Session, type UserView } from '../api'

const ROLES: UserView['role'][] = ['ADMIN', 'ACCOUNTANT', 'VIEWER']
const MIN_PASSWORD = 5

/** Same rule as the server, so the person hears about a bad password before the request is sent. */
function passwordProblem(next: string, confirm: string): string | null {
  if (next.length < MIN_PASSWORD) return `Password must be at least ${MIN_PASSWORD} characters.`
  if (next !== confirm) return 'The two passwords do not match.'
  return null
}

function Dialog({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="modal-bg" role="presentation" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal form" role="dialog" aria-modal="true" aria-label={title} style={{ maxWidth: 420 }}>
        <h3 style={{ marginTop: 0 }}>{title}</h3>
        {children}
      </div>
    </div>
  )
}

/** Masked replacement for window.prompt: new password typed twice. */
function SetPasswordDialog({ username, onSubmit, onClose }: { username: string; onSubmit: (password: string) => Promise<void>; onClose: () => void }) {
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const problem = next || confirm ? passwordProblem(next, confirm) : null

  async function submit() {
    const p = passwordProblem(next, confirm)
    if (p) { setError(p); return }
    setBusy(true)
    try { await onSubmit(next) } catch (e) { setError((e as Error).message); setBusy(false) }
  }

  return (
    <Dialog title={`New password for ${username}`} onClose={onClose}>
      <p className="small muted">Their current sign-ins on every device will end, and they will need this new password.</p>
      <div className="row" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
        <input type="password" autoComplete="new-password" autoFocus placeholder={`New password (${MIN_PASSWORD}+ characters)`} value={next}
               onChange={(e) => { setNext(e.target.value); setError('') }} />
        <input type="password" autoComplete="new-password" placeholder="Type it again" value={confirm}
               onChange={(e) => { setConfirm(e.target.value); setError('') }} onKeyDown={(e) => e.key === 'Enter' && submit()} />
      </div>
      {(error || problem) && <p className="small err">{error || problem}</p>}
      <div className="row" style={{ marginTop: 12, justifyContent: 'flex-end' }}>
        <button className="btn secondary" onClick={onClose} disabled={busy}>Cancel</button>
        <button className="btn" onClick={submit} disabled={busy || !next || !confirm}>Set password</button>
      </div>
    </Dialog>
  )
}

function ConfirmDialog({ title, body, confirmLabel, onConfirm, onClose }: { title: string; body: string; confirmLabel: string; onConfirm: () => void; onClose: () => void }) {
  return (
    <Dialog title={title} onClose={onClose}>
      <p className="small">{body}</p>
      <div className="row" style={{ marginTop: 12, justifyContent: 'flex-end' }}>
        <button className="btn secondary" onClick={onClose} autoFocus>Cancel</button>
        <button className="btn" onClick={onConfirm}>{confirmLabel}</button>
      </div>
    </Dialog>
  )
}

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
  const [confirm, setConfirm] = useState('')
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const problem = next || confirm ? passwordProblem(next, confirm) : null

  async function submit() {
    const p = passwordProblem(next, confirm)
    if (p) { setMsg({ ok: false, text: p }); return }
    try {
      const res = await api<{ token: string }>('/auth/change-password', {
        method: 'POST', body: JSON.stringify({ currentPassword: current, newPassword: next, confirmPassword: confirm }),
      })
      // the old token is dead now (changing the password ends every sign-in); keep this browser signed in with the new one
      const s = getSession()
      if (s && res?.token) setSession({ ...s, token: res.token })
      setCurrent(''); setNext(''); setConfirm('')
      setMsg({ ok: true, text: 'Password changed. Any other device you were signed in on has been signed out.' })
    } catch (e) {
      setMsg({ ok: false, text: (e as Error).message })
    }
  }

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <h3 style={{ marginTop: 0 }}>Change my password</h3>
      <div className="row">
        <input type="password" autoComplete="current-password" placeholder="Current password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        <input type="password" autoComplete="new-password" placeholder={`New password (${MIN_PASSWORD}+ characters)`} value={next} onChange={(e) => setNext(e.target.value)} />
        <input type="password" autoComplete="new-password" placeholder="New password again" value={confirm} onChange={(e) => setConfirm(e.target.value)}
               onKeyDown={(e) => e.key === 'Enter' && current && next && confirm && submit()} />
        <button className="btn" onClick={submit} disabled={!current || !next || !confirm || !!problem}>Change password</button>
      </div>
      {problem && !msg && <p className="small err">{problem}</p>}
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
  const [pwTarget, setPwTarget] = useState<UserView | null>(null)
  const [roleChange, setRoleChange] = useState<{ user: UserView; role: UserView['role'] } | null>(null)

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

  async function applyPassword(u: UserView, password: string) {
    await api(`/users/${u.id}/password`, { method: 'PUT', body: JSON.stringify({ password }) })
    setPwTarget(null)
    setMsg(`Password for ${u.username} changed. Their existing sign-ins have ended.`)
  }

  function remove(u: UserView) {
    if (window.confirm(`Delete the account ${u.username}?`)) run(() => api(`/users/${u.id}`, { method: 'DELETE' }), 'User deleted.')
  }

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <h3 style={{ marginTop: 0 }}>Users</h3>
      <p className="small muted">Admin: everything, including delete and reset. Accountant: upload, add and edit records, decide exceptions. Viewer: read only. Changing someone’s role or password, or removing them, signs them out everywhere straight away. You cannot change your own role; ask another admin.</p>
      <table>
        <thead><tr><th>Username</th><th>Role</th><th /></tr></thead>
        <tbody>
          {users.map((u) => (
            <tr key={u.id}>
              <td>{u.username}{u.username === session.username && <span className="muted small"> (you)</span>}</td>
              <td>
                <select value={u.role} disabled={u.username === session.username} title={u.username === session.username ? 'You cannot change your own role' : undefined}
                        onChange={(e) => setRoleChange({ user: u, role: e.target.value as UserView['role'] })}>
                  {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                </select>
              </td>
              <td>
                <div className="row" style={{ flexWrap: 'nowrap' }}>
                  <button className="btn secondary" onClick={() => setPwTarget(u)} disabled={u.username === session.username}
                          title={u.username === session.username ? 'Use “Change my password” above' : undefined}>Set password</button>
                  <button className="btn danger" onClick={() => remove(u)} disabled={u.username === session.username}>Delete</button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="row" style={{ marginTop: 12 }}>
        <input placeholder="New username" value={username} onChange={(e) => setUsername(e.target.value)} />
        <input type="password" placeholder={`Password (${MIN_PASSWORD}+ characters)`} value={password} onChange={(e) => setPassword(e.target.value)} />
        <select value={role} onChange={(e) => setRole(e.target.value as UserView['role'])}>
          {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
        </select>
        <button className="btn" onClick={add} disabled={!username || !password}>Add user</button>
      </div>
      {msg && <p className="small muted">{msg}</p>}
      {pwTarget && <SetPasswordDialog username={pwTarget.username} onSubmit={(pw) => applyPassword(pwTarget, pw)} onClose={() => setPwTarget(null)} />}
      {roleChange && (
        <ConfirmDialog
          title={`Change ${roleChange.user.username} to ${roleChange.role}?`}
          body={`${roleChange.user.username} is ${roleChange.user.role} now. They will be signed out everywhere and will need to sign in again to get the new access.`}
          confirmLabel={`Make ${roleChange.role}`}
          onClose={() => setRoleChange(null)}
          onConfirm={() => { const c = roleChange; setRoleChange(null); changeRole(c.user, c.role) }} />
      )}
    </div>
  )
}

function ResetData() {
  const [summary, setSummary] = useState<{ customers: number; invoices: number; transactions: number } | null>(null)
  const [confirm, setConfirm] = useState('')
  const [password, setPassword] = useState('')
  const [msg, setMsg] = useState('')

  const load = useCallback(() => api<{ customers: number; invoices: number; transactions: number }>('/admin/summary').then(setSummary).catch(() => setSummary(null)), [])
  useEffect(() => { load() }, [load])

  async function reset() {
    try {
      const r = await api<{ backup: string; mlFeedbackNotRemoved: number }>('/admin/reset', { method: 'POST', body: JSON.stringify({ confirm, password }) })
      setConfirm(''); setPassword('')
      setMsg(`All customers, invoices, payments, results and decisions were removed. A backup was saved as backups/${r.backup} on the server. You can now upload your own data.`
        + (r.mlFeedbackNotRemoved > 0 ? ` The ML service could not be reached for ${r.mlFeedbackNotRemoved} payment(s), so some old training labels may remain there until you retry or clear its feedback.` : ''))
      load()
    } catch (e) {
      setMsg((e as Error).message)
    }
  }

  return (
    <div className="card danger-zone">
      <h3 style={{ marginTop: 0 }}>Delete all business data</h3>
      <p className="small muted">
        Meant for clearing demo data before you load real data. It deletes every customer, invoice, bank payment,
        match result, manual decision and learned payer name{summary ? ` (currently ${summary.customers} customers, ${summary.invoices} invoices, ${summary.transactions} payments)` : ''},
        and retires the training labels that came from those decisions. Users, the audit log and the already-trained model are kept.
        The app saves a backup file on the server first and will not delete anything if that fails, but there is no undo button:
        for a full copy also run <code>scripts/backup.sh</code> (see docs/OPERATIONS.md).
      </p>
      <div className="row">
        <input placeholder="Type RESET to confirm" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        <input type="password" autoComplete="current-password" placeholder="Your password" value={password} onChange={(e) => setPassword(e.target.value)} />
        <button className="btn danger" onClick={reset} disabled={confirm !== 'RESET' || !password}>Delete all business data</button>
      </div>
      {msg && <p className="small muted">{msg}</p>}
    </div>
  )
}
