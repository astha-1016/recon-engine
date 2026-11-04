import { useCallback, useEffect, useState } from 'react'
import { api, rupees, type Session, type TxnRow } from '../api'

type Form = {
  transactionId: string; transactionDate: string; amount: string; direction: string; payerName: string
  channel: string; bankReference: string; reversalReference: string; narration: string
}
const EMPTY: Form = {
  transactionId: '', transactionDate: new Date().toISOString().slice(0, 10), amount: '', direction: 'CR', payerName: '',
  channel: 'NEFT', bankReference: '', reversalReference: '', narration: '',
}
const CHANNELS = ['NEFT', 'IMPS', 'UPI_ID', 'QR', 'CHEQUE', 'BANK']

/** Bank-statement lines ("payments"): browse, record one by hand, correct or delete a wrong row. */
export default function Payments({ session }: { session: Session }) {
  const [rows, setRows] = useState<TxnRow[]>([])
  const [q, setQ] = useState('')
  const [form, setForm] = useState<Form | null>(null)
  const [editing, setEditing] = useState(false)
  const [formError, setFormError] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const canAct = session.role !== 'VIEWER'
  const canDelete = session.role === 'ADMIN'

  const load = useCallback(() => {
    const params = new URLSearchParams()
    if (q) params.set('q', q)
    api<TxnRow[]>('/transactions?' + params).then(setRows).catch((e) => setError(e.message))
  }, [q])
  useEffect(() => { load() }, [load])

  function openNew() { setEditing(false); setFormError(''); setForm({ ...EMPTY }) }
  function openEdit(r: TxnRow) {
    setEditing(true); setFormError('')
    setForm({
      transactionId: r.id, transactionDate: r.date, amount: (Math.abs(r.amount) / 100).toFixed(2), direction: r.direction,
      payerName: r.payer, channel: r.channel, bankReference: r.bankRef, reversalReference: r.reversalRef, narration: r.narration,
    })
  }

  async function save() {
    if (!form) return
    try {
      if (editing) await api('/transactions/' + encodeURIComponent(form.transactionId), { method: 'PUT', body: JSON.stringify(form) })
      else await api('/transactions', { method: 'POST', body: JSON.stringify(form) })
      setForm(null)
      setNotice(editing ? 'Payment updated. Matching is re-running in the background…' : 'Payment recorded. Matching is re-running in the background…')
      load()
      setTimeout(load, 3000)
    } catch (e) {
      setFormError((e as Error).message)
    }
  }

  async function remove(r: TxnRow) {
    if (!window.confirm(`Delete payment ${r.id}? This cannot be undone.`)) return
    try {
      await api('/transactions/' + encodeURIComponent(r.id), { method: 'DELETE' })
      setNotice('Payment deleted.')
      load()
      setTimeout(load, 3000)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const set = (k: keyof Form) => (e: { target: { value: string } }) => setForm((f) => (f ? { ...f, [k]: e.target.value } : f))

  return (
    <>
      <div className="row" style={{ justifyContent: 'space-between', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>Payments</h2>
        <div className="row">
          <input placeholder="Search id, payer, reference" value={q} onChange={(e) => setQ(e.target.value)} />
          {canAct && <button className="btn" onClick={openNew}>Record payment</button>}
        </div>
      </div>
      <p className="muted">Bank-statement lines. Add one by hand, or use Upload for a whole statement. A payment you already decided on is locked: undo the decision first.</p>
      {error && <p className="err">{error}</p>}
      {notice && <p className="muted">{notice}</p>}
      <div className="card" style={{ overflowX: 'auto' }}>
        <table>
          <thead><tr><th>Payment</th><th>Date</th><th>Payer</th><th>Channel</th><th className="num">Amount</th><th>Result</th><th /></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td>{r.id}<div className="small muted">{r.bankRef}</div></td>
                <td>{r.date}</td>
                <td>{r.payer || <span className="muted">no payer name</span>}</td>
                <td>{r.channel}</td>
                <td className="num">{r.direction === 'DR' ? '−' : ''}{rupees(Math.abs(r.amount))}</td>
                <td>{r.status ? <span className={`pill ${r.status === 'AUTO' ? 'ok' : r.status === 'REVIEW' ? 'warn' : r.status === 'UNMATCHED' ? 'bad' : ''}`}>{r.status}</span> : <span className="muted small">not run yet</span>}</td>
                <td>
                  <div className="row" style={{ flexWrap: 'nowrap' }}>
                    {canAct && <button className="btn secondary" onClick={() => openEdit(r)}>Edit</button>}
                    {canDelete && <button className="btn danger" onClick={() => remove(r)}>Delete</button>}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {form && (
        <div className="modal-bg" onClick={() => setForm(null)}>
          <div className="modal form" onClick={(e) => e.stopPropagation()}>
            <h3 style={{ marginTop: 0 }}>{editing ? `Edit ${form.transactionId}` : 'Record payment'}</h3>
            <div className="fields">
              <label>Transaction ID<input value={form.transactionId} onChange={set('transactionId')} disabled={editing} /></label>
              <label>Date<input type="date" value={form.transactionDate} onChange={set('transactionDate')} /></label>
              <label>Amount (₹)<input value={form.amount} onChange={set('amount')} inputMode="decimal" placeholder="1000.00" /></label>
              <label>Direction
                <select value={form.direction} onChange={set('direction')}>
                  <option value="CR">Credit (money in)</option>
                  <option value="DR">Debit (money out)</option>
                </select>
              </label>
              <label>Payer name<input value={form.payerName} onChange={set('payerName')} /></label>
              <label>Channel<input list="channels" value={form.channel} onChange={set('channel')} /></label>
              <label>Bank reference (UTR / cheque no.)<input value={form.bankReference} onChange={set('bankReference')} /></label>
              <label>Reversal of (transaction ID)<input value={form.reversalReference} onChange={set('reversalReference')} /></label>
              <label className="wide">Narration / note<input value={form.narration} onChange={set('narration')} /></label>
            </div>
            <datalist id="channels">{CHANNELS.map((c) => <option key={c} value={c} />)}</datalist>
            {formError && <p className="err small">{formError}</p>}
            <div className="row" style={{ justifyContent: 'flex-end' }}>
              <button className="btn secondary" onClick={() => setForm(null)}>Cancel</button>
              <button className="btn" onClick={save}>{editing ? 'Save changes' : 'Record payment'}</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
