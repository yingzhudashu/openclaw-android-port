/**
 * OpenClaw Android Entry Point — Real Gateway
 * 
 * This imports the actual OpenClaw gateway server from openclaw-main source.
 * esbuild bundles everything into a single file for Android deployment.
 */

import process from "node:process";
import path from "node:path";
import { mkdirSync, writeFileSync } from "node:fs";

// ── Android environment setup ──────────────────────────────────────────────
process.env.OPENCLAW_ANDROID = "1";
process.env.OPENCLAW_SKIP_BROWSER = "1";
process.env.OPENCLAW_SKIP_NODE_PTY = "1";
process.env.OPENCLAW_SKIP_CANVAS_HOST = "1";
process.env.NODE_ENV = process.env.NODE_ENV || "production";

const ANDROID_DATA_DIR = process.env.OPENCLAW_ANDROID_DATA_DIR 
  || path.join(process.cwd(), "android-data");

process.env.OPENCLAW_CONFIG_DIR = process.env.OPENCLAW_CONFIG_DIR 
  || path.join(ANDROID_DATA_DIR, "config");
process.env.OPENCLAW_STATE_DIR = process.env.OPENCLAW_STATE_DIR 
  || path.join(ANDROID_DATA_DIR, "state");

// ── Logging ────────────────────────────────────────────────────────────────
const log = (level: string, ...args: unknown[]) => {
  const ts = new Date().toISOString();
  console.log(`[${ts}] [openclaw-android] [${level}]`, ...args);
};

log("info", "OpenClaw Android engine starting (real gateway)...");
log("info", `Data dir: ${ANDROID_DATA_DIR}`);
log("info", `PID: ${process.pid}`);

// ── Ensure directories exist ───────────────────────────────────────────────
for (const dir of [
  ANDROID_DATA_DIR,
  process.env.OPENCLAW_CONFIG_DIR!,
  process.env.OPENCLAW_STATE_DIR!,
  path.join(ANDROID_DATA_DIR, "workspace"),
  path.join(ANDROID_DATA_DIR, "sessions"),
  path.join(ANDROID_DATA_DIR, "memory"),
]) {
  try { mkdirSync(dir, { recursive: true }); } catch {}
}

// ── Health file ────────────────────────────────────────────────────────────
const healthFile = path.join(ANDROID_DATA_DIR, "engine-health.json");
function writeHealth(status: string, detail?: string) {
  try {
    writeFileSync(healthFile, JSON.stringify({
      status, pid: process.pid, timestamp: Date.now(),
      uptime: process.uptime(),
      memoryMB: Math.round(process.memoryUsage().rss / 1024 / 1024),
      detail,
    }));
  } catch {}
}
writeHealth("starting");
setInterval(() => writeHealth("running"), 10_000);

// ── Error handling ─────────────────────────────────────────────────────────
process.on("uncaughtException", (error) => {
  log("error", "Uncaught exception:", error.stack || error.message);
  writeHealth("error", error.message);
});
process.on("unhandledRejection", (reason) => {
  log("error", "Unhandled rejection:", reason);
});
process.on("SIGTERM", () => {
  log("info", "SIGTERM received, shutting down...");
  writeHealth("error", "shutting down");
  setTimeout(() => process.exit(0), 5000);
});

// ── Import and start the real OpenClaw gateway ─────────────────────────────
log("info", "Loading OpenClaw gateway...");

// This import will be resolved by esbuild to the actual openclaw-main source
import { startGatewayServer } from "openclaw-gateway-entry";

async function main() {
  try {
    log("info", "Starting gateway server on 127.0.0.1:18789...");
    const server = await startGatewayServer({
      // Android-specific options will be injected here
    });
    log("info", "Gateway server started successfully!");
    writeHealth("running", "gateway active");
  } catch (err: any) {
    log("error", "Failed to start gateway:", err.stack || err.message);
    writeHealth("error", `start failed: ${err.message}`);
    
    // Fallback: start a minimal HTTP server so the app shell can still connect
    log("info", "Starting fallback minimal server...");
    const http = await import("node:http");
    const srv = http.createServer((req, res) => {
      if (req.url === "/health") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ status: "fallback", error: err.message }));
      } else {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "Gateway failed to start", detail: err.message }));
      }
    });
    srv.listen(18789, "127.0.0.1", () => {
      log("info", "Fallback server listening on 127.0.0.1:18789");
    });
  }
}

main();
