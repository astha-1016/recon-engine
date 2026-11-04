import { useCallback, useEffect, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { api, rupees, type InvoiceView, type Session } from '../api'

const STATUSES = ['', 'OPEN', 'PARTIALLY_PAID', 'PAID', 'REFUNDED', 'DISPUTED']

type Form = { invoiceId: string; customerId: string; customerName: string; amount: string; invoiceDate: string; dueDate: string }
const EMPTY: Form = { invoiceId: '', customerId: '', customerName: '', amount: '', invoiceDate: new Date().toISOString().slice(0, 10), dueDate: '' }

export default function Invoices({ session }: { session: Session }) {
  const [rows, setRows] = useState<InvoiceView[]>([])
  const [status, setStatus] = useState('')
  const [q, setQ] = useState('')
  const [qr, setQr] = useState<{ link: string; invoiceId: string; amount: string } | null>(null)
  const [form, setForm] = useState<Form | null>(null)
  const [editing, setEditing] = useState(false)
  const [formError, setFormError] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const canAct = session.role !== 'VIEWER'
  const canDelete = session.role === 'ADMIN'

  const load = useCallback(() => {
    const params = new URLSearchParams()
    if (status) params.set('status', status)
    if (q) params.set('q', q)
    api<InvoiceView[]>('/invoices?' + params).then(setRows).catch((e) => setError(e.message))
  }, [status, q])
  useEffect(() => { load() }, [load])

  async function showQr(id: string) {
    try { setQr(await api('/invoices/' + encodeURIComponent(id) + '/upi-link')) } catch (e) { setError((e as Error).message) }
  }

  function openNew() { setEditing(false); setFormError(''); setForm({ ...EMPTY }) }
  function openEdit(r: InvoiceView) {
    setEditing(true); setFormError('')
    setForm({ invoiceId: r.id, customerId: r.customerId, customerName: r.customerName, amount: (r.amount / 100).toFixed(2), invoiceDate: r.issueDate, dueDate: r.dueDate })
  }

  async function save() {
    if (!form) return
    try {
      if (editing) await api('/invoices/' + encodeURIComponent(form.invoiceId), { method: 'PUT', body: JSON.stringify(form) })
      else await api('/invoices', { method: 'POST', body: JSON.stringify(form) })
      setForm(null)
      setNotice(editing ? 'Invoice updated. Matching is re-running in the background…' : 'Invoice added. Matching is re-running in the background…')
      load()
      setTimeout(load, 3000)
    } catch (e) {
      setFormError((e as Error).message)
    }
  }

  async function remove(r: InvoiceView) {
    if (!window.confirm(`Delete invoice ${r.id}? This cannot be undone.`)) return
    try {
      await api('/invoices/' + encodeURIComponent(r.id), { method: 'DELETE' })
      setNotice('Invoice deleted.')
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
        <h2 style={{ margin: 0 }}>Invoices</h2>
        <div className="row">
          <input placeholder="Search invoice or customer" value={q} onChange={(e) => setQ(e.target.value)} />
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            {STATUSES.map((s) => <option key={s} value={s}>{s ? s.replace('_', ' ') : 'All statuses'}</option>)}
          </select>
          {canAct && <button className="btn" onClick={openNew}>New invoice</button>}
        </div>
      </div>
      {error && <p className="err">{error}</p>}
      {notice && <p className="muted">{notice}</p>}
      <div className="card" style={{ overflowX: 'auto' }}>
        <table>
          <thead><tr><th>Invoice</th><th>Customer</th><th>Due</th><th className="num">Amount</th><th className="num">Paid</th><th className="num">Outstanding</th><th>Status</th><th /></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td>{r.id}</td><td>{r.customerName}</td><td>{r.dueDate}</td>
                <td className="num">{rupees(r.amount)}</td><td className="num">{rupees(r.paid)}</td><td className="num">{rupees(r.outstanding)}</td>
                <td><span className={`pill ${r.status === 'PAID' ? 'ok' : r.status === 'DISPUTED' ? 'bad' : ''}`}>{r.status.replace('_', ' ')}</span></td>
                <td>
                  <div className="row" style={{ flexWrap: 'nowrap' }}>
                    {r.outstanding > 0 && <button className="btn secondary" onClick={() => showQr(r.id)}>Pay QR</button>}
                    {canAct && <button className="btn secondary" onClick={() => openEdit(r)}>Edit</button>}
                    {canDelete && <button className="btn danger" onClick={() => remove(r)}>Delete</button>}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {qr && (
        <div className="modal-bg" onClick={() => setQr(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3 style={{ marginTop: 0 }}>{qr.invoiceId}</h3>
            <QRCodeSVG value={qr.link} size={220} />
            <p className="small">Pay ₹{qr.amount}. The invoice number is embedded in the UPI note, so the bank statement will carry it and the payment matches automatically.</p>
            <button className="btn secondary" onClick={() => setQr(null)}>Close</button>
          </div>
        </div>
      )}
      {form && (
        <div className="modal-bg" onClick={() => setForm(null)}>
          <div className="modal form" onClick={(e) => e.stopPropagation()}>
            <h3 style={{ marginTop: 0 }}>{editing ? `Edit ${form.invoiceId}` : 'New invoice'}</h3>
            <div className="fields">
              <label>Invoice number<input value={form.invoiceId} onChange={set('invoiceId')} disabled={editing} /></label>
              <label>Amount (₹)<input value={form.amount} onChange={set('amount')} inputMode="decimal" placeholder="1000.00" /></label>
              <label>Customer ID<input value={form.customerId} onChange={set('customerId')} /></label>
              <label>Customer name<input value={form.customerName} onChange={set('customerName')} /></label>
              <label>Invoice date<input type="date" value={form.invoiceDate} onChange={set('invoiceDate')} /></label>
              <label>Due date<input type="date" value={form.dueDate} onChange={set('dueDate')} /></label>
            </div>
            {formError && <p className="err small">{formError}</p>}
            <div className="row" style={{ justifyContent: 'flex-end' }}>
              <button className="btn secondary" onClick={() => setForm(null)}>Cancel</button>
              <button className="btn" onClick={save}>{editing ? 'Save changes' : 'Add invoice'}</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
