#!/usr/bin/env node
// Validates the free-pi token from $FREEPI_TOKEN and prints your account status.
// Never prints the token itself. Exits 1 with a clear error on auth problems.

const BASE_URL = process.env.FREEPI_BASE_URL || "https://api.freepi.ai";
const token = (process.env.FREEPI_TOKEN || "").trim();
const quiet = process.argv.includes("--quiet");

function fail(message) {
  console.log(`::error::${message}`);
  process.exit(1);
}

if (!token) {
  fail(
    "FREEPI_TOKEN is empty. Add the repo secret FREEPI_TOKEN with your free-pi token " +
      '(from ~/.free-pi/agent/credentials.json -> "token").',
  );
}

// The token is a JWT; read "exp" without verifying it (the API is the authority).
let exp;
try {
  const payload = JSON.parse(Buffer.from(token.split(".")[1] ?? "", "base64url").toString("utf8"));
  exp = typeof payload.exp === "number" ? payload.exp : undefined;
} catch {
  fail("FREEPI_TOKEN does not look like a free-pi token (expected a JWT).");
}
if (!exp) fail("FREEPI_TOKEN has no expiry claim; re-copy it from credentials.json.");

const daysLeft = (exp * 1000 - Date.now()) / 86400000;
if (daysLeft <= 0) {
  fail(
    `The free-pi token expired ${Math.abs(daysLeft).toFixed(1)} days ago. ` +
      "Sign in again locally with `npx -y free-pi-cli@latest`, then update the FREEPI_TOKEN secret.",
  );
}

let me;
try {
  const res = await fetch(`${BASE_URL}/me`, {
    headers: { authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(20000),
  });
  if (res.status === 401) {
    fail("The API rejected FREEPI_TOKEN (401). Sign in again locally and update the secret.");
  }
  if (!res.ok) fail(`Could not read account status: HTTP ${res.status} from ${BASE_URL}/me`);
  me = await res.json();
} catch (err) {
  fail(`Could not reach ${BASE_URL}: ${err instanceof Error ? err.message : String(err)}`);
}

const money = (v) => (typeof v === "number" ? `$${v.toFixed(2)}` : "n/a");
const lines = [
  `free-pi account: @${me.handle ?? "unknown"} (tier: ${me.tier ?? "n/a"})`,
  `token valid for ${daysLeft.toFixed(0)} more days`,
  `today: ${money(me.remaining_usd_today)} left of ${money(me.cap_usd_today)}` +
    (typeof me.remaining_credits_today === "number" ? ` (${me.remaining_credits_today} credits left)` : ""),
  `purchased credits: ${money(me.credit_usd)}`,
];
if (me.consent_version) {
  lines.push(`server consent record: ${me.consent_version} (accepted in a terminal = fine for CI)`);
}

if (!quiet) {
  for (const line of lines) console.log(line);
  if (process.env.GITHUB_STEP_SUMMARY) {
    const { appendFileSync } = await import("node:fs");
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `**free-pi** — ${lines[0]}\n${lines.slice(1).join("\n")}\n\n`);
  }
}
