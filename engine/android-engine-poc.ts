/**
 * OpenClaw Android Engine - Enhanced PoC
 * 
 * A functional mini-Gateway that validates all key capabilities
 * needed for the Android port:
 * 
 * 1. HTTP Server (Gateway API)
 * 2. WebSocket Server (real-time communication)
 * 3. LLM Provider integration (OpenAI-compatible)
 * 4. Session management
 * 5. Config persistence
 * 6. Tool execution (shell commands)
 * 7. File operations (workspace)
 * 8. Cron-like scheduling
 * 9. Memory/search (basic)
 * 
 * This runs standalone without the full OpenClaw codebase.
 * Target: validate that all these capabilities work on Android.
 */

import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { WebSocketServer, WebSocket } from "ws";
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { spawn, type ChildProcess } from "node:child_process";
import { randomUUID } from "node:crypto";

// ══════════════════════════════════════════════════════════════════════════════
// Configuration
// ══════════════════════════════════════════════════════════════════════════════

const VERSION = "2026.4.1-android-poc";
const PORT = 19789; // Use different port from OpenClaw's own gateway (18789)
const DATA_DIR = process.env.OPENCLAW_ANDROID_DATA_DIR || join(process.cwd(), "android-data");
const CONFIG_DIR = join(DATA_DIR, "config");
const STATE_DIR = join(DATA_DIR, "state");
const WORKSPACE_DIR = join(DATA_DIR, "workspace");
const SESSIONS_DIR = join(DATA_DIR, "sessions");
const MEMORY_DIR = join(WORKSPACE_DIR, "memory");

// Ensure directories
for (const dir of [DATA_DIR, CONFIG_DIR, STATE_DIR, WORKSPACE_DIR, SESSIONS_DIR, MEMORY_DIR]) {
  mkdirSync(dir, { recursive: true });
}

const CONFIG_FILE = join(CONFIG_DIR, "openclaw.json");

// ══════════════════════════════════════════════════════════════════════════════
// Logging
// ══════════════════════════════════════════════════════════════════════════════

function log(level: string, ...args: unknown[]) {
  const ts = new Date().toISOString().slice(11, 19);
  console.log(`[${ts}] [${level}]`, ...args);
}

log("info", `OpenClaw Android Engine v${VERSION}`);
log("info", `Node.js ${process.version} | ${process.platform} ${process.arch}`);
log("info", `Data: ${DATA_DIR}`);
log("info", `PID: ${process.pid} | Memory: ${Math.round(process.memoryUsage().rss / 1024 / 1024)}MB`);

// ══════════════════════════════════════════════════════════════════════════════
// Config Management
// ══════════════════════════════════════════════════════════════════════════════

interface ProviderConfig {
  name: string;
  apiKey: string;
  baseUrl?: string;
  model?: string;
}

interface OpenClawConfig {
  provider?: ProviderConfig;
  gateway?: { port: number; token?: string; password?: string };
  agent?: { defaultModel?: string; systemPrompt?: string };
  channels?: Record<string, any>;
}

function loadConfig(): OpenClawConfig {
  try {
    if (existsSync(CONFIG_FILE)) {
      return JSON.parse(readFileSync(CONFIG_FILE, "utf8"));
    }
  } catch (e) {
    log("warn", "Config load failed:", e);
  }
  return {};
}

function saveConfig(config: OpenClawConfig) {
  writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2));
}

let config = loadConfig();

// ══════════════════════════════════════════════════════════════════════════════
// Session Management
// ══════════════════════════════════════════════════════════════════════════════

interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "system" | "tool";
  content: string;
  timestamp: number;
  toolCalls?: any[];
}

interface Session {
  key: string;
  id: string;
  messages: ChatMessage[];
  createdAt: number;
  updatedAt: number;
}

const sessions = new Map<string, Session>();

