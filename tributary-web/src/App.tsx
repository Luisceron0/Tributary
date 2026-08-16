import { useState } from "react";
import { RecordVerification } from "./components/RecordVerification";
import "./App.css";

/**
 * ADR-010: demo mode. The role selector below is NOT a login — it swaps between pre-minted
 * demo tokens. It is labelled as such in the interface on purpose: the system never claims to
 * do something it does not do, and it does not authenticate anyone here.
 */
export default function App() {
  const [recordId, setRecordId] = useState("");
  const [submitted, setSubmitted] = useState<string | null>(null);

  return (
    <main>
      <header>
        <h1>Tributary</h1>
        <p>
          Multi-regime e-invoicing reference implementation. <strong>Not certified</strong> under
          any fiscal regime — see ADR-005.
        </p>
        <p role="note" className="notice">
          Demo instance. Data is synthetic and reset on a schedule; the credentials this page uses
          are public by design and grant no access to anything real.
        </p>
      </header>

      <section>
        <h2>Verify a fiscal record</h2>
        <p>
          The public verification endpoint — the destination of the ES-regime QR code. No
          credentials required.
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
      </section>

      {submitted && <RecordVerification recordId={submitted} />}
    </main>
  );
}
