/**
 * Bundle the enhanced PoC engine into a single .mjs file
 * that can run on Android with Node.js.
 * 
 * Usage: node scripts/bundle-poc.mjs
 */

import { build } from "esbuild";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { statSync } from "node:fs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, "..");

async function bundle() {
  console.log("🔧 Bundling OpenClaw Android PoC Engine...");
  
  const result = await build({
    entryPoints: [resolve(ROOT, "engine", "android-engine-poc.ts")],
    bundle: true,
    platform: "node",
    target: "node22",
    format: "cjs",
    outfile: resolve(ROOT, "dist", "android-engine-poc.cjs"),
    minify: true,
    sourcemap: false,
    treeShaking: true,
    external: [
      "node:*",
      // ws library uses bare require("events"), require("http"), etc.
      "events", "http", "https", "net", "tls", "crypto", "stream",
      "buffer", "url", "zlib", "util", "fs", "path", "os",
      "child_process", "worker_threads",
    ],
    logLevel: "info",
  });

  const stat = statSync(resolve(ROOT, "dist", "android-engine-poc.cjs"));
  console.log(`✅ Bundle complete: dist/android-engine-poc.cjs (${(stat.size / 1024).toFixed(1)} KB)`);
  
  if (result.warnings.length) {
    console.warn(`⚠️ ${result.warnings.length} warnings`);
  }
}

bundle().catch(e => { console.error("❌", e); process.exit(1); });