function getOrCreateSession(key: string): Session {
  if (!sessions.has(key)) {
    const session: Session = {
      key,
      id: randomUUID(),
      messages: [],
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    sessions.set(key, session);
    log("info", `Session created: ${key}`);
  }
  return sessions.get(key)!;
}

function saveSession(session: Session) {
  const file = join(SESSIONS_DIR, `${session.key}.json`);
  writeFileSync(file, JSON.stringify(session, null, 2));
}

// Load persisted sessions
try {
  for (const file of readdirSync(SESSIONS_DIR)) {
    if (file.endsWith(".json")) {
      const data = JSON.parse(readFileSync(join(SESSIONS_DIR, file), "utf8"));
      sessions.set(data.key, data);
    }
  }
  log("info", `Loaded ${sessions.size} persisted sessions`);
} catch { /* ignore */ }

// ══════════════════════════════════════════════════════════════════════════════
// LLM Provider
// ══════════════════════════════════════════════════════════════════════════════

async function callLLM(
  messages: Array<{ role: string; content: string }>,
  options: { model?: string; maxTokens?: number; stream?: boolean } = {}
): Promise<string> {
  const provider = config.provider;
  if (!provider?.apiKey) {
    throw new Error("No provider configured. Set provider.apiKey in config.");
  }

  const baseUrl = provider.baseUrl || "https://api.openai.com/v1";
  const model = options.model || provider.model || "gpt-4o-mini";

  log("info", `LLM call: ${model} (${messages.length} messages)`);

  const response = await fetch(`${baseUrl}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${provider.apiKey}`,
    },
    body: JSON.stringify({
      model,
      messages,
      max_tokens: options.maxTokens || 4096,
    }),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`LLM API error ${response.status}: ${text.slice(0, 200)}`);
  }

  const data = await response.json() as any;
  const reply = data.choices?.[0]?.message?.content || "";
  log("info", `LLM reply: ${reply.length} chars`);
  return reply;
}

// ══════════════════════════════════════════════════════════════════════════════
// Tool Execution
// ══════════════════════════════════════════════════════════════════════════════

interface ExecResult {
  stdout: string;
  stderr: string;
  exitCode: number;
  timedOut: boolean;
}

function execCommand(command: string, args: string[] = [], cwd?: string, timeoutMs = 30000): Promise<ExecResult> {
  return new Promise((resolve) => {
    const child = spawn(command, args, {
      cwd: cwd || WORKSPACE_DIR,
      shell: true,
      stdio: ["pipe", "pipe", "pipe"],
      timeout: timeoutMs,
    });

    let stdout = "";
    let stderr = "";
    let timedOut = false;

    child.stdout?.on("data", (d) => { stdout += d.toString(); });
    child.stderr?.on("data", (d) => { stderr += d.toString(); });

    child.on("error", (err) => {
      resolve({ stdout, stderr: err.message, exitCode: 1, timedOut: false });
    });

    child.on("exit", (code, signal) => {
      timedOut = signal === "SIGTERM";
      resolve({ stdout, stderr, exitCode: code ?? 1, timedOut });
    });

    // Safety timeout
    setTimeout(() => {
      if (!child.killed) {
        child.kill("SIGTERM");
        timedOut = true;
      }
    }, timeoutMs);
  });
}

// ══════════════════════════════════════════════════════════════════════════════
// Memory (basic keyword search)
// ══════════════════════════════════════════════════════════════════════════════

function searchMemory(query: string, maxResults = 5): Array<{ file: string; line: number; text: string; score: number }> {
  const results: Array<{ file: string; line: number; text: string; score: number }> = [];
  const keywords = query.toLowerCase().split(/\s+/).filter(Boolean);

  function searchDir(dir: string) {
    try {
      for (const entry of readdirSync(dir)) {
        const full = join(dir, entry);
        const stat = statSync(full);
        if (stat.isDirectory()) {
          searchDir(full);
        } else if (entry.endsWith(".md") || entry.endsWith(".txt") || entry.endsWith(".json")) {
          try {
            const content = readFileSync(full, "utf8");
            const lines = content.split("\n");
            for (let i = 0; i < lines.length; i++) {
              const lower = lines[i].toLowerCase();
              const matchCount = keywords.filter(kw => lower.includes(kw)).length;
              if (matchCount > 0) {
                results.push({
                  file: full.replace(WORKSPACE_DIR, ""),
                  line: i + 1,
                  text: lines[i].trim().slice(0, 200),
                  score: matchCount / keywords.length,
                });
              }
            }
          } catch { /* skip unreadable files */ }
        }
      }
    } catch { /* skip unreadable dirs */ }
  }

  searchDir(WORKSPACE_DIR);
  results.sort((a, b) => b.score - a.score);
  return results.slice(0, maxResults);
}

