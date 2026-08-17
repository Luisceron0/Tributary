import createClient from "openapi-fetch";
import type { components, paths } from "./schema";

/**
 * T-801: the client is typed by `schema.d.ts`, which is GENERATED from `docs/openapi.json`
 * (`npm run generate:api`) — never hand-written. That is the point of the whole arrangement: if
 * the API contract drifts, the regenerated types stop matching the call sites below and
 * `tsc -b` fails the build. The OpenAPI document stops being documentation and becomes a
 * verified control, the same "verified, not declared" discipline the Maven side already runs on.
 *
 * ADR-010: authentication is demo mode. There is no login, no session, and no token issuance —
 * `tributary-api` remains a pure OAuth2 resource server exactly as ADR-006 decided. The bearer
 * token here is a pre-minted demo credential and is readable by anyone who opens DevTools; that
 * is inherent to the decision, and the deployment shape (throwaway database, synthetic data,
 * scheduled reset) is what makes it acceptable — not the fact that it is called a demo.
 */

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

export type DemoRole = "OPERATOR" | "AUDITOR" | "ADMIN";

export type InvoiceResponse = components["schemas"]["InvoiceResponseDto"];
export type InvoiceRequest = components["schemas"]["InvoiceRequestDto"];
export type RecordVerification = components["schemas"]["RecordVerificationView"];

const client = createClient<paths>({ baseUrl: API_BASE_URL });

/**
 * Demo tokens, injected at build time. Never a real credential — see ADR-010.
 *
 * The public deployment deliberately builds **without** an ADMIN token. That is not a UI-level
 * restriction that a determined visitor could bypass: every token is RS256-signed, so reading the
 * published OPERATOR/AUDITOR tokens does not let anyone mint an ADMIN one — that needs the private
 * key, which never leaves the build environment. With no valid ADMIN token in existence, `DELETE
 * /api/v1/subjects/{subjectId}/personal-data` is unreachable on that instance by cryptography
 * rather than by policy. CV-08 is still verified where it means something: against the API, in the
 * integration suite.
 */
function demoToken(role: DemoRole): string | undefined {
  const tokens: Record<DemoRole, string | undefined> = {
    OPERATOR: import.meta.env.VITE_DEMO_TOKEN_OPERATOR,
    AUDITOR: import.meta.env.VITE_DEMO_TOKEN_AUDITOR,
    ADMIN: import.meta.env.VITE_DEMO_TOKEN_ADMIN,
  };
  return tokens[role];
}

/** Which roles this build actually carries a credential for. */
export function roleIsAvailable(role: DemoRole): boolean {
  return Boolean(demoToken(role));
}

function authHeaders(role: DemoRole): Record<string, string> {
  const token = demoToken(role);
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function registerInvoice(role: DemoRole, body: InvoiceRequest) {
  return client.POST("/api/v1/invoices", { body, headers: authHeaders(role) });
}

export async function issueInvoice(role: DemoRole, businessKey: string) {
  return client.POST("/api/v1/invoices/{businessKey}/issuances", {
    params: { path: { businessKey } },
    headers: authHeaders(role),
  });
}

export async function getInvoice(role: DemoRole, businessKey: string) {
  return client.GET("/api/v1/invoices/{businessKey}", {
    params: { path: { businessKey } },
    headers: authHeaders(role),
  });
}

export async function verifyChain(role: DemoRole, chainId: string) {
  return client.GET("/api/v1/chains/{chainId}/verification", {
    params: { path: { chainId } },
    headers: authHeaders(role),
  });
}

/** RF-007, ADMIN only. Unreachable on the public demo build — see {@link demoToken}. */
export async function suppressPersonalData(role: DemoRole, subjectId: string, justification: string) {
  return client.DELETE("/api/v1/subjects/{subjectId}/personal-data", {
    params: { path: { subjectId } },
    body: { justification },
    headers: authHeaders(role),
  });
}

/** ADR-009: the one route that needs no token at all — the real destination of the ES QR. */
export async function verifyRecord(recordId: string) {
  return client.GET("/api/v1/records/{recordId}/verification", {
    params: { path: { recordId } },
  });
}
