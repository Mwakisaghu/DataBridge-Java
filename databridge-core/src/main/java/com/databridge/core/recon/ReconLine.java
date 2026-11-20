package com.databridge.core.recon;

import java.math.BigDecimal;

/**
 * One line of a reconciliation: the trial balance itself (debits against credits) or one control
 * account (GL balance against its subledgers). Only these account-level totals are kept; no
 * record-level client data.
 *
 * @param left     TB: total debits; CONTROL: the GL balance on its normal side
 * @param right    TB: total credits; CONTROL: the subledger total
 * @param variance right minus left for a control, debits minus credits for the TB
 */
public record ReconLine(
        int lineNo,
        Type type,
        String controlId,
        String glAccount,
        String name,
        BigDecimal left,
        BigDecimal right,
        BigDecimal variance,
        Status status,
        String sources,
        String note) {

    public enum Type { TB, CONTROL }

    /** TIES: within tolerance. VARIANCE: outside it. NOT_CHECKED: something needed was missing. */
    public enum Status { TIES, VARIANCE, NOT_CHECKED }
}