// ══════════════════════════════════════════════════════════════════════════════
// Cron (simple interval-based)
// ══════════════════════════════════════════════════════════════════════════════

interface CronJob {
  id: string;
  name: string;
  intervalMs: number;
  task: string;
  enabled: boolean;
  lastRun?: number;
  timer?: ReturnType<typeof setInterval>;
}

const cronJobs = new Map<string, CronJob>();

function createCronJob(name: string, intervalMs: number, task: string): CronJob {
  const id = randomUUID().slice(0, 8);
  const job: CronJob = { id, name, intervalMs, task, enabled: true };
  
  job.timer = setInterval(async () => {
    if (!job.enabled) return;
    job.lastRun = Date.now();
    log("info", `Cron [${name}]: executing`);
    // In full version, this would send to the agent
  }, intervalMs);
  
  cronJobs.set(id, job);
  log("info", `Cron created: ${name} (every ${intervalMs / 1000}s)`);
  return job;
}

// ══════════════════════════════════════════════════════════════════════════════
// WebSocket Server (Gateway Protocol)
// ══════════════════════════════════════════════════════════════════════════════

const httpServer = createServer(handleHttpRequest);
const wss = new WebSocketServer({ server: httpServer });

// Connected clients
const clients = new Map<string, { ws: WebSocket; role: string; authenticated: boolean }>();

wss.on("connection", (ws, req) => {
  const clientId = randomUUID().slice(0, 8);
  clients.set(clientId, { ws, role: "operator", authenticated: false });
  log("info", `WS client connected: ${clientId}`);

  ws.on("message", async (data) => {
    try {
      const msg = JSON.parse(data.toString());
      await handleWsMessage(clientId, msg, ws);
    } catch (e: any) {
      ws.send(JSON.stringify({ error: e.message }));
    }
  });

  ws.on("close", () => {
    clients.delete(clientId);
    log("info", `WS client disconnected: ${clientId}`);
  });

  // Send welcome
  ws.send(JSON.stringify({
    type: "welcome",
    version: VERSION,
    protocolVersion: 3,
    serverName: "OpenClaw Android",
  }));
});

