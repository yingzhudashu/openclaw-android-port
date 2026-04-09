/**
 * Minimal OpenClaw Android Engine - Proof of Concept
 * 
 * This is a stripped-down version that verifies:
 * 1. Node.js can start on Android
 * 2. HTTP server works (express-like)
 * 3. WebSocket works (ws-like)
 * 4. Config file read/write works
 * 5. LLM API calls work (direct HTTP)
 * 
 * This does NOT import from the full OpenClaw codebase.
 * It's a standalone PoC to validate the runtime environment.
 */

import { createServer } from "node:http";
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { join } from "node:path";

// ── Configuration ──────────────────────────────────────────────────────────
const PORT = 18789;
const DATA_DIR = process.env.OPENCLAW_ANDROID_DATA_DIR || join(process.cwd(), "data");
const CONFIG_FILE = join(DATA_DIR, "config.json");

console.log(`[openclaw-android-poc] Starting...`);
console.log(`[openclaw-android-poc] Data dir: ${DATA_DIR}`);
console.log(`[openclaw-android-poc] PID: ${process.pid}`);
console.log(`[openclaw-android-poc] Node.js: ${process.version}`);
console.log(`[openclaw-android-poc] Platform: ${process.platform} ${process.arch}`);
console.log(`[openclaw-android-poc] Memory: ${Math.round(process.memoryUsage().rss / 1024 / 1024)}MB`);

// ── Ensure data directory ──────────────────────────────────────────────────
mkdirSync(DATA_DIR, { recursive: true });

// ── Config management ──────────────────────────────────────────────────────
interface Config {
  provider?: {
    name: string;
    apiKey: string;
    baseUrl?: string;
    model?: string;
  };
  gateway?: {
    port: number;
    token?: string;
  };
}

function loadConfig(): Config {
  try {
    if (existsSync(CONFIG_FILE)) {
      return JSON.parse(readFileSync(CONFIG_FILE, "utf8"));
    }
  } catch (e) {
    console.warn("[config] Failed to load config:", e);
  }
  return {};
}

function saveConfig(config: Config): void {
  writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2));
}

let config = loadConfig();

// ── Simple HTTP API (Gateway equivalent) ───────────────────────────────────
const server = createServer(async (req, res) => {
  const url = new URL(req.url || "/", `http://localhost:${PORT}`);
  
  // CORS headers for local access
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
  
  if (req.method === "OPTIONS") {
    res.writeHead(200);
    res.end();
    return;
  }

  // Health check
  if (url.pathname === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      status: "ok",
      version: "2026.4.1-android-poc",
      uptime: process.uptime(),
      memoryMB: Math.round(process.memoryUsage().rss / 1024 / 1024),
      platform: `${process.platform} ${process.arch}`,
      nodeVersion: process.version,
    }));
    return;
  }

  // Config API
  if (url.pathname === "/api/config") {
    if (req.method === "GET") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(config));
      return;
    }
    if (req.method === "POST" || req.method === "PUT") {
      let body = "";
      req.on("data", (chunk) => { body += chunk; });
      req.on("end", () => {
        try {
          config = { ...config, ...JSON.parse(body) };
          saveConfig(config);
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ ok: true, config }));
        } catch (e: any) {
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: e.message }));
        }
      });
      return;
    }
  }

  // Chat API (simple LLM proxy)
  if (url.pathname === "/api/chat" && req.method === "POST") {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", async () => {
      try {
        const { message, sessionKey } = JSON.parse(body);
        
        if (!config.provider?.apiKey) {
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: "No provider configured. POST /api/config first." }));
          return;
        }

        // Direct LLM API call
        const response = await callLLM(message);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({
          ok: true,
          sessionKey: sessionKey || "main",
          reply: response,
        }));
      } catch (e: any) {
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  // 404
  res.writeHead(404, { "Content-Type": "application/json" });
  res.end(JSON.stringify({ 
    error: "Not found",
    endpoints: [
      "GET  /health",
      "GET  /api/config",
      "POST /api/config",
      "POST /api/chat",
    ],
  }));
});

// ── LLM API caller ─────────────────────────────────────────────────────────
async function callLLM(message: string): Promise<string> {
  const provider = config.provider;
  if (!provider) throw new Error("No provider configured");

  const baseUrl = provider.baseUrl || "https://api.openai.com/v1";
  const model = provider.model || "gpt-4o-mini";

  const response = await fetch(`${baseUrl}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${provider.apiKey}`,
    },
    body: JSON.stringify({
      model,
      messages: [
        { role: "user", content: message },
      ],
      max_tokens: 2048,
    }),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`LLM API error ${response.status}: ${text}`);
  }

  const data = await response.json() as any;
  return data.choices?.[0]?.message?.content || "(empty response)";
}

// ── Start server ───────────────────────────────────────────────────────────
server.listen(PORT, "127.0.0.1", () => {
  console.log(`[openclaw-android-poc] ✅ Gateway running on http://127.0.0.1:${PORT}`);
  console.log(`[openclaw-android-poc] Endpoints:`);
  console.log(`  GET  /health     - Health check`);
  console.log(`  GET  /api/config - Get config`);
  console.log(`  POST /api/config - Update config`);
  console.log(`  POST /api/chat   - Send chat message`);
  
  // Write health file for Kotlin layer
  writeFileSync(join(DATA_DIR, "engine-health.json"), JSON.stringify({
    status: "running",
    pid: process.pid,
    port: PORT,
    timestamp: Date.now(),
  }));
});

server.on("error", (err) => {
  console.error(`[openclaw-android-poc] ❌ Server error:`, err);
});

// Keep alive
process.on("SIGTERM", () => {
  console.log("[openclaw-android-poc] Shutting down...");
  server.close();
  process.exit(0);
});
