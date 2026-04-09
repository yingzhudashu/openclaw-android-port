/**
 * OpenClaw Android Entry Point
 * 
 * This is the Android-specific entry for the OpenClaw engine.
 * It starts a stripped-down Gateway server on localhost:18789
 * for the Kotlin UI layer to connect to via WebSocket.
 * 
 * Key differences from desktop entry.ts:
 * - Binds to 127.0.0.1 only (local communication)
 * - Skips browser automation (playwright)
 * - Skips terminal emulation (node-pty)
 * - Uses Android-specific bridges for image processing, shell execution
 * - Runs as a long-lived background process
 */

import process from "node:process";
import path from "node:path";

// ── Android environment setup ──────────────────────────────────────────────
process.env.OPENCLAW_ANDROID = "1";
process.env.OPENCLAW_SKIP_BROWSER = "1";        // No playwright on Android
process.env.OPENCLAW_SKIP_NODE_PTY = "1";        // No PTY on Android
process.env.OPENCLAW_SKIP_CANVAS_HOST = "1";     // Canvas via Kotlin WebView
process.env.NODE_ENV = process.env.NODE_ENV || "production";

// Data directory: /data/data/ai.openclaw.app/files/openclaw/
const ANDROID_DATA_DIR = process.env.OPENCLAW_ANDROID_DATA_DIR 
  || path.join(process.cwd(), "data");

process.env.OPENCLAW_CONFIG_DIR = process.env.OPENCLAW_CONFIG_DIR 
  || path.join(ANDROID_DATA_DIR, "config");
process.env.OPENCLAW_STATE_DIR = process.env.OPENCLAW_STATE_DIR 
  || path.join(ANDROID_DATA_DIR, "state");

// ── Logging ────────────────────────────────────────────────────────────────
const log = (level: string, ...args: unknown[]) => {
  const timestamp = new Date().toISOString();
  console.log(`[${timestamp}] [openclaw-android] [${level}]`, ...args);
};

log("info", "OpenClaw Android engine starting...");
log("info", `Data dir: ${ANDROID_DATA_DIR}`);
log("info", `Config dir: ${process.env.OPENCLAW_CONFIG_DIR}`);
log("info", `PID: ${process.pid}`);

// ── Ensure directories exist ───────────────────────────────────────────────
import { mkdirSync } from "node:fs";

for (const dir of [
  ANDROID_DATA_DIR,
  process.env.OPENCLAW_CONFIG_DIR!,
  process.env.OPENCLAW_STATE_DIR!,
  path.join(ANDROID_DATA_DIR, "workspace"),
  path.join(ANDROID_DATA_DIR, "sessions"),
  path.join(ANDROID_DATA_DIR, "memory"),
]) {
  try {
    mkdirSync(dir, { recursive: true });
  } catch {
    // ignore if exists
  }
}

// ── Health check IPC ───────────────────────────────────────────────────────
// The Kotlin layer can check if the engine is alive by reading this file
import { writeFileSync } from "node:fs";
const healthFile = path.join(ANDROID_DATA_DIR, "engine-health.json");

function writeHealthStatus(status: "starting" | "running" | "error", detail?: string) {
  try {
    writeFileSync(healthFile, JSON.stringify({
      status,
      pid: process.pid,
      timestamp: Date.now(),
      uptime: process.uptime(),
      memoryMB: Math.round(process.memoryUsage().rss / 1024 / 1024),
      detail,
    }));
  } catch {
    // ignore
  }
}

writeHealthStatus("starting");

// ── Periodic health update ─────────────────────────────────────────────────
setInterval(() => {
  writeHealthStatus("running");
}, 10_000);

// ── Error handling ─────────────────────────────────────────────────────────
process.on("uncaughtException", (error) => {
  log("error", "Uncaught exception:", error.stack || error.message);
  writeHealthStatus("error", error.message);
  // Don't exit — let the Kotlin layer decide whether to restart
});

process.on("unhandledRejection", (reason) => {
  log("error", "Unhandled rejection:", reason);
});

// ── Signal handling for Kotlin lifecycle ────────────────────────────────────
process.on("SIGTERM", () => {
  log("info", "Received SIGTERM, shutting down gracefully...");
  writeHealthStatus("error", "shutting down");
  // Allow 5 seconds for cleanup
  setTimeout(() => process.exit(0), 5000);
});

process.on("SIGINT", () => {
  log("info", "Received SIGINT, shutting down...");
  process.exit(0);
});

// ── Load the Gateway ──────────────────────────────────────────────────────────
// Note: Using minimal gateway for Android due to build complexity
// The full OpenClaw Gateway requires building openclaw-main first (npm run build)
// which generates hundreds of internal modules that are imported with .js extensions
// but exist as .ts source files that get compiled during the build process.
//
// For production Android builds:
// 1. Build openclaw-main: cd ../openclaw-main && npm run build
// 2. This creates dist/ folder with compiled .js files
// 3. Update the alias in bundle-android-engine.mjs to point to dist/gateway/server.impl.js
//
// For now, we use the minimal gateway which provides basic HTTP server functionality
// sufficient for Android integration testing.

import "./minimal-gateway.js";

log("info", "Gateway loaded and running.");
