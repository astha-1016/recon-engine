import { useState } from 'react'
import { api, type Report, type Session } from '../api'

const KINDS = [
  { kind: 'customers', title: 'Customers (master data)', hint: 'customer_id, name, phone, account_last4 — lets UPI phone handles and masked accounts resolve to a customer' },
  { kind: 'invoices', title: 'Invoices / ledger', hint: 'invoice_id, customer_id, customer_name, amount, invoice_date, due_date' },
  { kind: 'transactions', title: 'Bank statement', hint: 'transaction_id, transaction_date, amount, direction, payer_name, channel, bank_reference, reversal_reference, narration' },
]

export default function Upload({ session }: { session: Session }) {
  const [reports, setReports] = useState<Record<string, Report | string>>({})
  const canAct = session.role !== 'VIEWER'

  async function send(kind: string, file: File | undefined) {
    if (!file) return
    const form = new FormData()
    form.append('file', file)
    try {
      setReports((r) => ({ ...r, [kind]: '…uploading' }))
      const rep = await api<Report>(`/upload/${kind}`, { method: 'POST', body: form })
      setReports((r) => ({ ...r, [kind]: rep }))
    } catch (e) {
      setReports((r) => ({ ...r, [kind]: (e as Error).message }))
    }
  }

  return (
    <>
      <h2>Upload data</h2>
      <p className="muted">CSV or Excel. Uploading the same file twice is safe: identical rows are recognised and skipped.</p>
      {!canAct && <p className="err">Your role is read-only.</p>}
      <div className="grid">
        {KINDS.map((k) => {
          const rep = reports[k.kind]
          return (
            <div className="card" key={k.kind}>
              <h3 style={{ marginTop: 0 }}>{k.title}</h3>
              <p className="small muted">{k.hint}</p>
              <input type="file" accept=".csv,.xlsx,.xls" disabled={!canAct} onChange={(e) => send(k.kind, e.target.files?.[0])} />
              {typeof rep === 'string' && <p className="small err">{rep}</p>}
              {rep && typeof rep !== 'string' && (
                <div className="small" style={{ marginTop: 8 }}>
                  <div>{rep.rows} rows · <strong>{rep.inserted} added</strong> · {rep.duplicates} duplicates skipped · {rep.errors.length} rejected</div>
                  {rep.errors.slice(0, 5).map((m, i) => <div key={i} className="err">{m}</div>)}
                  {rep.errors.length > 5 && <div className="muted">…and {rep.errors.length - 5} more</div>}
                </div>
              )}
            </div>
          )
        })}
      </div>
      <p className="muted small">Next: go to Dashboard and press “Run reconciliation”.</p>
    </>
  )
}
