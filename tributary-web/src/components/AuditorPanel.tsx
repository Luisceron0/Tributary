import { useState } from "react";
import { getInvoice, verifyChain, type InvoiceResponse } from "../api/client";
import type { components } from "../api/schema";

type ChainVerification = components["schemas"]["ChainVerificationView"];

/**
 * T-803, AUDITOR: read a document, and verify a chain. This is the panel where the project's
 * thesis is visible — `BROKEN` names the exact record whose stored hash stops matching the
 * recomputed one (CV-03), which is the difference between detecting tampering and merely
 * asserting integrity.
 */
export function AuditorPanel() {
  const [businessKey, setBusinessKey] = useState("");
  const [chainId, setChainId] = useState("");
  const [invoice, setInvoice] = useState<InvoiceResponse | null>(null);
  const [chain, setChain] = useState<ChainVerification | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function lookUpInvoice() {
    setMessage(null);
    setInvoice(null);
    const { data, response } = await getInvoice("AUDITOR", businessKey.trim());
    if (response.status === 404) {
      setMessage("No invoice with that business key.");
      return;
    }
    if (!data) {
      setMessage(`Lookup failed (HTTP ${response.status}).`);
      return;
    }
    setInvoice(data);
  }

  async function checkChain() {
    setMessage(null);
    setChain(null);
    const { data, response } = await verifyChain("AUDITOR", chainId.trim());
    if (response.status === 404) {
      setMessage("Unknown chain, or a chain with no records — indistinguishable, so answered as not found.");
      return;
    }
    if (!data) {
      setMessage(`Verification failed (HTTP ${response.status}).`);
      return;
    }
    setChain(data);
  }

  return (
    <section>
      <h2>Auditor</h2>

      <label htmlFor="businessKey">Business key</label>
      <input id="businessKey" value={businessKey} onChange={(e) => setBusinessKey(e.target.value)} />
      <button type="button" onClick={lookUpInvoice} disabled={!businessKey.trim()}>
        Look up invoice
      </button>

      {invoice && (
        <dl>
          <dt>State</dt>
          <dd>{invoice.state}</dd>
          <dt>Buyer</dt>
          <dd>{invoice.buyerName}</dd>
          <dt>Tax inclusive</dt>
          <dd>
            {invoice.taxInclusiveAmount} {invoice.currency}
          </dd>
        </dl>
      )}

      <hr />

      <label htmlFor="chainId">Chain identifier</label>
      <input id="chainId" value={chainId} onChange={(e) => setChainId(e.target.value)} />
      <button type="button" data-testid="verify-chain" onClick={checkChain} disabled={!chainId.trim()}>
        Verify chain
      </button>

      {chain && (
        <div className={chain.status === "INTACT" ? "verdict intact" : "verdict broken"}>
          <p>
            <strong>{chain.status}</strong> · {chain.recordsVerified} record(s) verified
          </p>
          {chain.status === "BROKEN" && (
            <dl>
              <dt>Broken record</dt>
              <dd>
                <code>{chain.brokenRecordId}</code>
              </dd>
              <dt>Stored hash</dt>
              <dd>
                <code>{chain.storedHash}</code>
              </dd>
              <dt>Recomputed hash</dt>
              <dd>
                <code>{chain.recomputedHash}</code>
              </dd>
              <dt>Total mismatches</dt>
              <dd>{chain.totalMismatches}</dd>
            </dl>
          )}
        </div>
      )}

      {message && <p role="status">{message}</p>}
    </section>
  );
}
