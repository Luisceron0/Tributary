# Deployment runbook — Milestone 2 (§10.5)

Everything in `deploy/` and `docker-compose.prod.yml` is built and verified locally (see
"What was verified" below). What's left needs your Oracle Cloud account, your domain choice, and
your Factus dashboard — things this session structurally cannot act on. This document is the
exact sequence to close them.

## 1. Oracle Cloud: create the VM (your action)

1. Sign up at [cloud.oracle.com](https://cloud.oracle.com) if you don't have an account (requires
   identity verification and a card for verification only — the Always Free tier itself is not
   billed).
2. **Compute → Instances → Create instance.**
   - Image: **Ubuntu 24.04**, Shape: **VM.Standard.A1.Flex (Ampere ARM)** — the Always Free tier
     covers up to 4 OCPUs / 24 GB RAM; 2 OCPUs / 12 GB is generous for this stack.
   - Under "Add SSH keys," upload your public key (generate one with `ssh-keygen` if needed).
3. **Networking → Virtual Cloud Networks → your VCN → Security Lists → Default Security List →
   Add Ingress Rules:**
   - `0.0.0.0/0`, TCP, port `80`
   - `0.0.0.0/0`, TCP, port `443`
   - (port `22` for SSH is usually already open by the default rule — confirm it, don't add it wide open beyond your own IP if you'd rather restrict it)
4. Note the instance's **public IP** — you need it for the domain step next.

## 2. Domain (your action, two options)

- **You own a domain:** point an `A` record at the VM's public IP (e.g. `tributary.yourdomain.com`).
- **You don't:** use a wildcard DNS service that resolves to the IP with no registration —
  `<ip-with-dashes>.sslip.io`, e.g. IP `203.0.113.10` → `203-0-113-10.sslip.io`. This is a real,
  resolvable domain name from Let's Encrypt's point of view; Caddy requests a real certificate for
  it exactly as it would for a purchased domain. Zero cost, works today.

## 3. Server setup (your action, one script)

SSH into the VM, then:

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-plugin git
sudo usermod -aG docker "$USER" && newgrp docker

sudo mkdir -p /opt/tributary && sudo chown "$USER" /opt/tributary
git clone <this-repo-url> /opt/tributary
cd /opt/tributary

./deploy/setup-prod-env.sh <your-domain>   # e.g. 203-0-113-10.sslip.io
docker compose -f docker-compose.prod.yml up --build -d
```

First build takes a few minutes (Maven + npm, both from a cold cache on a fresh VM). Watch it:

```bash
docker compose -f docker-compose.prod.yml logs -f
```

Visit `https://<your-domain>/` once `edge-1` logs show the certificate obtained. If it doesn't
resolve, re-check step 1.3 (ingress rules) — Let's Encrypt's HTTP-01 challenge needs port 80
reachable from the internet, not just 443.

## 4. Auto-start and scheduled reset (your action, systemd)

```bash
sudo cp deploy/tributary.service /etc/systemd/system/
sudo systemctl enable --now tributary.service

echo "TRIBUTARY_DOMAIN=<your-domain>" | sudo tee /opt/tributary/deploy/reset-domain.env
sudo cp deploy/tributary-reset.service deploy/tributary-reset.timer /etc/systemd/system/
sudo systemctl enable --now tributary-reset.timer
```

The timer runs `deploy/reset.sh` daily at 04:00 UTC (edit the `OnCalendar=` line in
`tributary-reset.timer` before enabling it if you want a different schedule) — fresh database,
fresh JWT keypair, freshly minted tokens. Every previously published demo token (including any
leaked beyond the page itself) stops verifying the moment it runs.

## 5. T-903 — does not apply to this deployment

`FactusEnvironment.resolve` is only reached when `TRIBUTARY_REGIME=CO`. This deployment sets
`TRIBUTARY_REGIME=ES` in `docker-compose.prod.yml` and never reads a single `FACTUS_*` variable —
confirmed by grep before writing this document, not assumed. There is no Factus credential in
this deployment to rotate. If you later deploy the CO regime publicly, revisit this.

## 6. T-905 — run the offensive verification against the real public instance

Once the domain resolves over HTTPS, repeat T-702/T-708 against it instead of localhost —
`sqlmap`, the `alg:none`/HS256-confusion probes, and the CV-02/CV-03 tamper sequence all still
apply, pointed at `https://<your-domain>` instead of `http://localhost:8080`.

**One real interaction to plan for:** T-900's rate limiter is live on this deployment (120/min per
IP, 60/min per client by default). A `sqlmap --level 3 --risk 2` run will hit it. That is the
control working correctly, not a failure to work around — either raise
`TRIBUTARY_RATE_LIMIT_PER_IP`/`_PER_CLIENT` in `.env` deliberately for the duration of that one
run and restore it after, or treat `429` as an expected/ignored code for that pass
(`sqlmap --ignore-code=429`) and let the limiter keep protecting real traffic the rest of the
time. Do not disable `RateLimitFilter` to make the tool "pass."

## What was verified before this document was written

Not asserted from reading the config — run for real, locally, against this exact stack (Caddy
substituted a plain-HTTP local Caddyfile in place of the real domain block only, since Let's
Encrypt's ACME challenge needs a publicly reachable port 80 this sandbox doesn't have; routing,
proxying, and the JWT/rate-limit logic are identical either way):

- `docker compose -f docker-compose.prod.yml up --build` succeeds end to end.
- The frontend is served, deep links fall back to `index.html` (SPA routing).
- `/api/*` is genuinely proxied to the `api` container — confirmed against the API's own
  structured access log, not just the HTTP status code (a `404` from Caddy failing to route would
  look identical to a `404` from the API saying "no such record" at the curl layer alone).
- A full OPERATOR flow (register → issue → `202 ISSUED`) works through the proxy with no CORS
  configuration at all — same-origin by construction.
- **T-901 spoofing resistance, proven against the real topology, not just the unit tests:** 124
  requests, each with a different forged `X-Forwarded-For`, were rate-limited as a single client —
  proof the header is discarded beyond Caddy's own trusted hop, not merely asserted from reading
  `ClientIpResolver`'s source.
- The production frontend bundle was fetched and inspected byte-for-byte: zero occurrences of a
  hardcoded `localhost:8080` fallback, and decoding the embedded JWTs directly confirms exactly
  `operator:demo` and `auditor:demo` are present — no `admin:demo` anywhere in the artifact that
  would actually ship.
