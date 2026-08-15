package com.tributary.application.port;

import com.tributary.domain.Invoice;

/**
 * The single port every fiscal regime adapter implements — CO (Factus, live HTTP clearance), ES
 * (Verifactu, a local hash-chain insert) and DE (XRechnung, a local serialise-and-validate) are
 * three implementations of the SAME three operations (ADR-001). Nothing in this interface may
 * name a concrete regime's artifact (a CUFE, a reference code, a numbering range): the day a
 * fourth regime arrives, this interface should not need to change — only gain an implementation.
 */
public interface FiscalRegimePort {

  /**
   * Submits a draft invoice for issuance under this regime. What "issuance" means varies
   * completely by regime, but the caller never needs to know which: a network call to a
   * clearance authority for CO, a local chained-record insert for ES, a local
   * serialise-and-validate for DE.
   */
  IssuanceResult issue(Invoice invoice);

  /**
   * Produces this regime's correction artifact for an already-issued invoice (RF-004). The
   * original is never modified; this always creates something new that references it.
   */
  CancellationResult cancel(Invoice original, String correctionReason);

  /**
   * Asks the regime whether a given business key was issued, without side effects. This is what
   * the reconciler (RF-008) calls before ever deciding to retry an issuance — never the reverse.
   */
  RegimeQueryResult query(String businessKey);
}
