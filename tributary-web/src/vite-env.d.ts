/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  /** Pre-minted demo credentials — see ADR-010. Never real credentials. */
  readonly VITE_DEMO_TOKEN_OPERATOR?: string;
  readonly VITE_DEMO_TOKEN_AUDITOR?: string;
  readonly VITE_DEMO_TOKEN_ADMIN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
