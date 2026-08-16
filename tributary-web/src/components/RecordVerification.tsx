import { useEffect, useState } from "react";
import { verifyRecord, type RecordVerification as RecordVerificationData } from "../api/client";

/**
 * T-804 / ADR-007 + ADR-009: the real destination of the ES-regime QR.
 *
 * Until now that QR pointed at `GET /api/v1/records/{id}/verification`, which answers JSON — so a
 * person scanning a printed invoice with their phone got a machine response. This page closes
 * that gap. It is the one route that needs no token at all, matching ADR-009's single public
 * endpoint, and it renders exactly the six fields that endpoint is allowed to expose: no personal
 * data, no amounts, no tax identifiers.
 *
 * It also renders `nonSubmittedNotice` prominently rather than in fine print. ADR-005 and ADR-007
 * exist so the system never claims a submission that did not happen; hiding that notice here
 * would defeat the control this page is the visible end of.
 */
export function RecordVerification({ recordId }: { recordId: string }) {
  const [record, setRecord] = useState<RecordVerificationData | null>(null);
  const [status, setStatus] = useState<"loading" | "found" | "not-found" | "error">("loading");

  useEffect(() => {
    let cancelled = false;
    setStatus("loading");

    verifyRecord(recordId)
      .then(({ data, response }) => {
        if (cancelled) return;
        if (response.status === 404) {
          setStatus("not-found");
          return;
        }
        if (!data) {
          setStatus("error");
          return;
        }
        setRecord(data);
        setStatus("found");
      })
      .catch(() => {
        if (!cancelled) setStatus("error");
      });

    return () => {
      cancelled = true;
    };
  }, [recordId]);

  if (status === "loading") return <p>Verifying record…</p>;

  if (status === "not-found") {
    return (
      <section>
        <h1>No such record</h1>
        <p>
          No fiscal record exists with identifier <code>{recordId}</code>. A record that was never
          written cannot be verified — this is a negative answer, not an error.
        </p>
      </section>
    );
  }

  if (status === "error" || !record) {
    return (
      <section>
        <h1>Verification unavailable</h1>
        <p>The verifier could not be reached. No claim is made about this record either way.</p>
      </section>
    );
  }

  return (
    <section>
      <h1>Fiscal record verified</h1>

      {record.nonSubmittedNotice && (
        <p role="status" className="notice">
          {record.nonSubmittedNotice}
        </p>
      )}

      <dl>
        <dt>Record</dt>
        <dd>
          <code>{record.recordId}</code>
        </dd>

        <dt>Position in chain</dt>
        <dd>{record.chainPosition}</dd>

        <dt>Issued at</dt>
        <dd>{record.issuedAt}</dd>

        <dt>Hash</dt>
        <dd>
          <code>{record.hash}</code>
        </dd>

        <dt>Previous hash</dt>
        <dd>
          <code>{record.previousHash ?? "— first record in this chain"}</code>
        </dd>
      </dl>
    </section>
  );
}
