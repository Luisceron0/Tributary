import { useState } from "react";
import { AdminPanel } from "./components/AdminPanel";
import { AuditorPanel } from "./components/AuditorPanel";
import { OperatorPanel } from "./components/OperatorPanel";
import { RecordVerification } from "./components/RecordVerification";
import "./App.css";

type Tab = "verify" | "operator" | "auditor" | "admin";

/**
 * T-804: a scanned QR arrives as `/?record=<uuid>` and must verify immediately — nobody scanning
 * a printed invoice should have to retype a UUID into a form. Read straight from the query string
 * rather than adding a router: one dependency-free line for the only deep link this app has.
 */
const recordFromUrl = new URLSearchParams(window.location.search).get("record");

/**
 * ADR-010: demo mode. The tab strip below is NOT a login — it swaps between pre-minted demo
 * tokens, and it says so on the page. The system never claims to do something it does not do,
 * and it does not authenticate anyone here.
 */
export default function App() {
  const [tab, setTab] = useState<Tab>("verify");
  const [recordId, setRecordId] = useState(recordFromUrl ?? "");
  const [submitted, setSubmitted] = useState<string | null>(recordFromUrl);

  return (
    <main>
      <header>
        <h1>Tributary</h1>
        <p className="tagline">
          Multi-regime e-invoicing reference implementation — Colombia, Spain, Germany.
        </p>
        <p role="note" className="notice">
          <strong>Demo instance.</strong> Not certified under any fiscal regime and not for
          production issuance (ADR-005). Data is synthetic and reset on a schedule. The credentials
          this page uses are public by design and grant no access to anything real.
        </p>
      </header>

      <nav aria-label="Role">
        <button type="button" data-testid="tab-verify" aria-pressed={tab === "verify"} onClick={() => setTab("verify")}>
          Public verification
        </button>
        <button type="button" data-testid="tab-operator" aria-pressed={tab === "operator"} onClick={() => setTab("operator")}>
          Operator
        </button>
        <button type="button" data-testid="tab-auditor" aria-pressed={tab === "auditor"} onClick={() => setTab("auditor")}>
          Auditor
        </button>
        <button type="button" data-testid="tab-admin" aria-pressed={tab === "admin"} onClick={() => setTab("admin")}>
          Administrator
        </button>
      </nav>

      {tab === "verify" && (
        <section>
          <h2>Verify a fiscal record</h2>
          <p>
            The destination of the ES-regime QR code, and the only route in the whole system that
            needs no credentials at all (ADR-009).
          </p>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              setSubmitted(recordId.trim() || null);
            }}
          >
            <label htmlFor="recordId">Record identifier</label>
            <input
              id="recordId"
              value={recordId}
              onChange={(event) => setRecordId(event.target.value)}
              placeholder="00000000-0000-0000-0000-000000000000"
            />
            <button type="submit">Verify</button>
          </form>
          {submitted && <RecordVerification recordId={submitted} />}
        </section>
      )}

      {tab === "operator" && <OperatorPanel />}
      {tab === "auditor" && <AuditorPanel />}
      {tab === "admin" && <AdminPanel />}
    </main>
  );
}
