const BASE = (import.meta.env.VITE_API_URL as string | undefined) ?? '/api'

export type Session = { token: string; username: string; role: 'ADMIN' | 'ACCOUNTANT' | 'VIEWER' }

let session: Session | null = null
try {
  const raw = sessionStorage.getItem('session')
  if (raw) session = JSON.parse(raw)
} catch {
  session = null
}

export const getSession = () => session
export function setSession(s: Session | null) {
  session = s
  try {
    if (s) sessionStorage.setItem('session', JSON.stringify(s))
    else sessionStorage.removeItem('session')
  } catch {
    /* storage unavailable: stay logged in for this page load only */
  }
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { ...(init.headers as Record<string, string>) }
  if (session) headers.Authorization = `Bearer ${session.token}`
  if (init.body && !(init.body instanceof FormData)) headers['Content-Type'] = 'application/json'
  const res = await fetch(BASE + path, { ...init, headers })
  if (res.status === 401 && session) {
    setSession(null)
    window.location.reload()
  }
  const text = await res.text()
  if (!res.ok) {
    let msg = text
    try {
      msg = JSON.parse(text).message ?? JSON.parse(text).error ?? text
    } catch {
      /* not JSON */
    }
    if (/^\s*<(!doctype|html)/i.test(msg) || res.status === 502 || res.status === 503 || res.status === 504)
      msg = 'The server is not reachable right now. If it was just started, wait a minute and try again; otherwise check the backend logs.'
    const fallback = res.status === 401 ? 'Wrong username or password.'
      : res.status === 429 ? 'Too many failed attempts. Try again in a few minutes.'
      : `Request failed (${res.status}).`
    throw new ApiError(res.status, msg || fallback)
  }
  return (text ? JSON.parse(text) : null) as T
}

// ---- shapes returned by the backend ----
export type Dashboard = {
  autoMatched: number; pendingReview: number; unmatched: number; classified: number; autoMatchRate: number
  pendingReviewValue: number; unmatchedValue: number; outstandingTotal: number; unappliedCredits: number
  invoicesByStatus: Record<string, number>; running: boolean
  lastRun: { transactions: number; auto: number; review: number; unmatched: number; classified: number; millis: number; scorer: string; warnings: string[] } | null
}
export type PartView = { invoiceId: string; customerName: string; amount: number; invoiceAmount: number }
export type CandidateView = { rank: number; score: number; customerId: string; parts: PartView[]; adjustment: number; excess: number }
export type TxnView = { id: string; date: string; amount: number; direction: string; payer: string; channel: string; narration: string; bankRef: string }
export type ExceptionView = {
  txn: TxnView; decision: 'REVIEW' | 'UNMATCHED'; confidence: number; reason: string; kind: string; linkedTxnId: string; candidates: CandidateView[]
}
export type InvoiceView = {
  id: string; customerId: string; customerName: string; amount: number; paid: number; adjusted: number; outstanding: number
  issueDate: string; dueDate: string; status: string
}
export type TxnRow = {
  id: string; date: string; amount: number; direction: string; payer: string; channel: string
  bankRef: string; reversalRef: string; narration: string; status: string
}
export type UserView = { id: number; username: string; role: 'ADMIN' | 'ACCOUNTANT' | 'VIEWER' }
export type Report = { rows: number; inserted: number; duplicates: number; errors: string[] }
export type InvoiceOption = { id: string; outstanding: number; dueDate: string }
export type CreditView = { txnId: string; txnDate: string; payer: string; customerId: string; customerName: string; amount: number; openInvoices: InvoiceOption[] }
export type ApplicationView = { id: number; txnId: string; invoiceId: string; amount: number; appliedBy: string; appliedAt: string }
export type DecisionView = {
  txnId: string; date: string; txnAmount: number; direction: string; payer: string; decidedBy: string; decidedAt: string
  parts: { invoiceId: string; customerName: string; amount: number }[]
}

/** Paise -> rupees with Indian digit grouping. Display only; all arithmetic stays in paise on the server. */
export const rupees = (paise: number) =>
  '₹' + (paise / 100).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
export const pct = (v: number | null | undefined) => (v == null ? '–' : (v * 100).toFixed(1) + '%')
