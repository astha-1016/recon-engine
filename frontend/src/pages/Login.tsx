import { useState } from 'react'
import { api, type Session } from '../api'

export default function Login({ onLogin }: { onLogin: (s: Session) => void }) {
  const [username, setUsername] = useState('accountant')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')

  async function submit() {
    setError('')
    try {
      onLogin(await api<Session>('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }))
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <div className="card login">
      <h2>Reconciliation Engine</h2>
      <p className="muted small">Sign in as admin, accountant or viewer.</p>
      <input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="Username" />
      <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Password"
             onKeyDown={(e) => e.key === 'Enter' && submit()} />
      {error && <p className="err small">{error}</p>}
      <button className="btn" onClick={submit}>Sign in</button>
    </div>
  )
}
