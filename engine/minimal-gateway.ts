/**
 * Minimal Gateway for Android
 * 
 * This provides a stripped-down version of the OpenClaw gateway
 * that focuses only on essential functionality for Android.
 */

import process from "node:process";
import path from "node:path";
import http from "node:http";
import net from "node:net";
import { mkdirSync, writeFileSync } from "node:fs";

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

log("info", "OpenClaw Android minimal gateway starting...");
log("info", `Data dir: ${ANDROID_DATA_DIR}`);
log("info", `Config dir: ${process.env.OPENCLAW_CONFIG_DIR}`);
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
  try {
    mkdirSync(dir, { recursive: true });
  } catch {
    // ignore if exists
  }
}

// ── Health check IPC ───────────────────────────────────────────────────────
// The Kotlin layer can check if the engine is alive by reading this file
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

// ── Simple HTTP Server for basic communication ──────────────────────────────
const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: 'ok',
      pid: process.pid,
      uptime: process.uptime(),
      timestamp: new Date().toISOString()
    }));
  } else if (req.method === 'GET' && req.url === '/') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      message: 'OpenClaw Android Gateway',
      status: 'running',
      pid: process.pid
    }));
  } else {
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'Not found' }));
  }
});

const PORT = 18789;
const HOST = '127.0.0.1';

server.listen(PORT, HOST, () => {
  log("info", `Minimal gateway server listening on ${HOST}:${PORT}`);
  writeHealthStatus("running", `Listening on ${HOST}:${PORT}`);
});

// Handle server errors
server.on('error', (err: any) => {
  if (err.code === 'EADDRINUSE') {
    log("error", `Port ${PORT} is already in use`);
    writeHealthStatus("error", `Port ${PORT} in use`);
  } else {
    log("error", "Server error:", err.message);
    writeHealthStatus("error", err.message);
  }
});

// ── Keep process alive ──────────────────────────────────────────────────────
log("info", "Gateway started successfully!");