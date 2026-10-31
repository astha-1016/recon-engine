import { useEffect, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { api, rupees, type InvoiceView } from '../api'

const STATUSES = ['', 'OPEN', 'PARTIALLY_PAID', 'PAID', 'REFUNDED', 'DISPUTED']

export default function Invoices() {
  const [rows, setRows] = useState<InvoiceView[]>([])
  const [status, setStatus] = useState('')
  const [q, setQ] = useState('')
  const [qr, setQr] = useState<{ link: string; invoiceId: string; amount: string } | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    const params = new URLSearchParams()
    if (status) params.set('status', status)
    if (q) params.set('q', q)
    api<InvoiceView[]>('/invoices?' + params).then(setRows).catch((e) => setError(e.message))
  }, [status, q])

  async function showQr(id: string) {
    try { setQr(await api('/invoices/' + encodeURIComponent(id) + '/upi-link')) } catch (e) { setError((e as Error).message) }
  }

  return (
    <>
      <div className="row" style={{ justifyContent: 'space-between', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>Invoices</h2>
        <div className="row">
          <input placeholder="Search invoice or customer" value={q} onChange={(e) => setQ(e.target.value)} />
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            {STATUSES.map((s) => <option key={s} value={s}>{s ? s.replace('_', ' ') : 'All statuses'}</option>)}
          </select>
        </div>
      </div>
      {error && <p className="err">{error}</p>}
      <div className="card" style={{ overflowX: 'auto' }}>
        <table>
          <thead><tr><th>Invoice</th><th>Customer</th><th>Due</th><th className="num">Amount</th><th className="num">Paid</th><th className="num">Outstanding</th><th>Status</th><th /></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td>{r.id}</td><td>{r.customerName}</td><td>{r.dueDate}</td>
                <td className="num">{rupees(r.amount)}</td><td className="num">{rupees(r.paid)}</td><td className="num">{rupees(r.outstanding)}</td>
                <td><span className={`pill ${r.status === 'PAID' ? 'ok' : r.status === 'DISPUTED' ? 'bad' : ''}`}>{r.status.replace('_', ' ')}</span></td>
                <td>{r.outstanding > 0 && <button className="btn secondary" onClick={() => showQr(r.id)}>Pay QR</button>}</td>
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
    </>
  )
}
