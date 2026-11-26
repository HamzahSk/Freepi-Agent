#!/usr/bin/env node
// Regenerate .github/free-pi/models.json from free-pi's live model catalog.
//
//   node .github/free-pi/sync-models.mjs            # fetch https://api.freepi.ai/client-version
//   node .github/free-pi/sync-models.mjs catalog.json  # use a saved copy
//
// The endpoint gives ids, display names, free/paid status and a usage multiplier,
// but no context window. Those come from the same table free-pi-cli uses
// (src/provider.ts CONTEXT_WINDOWS in dist/main-*.js), with its fallback rules.
import { readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const CATALOG_URL = "https://api.freepi.ai/client-version";
const OUT = resolve(dirname(fileURLToPath(import.meta.url)), "models.json");

const MAX_OUTPUT_TOKENS = 32768;
const UNKNOWN_MODEL_WINDOW = 262144;
const FALLBACK_WINDOW = 420000;

// Keep in sync with free-pi-cli dist/main-*.js -> CONTEXT_WINDOWS.
const CONTEXT_WINDOWS = {
  particle: 420000,
  "deepseek-v4-flash": 420000,
  "deepseek-v4-pro": 420000,
  "glm-5.3-flash": 420000,
  "hy4-flash": 420000,
  "ling-3.1-flash": 420000,
  "solar-mini-4": 420000,
  fusion: 420000,
  "ling-3.0-tiny": 262144,
  "ling-3.0-flash": 262144,
  "qwen3.5-35b-darkbloom": 262144,
  "gpt-oss-20b": 131072,
  "qwen3.7-flash": 185000,
  "gpt-6.2-astra": 195000,
  "deepseek-v4.1-flash-1017-ultrafast": 200000,
  "deepseek-v4.1-flash-600tps": 200000,
  "abliterated-large-v2": 200000,
};

const contextWindowFor = (id) => {
  const bare = id.slice(id.lastIndexOf("/") + 1);
  if (CONTEXT_WINDOWS[bare] !== undefined) return CONTEXT_WINDOWS[bare];
  if (bare.startsWith("deepseek-v4.1-flash") || bare.startsWith("deepseek-v4-flash")) return FALLBACK_WINDOW;
  return UNKNOWN_MODEL_WINDOW;
};

const label = (m) => {
  const name = m.display_name ?? m.id;
  if (m.free) return `${name} (free)`;
  const bits = [];
  if (m.usage_x) bits.push(`~${m.usage_x}x usage`);
  if (m.note) bits.push(m.note);
  return bits.length > 0 ? `${name} · ${bits.join(" · ")}` : name;
};

const entry = (m) => ({
  id: m.id,
  name: label(m),
  reasoning: true,
  input: ["text"],
  contextWindow: contextWindowFor(m.id),
  maxTokens: MAX_OUTPUT_TOKENS,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
  compat: { supportsDeveloperRole: false },
});

const catalog = process.argv[2]
  ? JSON.parse(await readFile(process.argv[2], "utf8"))
  : await (await fetch(CATALOG_URL)).json();

if (!Array.isArray(catalog.models) || catalog.models.length === 0) {
  console.error("No models in the catalog; refusing to write models.json.");
  process.exit(1);
}

const main = catalog.models.filter((m) => m.section !== "defense");
const defense = catalog.models.filter((m) => m.section === "defense");

const provider = (name, models) => ({
  name,
  baseUrl: "https://api.freepi.ai/v1",
  api: "openai-completions",
  apiKey: "$FREEPI_TOKEN",
  headers: {
    // Free-pi rejects clients older than `min` and advertises `latest` here.
    "x-client-version": catalog.latest ?? "0.6.28",
    "x-ad-context": "none",
  },
  models: models.map(entry),
});

const providers = { "free-pi": provider("free-pi", main) };
if (defense.length > 0) providers["free-pi-defense"] = provider("free-pi-defense", defense);

await writeFile(OUT, `${JSON.stringify({ $schema: "https://pi.dev/schemas/models.schema.json", providers }, null, 2)}\n`);
console.log(`Wrote ${OUT}: ${main.length} models in free-pi, ${defense.length} in free-pi-defense (catalog ${catalog.latest}).`);
