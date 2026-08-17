import { useState } from "react";
import { roleIsAvailable, suppressPersonalData } from "../api/client";

/**
 * T-803, ADMIN: GDPR erasure by key destruction (RF-007, ADR-004).
 *
 * On the public demo build this panel renders as unavailable — and that is a cryptographic fact,
 * not a UI guard someone could bypass with DevTools. The build carries no ADMIN token, and every
 * token is RS256-signed, so no valid one can be produced without the private key. Explaining that
 * here, rather than hiding the panel, is the point: the separation-of-duties boundary is more
 * interesting visible than absent.
 */
export function AdminPanel() {
  const available = roleIsAvailable("ADMIN");
  const [subjectId, setSubjectId] = useState("");
  const [justification, setJustification] = useState("");
  const [message, setMessage] = useState<string | null>(null);

  if (!available) {
    return (
      <section>
        <h2>Administrator</h2>
        <p role="note" className="notice">
          Unavailable on this deployment, by design. This build carries no administrator
          credential, and tokens are RS256-signed — reading the operator and auditor tokens shipped
          in this page does not allow minting an administrator one, which would require the private
          key. Crypto-shredding is therefore unreachable here rather than merely hidden.
        </p>
        <p>
          The control it would exercise is verified where that is meaningful: in the integration
          suite, where an operator attempting erasure receives <code>403</code> (CV-08).
        </p>
      </section>
    );
  }

  async function suppress() {
    setMessage(null);
    const { response } = await suppressPersonalData("ADMIN", subjectId.trim(), justification.trim());
    if (response.status === 200) {
      setMessage("Key destroyed. The fiscal record survives intact and verifiable (ADR-004).");
    } else if (response.status === 409) {
      setMessage("Blocked: an active fiscal retention obligation still covers this subject.");
    } else {
      setMessage(`Refused (HTTP ${response.status}).`);
    }
  }

  return (
    <section>
      <h2>Administrator</h2>
      <label htmlFor="subjectId">Subject identifier</label>
      <input id="subjectId" value={subjectId} onChange={(e) => setSubjectId(e.target.value)} />
      <label htmlFor="justification">Justification</label>
      <input id="justification" value={justification} onChange={(e) => setJustification(e.target.value)} />
      <button type="button" onClick={suppress} disabled={!subjectId.trim() || !justification.trim()}>
        Destroy key (irreversible)
      </button>
      {message && <p role="status">{message}</p>}
    </section>
  );
}