async function handleWsMessage(clientId: string, msg: any, ws: WebSocket) {
  const { id, method, params } = msg;
  
  try {
    let result: any;
    
    switch (method) {
      case "auth.connect": {
        const client = clients.get(clientId);
        if (client) client.authenticated = true;
        result = { ok: true, mainSessionKey: "agent:main:main" };
        break;
      }
      
      case "chat.send": {
        const sessionKey = params?.sessionKey || "main";
        const message = params?.message || "";
        const session = getOrCreateSession(sessionKey);
        
        // Add user message
        session.messages.push({
          id: randomUUID(),
          role: "user",
          content: message,
          timestamp: Date.now(),
        });
        session.updatedAt = Date.now();

        // Broadcast typing indicator
        broadcastToClients({ type: "event", event: "chat.typing", data: { sessionKey } });

        // Call LLM
        const systemPrompt = config.agent?.systemPrompt || 
          "You are OpenClaw, a helpful AI assistant running on an Android phone.";
        
        const llmMessages = [
          { role: "system", content: systemPrompt },
          ...session.messages.slice(-20).map(m => ({ role: m.role, content: m.content })),
        ];

        const reply = await callLLM(llmMessages);

        // Add assistant message
        const assistantMsg: ChatMessage = {
          id: randomUUID(),
          role: "assistant",
          content: reply,
          timestamp: Date.now(),
        };
        session.messages.push(assistantMsg);
        session.updatedAt = Date.now();
        saveSession(session);

        // Broadcast reply
        broadcastToClients({
          type: "event",
          event: "chat.message",
          data: { sessionKey, message: assistantMsg },
        });

        result = { ok: true, runId: assistantMsg.id };
        break;
      }
      
      case "chat.history": {
        const sessionKey = params?.sessionKey || "main";
        const session = getOrCreateSession(sessionKey);
        result = { messages: session.messages.slice(-(params?.limit || 50)) };
        break;
      }
      
      case "sessions.list": {
        result = {
          sessions: Array.from(sessions.values()).map(s => ({
            key: s.key,
            id: s.id,
            messageCount: s.messages.length,
            updatedAt: s.updatedAt,
          })),
        };
        break;
      }
      
      case "config.get": {
        result = { config };
        break;
      }
      
      case "config.patch": {
        config = { ...config, ...params };
        saveConfig(config);
        result = { ok: true, config };
        break;
      }
      
      case "tools.exec": {
        const execResult = await execCommand(
          params?.command || "echo hello",
          params?.args || [],
          params?.cwd,
          params?.timeoutMs || 30000,
        );
        result = execResult;
        break;
      }
      
      case "tools.read": {
        const filePath = resolve(WORKSPACE_DIR, params?.path || "");
        if (!filePath.startsWith(WORKSPACE_DIR)) {
          throw new Error("Path traversal blocked");
        }
        const content = readFileSync(filePath, "utf8");
        result = { content };
        break;
      }
      
      case "tools.write": {
        const filePath = resolve(WORKSPACE_DIR, params?.path || "");
        if (!filePath.startsWith(WORKSPACE_DIR)) {
          throw new Error("Path traversal blocked");
        }
        mkdirSync(join(filePath, ".."), { recursive: true });
        writeFileSync(filePath, params?.content || "");
        result = { ok: true };
        break;
      }
      
      case "memory.search": {
        result = { results: searchMemory(params?.query || "", params?.maxResults) };
        break;
      }
      
      case "cron.list": {
        result = {
          jobs: Array.from(cronJobs.values()).map(j => ({
            id: j.id,
            name: j.name,
            intervalMs: j.intervalMs,
            enabled: j.enabled,
            lastRun: j.lastRun,
          })),
        };
        break;
      }
      
      case "cron.create": {
        const job = createCronJob(
          params?.name || "unnamed",
          params?.intervalMs || 60000,
          params?.task || "",
        );
        result = { ok: true, job: { id: job.id, name: job.name } };
        break;
      }
      
      case "node.describe": {
        result = {
          commands: [
            "chat.send", "chat.history", "sessions.list",
            "config.get", "config.patch",
            "tools.exec", "tools.read", "tools.write",
            "memory.search",
            "cron.list", "cron.create",
          ],
          capabilities: {
            chat: true,
            tools: true,
            memory: true,
            cron: true,
            browser: false, // Not available on Android
          },
          platform: "android",
          version: VERSION,
        };
        break;
      }
      
      default:
        throw new Error(`Unknown method: ${method}`);
    }

    ws.send(JSON.stringify({ id, result }));
  } catch (error: any) {
    ws.send(JSON.stringify({ id, error: { message: error.message } }));
  }
}

function broadcastToClients(msg: any) {
  const data = JSON.stringify(msg);
  for (const [, client] of clients) {
    if (client.ws.readyState === WebSocket.OPEN) {
      client.ws.send(data);
    }
  }
}

// ══════════════════════════════════════════════════════════════════════════════
// HTTP API (for simple REST access / health checks)
// ══════════════════════════════════════════════════════════════════════════════

