import { useCallback, useEffect, useState } from 'react'
import { api, rupees, type DecisionView, type Session } from '../api'

/** Everything a person decided by hand. Undo hands the payment back to the engine and withdraws what it taught. */
export default function Decisions({ session }: { session: Session }) {
  const [rows, setRows] = useState<DecisionView[]>([])
  const [message, setMessage] = useState('')
  const canAct = session.role !== 'VIEWER'

  const load = useCallback(() => api<DecisionView[]>('/decisions').then(setRows).catch((e) => setMessage(e.message)), [])
  useEffect(() => { load() }, [load])

  async function undo(txnId: string) {
    if (!window.confirm(`Undo your decision on ${txnId}? The payment goes back to the engine.`)) return
    try {
      await api(`/decisions/${encodeURIComponent(txnId)}/undo`, { method: 'POST' })
      setMessage('Undone. Re-running reconciliation in the background…')
      setTimeout(load, 3000)
    } catch (e) {
      setMessage((e as Error).message)
    }
  }

  return (
    <>
      <h2>Manual decisions</h2>
      <p className="muted">Undoing a decision returns the payment to the engine, removes the payer alias it taught if nothing else supports it, and discards its training label.</p>
      {message && <p className="muted">{message}</p>}
      <div className="card" style={{ overflowX: 'auto' }}>
        <table>
          <thead><tr><th>Payment</th><th>Allocated to</th><th>Decided by</th><th>When</th><th /></tr></thead>
          <tbody>
            {rows.map((d) => (
              <tr key={d.txnId}>
                <td>{d.txnId}<div className="small muted">{d.date} · {rupees(Math.abs(d.txnAmount))} {d.direction === 'DR' ? 'debit' : 'credit'} · {d.payer || 'no payer name'}</div></td>
                <td>{d.parts.map((p) => <div key={p.invoiceId}>{p.invoiceId} · {p.customerName} · {rupees(p.amount)}</div>)}</td>
                <td>{d.decidedBy}</td>
                <td>{new Date(d.decidedAt).toLocaleString()}</td>
                <td>{canAct && <button className="btn danger" onClick={() => undo(d.txnId)}>Undo</button>}</td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={5} className="muted">No manual decisions yet.</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  )
}
