import { useCallback, useEffect, useState } from 'react'
import { api, pct, rupees, type Dashboard as Dash, type Session } from '../api'

function Kpi({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="card kpi">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {sub && <div className="sub">{sub}</div>}
    </div>
  )
}

export default function Dashboard({ session, goTo }: { session: Session; goTo: () => void }) {
  const [d, setD] = useState<Dash | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const canRun = session.role !== 'VIEWER'

  const load = useCallback(() => api<Dash>('/dashboard').then(setD).catch((e) => setError(e.message)), [])
  useEffect(() => { load() }, [load])
  useEffect(() => {
    if (!d?.running) return
    const t = setInterval(load, 3000)
    return () => clearInterval(t)
  }, [d?.running, load])

  async function run() {
    setBusy(true); setError('')
    try { await api('/reconcile/run', { method: 'POST' }); await load() } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }

  if (!d) return <p className="muted">{error || 'Loading…'}</p>
  return (
    <>
      <div className="row" style={{ justifyContent: 'space-between', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>Dashboard</h2>
        {canRun && <button className="btn" disabled={busy || d.running} onClick={run}>{busy || d.running ? 'Reconciling…' : 'Run reconciliation'}</button>}
      </div>
      {error && <p className="err">{error}</p>}
      <div className="grid">
        <Kpi label="Auto-match rate" value={pct(d.autoMatchRate)} sub={`${d.autoMatched} payments matched without a person`} />
        <div className="card kpi" style={{ cursor: 'pointer' }} onClick={goTo}>
          <div className="label">Pending review</div>
          <div className="value">{d.pendingReview}</div>
          <div className="sub">{rupees(d.pendingReviewValue)} waiting · click to review</div>
        </div>
        <Kpi label="Unmatched" value={String(d.unmatched)} sub={`${rupees(d.unmatchedValue)} unexplained`} />
        <Kpi label="Outstanding" value={rupees(d.outstandingTotal)} sub="across all open invoices" />
        <Kpi label="Unapplied credits" value={rupees(d.unappliedCredits)} sub="overpayments awaiting allocation" />
      </div>
      <div className="card">
        <h3 style={{ marginTop: 0 }}>Invoices by status</h3>
        <div className="row">
          {Object.entries(d.invoicesByStatus).map(([k, v]) => <span key={k} className="pill">{k.replace('_', ' ')}: {v}</span>)}
        </div>
        {d.lastRun && (
          <p className="muted small" style={{ marginBottom: 0 }}>
            Last run: {d.lastRun.transactions} transactions in {(d.lastRun.millis / 1000).toFixed(1)}s using scorer “{d.lastRun.scorer}”.
            {d.lastRun.scorer === 'rules-fallback' && ' The ML service was unreachable, so the rule scorer was used.'}
          </p>
        )}
      </div>
    </>
  )
}
