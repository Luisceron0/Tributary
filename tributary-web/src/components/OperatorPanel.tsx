import { useState } from "react";
import { issueInvoice, registerInvoice, type InvoiceResponse } from "../api/client";

const SAMPLE = {
  issuer: { name: "Acme Exports SL", taxIdentifier: "ESB12345678", countryCode: "ES" },
  buyer: { name: "Handel GmbH", taxIdentifier: "DE123456789", countryCode: "DE" },
  currency: "EUR",
  issueDate: "2026-08-16",
  lines: [
    {
      lineIdentifier: "1",
      itemName: "Widgets",
      quantity: 1,
      unitCode: "C62",
      unitPrice: 100.0,
      taxCategory: "STANDARD",
      taxRate: 19,
    },
  ],
};

/**
 * T-803, OPERATOR: register then issue. Deliberately two steps, because that is what the domain
 * does — registration produces a DRAFT and issuance is the irreversible transition (ADR-003).
 * Collapsing them into one button would misrepresent the state machine the whole project is about.
 */
export function OperatorPanel() {
  const [saleId, setSaleId] = useState(() => `demo-${Date.now()}`);
  const [invoice, setInvoice] = useState<InvoiceResponse | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function register() {
    setBusy(true);
    setMessage(null);
    const { data, error, response } = await registerInvoice("OPERATOR", { saleId, ...SAMPLE });
    setBusy(false);
    if (error || !data) {
      setMessage(`Registration failed (HTTP ${response.status}).`);
      return;
    }
    setInvoice(data);
    setMessage(`Registered as ${data.state}. Nothing irreversible has happened yet.`);
  }

  async function issue() {
    if (!invoice?.businessKey) return;
    setBusy(true);
    setMessage(null);
    const { data, error, response } = await issueInvoice("OPERATOR", invoice.businessKey);
    setBusy(false);
    if (error || !data) {
      setMessage(`Issuance failed (HTTP ${response.status}).`);
      return;
    }
    setInvoice(data);
    setMessage(
      response.status === 424
        ? "Regime unreachable — left in NEEDS_RECONCILIATION. It will be queried before any retry, never re-issued blindly (ADR-003)."
        : `Issued. State is now ${data.state}, and the fiscal record is immutable from here (ADR-002).`,
    );
  }

  return (
    <section>
      <h2>Operator</h2>

      <label htmlFor="saleId">Sale identifier</label>
      <input id="saleId" value={saleId} onChange={(e) => setSaleId(e.target.value)} />

      <div className="actions">
        <button type="button" data-testid="register" onClick={register} disabled={busy}>
          1 · Register (DRAFT)
        </button>
        <button type="button" data-testid="issue" onClick={issue} disabled={busy || !invoice}>
          2 · Issue
        </button>
      </div>

      {message && <p role="status">{message}</p>}

      {invoice && (
        <dl>
          <dt>Business key</dt>
          <dd>
            <code>{invoice.businessKey}</code>
          </dd>
          <dt>State</dt>
          <dd>{invoice.state}</dd>
          <dt>Tax exclusive</dt>
          <dd>
            {invoice.taxExclusiveAmount} {invoice.currency}
          </dd>
          <dt>Tax total</dt>
          <dd>
            {invoice.taxTotal} {invoice.currency}
          </dd>
          <dt>Tax inclusive</dt>
          <dd>
            {invoice.taxInclusiveAmount} {invoice.currency}
          </dd>
        </dl>
      )}
    </section>
  );
}
