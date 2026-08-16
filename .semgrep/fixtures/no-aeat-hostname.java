package com.tributary.semgreptest;

class VulnerableAeatReference {

  // ruleid: tributary-no-aeat-hostname
  static final String LEAKED_URL = "https://www2.agenciatributaria.gob.es/verifactu-lookup";

  static final String SAFE_URL =
      // ok: tributary-no-aeat-hostname
      "https://verify.tributary.example/records/lookup";
}
