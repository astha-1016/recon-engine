package com.reconcile.engine;

import com.reconcile.engine.Model.BankTxn;
import com.reconcile.engine.Model.Channel;
import com.reconcile.engine.Model.Direction;
import com.reconcile.engine.Model.TxnKind;

import java.util.regex.Pattern;

/** Works out the payment channel and the kind of transaction from the raw bank row. */
public final class Classifier {
    private Classifier() {}

    private static final Pattern CHEQUE_RETURN = Pattern.compile("(?i)CHQ\\s*RET|CHEQUE\\s*(RET|BOUNCE)|DISHON|RTN\\s*CHQ|BOUNCED");
    private static final Pattern REFUND = Pattern.compile("(?i)REFUND");
    private static final Pattern REVERSAL = Pattern.compile("(?i)REVERSAL|REVERSED|\\bREV\\b");
    private static final Pattern FEE = Pattern.compile("(?i)CHARGE|\\bFEE\\b|INTEREST|\\bGST\\b|SERVICE");

    public static Channel channel(BankTxn t) {
        String c = t.channel().toUpperCase().replaceAll("[^A-Z_]", "");
        switch (c) {
            case "QR": return Channel.QR;
            case "UPI_ID": case "UPI": return Channel.UPI_ID;
            case "NEFT": return Channel.NEFT;
            case "IMPS": return Channel.IMPS;
            case "RTGS": return Channel.RTGS;
            case "CHEQUE": case "CHQ": case "CLG": return Channel.CHEQUE;
            case "CASH": return Channel.CASH;
            case "BANK": return Channel.BANK;
            default: break;
        }
        String n = t.narration().toUpperCase();
        if (n.contains("UPI/")) return Channel.UPI_ID;
        if (n.contains("NEFT")) return Channel.NEFT;
        if (n.contains("IMPS")) return Channel.IMPS;
        if (n.contains("RTGS")) return Channel.RTGS;
        if (n.contains("CHQ") || n.contains("CLG") || n.contains("CHEQUE")) return Channel.CHEQUE;
        if (n.contains("CASH")) return Channel.CASH;
        if (n.contains("QR")) return Channel.QR;
        return Channel.UNKNOWN;
    }

    public static TxnKind kind(BankTxn t) {
        if (t.direction() == Direction.CR) return TxnKind.PAYMENT;
        String text = t.narration() + " " + t.payer();
        if (CHEQUE_RETURN.matcher(text).find()) return TxnKind.CHEQUE_RETURN;
        if (REFUND.matcher(text).find()) return TxnKind.REFUND;
        if (REVERSAL.matcher(text).find()) return TxnKind.REVERSAL;
        if (channel(t) == Channel.BANK || FEE.matcher(text).find()) return TxnKind.BANK_FEE;
        return TxnKind.OTHER_DEBIT;
    }

    /** Cheques clear a few days late, so they get a wider date window than instant channels. */
    public static int windowDays(Channel c, Config cfg) {
        return cfg.dateWindowDays + (c == Channel.CHEQUE ? cfg.chequeExtraDays : 0);
    }
}
