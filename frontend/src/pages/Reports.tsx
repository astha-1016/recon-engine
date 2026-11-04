import { useEffect, useState } from 'react'
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api, pct, type Session } from '../api'

type Arm = { label: string; precision: number; recall: number; autoRate: number; manualLeft: number; statusAccuracy: number; correct: number; predicted: number; expected: number; reviewTxns?: number; reviewTop1Correct?: number }
type DatasetRow = { label: string; split: string; arms: Arm[] }
type Breakdown = { scenario?: string; channel?: string; expected: number; predicted: number; precision: number; recall: number }
type Evaluation = {
  generatedAt: string; split: string; transactions: number; invoices: number
  comparison: Arm[]; scenarios: Breakdown[]; channels: Breakdown[]; unseenDataset?: Arm; datasets?: DatasetRow[]
  correctionsCurve: { corrections: number; queueAfter: number; humanTouches: number }[]
}
type Models = { active: string | null; models: { version: string; algorithm: string; active: boolean; train_rows: number; feedback_rows: number; holdout_metrics: { average_precision: number; precision_at_auto: number | null; recall_at_auto: number | null } }[] }

export default function Reports({ session }: { session: Session }) {
  const [ev, setEv] = useState<Evaluation | null>(null)
  const [models, setModels] = useState<Models | null>(null)
  const [msg, setMsg] = useState('')

  useEffect(() => {
    api<Evaluation>('/reports/evaluation').then(setEv).catch((e) => setMsg(e.message))
    api<Models>('/ml/models').then(setModels).catch(() => setModels(null))
  }, [])

  async function retrain() {
    setMsg('Retraining… this can take a minute.')
    try {
      const r = await api<{ promoted: boolean; reason: string; version: string }>('/ml/retrain', { method: 'POST' })
      setMsg(r.promoted ? `New model ${r.version} is now active (${r.reason}).` : `New model kept but NOT promoted: ${r.reason}.`)
      setModels(await api<Models>('/ml/models'))
    } catch (e) { setMsg((e as Error).message) }
  }

  return (
    <>
      <h2>Reports</h2>
      {msg && <p className="muted">{msg}</p>}
      {ev && (
        <>
          <div className="card" style={{ marginBottom: 16 }}>
            <h3 style={{ marginTop: 0 }}>Does each tier earn its place?</h3>
            <p className="small muted">Measured on the held-out “{ev.split}” customers of a synthetic dataset ({ev.invoices} invoices, {ev.transactions} bank transactions). Generated {new Date(ev.generatedAt).toLocaleString()}.</p>
            <table>
              <thead><tr><th>Pipeline</th><th className="num">Precision</th><th className="num">Recall</th><th className="num">Auto-matched</th><th className="num">Manual work left</th><th className="num">Invoice status correct</th></tr></thead>
              <tbody>
                {ev.comparison.map((a) => (
                  <tr key={a.label}><td>{a.label}</td><td className="num">{pct(a.precision)}</td><td className="num">{pct(a.recall)}</td>
                    <td className="num">{pct(a.autoRate)}</td><td className="num">{pct(a.manualLeft)}</td><td className="num">{pct(a.statusAccuracy)}</td></tr>
                ))}
                {ev.unseenDataset && (
                  <tr><td>Full pipeline on a never-seen dataset (new seed)</td><td className="num">{pct(ev.unseenDataset.precision)}</td><td className="num">{pct(ev.unseenDataset.recall)}</td>
                    <td className="num">{pct(ev.unseenDataset.autoRate)}</td><td className="num">{pct(ev.unseenDataset.manualLeft)}</td><td className="num">{pct(ev.unseenDataset.statusAccuracy)}</td></tr>
                )}
              </tbody>
            </table>
          </div>

          {ev.datasets && ev.datasets.length > 0 && (
            <div className="card" style={{ marginBottom: 16 }}>
              <h3 style={{ marginTop: 0 }}>How it holds up when the data gets messy</h3>
              <p className="small muted">Noise added: typos in payer names, invoice numbers removed, payment dates shifted, bank charges deducted, customers with identical amounts, and duplicate bank rows. Precision is the number to watch: a wrong auto-match costs more than a missed one.</p>
              <table>
                <thead><tr><th>Dataset</th><th>Pipeline</th><th className="num">Precision</th><th className="num">Recall</th><th className="num">Auto-matched</th><th className="num">Review items with right top suggestion</th></tr></thead>
                <tbody>
                  {ev.datasets.flatMap((d) => d.arms.map((a, i) => (
                    <tr key={d.label + a.label}>
                      <td>{i === 0 ? <strong>{d.label}</strong> : ''}</td><td>{a.label}</td>
                      <td className="num">{pct(a.precision)}</td><td className="num">{pct(a.recall)}</td><td className="num">{pct(a.autoRate)}</td>
                      <td className="num">{a.reviewTxns && i === 2 ? `${a.reviewTop1Correct} of ${a.reviewTxns}` : '–'}</td>
                    </tr>
                  )))}
                </tbody>
              </table>
            </div>
          )}

          <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(380px, 1fr))' }}>
            <div className="card">
              <h3 style={{ marginTop: 0 }}>Recall by scenario</h3>
              <ResponsiveContainer width="100%" height={300}>
                <BarChart data={ev.scenarios.map((s) => ({ name: s.scenario, recall: +(s.recall * 100).toFixed(1) }))} layout="vertical" margin={{ left: 40 }}>
                  <CartesianGrid strokeDasharray="3 3" /><XAxis type="number" domain={[0, 100]} /><YAxis type="category" dataKey="name" width={140} /><Tooltip />
                  <Bar dataKey="recall" fill="#1f5eff" name="Recall %" />
                </BarChart>
              </ResponsiveContainer>
            </div>
            <div className="card">
              <h3 style={{ marginTop: 0 }}>Human corrections vs work left (rule scorer)</h3>
              <ResponsiveContainer width="100%" height={300}>
                <LineChart data={ev.correctionsCurve}>
                  <CartesianGrid strokeDasharray="3 3" /><XAxis dataKey="corrections" /><YAxis /><Tooltip /><Legend />
                  <Line dataKey="queueAfter" name="Queue left" stroke="#c0392b" /><Line dataKey="humanTouches" name="Corrections + queue" stroke="#14804a" />
                </LineChart>
              </ResponsiveContainer>
              <p className="small muted">Each correction clears itself and teaches an alias. The queue shrinks faster than the corrections made only when payers repeat; in this synthetic data most pay once or twice.</p>
            </div>
          </div>
        </>
      )}

      <div className="card">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h3 style={{ margin: 0 }}>ML models</h3>
          {session.role === 'ADMIN' && <button className="btn" onClick={retrain}>Retrain with feedback</button>}
        </div>
        {models ? (
          <table>
            <thead><tr><th>Version</th><th>Algo</th><th className="num">Train rows</th><th className="num">Feedback rows</th><th className="num">Avg precision</th><th className="num">Precision @ auto</th><th /></tr></thead>
            <tbody>
              {models.models.map((m) => (
                <tr key={m.version}><td>{m.version}</td><td>{m.algorithm}</td><td className="num">{m.train_rows}</td><td className="num">{m.feedback_rows}</td>
                  <td className="num">{m.holdout_metrics.average_precision.toFixed(4)}</td><td className="num">{pct(m.holdout_metrics.precision_at_auto)}</td>
                  <td>{m.active && <span className="pill ok">active</span>}</td></tr>
              ))}
            </tbody>
          </table>
        ) : <p className="muted small">ML service not reachable (the engine falls back to the rule scorer).</p>}
        <p className="small muted" style={{ marginBottom: 0 }}>A retrained model replaces the active one only if it scores better on the same untouched holdout.</p>
      </div>
    </>
  )
}