async function handleHttpRequest(req: IncomingMessage, res: ServerResponse) {
  const url = new URL(req.url || "/", `http://localhost:${PORT}`);
  
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
  
  if (req.method === "OPTIONS") { res.writeHead(200); res.end(); return; }

  const json = (status: number, data: any) => {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(data));
  };

  // Health
  if (url.pathname === "/health" || url.pathname === "/") {
    return json(200, {
      status: "ok",
      version: VERSION,
      uptime: Math.round(process.uptime()),
      memoryMB: Math.round(process.memoryUsage().rss / 1024 / 1024),
      platform: `${process.platform} ${process.arch}`,
      node: process.version,
      sessions: sessions.size,
      wsClients: clients.size,
      cronJobs: cronJobs.size,
    });
  }

  // Config
  if (url.pathname === "/api/config") {
    if (req.method === "GET") return json(200, config);
    if (req.method === "POST") {
      const body = await readBody(req);
      config = { ...config, ...JSON.parse(body) };
      saveConfig(config);
      return json(200, { ok: true });
    }
  }

  // Quick chat (REST fallback)
  if (url.pathname === "/api/chat" && req.method === "POST") {
    const body = JSON.parse(await readBody(req));
    const session = getOrCreateSession(body.sessionKey || "main");
    
    session.messages.push({
      id: randomUUID(), role: "user", content: body.message, timestamp: Date.now(),
    });

    try {
      const systemPrompt = config.agent?.systemPrompt || 
        "You are OpenClaw, a helpful AI assistant running on an Android phone.";
      const reply = await callLLM([
        { role: "system", content: systemPrompt },
        ...session.messages.slice(-20).map(m => ({ role: m.role, content: m.content })),
      ]);

      session.messages.push({
        id: randomUUID(), role: "assistant", content: reply, timestamp: Date.now(),
      });
      session.updatedAt = Date.now();
      saveSession(session);
      return json(200, { reply, sessionKey: session.key });
    } catch (e: any) {
      return json(500, { error: e.message });
    }
  }

  // Sessions
  if (url.pathname === "/api/sessions") {
    return json(200, Array.from(sessions.values()).map(s => ({
      key: s.key, messageCount: s.messages.length, updatedAt: s.updatedAt,
    })));
  }

  // Exec
  if (url.pathname === "/api/exec" && req.method === "POST") {
    const body = JSON.parse(await readBody(req));
    const result = await execCommand(body.command, body.args, body.cwd, body.timeoutMs);
    return json(200, result);
  }

  json(404, {
    error: "Not found",
    endpoints: [
      "GET  /health", "GET /api/config", "POST /api/config",
      "POST /api/chat", "GET /api/sessions", "POST /api/exec",
      "WS   ws://localhost:18789 (Gateway protocol)",
    ],
  });
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (c) => { body += c; });
    req.on("end", () => resolve(body));
    req.on("error", reject);
  });
}

// ══════════════════════════════════════════════════════════════════════════════
// Health file for Kotlin layer
// ══════════════════════════════════════════════════════════════════════════════

function writeHealth() {
  try {
    writeFileSync(join(DATA_DIR, "engine-health.json"), JSON.stringify({
      status: "running",
      version: VERSION,
      pid: process.pid,
      port: PORT,
      timestamp: Date.now(),
      uptime: Math.round(process.uptime()),
      memoryMB: Math.round(process.memoryUsage().rss / 1024 / 1024),
      sessions: sessions.size,
      wsClients: clients.size,
    }));
  } catch { /* ignore */ }
}

setInterval(writeHealth, 10000);

// ══════════════════════════════════════════════════════════════════════════════
// Start
// ══════════════════════════════════════════════════════════════════════════════

httpServer.listen(PORT, "127.0.0.1", () => {
  log("info", "═══════════════════════════════════════════════════");
  log("info", `  OpenClaw Android Engine v${VERSION}`);
  log("info", `  HTTP + WS: http://127.0.0.1:${PORT}`);
  log("info", `  Data: ${DATA_DIR}`);
  log("info", `  Memory: ${Math.round(process.memoryUsage().rss / 1024 / 1024)}MB`);
  log("info", "═══════════════════════════════════════════════════");
  log("info", "Capabilities:");
  log("info", "  ✅ Chat (LLM proxy)     ✅ Sessions");
  log("info", "  ✅ Config management    ✅ WebSocket protocol");
  log("info", "  ✅ Shell execution      ✅ File read/write");
  log("info", "  ✅ Memory search        ✅ Cron scheduling");
  log("info", "  ❌ Browser automation   ❌ Full PTY");
  log("info", "═══════════════════════════════════════════════════");
  writeHealth();
});

httpServer.on("error", (err) => {
  log("error", "Server error:", err);
});

// Graceful shutdown
process.on("SIGTERM", () => {
  log("info", "Shutting down...");
  wss.close();
  httpServer.close();
  process.exit(0);
});

process.on("SIGINT", () => {
  log("info", "Interrupted.");
  process.exit(0);
});

process.on("uncaughtException", (err) => {
  log("error", "Uncaught:", err.stack || err.message);
});

process.on("unhandledRejection", (reason) => {
  log("error", "Unhandled rejection:", reason);
});
