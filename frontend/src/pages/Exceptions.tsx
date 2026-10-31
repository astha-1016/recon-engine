import { useCallback, useEffect, useState } from 'react'
import { api, pct, rupees, type CandidateView, type ExceptionView, type Session } from '../api'

type Row = { invoiceId: string; amount: string }

function ScoreBar({ score }: { score: number }) {
  return <div className="bar"><span style={{ width: `${Math.round(score * 100)}%` }} /></div>
}

function Candidate({ c, txnAmount, canAct, onApprove }: { c: CandidateView; txnAmount: number; canAct: boolean; onApprove: () => void }) {
  const total = c.parts.reduce((s, p) => s + p.amount, 0)
  return (
    <div className="card" style={{ marginBottom: 12 }}>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <strong>Suggestion #{c.rank}</strong>
        <span className={`pill ${c.score >= 0.9 ? 'ok' : c.score >= 0.6 ? 'warn' : 'bad'}`}>{pct(c.score)} confidence</span>
      </div>
      <ScoreBar score={c.score} />
      <table>
        <thead><tr><th>Invoice</th><th>Customer</th><th className="num">Invoice total</th><th className="num">Allocate</th></tr></thead>
        <tbody>
          {c.parts.map((p) => (
            <tr key={p.invoiceId}>
              <td>{p.invoiceId}</td><td>{p.customerName}</td>
              <td className="num">{rupees(p.invoiceAmount)}</td><td className="num">{rupees(p.amount)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="small muted" style={{ marginBottom: 8 }}>
        {c.adjustment > 0 && <>Short by {rupees(c.adjustment)}: closes the invoice as a bank-fee / TDS adjustment. </>}
        {c.excess > 0 && <>Overpaid by {rupees(c.excess)}: kept as an unapplied credit. </>}
        {c.adjustment === 0 && c.excess === 0 && total < txnAmount && <>Leaves {rupees(txnAmount - total)} unallocated. </>}
      </p>
      {canAct && <button className="btn" onClick={onApprove}>Approve this match</button>}
    </div>
  )
}

export default function Exceptions({ session }: { session: Session }) {
  const [items, setItems] = useState<ExceptionView[]>([])
  const [sel, setSel] = useState<string | null>(null)
  const [filter, setFilter] = useState<'ALL' | 'REVIEW' | 'UNMATCHED'>('ALL')
  const [toast, setToast] = useState('')
  const [rows, setRows] = useState<Row[]>([{ invoiceId: '', amount: '' }])
  const canAct = session.role !== 'VIEWER'

  const load = useCallback(() => api<ExceptionView[]>('/exceptions').then(setItems).catch((e) => setToast(e.message)), [])
  useEffect(() => { load() }, [load])

  const visible = items.filter((i) => filter === 'ALL' || i.decision === filter)
  const current = items.find((i) => i.txn.id === sel) ?? visible[0]

  async function act(path: string, body?: unknown, message = 'Saved') {
    try {
      await api(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined })
      setToast(`${message}. Re-running reconciliation in the background…`)
      setTimeout(load, 4000)
      setTimeout(() => setToast(''), 5000)
    } catch (e) {
      setToast((e as Error).message)
    }
  }

  const abs = (n: number) => Math.abs(n)
  return (
    <>
      <div className="row" style={{ justifyContent: 'space-between', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>Exceptions ({visible.length})</h2>
        <select value={filter} onChange={(e) => setFilter(e.target.value as typeof filter)}>
          <option value="ALL">All</option><option value="REVIEW">Needs review</option><option value="UNMATCHED">Unmatched</option>
        </select>
      </div>
      <div className="split">
        <div>
          {visible.map((i) => (
            <div key={i.txn.id} className={`queue-item ${current?.txn.id === i.txn.id ? 'sel' : ''}`} onClick={() => setSel(i.txn.id)}>
              <div className="row" style={{ justifyContent: 'space-between' }}>
                <strong>{rupees(abs(i.txn.amount))}{i.txn.direction === 'DR' ? ' (debit)' : ''}</strong>
                <span className={`pill ${i.decision === 'REVIEW' ? 'warn' : 'bad'}`}>{i.decision === 'REVIEW' ? 'review' : 'unmatched'}</span>
              </div>
              <div className="small muted">{i.txn.payer || '(no payer name)'} · {i.txn.channel} · {i.txn.date}</div>
            </div>
          ))}
          {visible.length === 0 && <p className="muted">Nothing needs attention. 🎉</p>}
        </div>

        {current && (
          <div>
            <div className="card" style={{ marginBottom: 12 }}>
              <div className="row" style={{ justifyContent: 'space-between' }}>
                <h3 style={{ margin: 0 }}>{rupees(abs(current.txn.amount))} {current.txn.direction === 'DR' ? 'debit' : 'credit'}</h3>
                <span className="pill">{current.kind.replace('_', ' ')}</span>
              </div>
              <p className="small" style={{ marginBottom: 4 }}><strong>Payer:</strong> {current.txn.payer || '–'} · <strong>Channel:</strong> {current.txn.channel} · <strong>Date:</strong> {current.txn.date}</p>
              <p className="small" style={{ margin: 0 }}><strong>Narration:</strong> {current.txn.narration || '–'} · <strong>Ref:</strong> {current.txn.bankRef || '–'}</p>
              <p className="small muted" style={{ marginBottom: 0 }}>Why it is here: {current.reason}{current.linkedTxnId && ` (original payment ${current.linkedTxnId})`}</p>
            </div>

            {current.candidates.length === 0 && current.linkedTxnId && canAct && (
              <button className="btn" onClick={() => act(`/exceptions/${current.txn.id}/approve`, { rank: 1 }, 'Reversal confirmed')}>Confirm reversal of {current.linkedTxnId}</button>
            )}
            {current.candidates.map((c) => (
              <Candidate key={c.rank} c={c} txnAmount={abs(current.txn.amount)} canAct={canAct}
                         onApprove={() => act(`/exceptions/${current.txn.id}/approve`, { rank: c.rank }, 'Match approved')} />
            ))}
            {current.candidates.length === 0 && !current.linkedTxnId && <p className="muted">No close candidates were found. Assign it manually below.</p>}

            {canAct && (
              <div className="card">
                <h3 style={{ marginTop: 0 }}>Assign manually / split</h3>
                {rows.map((r, idx) => (
                  <div className="row" key={idx} style={{ marginBottom: 8 }}>
                    <input placeholder="Invoice ID, e.g. INV-2026-00012" value={r.invoiceId} style={{ flex: 2 }}
                           onChange={(e) => setRows(rows.map((x, k) => (k === idx ? { ...x, invoiceId: e.target.value } : x)))} />
                    <input placeholder="Amount" value={r.amount} style={{ flex: 1 }}
                           onChange={(e) => setRows(rows.map((x, k) => (k === idx ? { ...x, amount: e.target.value } : x)))} />
                  </div>
                ))}
                <div className="row">
                  <button className="btn secondary" onClick={() => setRows([...rows, { invoiceId: '', amount: '' }])}>+ Add invoice</button>
                  <button className="btn" onClick={() => act(`/exceptions/${current.txn.id}/assign`, { parts: rows.filter((r) => r.invoiceId && r.amount) }, 'Assignment saved')}>Save assignment</button>
                  <button className="btn danger" onClick={() => act(`/exceptions/${current.txn.id}/reject`, undefined, 'Suggestions rejected')}>Reject all suggestions</button>
                </div>
                <p className="small muted" style={{ marginBottom: 0 }}>Every decision also teaches the payer-alias table and becomes a labelled example for the next model retrain.</p>
              </div>
            )}
          </div>
        )}
      </div>
      {toast && <div className="toast">{toast}</div>}
    </>
  )
}
