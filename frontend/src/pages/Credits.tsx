import { useCallback, useEffect, useState } from 'react'
import { api, rupees, type ApplicationView, type CreditView, type Session } from '../api'

/** Surplus money from overpayments. A person can put it against another open invoice of the same customer. */
export default function Credits({ session }: { session: Session }) {
  const [credits, setCredits] = useState<CreditView[]>([])
  const [apps, setApps] = useState<ApplicationView[]>([])
  const [choice, setChoice] = useState<Record<string, { invoiceId: string; amount: string }>>({})
  const [message, setMessage] = useState('')
  const canAct = session.role !== 'VIEWER'

  const load = useCallback(() => {
    api<CreditView[]>('/credits').then(setCredits).catch((e) => setMessage(e.message))
    api<ApplicationView[]>('/credits/applications').then(setApps).catch(() => setApps([]))
  }, [])
  useEffect(() => { load() }, [load])

  const pick = (c: CreditView) => choice[c.txnId] ?? { invoiceId: c.openInvoices[0]?.id ?? '', amount: '' }

  async function apply(c: CreditView) {
    const sel = pick(c)
    const invoice = c.openInvoices.find((i) => i.id === sel.invoiceId)
    const amount = sel.amount || ((Math.min(c.amount, invoice?.outstanding ?? 0)) / 100).toFixed(2)
    try {
      await api(`/credits/${encodeURIComponent(c.txnId)}/apply`, { method: 'POST', body: JSON.stringify({ invoiceId: sel.invoiceId, amount }) })
      setMessage('Applied. Re-running reconciliation in the background…')
      setTimeout(load, 4000)
    } catch (e) {
      setMessage((e as Error).message)
    }
  }

  async function remove(id: number) {
    try {
      await api(`/credits/applications/${id}`, { method: 'DELETE' })
      setMessage('Removed. Re-running reconciliation in the background…')
      setTimeout(load, 4000)
    } catch (e) {
      setMessage((e as Error).message)
    }
  }

  return (
    <>
      <h2>Unapplied credits</h2>
      <p className="muted">When a customer pays more than an invoice needs, the surplus waits here. Put it against another open invoice of the same customer. Nothing is applied automatically.</p>
      {message && <p className="muted">{message}</p>}
      <div className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
        <table>
          <thead><tr><th>Payment</th><th>Customer</th><th className="num">Credit left</th><th>Apply to</th><th className="num">Amount</th><th /></tr></thead>
          <tbody>
            {credits.map((c) => {
              const sel = pick(c)
              return (
                <tr key={c.txnId}>
                  <td>{c.txnId}<div className="small muted">{c.txnDate} · {c.payer}</div></td>
                  <td>{c.customerName || c.customerId}</td>
                  <td className="num">{rupees(c.amount)}</td>
                  <td>
                    {c.openInvoices.length === 0 ? <span className="muted small">no open invoice for this customer</span> : (
                      <select value={sel.invoiceId} disabled={!canAct}
                              onChange={(e) => setChoice({ ...choice, [c.txnId]: { ...sel, invoiceId: e.target.value } })}>
                        {c.openInvoices.map((i) => <option key={i.id} value={i.id}>{i.id} · owes {rupees(i.outstanding)}</option>)}
                      </select>
                    )}
                  </td>
                  <td className="num">
                    <input style={{ width: 110 }} placeholder="max" value={sel.amount} disabled={!canAct}
                           onChange={(e) => setChoice({ ...choice, [c.txnId]: { ...sel, amount: e.target.value } })} />
                  </td>
                  <td>{canAct && c.openInvoices.length > 0 && <button className="btn" onClick={() => apply(c)}>Apply</button>}</td>
                </tr>
              )
            })}
            {credits.length === 0 && <tr><td colSpan={6} className="muted">No unapplied credits.</td></tr>}
          </tbody>
        </table>
      </div>

      <h3>Credit applications</h3>
      <div className="card" style={{ overflowX: 'auto' }}>
        <table>
          <thead><tr><th>Credit from</th><th>Invoice</th><th className="num">Amount</th><th>By</th><th>When</th><th /></tr></thead>
          <tbody>
            {apps.map((a) => (
              <tr key={a.id}>
                <td>{a.txnId}</td><td>{a.invoiceId}</td><td className="num">{rupees(a.amount)}</td><td>{a.appliedBy}</td>
                <td>{new Date(a.appliedAt).toLocaleString()}</td>
                <td>{canAct && <button className="btn danger" onClick={() => remove(a.id)}>Remove</button>}</td>
              </tr>
            ))}
            {apps.length === 0 && <tr><td colSpan={6} className="muted">Nothing applied yet.</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  )
}
