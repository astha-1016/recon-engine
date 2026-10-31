#!/usr/bin/env python3
"""Dependency-free synthetic INR reconciliation data. No real financial records."""
import argparse
import csv
import json
import random
from collections import Counter, defaultdict
from datetime import date, timedelta
from pathlib import Path


def money(paise):
    return f'{paise // 100}.{paise % 100:02d}' if paise >= 0 else '-' + money(-paise)


def generate(count=1000, seed=42, partial_rate=.15, split_rate=.10,
             refund_rate=.05, alias_rate=.20, messy_rate=.65):
    rates = [partial_rate, split_rate, refund_rate, alias_rate, messy_rate]
    if count < 30 or any(not 0 <= x <= 1 for x in rates):
        raise ValueError('Use at least 30 invoices and rates between 0 and 1.')
    if partial_rate + split_rate + refund_rate > .70:
        raise ValueError('Partial + split + refund rates must total at most 0.70 (room for bundles/open invoices).')
    r = random.Random(seed)
    invoices, txns, truth, feedback = [], [], [], []
    customers, groups = {}, defaultdict(list)
    base = date(2026, 7, 1)
    prefixes = ['ABC', 'Lotus', 'Vikas', 'Kaveri', 'Bharat', 'Meera', 'Unity', 'Surya']
    suffixes = ['Traders', 'Textiles', 'Engineering', 'Supplies', 'Foods']
    for i in range(count):
        c = i // 5
        cid = f'CUS-{c + 1:04d}'
        # Shared visible business names deliberately make payer-only matching ambiguous.
        name = f'{prefixes[c % 8]} {suffixes[(c // 8) % 5]} Pvt Ltd'
        split = 'test' if c % 10 >= 8 else 'validation' if c % 10 >= 6 else 'train'
        customers[cid] = (name, split, c)
        issued = base + timedelta(days=r.randrange(45))
        row = dict(invoice_id=f'INV-2026-{i + 1:05d}', customer_id=cid,
                   customer_name=name, amount=money(r.choice([1250000, 2500000, 5000000, 7500000, 10000000]) + r.randrange(10000)),
                   currency='INR', invoice_date=issued.isoformat(),
                   due_date=(issued + timedelta(days=30)).isoformat(), status='OPEN')
        invoices.append(row)
        groups[cid].append(row)
    # Convert exact decimal strings without using binary floating-point arithmetic.
    values = {x['invoice_id']: int(x['amount'].replace('.', '')) for x in invoices}
    scenario = {x['invoice_id']: 'full_payment' for x in invoices}
    shuffled = invoices.copy()
    r.shuffle(shuffled)
    cursor = 0
    for kind, rate in [('partial_payment', partial_rate), ('split_payment', split_rate), ('refund', refund_rate), ('unpaid', .10)]:
        for row in shuffled[cursor:cursor + int(count * rate)]:
            scenario[row['invoice_id']] = kind
        cursor += int(count * rate)
    bundles, reserved = [], set()
    target = max(1, count // 10)
    for rows in groups.values():
        eligible = [x for x in rows if scenario[x['invoice_id']] == 'full_payment']
        if len(eligible) >= 2 and len(reserved) < target:
            batch = eligible[:min(3, len(eligible), target - len(reserved))]
            if len(batch) >= 2:
                bundles.append(batch)
                for row in batch:
                    reserved.add(row['invoice_id'])
                    scenario[row['invoice_id']] = 'multi_invoice_payment'
    pending = []

    def add_payment(rows, amounts, kind, original=None, event_type='PAYMENT', day_offset=0):
        name, split, c = customers[rows[0]['customer_id']]
        latest = max(date.fromisoformat(x['invoice_date']) for x in rows)
        dt = latest + timedelta(days=10 + day_offset)
        pending.append(dict(rows=rows, amounts=amounts, kind=kind, original=original,
                            event_type=event_type, dt=dt, split=split, c=c, name=name))
        return len(pending) - 1

    for rows in bundles:
        add_payment(rows, [values[x['invoice_id']] for x in rows], 'multi_invoice_payment')
    for row in invoices:
        iid = row['invoice_id']
        a, kind = values[iid], scenario[iid]
        if kind in ('unpaid', 'multi_invoice_payment'):
            continue
        if kind == 'partial_payment':
            add_payment([row], [a * 40 // 100], kind)
        elif kind == 'split_payment':
            part = a // 3
            add_payment([row], [part], kind)
            add_payment([row], [part], kind, day_offset=5)
            add_payment([row], [a - 2 * part], kind, day_offset=10)
        elif kind == 'refund':
            original = add_payment([row], [a], kind)
            # Half bank reversals, half customer refunds; no refunds exceed settled cash.
            event = 'REVERSAL' if len(pending) % 2 else 'REFUND'
            add_payment([row], [-a], kind, original, event, 8)
        else:
            add_payment([row], [a], kind)
    alias_indices = set(r.sample(range(len(pending)), int(len(pending) * alias_rate)))
    messy_indices = set(r.sample(range(len(pending)), int(len(pending) * messy_rate)))
    ids = list(range(1, len(pending) + max(1, count // 20) + 1))
    r.shuffle(ids)
    event_ids = {i: f'TXN-{ids[i]:06d}' for i in range(len(pending))}
    references = {i: f'UTR{r.randrange(10**11, 10**12)}' for i in range(len(pending))}
    channels = ['QR', 'UPI_ID', 'NEFT', 'IMPS', 'CHEQUE']
    for i, p in enumerate(pending):
        mode = r.choice(channels)
        alias = i in alias_indices
        payer = p['name']
        if alias:
            payer = r.choice([f'synthetic{p["c"]:04d}@exampleupi', f'XXXX{p["c"] + 1000:04d}',
                              p['name'].upper().replace('TRADERS', 'TRD').replace('PVT LTD', 'P LTD')])
        original = p['original']
        reference = references[i]
        narration = f'{mode} {p["event_type"]}'
        if i not in messy_indices and original is None:
            narration += ' ' + ' '.join(x['invoice_id'] for x in p['rows'])
        if original is not None:
            narration += ' ' + p['event_type']
            # Some negative transactions have no explicit reversal reference.
            if i % 3:
                narration += ' ' + references[original]
        txns.append(dict(transaction_id=event_ids[i], transaction_date=p['dt'].isoformat(),
                         amount=money(sum(p['amounts'])), currency='INR', direction='DR' if original is not None else 'CR',
                         payer_name=payer, channel=mode, bank_reference=reference,
                         reversal_reference=references[original] if original is not None and i % 3 else '',
                         narration=narration))
        for row, amount in zip(p['rows'], p['amounts']):
            truth.append(dict(transaction_id=event_ids[i], invoice_id=row['invoice_id'], customer_id=row['customer_id'],
                              allocated_amount=money(amount), scenario=p['kind'], event_type=p['event_type'],
                              original_transaction_id=event_ids[original] if original is not None else '',
                              payer_is_alias=int(alias), missing_invoice_reference=int(not any(x['invoice_id'] in narration for x in p['rows'])),
                              evaluation_split=p['split']))
        # Simulated review examples only from training customers: never pretend these are real corrections.
        if alias and p['split'] == 'train' and original is None and len(p['rows']) == 1:
            correct = p['rows'][0]
            candidates = [x for x in groups[correct['customer_id']] if x['invoice_id'] != correct['invoice_id']]
            if candidates:
                feedback.append(dict(feedback_id=f'FDB-{len(feedback) + 1:05d}', transaction_id=event_ids[i],
                                     proposed_invoice_id=r.choice(candidates)['invoice_id'], corrected_invoice_id=correct['invoice_id'],
                                     confirmed_customer_id=correct['customer_id'], observed_payer=payer,
                                     reviewed_at=(p['dt'] + timedelta(days=1)).isoformat(), action='CORRECTED', source='SYNTHETIC_REVIEW'))
    for n in range(max(1, count // 20)):
        tid = f'TXN-{ids[len(pending) + n]:06d}'
        txns.append(dict(transaction_id=tid, transaction_date=(base + timedelta(days=75)).isoformat(),
                         amount=money(-r.randrange(10000, 100000)), currency='INR', direction='DR', payer_name='BANK SERVICE FEE',
                         channel='BANK', bank_reference=f'FEE{n:06d}', reversal_reference='', narration='MONTHLY SERVICE CHARGE'))
        truth.append(dict(transaction_id=tid, invoice_id='', customer_id='', allocated_amount='0.00', scenario='unmatched_bank_fee',
                          event_type='UNMATCHED', original_transaction_id='', payer_is_alias=0,
                          missing_invoice_reference=1, evaluation_split='test'))
    r.shuffle(txns)
    r.shuffle(invoices)
    totals = defaultdict(int)
    final = {}
    for x in truth:
        if x['invoice_id']:
            totals[x['invoice_id']] += int(x['allocated_amount'].replace('.', ''))
    for row in invoices:
        iid = row['invoice_id']
        state = 'DISPUTED' if any(t['invoice_id'] == iid and t['event_type'] == 'REVERSAL' for t in truth) else 'REFUNDED' if scenario[iid] == 'refund' else 'OPEN' if totals[iid] == 0 else 'PAID' if totals[iid] == values[iid] else 'PARTIAL'
        final[iid] = dict(status=state, net_paid=money(totals[iid]), remaining=money(values[iid] - totals[iid]))
    summary = dict(synthetic=True, seed=seed, currency='INR', invoice_count=count, transaction_count=len(txns),
                   feedback_count=len(feedback), allocation_count=sum(bool(x['invoice_id']) for x in truth),
                   requested_rates=dict(partial=partial_rate, split=split_rate, refund=refund_rate, alias=alias_rate, messy=messy_rate),
                   invoice_scenarios=dict(Counter(scenario.values())), transaction_channels=dict(Counter(x['channel'] for x in txns)),
                   alias_transaction_count=len(alias_indices), missing_reference_transaction_count=len(messy_indices),
                   expected_final_invoices=final)
    return dict(invoices=invoices, bank_transactions=txns, feedback_log=feedback, ground_truth=truth), summary


HEADERS = {
    'invoices': 'invoice_id customer_id customer_name amount currency invoice_date due_date status',
    'bank_transactions': 'transaction_id transaction_date amount currency direction payer_name channel bank_reference reversal_reference narration',
    'feedback_log': 'feedback_id transaction_id proposed_invoice_id corrected_invoice_id confirmed_customer_id observed_payer reviewed_at action source',
    'ground_truth': 'transaction_id invoice_id customer_id allocated_amount scenario event_type original_transaction_id payer_is_alias missing_invoice_reference evaluation_split',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--invoices', type=int, default=1000)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--output', type=Path, default=Path('sample_data'))
    for flag, default in [('partial', .15), ('split', .10), ('refund', .05), ('alias', .20), ('messy', .65)]:
        parser.add_argument(f'--{flag}-rate', type=float, default=default)
    args = parser.parse_args()
    try:
        datasets, summary = generate(args.invoices, args.seed, args.partial_rate, args.split_rate, args.refund_rate, args.alias_rate, args.messy_rate)
    except ValueError as e:
        parser.error(str(e))
    args.output.mkdir(parents=True, exist_ok=True)
    for name, rows in datasets.items():
        with (args.output / f'{name}.csv').open('w', newline='', encoding='utf-8') as f:
            writer = csv.DictWriter(f, fieldnames=HEADERS[name].split())
            writer.writeheader()
            writer.writerows(rows)
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print(f'Created {summary["invoice_count"]} invoices, {summary["transaction_count"]} bank transactions and {summary["feedback_count"]} simulated reviews in {args.output}.')


if __name__ == '__main__':
    main()
