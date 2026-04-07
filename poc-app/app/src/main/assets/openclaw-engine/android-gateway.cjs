/**
 * OpenClaw Android Gateway v2026.4.1
 * 
 * A pure-JavaScript, single-file HTTP gateway for running OpenClaw on Android.
 * Uses only Node.js built-in modules. CommonJS format for Node 18 compatibility.
 * 
 * Features:
 *   - HTTP API server (configurable port, default 18789)
 *   - LLM provider proxy with SSE streaming (OpenAI-compatible)
 *   - Multi-session chat with message history persistence
 *   - Runtime config management (openclaw.json)
 *   - File-based memory system (MEMORY.md)
 *   - Simplified cron scheduler (setInterval-based)
 * 
 * Zero external dependencies. Target size < 50 KB.
 */

'use strict';

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { URL } = require('url');

// ─── Constants ───────────────────────────────────────────────────────────────

const VERSION = '2026.4.5.5-android';
const START_TIME = Date.now();
const MAX_BODY_SIZE = 20 * 1024 * 1024; // 20 MB request body limit (images can be large)
const MAX_SESSIONS = 100;
const MAX_MESSAGES_PER_SESSION = 200;
const MAX_CONTEXT_TOKENS = 120000; // ~120k tokens, safe for most models
const SESSION_SAVE_DEBOUNCE_MS = 3000;

// Resolve base directory: prefer env override, else the directory this script lives in
const BASE_DIR = process.env.OPENCLAW_DATA_DIR || path.dirname(process.argv[1] || __filename);
const CONFIG_PATH = path.join(BASE_DIR, 'openclaw.json');
const SESSIONS_DIR = path.join(BASE_DIR, 'sessions');
const MEMORY_PATH = path.join(BASE_DIR, 'MEMORY.md');
const LOG_PATH = path.join(BASE_DIR, 'gateway.log');

// ─── Logger ──────────────────────────────────────────────────────────────────

const LogLevel = { DEBUG: 0, INFO: 1, WARN: 2, ERROR: 3 };
let currentLogLevel = LogLevel.INFO;

function log(level, tag, msg, extra) {
  if (level < currentLogLevel) return;
  const labels = ['DEBUG', 'INFO', 'WARN', 'ERROR'];
  const ts = new Date().toISOString();
  const line = `[${ts}] [${labels[level]}] [${tag}] ${msg}` +
    (extra ? ' ' + (typeof extra === 'string' ? extra : JSON.stringify(extra)) : '');
  
  // Console output
  if (level >= LogLevel.WARN) {
    process.stderr.write(line + '\n');
  } else {
    process.stdout.write(line + '\n');
  }

  // File output (best-effort, non-blocking)
  try {
    fs.appendFileSync(LOG_PATH, line + '\n');
  } catch (_) { /* ignore log write errors */ }
}

const logger = {
  debug: (tag, msg, extra) => log(LogLevel.DEBUG, tag, msg, extra),
  info:  (tag, msg, extra) => log(LogLevel.INFO, tag, msg, extra),
  warn:  (tag, msg, extra) => log(LogLevel.WARN, tag, msg, extra),
  error: (tag, msg, extra) => log(LogLevel.ERROR, tag, msg, extra),
};

// ─── Utility helpers ─────────────────────────────────────────────────────────

function uuid() {
  return crypto.randomUUID ? crypto.randomUUID() : crypto.randomBytes(16).toString('hex').replace(
    /(.{8})(.{4})(.{4})(.{4})(.{12})/, '$1-$2-$3-$4-$5'
  );
}

function ensureDir(dir) {
  try { fs.mkdirSync(dir, { recursive: true }); } catch (_) {}
}

function safeReadJSON(filePath, fallback) {
  try {
    const raw = fs.readFileSync(filePath, 'utf-8');
    return JSON.parse(raw);
  } catch (_) {
    return fallback;
  }
}

function safeWriteJSON(filePath, data) {
  try {
    ensureDir(path.dirname(filePath));
    fs.writeFileSync(filePath, JSON.stringify(data, null, 2), 'utf-8');
    return true;
  } catch (e) {
    logger.error('IO', 'Failed to write JSON: ' + filePath, e.message);
    return false;
  }
}

function safeReadText(filePath, fallback) {
  try { return fs.readFileSync(filePath, 'utf-8'); } catch (_) { return fallback; }
}

function safeWriteText(filePath, text) {
  try {
    ensureDir(path.dirname(filePath));
    fs.writeFileSync(filePath, text, 'utf-8');
    return true;
  } catch (e) {
    logger.error('IO', 'Failed to write text: ' + filePath, e.message);
    return false;
  }
}

function memoryUsageMB() {
  const m = process.memoryUsage();
  return {
    rss: +(m.rss / 1048576).toFixed(1),
    heapUsed: +(m.heapUsed / 1048576).toFixed(1),
    heapTotal: +(m.heapTotal / 1048576).toFixed(1),
    external: +(m.external / 1048576).toFixed(1),
  };
}

// ─── Config Manager ──────────────────────────────────────────────────────────

const DEFAULT_CONFIG = {
  version: VERSION,
  model: 'qwen3.5-plus',
  providers: {
    bailian: {
      api_key: '',
      base_url: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      models: ['qwen3.5-plus'],
    },
    openai: {
      api_key: '',
      base_url: 'https://api.openai.com/v1',
      models: ['gpt-4o'],
    },
    anthropic: {
      api_key: '',
      base_url: 'https://api.anthropic.com/v1',
      models: ['claude-sonnet-4-6'],
    },
    deepseek: {
      api_key: '',
      base_url: 'https://api.deepseek.com/v1',
      models: ['deepseek-chat'],
    },
  },
  embedding: {
    provider: '',
    model: '',
    api_key: '',
    base_url: '',
  },
  embedding_providers: {
    bailian: {
      base_url: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      models: ['text-embedding-v3'],
    },
    openai: {
      base_url: 'https://api.openai.com/v1',
      models: ['text-embedding-3-small'],
    },
    siliconflow: {
      base_url: 'https://api.siliconflow.cn/v1',
      models: ['BAAI/bge-m3'],
    },
  },
  available_models: ['qwen3.5-plus', 'gpt-4o', 'claude-sonnet-4-6', 'deepseek-chat'],
  system_prompt: '你是 OpenClaw 🦞，一个运行在 Android 手机上的 AI 个人助手。\n\n## 核心特质\n- 聪明高效，简洁直接\n- 中文优先回复，技术术语保留英文\n- 能帮忙做的事直接做，不反复确认\n- 有自己的观点，不是搜索引擎\n\n## 能力\n- 日常问答、知识查询、文本创作\n- 代码编写与调试\n- 翻译、总结、分析\n- 数学计算与逻辑推理\n\n## 风格\n- 简洁但不敷衍，详细但不啰嗦\n- 适当使用 emoji，但克制\n- 重要信息用加粗标注\n- 回复格式适合手机阅读（短段落）',
  gateway: {
    port: 18789,
    bind: '127.0.0.1',
  },
};

let config = {};

function loadConfig() {
  config = safeReadJSON(CONFIG_PATH, null);
  if (!config) {
    config = JSON.parse(JSON.stringify(DEFAULT_CONFIG));
    safeWriteJSON(CONFIG_PATH, config);
    logger.info('Config', 'Created default config at ' + CONFIG_PATH);
  } else {
    // Merge missing defaults (shallow)
    if (!config.version) config.version = VERSION;
    if (!config.providers) config.providers = DEFAULT_CONFIG.providers;
    if (!config.gateway) config.gateway = DEFAULT_CONFIG.gateway;
    if (!config.system_prompt) config.system_prompt = DEFAULT_CONFIG.system_prompt;
    if (!config.embedding) config.embedding = DEFAULT_CONFIG.embedding;
    if (!config.embedding_providers) config.embedding_providers = DEFAULT_CONFIG.embedding_providers;
    if (!config.available_models) config.available_models = DEFAULT_CONFIG.available_models;
    // Refresh provider models from defaults (user may have old cached models lists)
    if (config.providers && DEFAULT_CONFIG.providers) {
      for (const pName of Object.keys(DEFAULT_CONFIG.providers)) {
        if (config.providers[pName]) {
          config.providers[pName].models = DEFAULT_CONFIG.providers[pName].models;
          if (!config.providers[pName].base_url) config.providers[pName].base_url = DEFAULT_CONFIG.providers[pName].base_url;
        }
      }
    }
    logger.info('Config', 'Loaded config from ' + CONFIG_PATH);
  }
  return config;
}

function saveConfig() {
  return safeWriteJSON(CONFIG_PATH, config);
}

function updateConfig(patch) {
  // Only allow updating known safe keys
  const ALLOWED = ['model', 'system_prompt', 'providers', 'gateway', 'max_agent_steps', 'embedding', 'memory_mode', 'available_models', 'default_provider'];
  for (const key of Object.keys(patch)) {
    if (ALLOWED.includes(key)) {
      if (key === 'providers' && typeof patch.providers === 'object') {
        // Deep-merge providers
        for (const pName of Object.keys(patch.providers)) {
          if (patch.providers[pName] && patch.providers[pName]._delete) {
            // Delete provider
            delete config.providers[pName];
          } else {
            if (!config.providers[pName]) config.providers[pName] = {};
            Object.assign(config.providers[pName], patch.providers[pName]);
          }
        }
      } else if (key === 'embedding' && typeof patch.embedding === 'object') {
        // Deep-merge embedding config
        if (!config.embedding) config.embedding = {};
        Object.assign(config.embedding, patch.embedding);
      } else {
        config[key] = patch[key];
      }
    }
  }
  saveConfig();
  return config;
}

/**
 * Resolve the provider config for a given model name.
 * Returns { api_key, base_url, model } or null.
 */
function resolveProvider(modelName, explicitProvider) {
  const m = modelName || config.model || 'qwen3.5-plus';
  // If explicit provider given, use it directly
  if (explicitProvider && config.providers && config.providers[explicitProvider]) {
    const p = config.providers[explicitProvider];
    return { provider: explicitProvider, api_key: p.api_key, base_url: p.base_url, model: m };
  }
  for (const [provName, prov] of Object.entries(config.providers || {})) {
    if (prov.models && prov.models.includes(m)) {
      return { provider: provName, api_key: prov.api_key, base_url: prov.base_url, model: m };
    }
  }
  // Fallback: try to guess provider from model prefix
  if (m.startsWith('qwen')) {
    const p = config.providers.bailian || {};
    return { provider: 'bailian', api_key: p.api_key || '', base_url: p.base_url || DEFAULT_CONFIG.providers.bailian.base_url, model: m };
  }
  if (m.startsWith('deepseek') || m.includes('DeepSeek')) {
    const p = config.providers.deepseek || config.providers.siliconflow || {};
    return { provider: 'deepseek', api_key: p.api_key || '', base_url: p.base_url || DEFAULT_CONFIG.providers.deepseek.base_url, model: m };
  }
  if (m.startsWith('gpt') || m.startsWith('o1') || m.startsWith('o3')) {
    const p = config.providers.openai || {};
    return { provider: 'openai', api_key: p.api_key || '', base_url: p.base_url || DEFAULT_CONFIG.providers.openai.base_url, model: m };
  }
  if (m.startsWith('claude')) {
    const p = config.providers.anthropic || {};
    return { provider: 'anthropic', api_key: p.api_key || '', base_url: p.base_url || DEFAULT_CONFIG.providers.anthropic.base_url, model: m };
  }
  // Last resort: try bailian (most likely to have a key)
  const fallbackP = config.providers.bailian || {};
  if (fallbackP.api_key) {
    return { provider: 'bailian', api_key: fallbackP.api_key, base_url: fallbackP.base_url || DEFAULT_CONFIG.providers.bailian.base_url, model: m };
  }
  return null;
}

// Get fallback models (all configured models with API keys, excluding the failed one)
function getFallbackModels(failedModel) {
  const fallbacks = [];
  for (const [provName, prov] of Object.entries(config.providers || {})) {
    if (!prov.api_key) continue;
    for (const m of (prov.models || [])) {
      if (m !== failedModel) {
        fallbacks.push({ model: m, provider: provName });
      }
    }
  }
  return fallbacks;
}

// Estimate token count (~4 chars per token for mixed CJK/English)
function estimateTokens(text) {
  if (!text) return 0;
  return Math.ceil(text.length / 3);
}

// Truncate message history to fit within context window
function truncateMessages(messages, maxTokens) {
  if (!messages || messages.length === 0) return messages;
  let totalTokens = 0;
  // Always keep system message
  const systemMsgs = messages.filter(m => m.role === 'system');
  const nonSystemMsgs = messages.filter(m => m.role !== 'system');
  for (const m of systemMsgs) totalTokens += estimateTokens(typeof m.content === 'string' ? m.content : JSON.stringify(m.content));
  // Add messages from newest to oldest
  const kept = [];
  for (let i = nonSystemMsgs.length - 1; i >= 0; i--) {
    const m = nonSystemMsgs[i];
    const tokens = estimateTokens(typeof m.content === 'string' ? m.content : JSON.stringify(m.content));
    if (totalTokens + tokens > maxTokens) break;
    totalTokens += tokens;
    kept.unshift(m);
  }
  if (kept.length < nonSystemMsgs.length) {
    logger.info('Context', `Truncated ${nonSystemMsgs.length - kept.length} messages to fit ${maxTokens} token limit`);
  }
  return [...systemMsgs, ...kept];
}

// ─── Session Manager ─────────────────────────────────────────────────────────

/**
 * Sessions are stored in memory and periodically flushed to disk.
 * 
 * Session structure:
 * {
 *   id: string,
 *   title: string,
 *   model: string,
 *   created_at: string (ISO),
 *   updated_at: string (ISO),
 *   messages: [ { role, content, timestamp } ],
 *   system_prompt: string
 * }
 */

const sessions = new Map();
let sessionSaveTimer = null;

function sessionFilePath(id) {
  return path.join(SESSIONS_DIR, id + '.json');
}

function loadSessions() {
  ensureDir(SESSIONS_DIR);
  try {
    const files = fs.readdirSync(SESSIONS_DIR).filter(f => f.endsWith('.json'));
    for (const f of files) {
      try {
        const data = JSON.parse(fs.readFileSync(path.join(SESSIONS_DIR, f), 'utf-8'));
        if (data && data.id) {
          // Trim old messages if too many
          if (data.messages && data.messages.length > MAX_MESSAGES_PER_SESSION) {
            data.messages = data.messages.slice(-MAX_MESSAGES_PER_SESSION);
          }
          sessions.set(data.id, data);
        }
      } catch (_) { /* skip corrupt files */ }
    }
    logger.info('Sessions', `Loaded ${sessions.size} session(s) from disk`);
  } catch (_) {}
}

function scheduleSave() {
  if (sessionSaveTimer) return;
  sessionSaveTimer = setTimeout(() => {
    sessionSaveTimer = null;
    flushSessions();
  }, SESSION_SAVE_DEBOUNCE_MS);
}

function flushSessions() {
  ensureDir(SESSIONS_DIR);
  for (const [id, sess] of sessions) {
    try {
      fs.writeFileSync(sessionFilePath(id), JSON.stringify(sess, null, 2), 'utf-8');
    } catch (e) {
      logger.error('Sessions', 'Failed to save session ' + id, e.message);
    }
  }
}

function createSession(opts) {
  if (sessions.size >= MAX_SESSIONS) {
    // Evict oldest
    let oldest = null;
    for (const [, s] of sessions) {
      if (!oldest || s.updated_at < oldest.updated_at) oldest = s;
    }
    if (oldest) deleteSession(oldest.id);
  }

  const now = new Date().toISOString();
  const sess = {
    id: uuid(),
    title: (opts && opts.title) || 'New Chat',
    model: (opts && opts.model) || config.model || 'qwen3.5-plus',
    provider: (opts && opts.provider) || '',
    created_at: now,
    updated_at: now,
    messages: [],
    system_prompt: (opts && opts.system_prompt) || config.system_prompt || '',
  };
  sessions.set(sess.id, sess);
  scheduleSave();
  return sess;
}

function getSession(id) {
  return sessions.get(id) || null;
}

function listSessions() {
  const list = [];
  for (const [, s] of sessions) {
    list.push({
      id: s.id,
      title: s.title,
      model: s.model,
      created_at: s.created_at,
      updated_at: s.updated_at,
      message_count: s.messages.length,
    });
  }
  // Sort by updated_at descending
  list.sort((a, b) => (b.updated_at > a.updated_at ? 1 : -1));
  return list;
}

function deleteSession(id) {
  sessions.delete(id);
  try { fs.unlinkSync(sessionFilePath(id)); } catch (_) {}
  return true;
}

function addMessage(sessionId, role, content, extra) {
  const sess = sessions.get(sessionId);
  if (!sess) return null;
  const msg = { role, content, timestamp: new Date().toISOString() };
  if (extra && extra.image_base64) msg.image_base64 = extra.image_base64;
  sess.messages.push(msg);
  sess.updated_at = msg.timestamp;
  // Auto-title from first user message
  if (role === 'user' && sess.messages.filter(m => m.role === 'user').length === 1) {
    sess.title = content.slice(0, 50).replace(/\n/g, ' ');
  }
  // Trim if over limit
  if (sess.messages.length > MAX_MESSAGES_PER_SESSION) {
    sess.messages = sess.messages.slice(-MAX_MESSAGES_PER_SESSION);
  }
  scheduleSave();
  return msg;
}

// ─── Memory System ───────────────────────────────────────────────────────────

const MEMORY_VECTORS_PATH = path.join(BASE_DIR, 'memory_vectors.json');

function readMemory() {
  return safeReadText(MEMORY_PATH, '');
}

function writeMemory(content) {
  return safeWriteText(MEMORY_PATH, content);
}

function appendMemory(text) {
  const existing = readMemory();
  const separator = existing.length > 0 ? '\n\n' : '';
  const entry = `## ${new Date().toISOString()}\n${text}`;
  return writeMemory(existing + separator + entry);
}

// Vector memory functions
async function getEmbedding(text) {
  const emb = config.embedding || {};
  if (!emb.api_key || !emb.model || !emb.base_url) {
    throw new Error('Embedding model not configured');
  }
  const baseUrl = emb.base_url.replace(/\/+$/, '');
  const resp = await httpRequest(`${baseUrl}/embeddings`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${emb.api_key}`,
    },
    body: JSON.stringify({ model: emb.model, input: text.slice(0, 2000) }),
    timeout: 15000,
  });
  const data = JSON.parse(resp.body);
  if (data.data && data.data[0] && data.data[0].embedding) {
    return data.data[0].embedding;
  }
  throw new Error('Invalid embedding response');
}

function cosineSimilarity(a, b) {
  let dot = 0, na = 0, nb = 0;
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i];
  }
  return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-10);
}

function loadVectorStore() {
  try { return JSON.parse(fs.readFileSync(MEMORY_VECTORS_PATH, 'utf-8')); }
  catch { return { entries: [] }; }
}

function saveVectorStore(store) {
  safeWriteJSON(MEMORY_VECTORS_PATH, store);
}

async function vectorMemoryStore(text) {
  const embedding = await getEmbedding(text);
  const store = loadVectorStore();
  store.entries.push({ text: text.slice(0, 2000), embedding, timestamp: Date.now() });
  if (store.entries.length > 500) store.entries = store.entries.slice(-500);
  saveVectorStore(store);
}

async function vectorMemorySearch(query, limit = 5) {
  const queryEmb = await getEmbedding(query);
  const store = loadVectorStore();
  if (store.entries.length === 0) {
    return [{ text: readMemory(), score: 1.0, source: 'full_memory' }];
  }
  const scored = store.entries.map(e => ({
    text: e.text, score: cosineSimilarity(queryEmb, e.embedding), timestamp: e.timestamp,
  }));
  scored.sort((a, b) => b.score - a.score);
  return scored.slice(0, limit);
}

// ─── Cron Manager (simplified) ───────────────────────────────────────────────

/**
 * Each cron job:
 * {
 *   id: string,
 *   name: string,
 *   interval_ms: number,
 *   action: string,       // "log" | "http" | "memory"
 *   payload: any,
 *   created_at: string,
 *   last_run: string|null,
 *   run_count: number,
 *   _timer: NodeJS timer (not serialized)
 * }
 */

const cronJobs = new Map();

function startCronTimer(job) {
  if (job._timer) clearInterval(job._timer);
  job._timer = setInterval(() => {
    job.last_run = new Date().toISOString();
    job.run_count++;
    logger.info('Cron', `Running job "${job.name}" (${job.id}), count=${job.run_count}`);
    executeCronAction(job);
  }, job.interval_ms);
  // Prevent timer from keeping process alive if it's the only thing left
  if (job._timer.unref) job._timer.unref();
}

function executeCronAction(job) {
  try {
    switch (job.action) {
      case 'log':
        logger.info('CronAction', job.payload || 'cron tick');
        break;
      case 'memory':
        appendMemory(`[Cron: ${job.name}] ${job.payload || 'tick'}`);
        break;
      case 'http': {
        // Simple GET request (fire-and-forget)
        const url = job.payload;
        if (typeof url === 'string' && url.startsWith('http')) {
          const mod = url.startsWith('https') ? https : http;
          const req = mod.get(url, (res) => { res.resume(); });
          req.on('error', (e) => logger.warn('CronHTTP', e.message));
          req.end();
        }
        break;
      }
      default:
        logger.warn('Cron', 'Unknown action: ' + job.action);
    }
  } catch (e) {
    logger.error('Cron', 'Job execution failed: ' + job.id, e.message);
  }
}

function addCronJob(opts) {
  const id = uuid();
  const job = {
    id,
    name: opts.name || 'Unnamed',
    interval_ms: Math.max(opts.interval_ms || 60000, 5000), // min 5s
    action: opts.action || 'log',
    payload: opts.payload || null,
    created_at: new Date().toISOString(),
    last_run: null,
    run_count: 0,
  };
  cronJobs.set(id, job);
  startCronTimer(job);
  logger.info('Cron', `Added job "${job.name}" (${id}), interval=${job.interval_ms}ms`);
  return job;
}

function listCronJobs() {
  const list = [];
  for (const [, j] of cronJobs) {
    list.push({
      id: j.id,
      name: j.name,
      interval_ms: j.interval_ms,
      action: j.action,
      payload: j.payload,
      created_at: j.created_at,
      last_run: j.last_run,
      run_count: j.run_count,
    });
  }
  return list;
}

function deleteCronJob(id) {
  const job = cronJobs.get(id);
  if (!job) return false;
  if (job._timer) clearInterval(job._timer);
  cronJobs.delete(id);
  logger.info('Cron', `Deleted job "${job.name}" (${id})`);
  return true;
}

// ─── LLM Streaming Proxy ────────────────────────────────────────────────────

/**
 * Calls an OpenAI-compatible chat/completions endpoint with stream=true,
 * and pipes SSE chunks to the HTTP response.
 * 
 * @param {object} opts - { model, messages, session_id }
 * @param {http.ServerResponse} res - The client response to stream to
 */
function streamChat(opts, res) {
  const provInfo = resolveProvider(opts.model, opts.provider);
  if (!provInfo) {
    sendJSON(res, 400, { error: 'unknown_model', message: `Cannot resolve provider for model: ${opts.model}` });
    return;
  }
  if (!provInfo.api_key) {
    sendJSON(res, 401, { error: 'missing_api_key', message: `API key not configured for provider: ${provInfo.provider}` });
    return;
  }

  const baseUrl = provInfo.base_url.replace(/\/+$/, '');
  let endpoint = baseUrl + '/chat/completions';

  let parsedUrl;
  try {
    parsedUrl = new URL(endpoint);
  } catch (e) {
    sendJSON(res, 400, { error: 'invalid_base_url', message: 'Cannot parse provider base_url: ' + e.message });
    return;
  }

  const isHttps = parsedUrl.protocol === 'https:';
  const mod = isHttps ? https : http;
  const defaultPort = isHttps ? 443 : 80;

  // Build messages array with system prompt
  const messages = [];
  if (opts.system_prompt) {
    messages.push({ role: 'system', content: opts.system_prompt });
  }
  if (Array.isArray(opts.messages)) {
    for (const m of opts.messages) {
      messages.push({ role: m.role, content: m.content });
    }
  }

  const reqBody = JSON.stringify({
    model: provInfo.model,
    messages: messages,
    stream: true,
    temperature: opts.temperature !== undefined ? opts.temperature : 0.7,
    max_tokens: opts.max_tokens || 4096,
  });

  const reqOptions = {
    hostname: parsedUrl.hostname,
    port: parsedUrl.port || defaultPort,
    path: parsedUrl.pathname + (parsedUrl.search || ''),
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer ' + provInfo.api_key,
      'User-Agent': 'openclaw/' + VERSION,
      'X-KILOCODE-FEATURE': 'openclaw',
      'Accept': 'text/event-stream',
      'Content-Length': Buffer.byteLength(reqBody),
    },
    timeout: 120000, // 2 min
  };

  logger.info('LLM', `Streaming to ${provInfo.provider}/${provInfo.model}, ${messages.length} messages`);
  logger.info('LLM', `Headers: UA=${reqOptions.headers['User-Agent']}, Feature=${reqOptions.headers['X-KILOCODE-FEATURE']}`);

  // Set SSE headers on client response
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
    'X-Accel-Buffering': 'no',
    'Access-Control-Allow-Origin': '*',
  });

  let fullContent = '';
  let finished = false;

  const upstream = mod.request(reqOptions, (upRes) => {
    if (upRes.statusCode !== 200) {
      let errBody = '';
      upRes.on('data', (c) => { errBody += c.toString(); });
      upRes.on('end', () => {
        logger.error('LLM', `Provider returned ${upRes.statusCode}`, errBody.slice(0, 500));
        res.write(`data: ${JSON.stringify({ error: true, status: upRes.statusCode, message: errBody.slice(0, 500) })}\n\n`);
        res.write('data: [DONE]\n\n');
        res.end();
        finished = true;
      });
      return;
    }

    let buffer = '';

    upRes.on('data', (chunk) => {
      buffer += chunk.toString();
      // Process complete lines
      const lines = buffer.split('\n');
      buffer = lines.pop() || ''; // keep incomplete line in buffer

      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;

        if (trimmed === 'data: [DONE]') {
          res.write('data: [DONE]\n\n');
          continue;
        }

        if (trimmed.startsWith('data: ')) {
          const jsonStr = trimmed.slice(6);
          try {
            const parsed = JSON.parse(jsonStr);
            const delta = parsed.choices && parsed.choices[0] && parsed.choices[0].delta;
            if (delta && delta.content) {
              fullContent += delta.content;
            }
            // Forward to client
            res.write(trimmed + '\n\n');
          } catch (_) {
            // Forward raw line even if we can't parse it
            res.write(trimmed + '\n\n');
          }
        }
      }
    });

    upRes.on('end', () => {
      if (!finished) {
        // Store assistant message in session
        if (opts.session_id && fullContent) {
          addMessage(opts.session_id, 'assistant', fullContent);
          // 自动记忆：每5轮对话自动提取要点写入 MEMORY.md
          const _sess = getSession(opts.session_id);
          if (_sess && _sess.messages.length % 10 === 0 && _sess.messages.length > 0) {
            const recentMsgs = _sess.messages.slice(-10).map(m => m.role + ': ' + m.content.slice(0, 200)).join('\n');
            appendMemory('Auto-extract from session ' + opts.session_id + ':\n' + recentMsgs.slice(0, 1000));
            logger.info('Memory', 'Auto-extracted memory from session ' + opts.session_id);
          }
        }
        if (!res.writableEnded) {
          res.end();
        }
        finished = true;
        logger.info('LLM', `Stream complete, ${fullContent.length} chars`);
      }
    });

    upRes.on('error', (e) => {
      logger.error('LLM', 'Upstream response error', e.message);
      if (!finished) {
        res.write(`data: ${JSON.stringify({ error: true, message: e.message })}\n\n`);
        res.write('data: [DONE]\n\n');
        res.end();
        finished = true;
      }
    });
  });

  upstream.on('error', (e) => {
    logger.error('LLM', 'Upstream request error', e.message);
    if (!finished) {
      res.write(`data: ${JSON.stringify({ error: true, message: 'Upstream connection error: ' + e.message })}\n\n`);
      res.write('data: [DONE]\n\n');
      res.end();
      finished = true;
    }
  });

  upstream.on('timeout', () => {
    logger.error('LLM', 'Upstream request timeout');
    upstream.destroy();
    if (!finished) {
      res.write(`data: ${JSON.stringify({ error: true, message: 'Request timeout (120s)' })}\n\n`);
      res.write('data: [DONE]\n\n');
      res.end();
      finished = true;
    }
  });

  // Handle client disconnect
  res.on('close', () => {
    if (!finished) {
      logger.info('LLM', 'Client disconnected, aborting upstream');
      upstream.destroy();
      finished = true;
    }
  });

  upstream.write(reqBody);
  upstream.end();
}

/**
 * Non-streaming chat (for simple requests or when stream=false)
 */
function nonStreamChat(opts, callback) {
  const provInfo = resolveProvider(opts.model);
  if (!provInfo) return callback({ error: 'unknown_model', message: `Cannot resolve provider for model: ${opts.model}` });
  if (!provInfo.api_key) return callback({ error: 'missing_api_key', message: `API key not configured for provider: ${provInfo.provider}` });

  const baseUrl = provInfo.base_url.replace(/\/+$/, '');
  let parsedUrl;
  try {
    parsedUrl = new URL(baseUrl + '/chat/completions');
  } catch (e) {
    return callback({ error: 'invalid_base_url', message: e.message });
  }

  const isHttps = parsedUrl.protocol === 'https:';
  const mod = isHttps ? https : http;

  const messages = [];
  if (opts.system_prompt) messages.push({ role: 'system', content: opts.system_prompt });
  if (Array.isArray(opts.messages)) {
    for (const m of opts.messages) messages.push({ role: m.role, content: m.content });
  }

  const reqBody = JSON.stringify({
    model: provInfo.model,
    messages,
    stream: false,
    temperature: opts.temperature !== undefined ? opts.temperature : 0.7,
    max_tokens: opts.max_tokens || 4096,
  });

  const reqOptions = {
    hostname: parsedUrl.hostname,
    port: parsedUrl.port || (isHttps ? 443 : 80),
    path: parsedUrl.pathname + (parsedUrl.search || ''),
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer ' + provInfo.api_key,
      'User-Agent': 'openclaw/' + VERSION,
      'X-KILOCODE-FEATURE': 'openclaw',
      'Content-Length': Buffer.byteLength(reqBody),
    },
    timeout: 120000,
  };

  const req = mod.request(reqOptions, (res) => {
    let body = '';
    res.on('data', (c) => { body += c.toString(); });
    res.on('end', () => {
      try {
        const parsed = JSON.parse(body);
        if (res.statusCode !== 200) {
          return callback({ error: 'provider_error', status: res.statusCode, detail: parsed });
        }
        const content = parsed.choices && parsed.choices[0] && parsed.choices[0].message && parsed.choices[0].message.content;
        callback(null, { content, usage: parsed.usage, model: parsed.model });
      } catch (e) {
        callback({ error: 'parse_error', message: e.message, raw: body.slice(0, 500) });
      }
    });
  });
  req.on('error', (e) => callback({ error: 'request_error', message: e.message }));
  req.on('timeout', () => { req.destroy(); callback({ error: 'timeout', message: 'Request timed out' }); });
  req.write(reqBody);
  req.end();
}

// ─── HTTP Helpers ────────────────────────────────────────────────────────────

function sendJSON(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Access-Control-Allow-Origin': '*',
  });
  res.end(body);
}

function sendError(res, status, code, message) {
  sendJSON(res, status, { error: code, message });
}

function parseBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_SIZE) {
        reject(new Error('Request body too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf-8');
      if (!raw) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch (e) {
        reject(new Error('Invalid JSON: ' + e.message));
      }
    });
    req.on('error', reject);
  });
}

function parseQuery(urlStr) {
  const idx = urlStr.indexOf('?');
  if (idx === -1) return {};
  const qs = urlStr.slice(idx + 1);
  const params = {};
  for (const pair of qs.split('&')) {
    const [k, v] = pair.split('=');
    if (k) params[decodeURIComponent(k)] = v ? decodeURIComponent(v) : '';
  }
  return params;
}

function getPathname(urlStr) {
  const idx = urlStr.indexOf('?');
  return idx === -1 ? urlStr : urlStr.slice(0, idx);
}

// ─── Route Handlers ──────────────────────────────────────────────────────────

const routes = {};

function route(method, path, handler) {
  routes[method + ' ' + path] = handler;
}

// --- Health ---
route('GET', '/health', (req, res) => {
  sendJSON(res, 200, {
    status: 'ok',
    version: VERSION,
    uptime: Math.floor((Date.now() - START_TIME) / 1000),
    timestamp: new Date().toISOString(),
  });
});

// --- Status ---
route('GET', '/api/status', (req, res) => {
  sendJSON(res, 200, {
    status: 'ok',
    version: VERSION,
    platform: 'android',
    node_version: process.version,
    uptime: Math.floor((Date.now() - START_TIME) / 1000),
    uptime_seconds: Math.floor((Date.now() - START_TIME) / 1000),
    memory: process.memoryUsage(),
    memory_mb: memoryUsageMB(),
    sessions: sessions.size,
    session_count: sessions.size,
    cron_count: cronJobs.size,
    config_model: config.model,
    memory_size: readMemory().length,
    pid: process.pid,
    timestamp: new Date().toISOString(),
  });
});

// --- Chat (streaming) ---
route('POST', '/api/chat', async (req, res) => {
  let body;
  try {
    body = await parseBody(req);
  } catch (e) {
    return sendError(res, 400, 'bad_request', e.message);
  }

  const message = body.message || body.content;
  if (!message || typeof message !== 'string') {
    return sendError(res, 400, 'missing_message', 'Field "message" is required');
  }

  // Resolve or create session
  let sessionId = body.session_id;
  let sess;
  if (sessionId) {
    sess = getSession(sessionId);
    if (!sess) {
      return sendError(res, 404, 'session_not_found', 'Session not found: ' + sessionId);
    }
  } else {
    sess = createSession({ model: body.model, title: body.title });
    sessionId = sess.id;
  }

  // Add user message
  addMessage(sessionId, 'user', message);

  const model = body.model || sess.model || config.model;
  const provider = body.provider || sess.provider || '';
  const stream = body.stream !== false; // default true

  if (stream) {
    streamChat({
      model,
      provider,
      messages: sess.messages,
      system_prompt: buildSystemPrompt(config.system_prompt, sess.system_prompt),
      session_id: sessionId,
      temperature: body.temperature,
      max_tokens: body.max_tokens,
    }, res);
  } else {
    nonStreamChat({
      model,
      messages: sess.messages,
      system_prompt: buildSystemPrompt(config.system_prompt, sess.system_prompt),
      temperature: body.temperature,
      max_tokens: body.max_tokens,
    }, (err, result) => {
      if (err) {
        return sendJSON(res, 502, { error: err.error, message: err.message, detail: err.detail });
      }
      addMessage(sessionId, 'assistant', result.content);
      // 自动记忆：每5轮对话自动提取要点写入 MEMORY.md
      if (sess.messages.length % 10 === 0 && sess.messages.length > 0) {
        const recentMsgs = sess.messages.slice(-10).map(m => m.role + ': ' + m.content.slice(0, 200)).join('\n');
        appendMemory('Auto-extract from session ' + sessionId + ':\n' + recentMsgs.slice(0, 1000));
        logger.info('Memory', 'Auto-extracted memory from session ' + sessionId);
      }
      sendJSON(res, 200, {
        session_id: sessionId,
        model: result.model || model,
        content: result.content,
        usage: result.usage,
      });
    });
  }
});

// --- Sessions ---
route('GET', '/api/sessions', (req, res) => {
  sendJSON(res, 200, { sessions: listSessions() });
});

route('POST', '/api/sessions', async (req, res) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  const sess = createSession(body);
  sendJSON(res, 201, { session: { id: sess.id, title: sess.title, model: sess.model, created_at: sess.created_at } });
});

route('GET', '/api/sessions/:id', (req, res, params) => {
  const sess = getSession(params.id);
  if (!sess) return sendError(res, 404, 'not_found', 'Session not found');
  sendJSON(res, 200, {
    session: {
      id: sess.id,
      title: sess.title,
      model: sess.model,
      created_at: sess.created_at,
      updated_at: sess.updated_at,
      message_count: sess.messages.length,
      messages: sess.messages,
      system_prompt: sess.system_prompt,
    },
  });
});

route('POST', '/api/sessions/:id/title', async (req, res, params) => {
  const sess = getSession(params.id);
  if (!sess) return sendError(res, 404, 'not_found', 'Session not found');
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  const title = String(body.title || '').trim();
  if (!title) return sendError(res, 400, 'missing_title', 'Field "title" is required');
  sess.title = title.slice(0, 80);
  sess.updated_at = new Date().toISOString();
  scheduleSave();
  sendJSON(res, 200, { session: { id: sess.id, title: sess.title, updated_at: sess.updated_at } });
});

route('POST', '/api/sessions/:id/messages', async (req, res, params) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  const sess = sessions[params.id];
  if (!sess) return sendError(res, 404, 'not_found', 'Session not found');
  const msg = {
    role: body.role || 'assistant',
    content: body.content || '',
    timestamp: new Date().toISOString(),
  };
  sess.messages.push(msg);
  sess.updated_at = msg.timestamp;
  safeWriteJSON(path.join(SESSIONS_DIR, params.id + '.json'), sess);
  sendJSON(res, 200, { message: 'added' });
});

route('DELETE', '/api/sessions/:id', (req, res, params) => {
  if (!getSession(params.id)) return sendError(res, 404, 'not_found', 'Session not found');
  deleteSession(params.id);
  sendJSON(res, 200, { deleted: true, id: params.id });
});

// --- Config ---
route('GET', '/api/config', (req, res) => {
  // Redact API keys for security
  const safe = JSON.parse(JSON.stringify(config));
  for (const prov of Object.values(safe.providers || {})) {
    if (prov.api_key) {
      prov.api_key = prov.api_key.slice(0, 6) + '***' + prov.api_key.slice(-4);
    }
  }
  sendJSON(res, 200, { config: safe });
});

route('POST', '/api/config', async (req, res) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  const updated = updateConfig(body);
  // Return redacted config
  const safe = JSON.parse(JSON.stringify(updated));
  for (const prov of Object.values(safe.providers || {})) {
    if (prov.api_key) {
      prov.api_key = prov.api_key.slice(0, 6) + '***' + prov.api_key.slice(-4);
    }
  }
  sendJSON(res, 200, { config: safe, message: 'Config updated' });
});

// --- Memory ---
route('GET', '/api/memory', (req, res) => {
  const content = readMemory();
  sendJSON(res, 200, { content, size: Buffer.byteLength(content, 'utf-8') });
});

route('POST', '/api/memory', async (req, res) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  
  if (body.append && typeof body.content === 'string') {
    appendMemory(body.content);
    sendJSON(res, 200, { message: 'Memory appended' });
  } else if (typeof body.content === 'string') {
    writeMemory(body.content);
    sendJSON(res, 200, { message: 'Memory written' });
  } else {
    sendError(res, 400, 'missing_content', 'Field "content" is required');
  }
});

// --- Memory Search (simple grep) ---
route('GET', '/api/memory/search', (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const query = (url.searchParams.get('q') || '').toLowerCase().trim();
  if (!query) return sendJSON(res, 200, { results: [], query: '' });
  
  const results = [];
  // Search in MEMORY.md
  try {
    const memContent = readMemory();
    const lines = memContent.split('\n');
    lines.forEach((line, i) => {
      if (line.toLowerCase().includes(query)) {
        results.push({ file: 'MEMORY.md', line: i + 1, text: line.trim() });
      }
    });
  } catch (_) {}
  
  // Search in memory/ directory
  const memDir = path.join(WORKSPACE_DIR, 'memory');
  try {
    if (fs.existsSync(memDir)) {
      const walk = (dir) => {
        for (const entry of fs.readdirSync(dir)) {
          const fp = path.join(dir, entry);
          const st = fs.statSync(fp);
          if (st.isDirectory()) { walk(fp); continue; }
          if (!entry.endsWith('.md')) continue;
          try {
            const content = fs.readFileSync(fp, 'utf-8');
            const lines = content.split('\n');
            const relPath = path.relative(WORKSPACE_DIR, fp).replace(/\\\\/g, '/');
            lines.forEach((line, i) => {
              if (line.toLowerCase().includes(query)) {
                results.push({ file: relPath, line: i + 1, text: line.trim() });
              }
            });
          } catch (_) {}
        }
      };
      walk(memDir);
    }
  } catch (_) {}
  
  sendJSON(res, 200, { results: results.slice(0, 100), query, total: results.length });
});

route('GET', '/api/cron', (req, res) => {
  sendJSON(res, 200, { jobs: listCronJobs() });
});

route('POST', '/api/cron', async (req, res) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  if (!body.name) return sendError(res, 400, 'missing_name', 'Field "name" is required');
  const job = addCronJob(body);
  sendJSON(res, 201, {
    job: { id: job.id, name: job.name, interval_ms: job.interval_ms, action: job.action },
  });
});

route('DELETE', '/api/cron/:id', (req, res, params) => {
  const ok = deleteCronJob(params.id);
  if (!ok) return sendError(res, 404, 'not_found', 'Cron job not found');
  sendJSON(res, 200, { deleted: true, id: params.id });
});

// --- Models list ---
route('GET', '/api/models', (req, res) => {
  const models = [];
  for (const [provName, prov] of Object.entries(config.providers || {})) {
    for (const m of (prov.models || [])) {
      models.push({ id: m, provider: provName });
    }
  }
  sendJSON(res, 200, { models, current: config.model });
});

// --- Providers list ---
route('GET', '/api/providers', (req, res) => {
  const providers = {};
  for (const [name, prov] of Object.entries(config.providers || {})) {
    providers[name] = {
      base_url: prov.base_url,
      models: prov.models || [],
      configured: !!(prov.api_key),
    };
  }
  sendJSON(res, 200, { providers });
});

// --- Embedding config ---
route('GET', '/api/embedding', (req, res) => {
  const emb = config.embedding || {};
  const embProviders = config.embedding_providers || DEFAULT_CONFIG.embedding_providers || {};
  sendJSON(res, 200, {
    provider: emb.provider || '',
    model: emb.model || '',
    has_key: !!(emb.api_key),
    base_url: emb.base_url || '',
    configured: !!(emb.provider && emb.model && emb.api_key),
    providers: embProviders,
  });
});

route('POST', '/api/embedding', async (req, res) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  if (!config.embedding) config.embedding = {};
  if (body.provider !== undefined) config.embedding.provider = body.provider;
  if (body.model !== undefined) config.embedding.model = body.model;
  if (body.api_key !== undefined) config.embedding.api_key = body.api_key;
  if (body.base_url !== undefined) config.embedding.base_url = body.base_url;
  safeWriteJSON(CONFIG_PATH, config);
  sendJSON(res, 200, { message: 'Embedding config updated' });
});

route('POST', '/api/embedding/providers', async (req, res) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  if (!config.embedding_providers) config.embedding_providers = {};
  const action = body.action || 'add';
  const name = body.name;
  if (!name) return sendError(res, 400, 'bad_request', 'name required');
  if (action === 'delete') {
    delete config.embedding_providers[name];
  } else {
    if (!config.embedding_providers[name]) config.embedding_providers[name] = {};
    if (body.base_url !== undefined) config.embedding_providers[name].base_url = body.base_url;
    if (body.models !== undefined) config.embedding_providers[name].models = body.models;
  }
  safeWriteJSON(CONFIG_PATH, config);
  sendJSON(res, 200, { providers: config.embedding_providers });
});

// --- Soul System ---
const SOUL_PATH = path.join(BASE_DIR, 'SOUL.md');
const HEARTBEAT_PATH = path.join(BASE_DIR, 'HEARTBEAT.md');
const USER_PATH = path.join(BASE_DIR, 'USER.md');
const AGENTS_PATH = path.join(BASE_DIR, 'AGENTS.md');
const TOOLS_PATH = path.join(BASE_DIR, 'TOOLS.md');

// --- Preset workspace files (write defaults if missing) ---
const PRESET_FILES = {
  [SOUL_PATH]: `# SOUL.md - Who You Are

**Name:** OpenClaw 🦞
**Role:** AI 个人助手（Android 端）
**Traits:** 聪明高效、简洁直接、友好专业

## Core Truths
- Be genuinely helpful, not performatively helpful
- Have opinions. An assistant with no personality is just a search engine
- Be resourceful before asking. Try to figure it out first
- Earn trust through competence

## 说话风格
- 中文优先，技术术语保留英文
- 简洁直接，不啰嗦
- 可以用 emoji，但克制
- 重要信息用加粗标注
- 回复格式适合手机阅读（短段落）

## 行为准则
- 能帮忙做的事就直接做，不反复确认
- 不确定的事先问再做
- 同一命令最多重试 2 次，失败就报告

## 记忆
重要信息自动记录到 MEMORY.md。
`,
  [USER_PATH]: `# USER.md - About Your Human

- **Name:** 待确认
- **Timezone:** Asia/Shanghai (UTC+8)

## 偏好
- 喜欢简洁直接
- 任务能做就直接做，不反复确认
`,
  [HEARTBEAT_PATH]: `# HEARTBEAT.md - 定时任务配置

## 核心规则
- 连续 3 天失败才排查配置

## 限流防护
- 任务间隔 ≥5 分钟
- 失败 1 次就放弃，不重试轰炸
`,
  [AGENTS_PATH]: `# AGENTS.md - Agent Rules

## Every Session
1. Read SOUL.md — who you are
2. Read USER.md — who you're helping

## Safety
- Don't exfiltrate private data
- Ask before external actions

## Task Rules
- Simple tasks: just do it
- Same command max retry 2 times
- Batch checks in one script
`,
  [TOOLS_PATH]: `# TOOLS.md - 工具与技能指南

## 快速决策
| 关键词 | 使用方式 |
|-------|--------|
| AI搜索 | web_search (Tavily) |
| 天气 | get_weather |
| 新闻 | news_summary |
| 网页内容 | web_fetch / browser_navigate |
| 执行命令 | exec |
| 读写文件 | read_file / write_file |
| 安装技能 | skill_search + skill_install |

## 规则
- 简单任务直接做，不规划
- 同一命令最多重试 2 次
- 批量检查用一个脚本
- 安装技能前必须征得用户同意
`,
};

for (const [filePath, defaultContent] of Object.entries(PRESET_FILES)) {
  if (!fs.existsSync(filePath)) {
    try {
      safeWriteText(filePath, defaultContent);
      logger.info('Init', 'Created preset: ' + path.basename(filePath));
    } catch (e) {
      logger.warn('Init', 'Failed to create preset: ' + path.basename(filePath) + ': ' + e.message);
    }
  }
}

// Cache for SOUL content to avoid repeated file reads
let cachedSoulContent = null;
let lastSoulReadTime = 0;
const SOUL_CACHE_TTL = 5000; // 5 seconds cache

function getSoulContent() {
  const now = Date.now();
  if (!cachedSoulContent || (now - lastSoulReadTime) > SOUL_CACHE_TTL) {
    try {
      cachedSoulContent = fs.readFileSync(SOUL_PATH, 'utf-8');
      lastSoulReadTime = now;
      logger.debug('Soul', 'Reloaded SOUL.md content');
    } catch (e) {
      logger.warn('Soul', 'Could not read SOUL.md, using default', e.message);
      cachedSoulContent = '# SOUL.md\nYou are a helpful AI assistant.';
      lastSoulReadTime = now;
    }
  }
  return cachedSoulContent;
}

// Helper to combine system prompt with soul content
function getAvailableSkills() {
  const skills = [];
  try {
    if (fs.existsSync(SKILLS_DIR)) {
      for (const entry of fs.readdirSync(SKILLS_DIR)) {
        const skillDir = path.join(SKILLS_DIR, entry);
        if (!fs.statSync(skillDir).isDirectory()) continue;
        const skillMd = path.join(skillDir, 'SKILL.md');
        if (!fs.existsSync(skillMd)) continue;
        const content = fs.readFileSync(skillMd, 'utf-8');
        const descMatch = content.match(/description[:\s]*([^\n]+)/i);
        const description = descMatch ? descMatch[1].trim() : '';
        skills.push({ name: entry, description, path: skillMd });
      }
    }
  } catch (_) {}
  return skills;
}

function buildSystemPrompt(customPrompt, sessionPrompt) {
  const soulContent = getSoulContent();
  const parts = [];
  
  // Add soul content first (core identity)
  if (soulContent.trim()) {
    parts.push(soulContent);
  }
  
  // Add session-specific system prompt
  if (sessionPrompt && sessionPrompt.trim()) {
    parts.push(sessionPrompt);
  }
  
  // Add custom prompt if different from session/system
  if (customPrompt && customPrompt.trim() && 
      customPrompt !== sessionPrompt && 
      !parts.some(part => part.includes(customPrompt.trim()))) {
    parts.push(customPrompt);
  }

  // Inject available skills
  const skills = getAvailableSkills();
  if (skills.length > 0) {
    let skillSection = '## Skills (mandatory)\nBefore replying: scan <available_skills> <description> entries.\n';
    skillSection += '- If exactly one skill clearly applies: read its SKILL.md, then follow it.\n';
    skillSection += '- If multiple could apply: choose the most specific one.\n';
    skillSection += '- If none clearly apply: do not read any SKILL.md.\n\n';
    skillSection += '<available_skills>\n';
    for (const s of skills) {
      skillSection += `  <skill>\n    <name>${s.name}</name>\n    <description>${s.description}</description>\n    <location>${s.path}</location>\n  </skill>\n`;
    }
    skillSection += '</available_skills>';
    parts.push(skillSection);
  }

  // Inject workspace context files (AGENTS.md, USER.md, TOOLS.md, HEARTBEAT.md)
  const workspaceDir = path.join(BASE_DIR, 'workspace');
  parts.push(`## Workspace\nBase directory: ${BASE_DIR}\nWorkspace directory: ${workspaceDir}\nContext files (SOUL.md, USER.md, AGENTS.md, TOOLS.md, HEARTBEAT.md): ${BASE_DIR}/\nMemory file: ${MEMORY_PATH}\nSkills directory: ${path.join(BASE_DIR, 'skills')}/\n\nUse read_file/write_file/list_files to access any file.`);
  const contextFiles = ['AGENTS.md', 'USER.md', 'TOOLS.md', 'HEARTBEAT.md'];
  for (const cf of contextFiles) {
    const cfPath = path.join(BASE_DIR, cf);
    if (fs.existsSync(cfPath)) {
      try {
        const cfContent = fs.readFileSync(cfPath, 'utf-8').trim();
        if (cfContent) parts.push(`## ${cf}\n${cfContent}`);
      } catch (_) {}
    }
  }
  
  return parts.join('\n\n---\n\n');
}

route('GET', '/api/soul', (req, res) => {
  const content = safeReadText(SOUL_PATH, '# SOUL.md\nYou are a helpful AI assistant.');
  sendJSON(res, 200, { content, size: Buffer.byteLength(content, 'utf-8') });
});

route('POST', '/api/soul', async (req, res) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  if (typeof body.content !== 'string') return sendError(res, 400, 'missing_content', 'Field "content" is required');
  safeWriteText(SOUL_PATH, body.content);
  sendJSON(res, 200, { message: 'Soul updated', size: Buffer.byteLength(body.content, 'utf-8') });
});

// --- Heartbeat System ---
route('GET', '/api/heartbeat', (req, res) => {
  const content = safeReadText(HEARTBEAT_PATH, '');
  sendJSON(res, 200, { content, size: Buffer.byteLength(content, 'utf-8'), active: !!heartbeatTimer });
});

route('POST', '/api/heartbeat', async (req, res) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  if (typeof body.content === 'string') {
    safeWriteText(HEARTBEAT_PATH, body.content);
  }
  if (typeof body.interval_ms === 'number') {
    startHeartbeat(body.interval_ms);
  }
  sendJSON(res, 200, { message: 'Heartbeat updated', active: !!heartbeatTimer });
});

// --- USER.md API ---
route('GET', '/api/user', (req, res) => {
  const content = safeReadText(USER_PATH, '');
  sendJSON(res, 200, { content, size: Buffer.byteLength(content, 'utf-8') });
});
route('POST', '/api/user', async (req, res) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  if (typeof body.content !== 'string') return sendError(res, 400, 'missing_content', 'Field "content" is required');
  safeWriteText(USER_PATH, body.content);
  sendJSON(res, 200, { message: 'USER.md updated' });
});

// --- AGENTS.md API ---
route('GET', '/api/agents', (req, res) => {
  const content = safeReadText(AGENTS_PATH, '');
  sendJSON(res, 200, { content, size: Buffer.byteLength(content, 'utf-8') });
});
route('POST', '/api/agents', async (req, res) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  if (typeof body.content !== 'string') return sendError(res, 400, 'missing_content', 'Field "content" is required');
  safeWriteText(AGENTS_PATH, body.content);
  sendJSON(res, 200, { message: 'AGENTS.md updated' });
});

// --- TOOLS.md API ---
route('GET', '/api/tools-md', (req, res) => {
  const content = safeReadText(TOOLS_PATH, '');
  sendJSON(res, 200, { content, size: Buffer.byteLength(content, 'utf-8') });
});
route('POST', '/api/tools-md', async (req, res) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  if (typeof body.content !== 'string') return sendError(res, 400, 'missing_content', 'Field "content" is required');
  safeWriteText(TOOLS_PATH, body.content);
  sendJSON(res, 200, { message: 'TOOLS.md updated' });
});

let heartbeatTimer = null;
function startHeartbeat(intervalMs) {
  if (heartbeatTimer) clearInterval(heartbeatTimer);
  if (intervalMs <= 0) { heartbeatTimer = null; return; }
  heartbeatTimer = setInterval(() => {
    const hb = safeReadText(HEARTBEAT_PATH, '');
    if (hb.trim()) {
      logger.info('Heartbeat', 'Heartbeat tick, config size: ' + hb.length);
      appendMemory('[Heartbeat] ' + new Date().toISOString() + ' - checked');
    }
  }, Math.max(intervalMs, 60000));
  if (heartbeatTimer.unref) heartbeatTimer.unref();
  logger.info('Heartbeat', 'Started with interval ' + intervalMs + 'ms');
}

// --- Skill System ---
const SKILLS_DIR = path.join(BASE_DIR, 'skills');
ensureDir(SKILLS_DIR);

route('GET', '/api/skills', (req, res) => {
  const skills = [];
  try {
    for (const entry of fs.readdirSync(SKILLS_DIR)) {
      const skillDir = path.join(SKILLS_DIR, entry);
      if (!fs.statSync(skillDir).isDirectory()) continue;
      const skillMd = path.join(skillDir, 'SKILL.md');
      const hasSkill = fs.existsSync(skillMd);
      let description = '';
      if (hasSkill) {
        const content = fs.readFileSync(skillMd, 'utf-8');
        const descMatch = content.match(/description[:\s]*([^\n]+)/i);
        if (descMatch) description = descMatch[1].trim();
      }
      skills.push({ name: entry, has_skill_md: hasSkill, description });
    }
  } catch (_) {}
  sendJSON(res, 200, { skills, count: skills.length });
});

route('GET', '/api/skills/:name', (req, res, params) => {
  const skillDir = path.join(SKILLS_DIR, params.name);
  const skillMd = path.join(skillDir, 'SKILL.md');
  if (!fs.existsSync(skillMd)) return sendError(res, 404, 'not_found', 'Skill not found: ' + params.name);
  const content = fs.readFileSync(skillMd, 'utf-8');
  // Parse description and metadata from SKILL.md
  const descMatch = content.match(/description[:\s]*([^\n]+)/i);
  const description = descMatch ? descMatch[1].trim() : '';
  // List all files in skill directory
  const files = [];
  try {
    for (const f of fs.readdirSync(skillDir)) {
      const stat = fs.statSync(path.join(skillDir, f));
      files.push({ name: f, size: stat.size });
    }
  } catch (_) {}
  sendJSON(res, 200, { name: params.name, content, description, files });
});

// --- Skill Install (from URL or inline) ---
route('POST', '/api/skills/install', async (req, res) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  
  const name = body.name;
  if (!name || typeof name !== 'string' || name.includes('..') || name.includes('/')) {
    return sendError(res, 400, 'invalid_name', 'Valid skill name is required');
  }
  
  const skillDir = path.join(SKILLS_DIR, name);
  ensureDir(skillDir);
  
  if (body.skill_md && typeof body.skill_md === 'string') {
    // Install from inline content
    fs.writeFileSync(path.join(skillDir, 'SKILL.md'), body.skill_md, 'utf-8');
    // Write additional files if provided
    if (body.files && typeof body.files === 'object') {
      for (const [fname, fcontent] of Object.entries(body.files)) {
        if (typeof fcontent === 'string' && !fname.includes('..')) {
          const fp = path.join(skillDir, fname);
          ensureDir(path.dirname(fp));
          fs.writeFileSync(fp, fcontent, 'utf-8');
        }
      }
    }
    sendJSON(res, 200, { installed: true, name, path: skillDir });
  } else if (body.url && typeof body.url === 'string') {
    // Install from URL (fetch SKILL.md)
    try {
      const https = body.url.startsWith('https') ? require('https') : require('http');
      const fetchContent = (url) => new Promise((resolve, reject) => {
        https.get(url, { timeout: 15000 }, (r) => {
          if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) {
            return fetchContent(r.headers.location).then(resolve).catch(reject);
          }
          let data = '';
          r.on('data', d => data += d);
          r.on('end', () => resolve(data));
          r.on('error', reject);
        }).on('error', reject);
      });
      const content = await fetchContent(body.url);
      fs.writeFileSync(path.join(skillDir, 'SKILL.md'), content, 'utf-8');
      sendJSON(res, 200, { installed: true, name, source: 'url', path: skillDir });
    } catch (e) {
      sendError(res, 500, 'fetch_failed', 'Failed to fetch skill: ' + e.message);
    }
  } else {
    sendError(res, 400, 'missing_content', 'Provide skill_md (inline) or url');
  }
});

// --- Skill Uninstall ---
route('DELETE', '/api/skills/:name', (req, res, params) => {
  const skillDir = path.join(SKILLS_DIR, params.name);
  if (!fs.existsSync(skillDir)) return sendError(res, 404, 'not_found', 'Skill not found');
  // Recursive delete
  function rmDir(dir) {
    for (const f of fs.readdirSync(dir)) {
      const fp = path.join(dir, f);
      if (fs.statSync(fp).isDirectory()) rmDir(fp);
      else fs.unlinkSync(fp);
    }
    fs.rmdirSync(dir);
  }
  try {
    rmDir(skillDir);
    sendJSON(res, 200, { deleted: true, name: params.name });
  } catch (e) {
    sendError(res, 500, 'delete_failed', e.message);
  }
});

// --- Skill Update (overwrite SKILL.md) ---
route('PUT', '/api/skills/:name', async (req, res, params) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  const skillDir = path.join(SKILLS_DIR, params.name);
  if (!fs.existsSync(skillDir)) return sendError(res, 404, 'not_found', 'Skill not found');
  if (body.skill_md && typeof body.skill_md === 'string') {
    fs.writeFileSync(path.join(skillDir, 'SKILL.md'), body.skill_md, 'utf-8');
  }
  if (body.files && typeof body.files === 'object') {
    for (const [fname, fcontent] of Object.entries(body.files)) {
      if (typeof fcontent === 'string' && !fname.includes('..')) {
        const fp = path.join(skillDir, fname);
        ensureDir(path.dirname(fp));
        fs.writeFileSync(fp, fcontent, 'utf-8');
      }
    }
  }
  sendJSON(res, 200, { updated: true, name: params.name });
});

// ─── Router ──────────────────────────────────────────────────────────────────

/**
 * Simple router with :param support.
 * Matches routes registered via route(method, path, handler).
 */
function matchRoute(method, pathname) {
  // Exact match first
  const exact = routes[method + ' ' + pathname];
  if (exact) return { handler: exact, params: {} };

  // Parameterized match
  for (const [key, handler] of Object.entries(routes)) {
    const [rMethod, rPath] = key.split(' ', 2);
    if (rMethod !== method) continue;
    if (!rPath.includes(':')) continue;

    const routeParts = rPath.split('/');
    const pathParts = pathname.split('/');
    if (routeParts.length !== pathParts.length) continue;

    const params = {};
    let match = true;
    for (let i = 0; i < routeParts.length; i++) {
      if (routeParts[i].startsWith(':')) {
        params[routeParts[i].slice(1)] = pathParts[i];
      } else if (routeParts[i] !== pathParts[i]) {
        match = false;
        break;
      }
    }
    if (match) return { handler, params };
  }

  return null;
}

// ─── HTTP Server ─────────────────────────────────────────────────────────────

function createServer() {
  const server = http.createServer(async (req, res) => {
    const method = req.method.toUpperCase();
    const pathname = getPathname(req.url || '/');

    // CORS preflight
    if (method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Session-Id',
        'Access-Control-Max-Age': '86400',
      });
      return res.end();
    }

    logger.debug('HTTP', `${method} ${pathname}`);

    const matched = matchRoute(method, pathname);
    if (!matched) {
      return sendError(res, 404, 'not_found', `No route for ${method} ${pathname}`);
    }

    try {
      await matched.handler(req, res, matched.params, parseQuery(req.url || ''));
    } catch (e) {
      logger.error('HTTP', `Handler error for ${method} ${pathname}`, e.message);
      if (!res.headersSent) {
        sendError(res, 500, 'internal_error', 'Internal server error: ' + e.message);
      }
    }
  });

  server.on('error', (e) => {
    logger.error('Server', 'Server error', e.message);
    if (e.code === 'EADDRINUSE') {
      logger.error('Server', `Port ${config.gateway.port} is already in use!`);
      process.exit(1);
    }
  });

  // Graceful shutdown
  const shutdown = (signal) => {
    logger.info('Server', `Received ${signal}, shutting down...`);
    flushSessions(); // Save all sessions before exit
    server.close(() => {
      logger.info('Server', 'Server closed');
      process.exit(0);
    });
    // Force exit after 5s
    setTimeout(() => process.exit(0), 5000).unref();
  };

  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));

  // Uncaught error handling
  process.on('uncaughtException', (e) => {
    logger.error('Fatal', 'Uncaught exception', e.stack || e.message);
    flushSessions();
  });
  process.on('unhandledRejection', (reason) => {
    logger.error('Fatal', 'Unhandled rejection', String(reason));
  });

  return server;
}

// ─── Backup / Restore System ─────────────────────────────────────────────────

function walkForBackup(dir, base, result = []) {
  if (!fs.existsSync(dir)) return result;
  for (const entry of fs.readdirSync(dir)) {
    if (entry === 'node_modules' || entry === '.git' || entry.startsWith('_tmp_') || entry.endsWith('.apk')) continue;
    const full = path.join(dir, entry);
    const rel = path.relative(base, full).replace(/\\/g, '/');
    try {
      const stat = fs.statSync(full);
      if (stat.isDirectory()) {
        walkForBackup(full, base, result);
      } else if (stat.isFile() && stat.size < 10 * 1024 * 1024) {
        if (entry === 'gateway.log') continue;
        const content = fs.readFileSync(full);
        result.push({
          path: rel,
          size: stat.size,
          modified: stat.mtimeMs,
          content_b64: content.toString('base64'),
        });
      }
    } catch (_) {}
  }
  return result;
}

route('GET', '/api/backup', (req, res) => {
  logger.info('Backup', 'Creating backup...');
  try {
    const files = walkForBackup(BASE_DIR, BASE_DIR);
    const backup = {
      version: 2,
      format: 'openclaw-android-backup',
      created_at: new Date().toISOString(),
      engine_version: VERSION,
      files,
      file_count: files.length,
      total_bytes: files.reduce((sum, f) => sum + f.size, 0),
      checksum: crypto.createHash('sha256').update(JSON.stringify(files.map(f => f.path + ':' + f.size))).digest('hex'),
    };
    logger.info('Backup', `Done: ${files.length} files, ${backup.total_bytes} bytes`);
    const jsonStr = JSON.stringify(backup);
    res.writeHead(200, {
      'Content-Type': 'application/json',
      'Content-Disposition': `attachment; filename="openclaw-backup-${new Date().toISOString().slice(0,10)}.json"`,
      'Content-Length': Buffer.byteLength(jsonStr, 'utf-8'),
    });
    res.end(jsonStr);
  } catch (e) {
    logger.error('Backup', 'Failed: ' + e.message);
    sendError(res, 500, 'backup_failed', e.message);
  }
});

route('POST', '/api/backup/restore', async (req, res) => {
  logger.info('Restore', 'Starting restore...');
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  if (!body || body.format !== 'openclaw-android-backup') {
    return sendError(res, 400, 'invalid_backup', 'Not a valid OpenClaw backup file');
  }
  if (!body.files || !Array.isArray(body.files)) {
    return sendError(res, 400, 'invalid_backup', 'Backup contains no files');
  }
  const expectedChecksum = crypto.createHash('sha256')
    .update(JSON.stringify(body.files.map(f => f.path + ':' + f.size)))
    .digest('hex');
  if (body.checksum && body.checksum !== expectedChecksum) {
    return sendError(res, 400, 'checksum_mismatch', 'Backup integrity check failed');
  }
  const restored = [];
  const errors = [];
  try {
    for (const file of body.files) {
      try {
        const targetPath = path.join(BASE_DIR, file.path);
        const resolved = path.resolve(targetPath);
        const resolvedBase = path.resolve(BASE_DIR);
        if (!resolved.startsWith(resolvedBase)) {
          errors.push({ path: file.path, error: 'Path traversal blocked' });
          continue;
        }
        ensureDir(path.dirname(targetPath));
        fs.writeFileSync(targetPath, Buffer.from(file.content_b64, 'base64'));
        restored.push(file.path);
      } catch (e) {
        errors.push({ path: file.path, error: e.message });
      }
    }
    loadConfig();
    cachedSoulContent = null;
    lastSoulReadTime = 0;
    loadSessions();
    logger.info('Restore', `Done: ${restored.length}/${body.files.length} files, ${errors.length} errors`);
    sendJSON(res, 200, {
      status: 'restored',
      files_restored: restored.length,
      files_total: body.files.length,
      errors: errors.length > 0 ? errors : undefined,
      message: `\u2705 \u6062\u590d\u5b8c\u6210\uff1a${restored.length} \u4e2a\u6587\u4ef6\u5df2\u6062\u590d`,
    });
  } catch (e) {
    logger.error('Restore', 'Failed: ' + e.message);
    sendError(res, 500, 'restore_failed', e.message);
  }
});

// ─── Main Entry ──────────────────────────────────────────────────────────────

function main() {
  console.log('');
  console.log('╔══════════════════════════════════════════╗');
  console.log('║   OpenClaw Android Gateway v' + VERSION + '  ║');
  console.log('║   Pure JS · Zero Dependencies · Android  ║');
  console.log('╚══════════════════════════════════════════╝');
  console.log('');

  // Load config
  loadConfig();
  logger.info('Main', 'Base directory: ' + BASE_DIR);
  logger.info('Main', 'Config path: ' + CONFIG_PATH);

  // --- First-run setup: create preset files if missing ---
  const workspaceDir = path.join(BASE_DIR, 'workspace');
  ensureDir(workspaceDir);
  ensureDir(path.join(workspaceDir, 'memory'));

  // SOUL.md is created by PRESET_FILES above, only create if somehow missing
  if (!fs.existsSync(SOUL_PATH)) {
    safeWriteText(SOUL_PATH, PRESET_FILES[SOUL_PATH] || '# SOUL.md\nYou are a helpful AI assistant.');
    logger.info('Main', 'Created default SOUL.md');
  }

  // MEMORY.md preset
  if (!fs.existsSync(MEMORY_PATH)) {
    safeWriteText(MEMORY_PATH, '# MEMORY.md\n\n> OpenClaw Android \u7aef\u4fa7\u7248\u8bb0\u5fc6\u6587\u4ef6\n\n---\n');
    logger.info('Main', 'Created default MEMORY.md');
  }

  // Load sessions
  loadSessions();

  // Memory status
  const memContent = readMemory();
  logger.info('Main', `Memory file: ${memContent.length > 0 ? memContent.length + ' bytes' : 'empty/not found'}`);

  // Start server
  const server = createServer();
  const port = (config.gateway && config.gateway.port) || 18789;
  const bind = (config.gateway && config.gateway.bind) || '127.0.0.1';

  server.listen(port, bind, () => {
    console.log('');
    logger.info('Main', `🚀 Gateway listening on http://${bind}:${port}`);
    logger.info('Main', `   Health:   GET  http://${bind}:${port}/health`);
    logger.info('Main', `   Status:   GET  http://${bind}:${port}/api/status`);
    logger.info('Main', `   Chat:     POST http://${bind}:${port}/api/chat`);
    logger.info('Main', `   Sessions: GET  http://${bind}:${port}/api/sessions`);
    logger.info('Main', `   Config:   GET  http://${bind}:${port}/api/config`);
    logger.info('Main', `   Memory:   GET  http://${bind}:${port}/api/memory`);
    logger.info('Main', `   Cron:     GET  http://${bind}:${port}/api/cron`);
    logger.info('Main', `   Models:   GET  http://${bind}:${port}/api/models`);
    console.log('');
    logger.info('Main', `Node ${process.version} | PID ${process.pid} | Model: ${config.model}`);
    logger.info('Main', `Engine v${VERSION} | BaseURL: ${(config.providers?.bailian?.base_url || 'N/A')}`);
    logger.info('Main', `UA: openclaw/${VERSION} | Feature: openclaw`);
    logger.info('Main', `Memory: ${JSON.stringify(memoryUsageMB())} MB`);
    logger.info('Main', 'Ready to serve requests ✓');
    console.log('');
  });
}

// --- Workspace 文件管理 ---

// Alias: /api/workspace -> /api/workspace/files (for Android status page)
route('GET', '/api/workspace', (req, res) => {
  const workspaceDir = path.join(BASE_DIR, 'workspace');
  try {
    ensureDir(workspaceDir);
    const walkDir = (dir, base) => {
      const results = [];
      for (const item of fs.readdirSync(dir)) {
        const full = path.join(dir, item);
        const rel = path.relative(base, full);
        const stat = fs.statSync(full);
        if (stat.isDirectory()) {
          results.push(...walkDir(full, base));
        } else {
          results.push(rel);
        }
      }
      return results;
    };
    const files = walkDir(workspaceDir, workspaceDir);
    sendJSON(res, 200, { files, count: files.length, path: workspaceDir });
  } catch (e) {
    sendJSON(res, 200, { files: [], count: 0, path: workspaceDir, error: e.message });
  }
});

route('GET', '/api/workspace/files', (req, res) => {
  // 列出 workspace 目录下的所有文件（递归）
  // 仅限 .md/.txt/.json/.csv 文件
  // 返回 { files: [{ path, size, modified }] }
  
  const workspaceDir = path.join(BASE_DIR, 'workspace');
  
  function walkDirectory(currentPath, basePath) {
    const results = [];
    const items = fs.readdirSync(currentPath);
    
    for (const item of items) {
      const fullPath = path.join(currentPath, item);
      const relativePath = path.relative(basePath, fullPath);
      const stat = fs.statSync(fullPath);
      
      if (stat.isDirectory()) {
        results.push(...walkDirectory(fullPath, basePath));
      } else {
        const ext = path.extname(fullPath).toLowerCase();
        if (['.md', '.txt', '.json', '.csv'].includes(ext)) {
          results.push({
            path: relativePath,
            size: stat.size,
            modified: stat.mtime.toISOString()
          });
        }
      }
    }
    
    return results;
  }
  
  try {
    ensureDir(workspaceDir);
    const files = walkDirectory(workspaceDir, workspaceDir);
    sendJSON(res, 200, { files });
  } catch (error) {
    logger.error('Workspace', 'Error listing files', error.message);
    sendError(res, 500, 'server_error', 'Failed to list workspace files');
  }
});

route('GET', '/api/workspace/files/:path', (req, res, params) => {
  // 读取指定路径的文件内容
  // 路径相对于 workspace 目录
  // 防止路径遍历攻击
  // 返回 { content, path, size }
  
  // 防止路径遍历攻击
  const requestedPath = params.path;
  if (requestedPath.includes('../') || requestedPath.includes('..\\')) {
    return sendError(res, 400, 'invalid_path', 'Path traversal is not allowed');
  }
  
  const filePath = path.join(BASE_DIR, 'workspace', requestedPath);
  const workspaceDir = path.join(BASE_DIR, 'workspace');
  
  // Ensure the file path is within the workspace directory
  const relativePath = path.relative(workspaceDir, filePath);
  if (relativePath.startsWith('..') || path.isAbsolute(relativePath)) {
    return sendError(res, 400, 'invalid_path', 'Invalid file path');
  }
  
  try {
    const ext = path.extname(filePath).toLowerCase();
    if (!['.md', '.txt', '.json', '.csv'].includes(ext)) {
      return sendError(res, 400, 'invalid_file_type', 'Only .md, .txt, .json, .csv files are allowed');
    }
    
    const content = fs.readFileSync(filePath, 'utf-8');
    const stat = fs.statSync(filePath);
    
    sendJSON(res, 200, {
      content,
      path: requestedPath,
      size: stat.size,
      modified: stat.mtime.toISOString()
    });
  } catch (error) {
    if (error.code === 'ENOENT') {
      return sendError(res, 404, 'not_found', 'File not found');
    }
    logger.error('Workspace', 'Error reading file', error.message);
    sendError(res, 500, 'server_error', 'Failed to read file');
  }
});

route('POST', '/api/workspace/files/:path', async (req, res, params) => {
  // 写入文件内容到指定路径
  // 路径相对于 workspace 目录
  // 自动创建中间目录
  // 返回 { saved: true, path, size }
  
  let body;
  try {
    body = await parseBody(req);
  } catch (e) {
    return sendError(res, 400, 'bad_request', e.message);
  }
  
  if (!body.content || typeof body.content !== 'string') {
    return sendError(res, 400, 'missing_content', 'Field "content" is required');
  }
  
  // 防止路径遍历攻击
  const requestedPath = params.path;
  if (requestedPath.includes('../') || requestedPath.includes('..\\')) {
    return sendError(res, 400, 'invalid_path', 'Path traversal is not allowed');
  }
  
  const filePath = path.join(BASE_DIR, 'workspace', requestedPath);
  const workspaceDir = path.join(BASE_DIR, 'workspace');
  
  // Ensure the file path is within the workspace directory
  const relativePath = path.relative(workspaceDir, filePath);
  if (relativePath.startsWith('..') || path.isAbsolute(relativePath)) {
    return sendError(res, 400, 'invalid_path', 'Invalid file path');
  }
  
  try {
    const ext = path.extname(filePath).toLowerCase();
    if (!['.md', '.txt', '.json', '.csv'].includes(ext)) {
      return sendError(res, 400, 'invalid_file_type', 'Only .md, .txt, .json, .csv files are allowed');
    }
    
    // Create directory if it doesn't exist
    ensureDir(path.dirname(filePath));
    
    fs.writeFileSync(filePath, body.content, 'utf-8');
    const stat = fs.statSync(filePath);
    
    sendJSON(res, 200, {
      saved: true,
      path: requestedPath,
      size: stat.size,
      modified: stat.mtime.toISOString()
    });
  } catch (error) {
    logger.error('Workspace', 'Error writing file', error.message);
    sendError(res, 500, 'server_error', 'Failed to write file');
  }
});

// ─── Tool Use Agent System ───────────────────────────────────────────────────

const os = require('os');
const { exec: execCmd, execSync } = require('child_process');

// Maximum agent loop iterations to prevent infinite loops (configurable via openclaw.json)
const DEFAULT_MAX_AGENT_STEPS = 25;
const TOOL_EXEC_TIMEOUT = 30000; // 30s per tool call
const TAVILY_API_KEY = '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';
const BROWSER_BRIDGE_URL = 'http://127.0.0.1:18790';

// ─── Tool Definitions (OpenAI function calling format) ───────────────────────

const TOOL_DEFINITIONS = [
  {
    type: 'function',
    category: 'skill',
    function: {
      name: 'web_search',
      description: '使用 Tavily AI 搜索引擎搜索互联网信息。用于查询新闻、天气、股票、知识等实时信息。',
      parameters: {
        type: 'object',
        properties: {
          query: { type: 'string', description: '搜索查询词' },
          max_results: { type: 'number', description: '最大结果数（默认 5）' },
        },
        required: ['query'],
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'web_fetch',
      description: '抓取指定 URL 的网页内容，返回纯文本。用于读取文章、网页详情。',
      parameters: {
        type: 'object',
        properties: {
          url: { type: 'string', description: '要抓取的 URL' },
          max_chars: { type: 'number', description: '最大返回字符数（默认 5000）' },
        },
        required: ['url'],
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'exec',
      description: '在设备上执行 shell 命令。可以运行任何 Linux/Android 命令。',
      parameters: {
        type: 'object',
        properties: {
          command: { type: 'string', description: 'Shell 命令' },
          timeout: { type: 'number', description: '超时秒数（默认 30）' },
        },
        required: ['command'],
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'read_file',
      description: '读取设备上的文件内容。支持文本文件。',
      parameters: {
        type: 'object',
        properties: {
          path: { type: 'string', description: '文件路径' },
          encoding: { type: 'string', description: '编码（默认 utf-8）' },
        },
        required: ['path'],
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'write_file',
      description: '写入内容到设备上的文件。自动创建目录。',
      parameters: {
        type: 'object',
        properties: {
          path: { type: 'string', description: '文件路径' },
          content: { type: 'string', description: '文件内容' },
        },
        required: ['path', 'content'],
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'list_files',
      description: '列出目录下的文件和子目录。',
      parameters: {
        type: 'object',
        properties: {
          path: { type: 'string', description: '目录路径' },
        },
        required: ['path'],
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'memory_read',
      description: '读取 AI 的长期记忆。基础模式返回全文，向量模式支持语义搜索。使用 query 参数搜索特定信息。',
      parameters: {
        type: 'object',
        properties: {
          query: { type: 'string', description: '搜索关键词（向量模式时进行语义搜索）' },
          limit: { type: 'number', description: '最多返回结果数（默认 5）' },
        },
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'memory_write',
      description: '追加内容到 AI 的长期记忆文件。用于保存重要信息。',
      parameters: {
        type: 'object',
        properties: {
          content: { type: 'string', description: '要记忆的内容' },
        },
        required: ['content'],
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'device_info',
      description: '获取设备系统信息：平台、CPU、内存、运行时间等。',
      parameters: {
        type: 'object',
        properties: {},
      },
    },
  },
  {
    type: 'function',
    category: 'skill',
    function: {
      name: 'get_weather',
      description: '查询天气信息。',
      parameters: {
        type: 'object',
        properties: {
          location: { type: 'string', description: '城市名（英文，如 Shanghai, Beijing）' },
        },
        required: ['location'],
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'browser_navigate',
      description: '在手机浏览器中打开指定 URL。用于浏览网页、登录网站、查看页面。',
      parameters: {
        type: 'object',
        properties: {
          url: { type: 'string', description: '要打开的 URL' },
        },
        required: ['url'],
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'browser_content',
      description: '获取当前浏览器页面的文本内容。在 browser_navigate 之后使用。',
      parameters: {
        type: 'object',
        properties: {
          max_chars: { type: 'number', description: '最大返回字符数（默认 50000）' },
        },
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'browser_eval',
      description: '在当前浏览器页面上执行 JavaScript 代码。可以用来提取数据、点击按钮、填写表单等。',
      parameters: {
        type: 'object',
        properties: {
          script: { type: 'string', description: '要执行的 JavaScript 代码' },
        },
        required: ['script'],
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'browser_screenshot',
      description: '对当前浏览器页面截图。返回 base64 编码的图片。',
      parameters: {
        type: 'object',
        properties: {},
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'browser_click',
      description: '点击浏览器页面上的元素。使用 CSS 选择器定位。',
      parameters: {
        type: 'object',
        properties: {
          selector: { type: 'string', description: 'CSS 选择器（如 #login-btn, .submit, a[href="/"]）' },
        },
        required: ['selector'],
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'browser_type',
      description: '在浏览器页面的输入框中输入文字。',
      parameters: {
        type: 'object',
        properties: {
          selector: { type: 'string', description: 'CSS 选择器（如 input[name="search"], #username）' },
          text: { type: 'string', description: '要输入的文字' },
        },
        required: ['selector', 'text'],
      },
    },
  },
  {
    type: 'function',
    category: 'skill',
    function: {
      name: 'news_summary',
      description: '获取最近 24 小时的热点新闻摘要。自动访问新浪新闻或澎湃新闻，提取头条新闻。',
      parameters: {
        type: 'object',
        properties: {
          source: { type: 'string', description: '新闻源（sina|thepaper|default）', enum: ['sina', 'thepaper', 'default'] },
        },
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'skill_search',
      description: '在 ClawHub 技能市场搜索可安装的 AI 技能。当用户想要安装新技能或查找某类功能时使用。返回技能名称、描述和版本信息。',
      parameters: {
        type: 'object',
        properties: {
          query: { type: 'string', description: '搜索关键词，如 "weather"、"stock"、"股票"' },
          limit: { type: 'number', description: '最多返回结果数（默认 5）' },
        },
        required: ['query'],
      },
    },
  },
  {
    type: 'function',
    category: 'core',
    function: {
      name: 'skill_install',
      description: '从 ClawHub 市场安装技能。在调用前必须先用 skill_search 找到技能的 slug，并告知用户待安装的技能信息，得到用户确认后再调用。',
      parameters: {
        type: 'object',
        properties: {
          slug: { type: 'string', description: '技能的 slug 标识符，如 "weather"、"stock-api"' },
          force: { type: 'boolean', description: '是否强制重新安装（覆盖已有）' },
        },
        required: ['slug'],
      },
    },
  },
];

// ─── Tool Executors ──────────────────────────────────────────────────────────

function httpRequest(urlStr, options = {}) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(urlStr);
    const mod = parsed.protocol === 'https:' ? https : http;
    const reqOpts = {
      hostname: parsed.hostname,
      port: parsed.port || (parsed.protocol === 'https:' ? 443 : 80),
      path: parsed.pathname + (parsed.search || ''),
      method: options.method || 'GET',
      headers: {
        'User-Agent': 'openclaw/' + VERSION,
        ...options.headers,
      },
      timeout: options.timeout || 30000,
    };
    const req = mod.request(reqOpts, (res) => {
      // Handle redirects (3xx)
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        httpRequest(res.headers.location, options).then(resolve).catch(reject);
        return;
      }
      if (options.raw) {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const rawBody = Buffer.concat(chunks);
          resolve({ status: res.statusCode, body: rawBody.toString('binary'), rawBody, headers: res.headers });
        });
      } else {
        let body = '';
        res.on('data', (c) => body += c);
        res.on('end', () => resolve({ status: res.statusCode, body, headers: res.headers }));
      }
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('Request timeout')); });
    if (options.body) req.write(options.body);
    req.end();
  });
}

function stripHtml(html) {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<style[\s\S]*?<\/style>/gi, '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/\s+/g, ' ')
    .trim();
}

async function executeTool(name, args) {
  logger.info('Tool', `Executing: ${name}(${JSON.stringify(args).slice(0, 200)})`);
  
  try {
    switch (name) {
      case 'web_search': {
        const query = args.query;
        const maxResults = args.max_results || 5;
        // Tavily requires API key in body
        const reqBody = JSON.stringify({
          api_key: TAVILY_API_KEY,
          query,
          max_results: maxResults,
          search_depth: 'basic',
          include_answer: true,
        });
        try {
          const resp = await httpRequest('https://api.tavily.com/search', {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'Content-Length': Buffer.byteLength(reqBody),
            },
            body: reqBody,
            timeout: 20000,
          });
          const data = JSON.parse(resp.body);
          if (data.detail && data.detail.includes('API key')) {
            return { error: 'Tavily API key invalid', detail: data.detail };
          }
          const results = (data.results || []).map(r => ({
            title: r.title,
            url: r.url,
            content: (r.content || '').slice(0, 500),
          }));
          return { answer: data.answer || '', results, query };
        } catch (e) {
          // Fallback: use web_fetch on a known news site
          logger.warn('Tool', `Tavily failed (${e.message}), falling back to news site`);
          return {
            error: `Tavily search failed: ${e.message}`,
            suggestion: 'Try browser_navigate to a news site instead',
          };
        }
      }

      case 'web_fetch': {
        const maxChars = args.max_chars || 5000;
        const resp = await httpRequest(args.url, { timeout: 15000 });
        // Follow redirects (simple)
        if (resp.status >= 300 && resp.status < 400 && resp.headers.location) {
          const resp2 = await httpRequest(resp.headers.location, { timeout: 15000 });
          return { content: stripHtml(resp2.body).slice(0, maxChars), url: args.url, chars: Math.min(resp2.body.length, maxChars) };
        }
        return { content: stripHtml(resp.body).slice(0, maxChars), url: args.url, chars: Math.min(resp.body.length, maxChars) };
      }

      case 'exec': {
        const timeout = (args.timeout || 30) * 1000;
        // Security: block destructive commands
        const blocked = ['rm -rf /', 'mkfs', 'dd if=/dev/zero'];
        if (blocked.some(b => args.command.includes(b))) {
          return { error: 'Command blocked for safety', command: args.command };
        }
        return new Promise((resolve) => {
          execCmd(args.command, {
            timeout,
            maxBuffer: 100 * 1024,
            cwd: args.cwd || BASE_DIR,
          }, (err, stdout, stderr) => {
            resolve({
              stdout: (stdout || '').slice(0, 10000),
              stderr: (stderr || '').slice(0, 5000),
              exit_code: err ? (err.code || 1) : 0,
            });
          });
        });
      }

      case 'read_file': {
        if (!args.path) return { error: 'path is required' };
        const encoding = args.encoding || 'utf-8';
        try {
          const content = fs.readFileSync(args.path, encoding);
          return { content: content.slice(0, 50000), size: content.length, path: args.path };
        } catch (e) {
          return { error: e.message, path: args.path };
        }
      }

      case 'write_file': {
        if (!args.path) return { error: 'path is required' };
        try {
          ensureDir(path.dirname(args.path));
          fs.writeFileSync(args.path, args.content || '', 'utf-8');
          return { written: true, path: args.path, size: (args.content || '').length };
        } catch (e) {
          return { error: e.message, path: args.path };
        }
      }

      case 'list_files': {
        const dirPath = args.path || BASE_DIR;
        const entries = fs.readdirSync(dirPath, { withFileTypes: true });
        const files = entries.map(e => ({
          name: e.name,
          type: e.isDirectory() ? 'directory' : 'file',
          size: e.isDirectory() ? null : (() => { try { return fs.statSync(path.join(dirPath, e.name)).size; } catch(_) { return null; } })(),
        }));
        return { path: dirPath, files };
      }

      case 'memory_read': {
        if (config.memory_mode === 'vector' && args.query) {
          // Vector memory search using embedding model
          try {
            const searchResults = await vectorMemorySearch(args.query, args.limit || 5);
            return { mode: 'vector', query: args.query, results: searchResults };
          } catch (e) {
            logger.warn('Memory', `Vector search failed, falling back to basic: ${e.message}`);
            const content = readMemory();
            return { mode: 'basic_fallback', content, size: content.length, error: e.message };
          }
        }
        const content = readMemory();
        return { mode: 'basic', content, size: content.length };
      }

      case 'memory_write': {
        appendMemory(args.content);
        if (config.memory_mode === 'vector') {
          // Also store embedding for vector search
          try {
            await vectorMemoryStore(args.content);
          } catch (e) {
            logger.warn('Memory', `Vector store failed: ${e.message}`);
          }
        }
        return { appended: true, content_length: args.content.length, mode: config.memory_mode || 'basic' };
      }

      case 'device_info': {
        return {
          platform: os.platform(),
          arch: os.arch(),
          node_version: process.version,
          hostname: os.hostname(),
          uptime_seconds: Math.floor(os.uptime()),
          memory: {
            total_mb: Math.round(os.totalmem() / 1048576),
            free_mb: Math.round(os.freemem() / 1048576),
          },
          cpus: os.cpus().length,
          load_avg: os.loadavg(),
        };
      }

      case 'get_weather': {
        const loc = encodeURIComponent(args.location);
        const resp = await httpRequest(`https://wttr.in/${loc}?format=j1`, { timeout: 10000 });
        const data = JSON.parse(resp.body);
        const current = data.current_condition && data.current_condition[0];
        if (!current) return { error: 'Weather data unavailable' };
        return {
          location: args.location,
          temperature_c: current.temp_C,
          feels_like_c: current.FeelsLikeC,
          condition: current.weatherDesc && current.weatherDesc[0] && current.weatherDesc[0].value,
          humidity: current.humidity + '%',
          wind_kmph: current.windspeedKmph,
          wind_dir: current.winddir16Point,
          visibility_km: current.visibility,
        };
      }

      // ─── Browser Tools (via WebView Bridge on port 18790) ───

      case 'browser_navigate': {
        const resp = await httpRequest(BROWSER_BRIDGE_URL + '/browser/navigate', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url: args.url, timeout_ms: args.timeout_ms || 30000 }),
          timeout: 35000,
        });
        return JSON.parse(resp.body);
      }

      case 'browser_content': {
        const maxChars = args.max_chars || 50000;
        const resp = await httpRequest(BROWSER_BRIDGE_URL + '/browser/content?max_chars=' + maxChars, { timeout: 15000 });
        return JSON.parse(resp.body);
      }

      case 'browser_eval': {
        const resp = await httpRequest(BROWSER_BRIDGE_URL + '/browser/eval', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ script: args.script }),
          timeout: 15000,
        });
        return JSON.parse(resp.body);
      }

      case 'browser_screenshot': {
        const resp = await httpRequest(BROWSER_BRIDGE_URL + '/browser/screenshot', { timeout: 15000 });
        const data = JSON.parse(resp.body);
        // Don't return full base64 to LLM, just a summary
        return {
          captured: !!data.image_base64,
          format: data.format,
          url: data.url,
          size_bytes: data.image_base64 ? Math.round(data.image_base64.length * 0.75) : 0,
        };
      }

      case 'browser_click': {
        const resp = await httpRequest(BROWSER_BRIDGE_URL + '/browser/click', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ selector: args.selector }),
          timeout: 10000,
        });
        return JSON.parse(resp.body);
      }

      case 'browser_type': {
        const resp = await httpRequest(BROWSER_BRIDGE_URL + '/browser/type', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ selector: args.selector, text: args.text }),
          timeout: 10000,
        });
        return JSON.parse(resp.body);
      }

      case 'news_summary': {
        // Use browser to fetch news from Chinese sites
        const source = args.source || 'default';
        const urls = {
          sina: 'https://news.sina.cn/',
          thepaper: 'https://m.thepaper.cn/',
          default: 'https://m.thepaper.cn/',
        };
        const url = urls[source] || urls.default;
        
        try {
          // Navigate
          const navResult = await httpRequest(BROWSER_BRIDGE_URL + '/browser/navigate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ url, timeout_ms: 25000 }),
            timeout: 30000,
          });
          const navData = JSON.parse(navResult.body);
          if (navData.error) {
            return { error: `Failed to load news: ${navData.error}`, url };
          }
          
          // Give page time to render JS content
          await new Promise(resolve => setTimeout(resolve, 2000));
          
          // Extract content
          const contentResp = await httpRequest(BROWSER_BRIDGE_URL + '/browser/content?max_chars=30000', { timeout: 10000 });
          const contentData = JSON.parse(contentResp.body);
          const content = contentData.content || '';
          
          // Extract headlines (simple pattern matching)
          const lines = content.split('\n').map(l => l.trim()).filter(l => l.length > 10 && l.length < 200);
          const headlines = [];
          for (const line of lines) {
            if (/\d+/.test(line) || /[\u4e00-\u9fa5]{4,}/.test(line)) {
              headlines.push(line);
            }
            if (headlines.length >= 15) break;
          }
          
          return {
            source: source === 'sina' ? '新浪新闻' : '澎湃新闻',
            url,
            headlines: headlines.slice(0, 10),
            raw_content_length: content.length,
            fetched_at: new Date().toISOString(),
          };
        } catch (e) {
          return { error: `News fetch failed: ${e.message}`, suggestion: 'Try again or use browser_navigate directly' };
        }
      }

      // ─── Skill Management ────────────────────────────────────────────

      case 'skill_search': {
        const query = args.query;
        const limit = args.limit || 5;
        const CLAWHUB_REGISTRY = 'https://clawhub.ai';
        try {
          const url = `${CLAWHUB_REGISTRY}/api/v1/search?q=${encodeURIComponent(query)}&limit=${limit}`;
          const resp = await httpRequest(url, { timeout: 15000 });
          const data = JSON.parse(resp.body);
          const results = (data.results || []).map(r => ({
            slug: r.slug || 'unknown',
            name: r.displayName || r.slug || 'unknown',
            description: (r.description || '').slice(0, 200),
            version: r.version || '',
            score: r.score ? r.score.toFixed(3) : '',
            author: r.author || '',
          }));
          return {
            query,
            count: results.length,
            results,
            hint: '找到以上技能，请告诉用户搜索结果，让用户确认要安装哪个。用户确认后再调用 skill_install。',
          };
        } catch (e) {
          return { error: `ClawHub search failed: ${e.message}` };
        }
      }

      case 'skill_install': {
        const slug = args.slug;
        const force = args.force || false;
        const CLAWHUB_REGISTRY = 'https://clawhub.ai';
        const skillsDir = path.join(BASE_DIR, 'skills');
        const targetDir = path.join(skillsDir, slug);

        try {
          // Check if already installed
          if (!force && fs.existsSync(targetDir)) {
            return {
              status: 'already_installed',
              slug,
              path: targetDir,
              message: `${slug} 已经安装。如需重新安装，请使用 force: true`,
            };
          }

          // 1. Get skill metadata
          logger.info('Skill', `Fetching metadata for ${slug}`);
          const metaResp = await httpRequest(`${CLAWHUB_REGISTRY}/api/v1/skills/${encodeURIComponent(slug)}`, { timeout: 15000 });
          const meta = JSON.parse(metaResp.body);

          if (meta.moderation && meta.moderation.isMalwareBlocked) {
            return { error: `${slug} 被标记为恶意技能，无法安装` };
          }

          const version = meta.latestVersion?.version;
          if (!version) {
            return { error: `无法解析 ${slug} 的最新版本` };
          }

          // 2. Download zip
          logger.info('Skill', `Downloading ${slug}@${version}`);
          const dlUrl = `${CLAWHUB_REGISTRY}/api/v1/download?slug=${encodeURIComponent(slug)}&version=${encodeURIComponent(version)}`;
          const dlResp = await httpRequest(dlUrl, { timeout: 30000, raw: true });

          // 3. Extract zip
          ensureDir(skillsDir);
          if (force && fs.existsSync(targetDir)) {
            fs.rmSync(targetDir, { recursive: true, force: true });
          }
          ensureDir(targetDir);

          // Simple zip extraction using Node.js built-in (or buffer-based)
          const tmpZip = path.join(BASE_DIR, `_tmp_skill_${slug}.zip`);
          fs.writeFileSync(tmpZip, dlResp.rawBody || Buffer.from(dlResp.body, 'binary'));

          // Use unzip via child_process
          const { execSync } = require('child_process');
          try {
            // Try unzip (Android/Linux)
            execSync(`unzip -o "${tmpZip}" -d "${targetDir}"`, { timeout: 30000, stdio: 'pipe' });
          } catch {
            try {
              // Fallback: tar (some Android)
              execSync(`tar -xf "${tmpZip}" -C "${targetDir}"`, { timeout: 30000, stdio: 'pipe' });
            } catch (e2) {
              // Clean up
              try { fs.unlinkSync(tmpZip); } catch {}
              return { error: `解压失败: ${e2.message}。设备可能缺少 unzip 命令。` };
            }
          }
          try { fs.unlinkSync(tmpZip); } catch {}

          // 4. Write origin metadata
          const originData = {
            version: 1,
            registry: CLAWHUB_REGISTRY,
            slug,
            installedVersion: version,
            installedAt: Date.now(),
          };
          fs.writeFileSync(path.join(targetDir, '.clawhub-origin.json'), JSON.stringify(originData, null, 2));

          // 5. Check for SKILL.md
          const hasSkillMd = fs.existsSync(path.join(targetDir, 'SKILL.md'));
          const files = fs.readdirSync(targetDir);

          logger.info('Skill', `Installed ${slug}@${version} to ${targetDir}`);
          return {
            status: 'installed',
            slug,
            version,
            name: meta.displayName || meta.name || slug,
            description: (meta.description || '').slice(0, 200),
            path: targetDir,
            files: files.slice(0, 20),
            has_skill_md: hasSkillMd,
            message: `✅ 已成功安装 ${meta.displayName || slug} v${version}`,
          };
        } catch (e) {
          logger.error('Skill', `Install failed: ${e.message}`);
          return { error: `安装失败: ${e.message}` };
        }
      }

      default:
        return { error: `Unknown tool: ${name}` };
    }
  } catch (e) {
    logger.error('Tool', `Tool ${name} failed: ${e.message}`);
    return { error: e.message };
  }
}

// ─── Agent Loop ──────────────────────────────────────────────────────────────

/**
 * The core Agent Loop: sends messages to LLM with tool definitions,
 * if LLM responds with tool_calls, execute them and loop back.
 * Continues until LLM gives a final text response or max steps reached.
 */
async function agentChat(opts) {
  const provInfo = resolveProvider(opts.model);
  if (!provInfo) return { error: 'unknown_model', message: `Cannot resolve provider for model: ${opts.model}` };
  if (!provInfo.api_key) return { error: 'missing_api_key', message: `API key not configured for provider: ${provInfo.provider}` };

  const messages = [];
  if (opts.system_prompt) {
    messages.push({ role: 'system', content: opts.system_prompt + '\n\n你有以下工具可以使用。当需要搜索信息、执行命令、读写文件时，主动调用工具。不要猜测答案，用工具获取真实数据。\n\n你支持查看用户发送的图片。当用户发送图片时，图片会以 base64 格式附加在消息中。\n\n## 技能安装流程\n当用户要求安装技能时：\n1. 先用 skill_search 搜索相关技能\n2. 向用户展示搜索结果（名称、描述、版本）\n3. 等待用户确认后，再调用 skill_install 安装\n4. 安装完成后告诉用户结果\n绝对不要在用户未确认的情况下直接安装。' });
  }
  if (Array.isArray(opts.messages)) {
    for (const m of opts.messages) {
      const msg = { role: m.role, content: m.content };
      // Handle image attachments
      if (m.image_base64 && m.role === 'user') {
        msg.content = [
          { type: 'text', text: m.content || '请分析这张图片' },
          { type: 'image_url', image_url: { url: `data:image/jpeg;base64,${m.image_base64}` } }
        ];
      }
      messages.push(msg);
    }
  }

  const baseUrl = provInfo.base_url.replace(/\/+$/, '');
  let steps = 0;
  const toolLog = [];
  const maxSteps = (config.max_agent_steps && Number.isInteger(config.max_agent_steps) && config.max_agent_steps > 0)
    ? config.max_agent_steps : DEFAULT_MAX_AGENT_STEPS;

  while (steps < maxSteps) {
    steps++;
    logger.info('Agent', `Step ${steps}/${maxSteps}, ${messages.length} messages`);

    // Truncate context if needed
    const truncatedMessages = truncateMessages(messages, MAX_CONTEXT_TOKENS);

    // Call LLM with tools — strip non-standard fields (category) before sending to API
    const toolsForApi = TOOL_DEFINITIONS.map(t => ({ type: t.type, function: t.function }));
    const reqBody = JSON.stringify({
      model: provInfo.model,
      messages: truncatedMessages,
      tools: toolsForApi,
      tool_choice: 'auto',
      temperature: opts.temperature !== undefined ? opts.temperature : 0.7,
      max_tokens: opts.max_tokens || 4096,
      stream: false,
    });

    let llmResponse;
    try {
      const resp = await httpRequest(baseUrl + '/chat/completions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer ' + provInfo.api_key,
          'User-Agent': 'openclaw/' + VERSION,
          'X-KILOCODE-FEATURE': 'openclaw',
          'Content-Length': Buffer.byteLength(reqBody),
        },
        timeout: 120000,
        body: reqBody,
      });
      llmResponse = JSON.parse(resp.body);
      if (resp.status !== 200) {
        logger.error('Agent', `LLM error: HTTP ${resp.status}`, resp.body.slice(0, 500));
        return { error: 'llm_error', status: resp.status, detail: llmResponse };
      }
    } catch (e) {
      logger.error('Agent', `LLM request failed: ${e.message}`);
      return { error: 'request_error', message: e.message };
    }

    const choice = llmResponse.choices && llmResponse.choices[0];
    if (!choice) {
      return { error: 'no_choice', detail: llmResponse };
    }

    const assistantMsg = choice.message;

    // Check if LLM wants to call tools
    if (assistantMsg.tool_calls && assistantMsg.tool_calls.length > 0) {
      // Add assistant message with tool_calls to history
      messages.push({
        role: 'assistant',
        content: assistantMsg.content || null,
        tool_calls: assistantMsg.tool_calls,
      });

      // Execute each tool call
      for (const tc of assistantMsg.tool_calls) {
        const funcName = tc.function.name;
        let funcArgs = {};
        try {
          funcArgs = JSON.parse(tc.function.arguments || '{}');
        } catch (_) {
          funcArgs = {};
        }

        logger.info('Agent', `Tool call: ${funcName}(${JSON.stringify(funcArgs).slice(0, 100)})`);
        const toolResult = await executeTool(funcName, funcArgs);
        const resultStr = JSON.stringify(toolResult).slice(0, 10000);
        const toolDef = TOOL_DEFINITIONS.find(t => t.function.name === funcName);
        toolLog.push({ step: steps, tool: funcName, category: (toolDef && toolDef.category) || 'core', args: funcArgs, result_preview: resultStr.slice(0, 200) });

        // Add tool result to messages
        messages.push({
          role: 'tool',
          tool_call_id: tc.id,
          content: resultStr,
        });
      }

      // Continue the loop — LLM will see tool results
      continue;
    }

    // No tool calls — LLM gave a final response
    const content = assistantMsg.content || '';
    // Handle reasoning_content (thinking)
    const reasoning = assistantMsg.reasoning_content || '';
    
    logger.info('Agent', `Final response: ${content.length} chars, ${steps} steps, ${toolLog.length} tool calls`);
    return {
      content,
      reasoning,
      model: llmResponse.model || provInfo.model,
      usage: llmResponse.usage,
      steps,
      tool_log: toolLog,
    };
  }

  // Max steps reached
  logger.warn('Agent', `Max steps (${maxSteps}) reached`);
  return {
    content: '⚠️ 已达到最大工具调用次数限制，以下是目前的结果。',
    steps,
    tool_log: toolLog,
    truncated: true,
  };
}

// ─── Agent Chat Stream (SSE + tool loop) ─────────────────────────────────────
async function agentChatStream(opts, res) {
  const provInfo = resolveProvider(opts.model);
  if (!provInfo) {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' });
    res.write('data: ' + JSON.stringify({ error: 'unknown_model' }) + '\n\n');
    res.end(); return;
  }
  if (!provInfo.api_key) {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' });
    res.write('data: ' + JSON.stringify({ error: 'missing_api_key' }) + '\n\n');
    res.end(); return;
  }

  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' });

  const messages = [];
  if (opts.system_prompt) {
    messages.push({ role: 'system', content: opts.system_prompt + '\n\n你有以下工具可以使用。当需要搜索信息、执行命令、读写文件时，主动调用工具。不要猜测答案，用工具获取真实数据。' });
  }
  if (Array.isArray(opts.messages)) {
    for (const m of opts.messages) {
      const msg = { role: m.role, content: m.content };
      if (m.image_base64 && m.role === 'user') {
        msg.content = [
          { type: 'text', text: m.content || '请分析这张图片' },
          { type: 'image_url', image_url: { url: `data:image/jpeg;base64,${m.image_base64}` } }
        ];
      }
      messages.push(msg);
    }
  }

  const baseUrl = provInfo.base_url.replace(/\/+$/, '');
  let steps = 0;
  const toolLog = [];
  const maxSteps = (config.max_agent_steps && Number.isInteger(config.max_agent_steps) && config.max_agent_steps > 0)
    ? config.max_agent_steps : DEFAULT_MAX_AGENT_STEPS;

  while (steps < maxSteps) {
    steps++;
    logger.info('AgentStream', `Step ${steps}/${maxSteps}`);

    // Truncate context if needed
    const truncatedMessages = truncateMessages(messages, MAX_CONTEXT_TOKENS);

    // Try streaming from LLM
    const toolsForApi = TOOL_DEFINITIONS.map(t => ({ type: t.type, function: t.function }));
    const reqBody = JSON.stringify({
      model: provInfo.model,
      messages: truncatedMessages,
      tools: toolsForApi,
      tool_choice: 'auto',
      temperature: opts.temperature !== undefined ? opts.temperature : 0.7,
      max_tokens: opts.max_tokens || 4096,
      stream: true,
    });

    let llmResult;
    try {
      llmResult = await streamLLMWithTools(baseUrl, provInfo.api_key, reqBody, res);
    } catch (e) {
      res.write('data: ' + JSON.stringify({ error: e.message }) + '\n\n');
      res.end(); return;
    }

    if (llmResult.tool_calls && llmResult.tool_calls.length > 0) {
      // Tell client about tool calls
      for (const tc of llmResult.tool_calls) {
        res.write('event: tool_call\ndata: ' + JSON.stringify({ name: tc.function.name, id: tc.id }) + '\n\n');
      }

      // Add assistant message with tool_calls
      messages.push({
        role: 'assistant',
        content: llmResult.content || null,
        tool_calls: llmResult.tool_calls,
      });

      // Execute tools
      for (const tc of llmResult.tool_calls) {
        const funcName = tc.function.name;
        let funcArgs = {};
        try { funcArgs = JSON.parse(tc.function.arguments || '{}'); } catch (_) {}
        logger.info('AgentStream', `Tool: ${funcName}`);
        const toolResult = await executeTool(funcName, funcArgs);
        const resultStr = JSON.stringify(toolResult).slice(0, 10000);
        toolLog.push({ step: steps, tool: funcName, args: funcArgs, result_preview: resultStr.slice(0, 200) });

        messages.push({ role: 'tool', tool_call_id: tc.id, content: resultStr });
        res.write('event: tool_result\ndata: ' + JSON.stringify({ name: funcName, preview: resultStr.slice(0, 300) }) + '\n\n');
      }
      continue; // Next loop iteration
    }

    // No tool calls — done
    if (opts.session_id) {
      addMessage(opts.session_id, 'assistant', llmResult.content || '');
    }
    res.write('event: done\ndata: ' + JSON.stringify({
      session_id: opts.session_id || '',
      model: provInfo.model,
      steps,
      tool_log: toolLog,
    }) + '\n\n');
    res.end();
    return;
  }

  // Max steps
  res.write('event: done\ndata: ' + JSON.stringify({ session_id: opts.session_id || '', steps, tool_log: toolLog, truncated: true }) + '\n\n');
  res.end();
}

// Stream LLM response, accumulate content + detect tool_calls
async function streamLLMWithTools(baseUrl, apiKey, reqBody, clientRes) {
  return new Promise((resolve, reject) => {
    const url = new URL(baseUrl + '/chat/completions');
    const mod = url.protocol === 'https:' ? require('https') : require('http');
    const req = mod.request({
      hostname: url.hostname,
      port: url.port || (url.protocol === 'https:' ? 443 : 80),
      path: url.pathname,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + apiKey,
        'User-Agent': 'openclaw/' + VERSION,
        'Content-Length': Buffer.byteLength(reqBody),
      },
      timeout: 120000,
    }, (resp) => {
      if (resp.statusCode !== 200) {
        let body = '';
        resp.on('data', c => body += c);
        resp.on('end', () => reject(new Error(`LLM HTTP ${resp.statusCode}: ${body.slice(0, 200)}`)));
        return;
      }
      let buffer = '';
      let content = '';
      let toolCalls = [];
      let reasoning = '';

      resp.on('data', (chunk) => {
        buffer += chunk.toString();
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        for (const line of lines) {
          if (!line.startsWith('data: ')) continue;
          const data = line.slice(6).trim();
          if (data === '[DONE]') continue;
          try {
            const parsed = JSON.parse(data);
            const delta = parsed.choices && parsed.choices[0] && parsed.choices[0].delta;
            if (!delta) continue;

            // Content streaming
            if (delta.content) {
              content += delta.content;
              clientRes.write('data: ' + JSON.stringify({ content: delta.content }) + '\n\n');
            }
            // Reasoning
            if (delta.reasoning_content) {
              reasoning += delta.reasoning_content;
            }
            // Tool calls accumulation
            if (delta.tool_calls) {
              for (const tc of delta.tool_calls) {
                const idx = tc.index || 0;
                if (!toolCalls[idx]) {
                  toolCalls[idx] = { id: tc.id || '', function: { name: '', arguments: '' } };
                }
                if (tc.id) toolCalls[idx].id = tc.id;
                if (tc.function) {
                  if (tc.function.name) toolCalls[idx].function.name += tc.function.name;
                  if (tc.function.arguments) toolCalls[idx].function.arguments += tc.function.arguments;
                }
              }
            }
          } catch (_) {}
        }
      });

      resp.on('end', () => {
        resolve({ content, reasoning, tool_calls: toolCalls.length > 0 ? toolCalls : null });
      });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('LLM timeout')); });
    req.write(reqBody);
    req.end();
  });
}

// ─── Agent Chat Route ────────────────────────────────────────────────────────

route('POST', '/api/agent/chat', async (req, res) => {
  let body;
  try {
    body = await parseBody(req);
  } catch (e) {
    return sendError(res, 400, 'bad_request', e.message);
  }

  const message = body.message || body.content;
  if (!message || typeof message !== 'string') {
    return sendError(res, 400, 'missing_message', 'Field "message" is required');
  }

  // Resolve or create session
  let sessionId = body.session_id;
  let sess;
  if (sessionId) {
    sess = getSession(sessionId);
    if (!sess) {
      return sendError(res, 404, 'session_not_found', 'Session not found: ' + sessionId);
    }
  } else {
    sess = createSession({ model: body.model, title: body.title });
    sessionId = sess.id;
  }

  // Add user message (with optional image)
  const imageBase64_agent = body.image_base64 || null;
  addMessage(sessionId, 'user', message, imageBase64_agent ? { image_base64: imageBase64_agent } : undefined);

  const model = body.model || sess.model || config.model;
  const wantStream = body.stream === true;

  if (wantStream) {
    // SSE stream with tool loop
    await agentChatStream({
      model,
      messages: sess.messages,
      system_prompt: buildSystemPrompt(config.system_prompt, sess.system_prompt),
      session_id: sessionId,
      temperature: body.temperature,
      max_tokens: body.max_tokens,
    }, res);
    return;
  }

  // Non-stream (original behavior)
  // Keep-alive: send space every 15s to prevent client/proxy timeout during long agent runs
  const keepAlive = setInterval(() => {
    try { if (!res.writableEnded) res.write(' '); } catch (_) {}
  }, 15000);

  try {
    const result = await agentChat({
      model,
      messages: sess.messages,
      system_prompt: buildSystemPrompt(config.system_prompt, sess.system_prompt),
      temperature: body.temperature,
      max_tokens: body.max_tokens,
    });

    clearInterval(keepAlive);

    if (result.error) {
      // Try fallback models
      const fallbacks = getFallbackModels(model);
      for (const fb of fallbacks) {
        logger.info('Agent', `Primary model failed, trying fallback: ${fb.model} (${fb.provider})`);
        const fbResult = await agentChat({
          model: fb.model,
          messages: sess.messages,
          system_prompt: buildSystemPrompt(config.system_prompt, sess.system_prompt),
          temperature: body.temperature,
          max_tokens: body.max_tokens,
        });
        if (!fbResult.error) {
          clearInterval(keepAlive);
          addMessage(sessionId, 'assistant', fbResult.content);
          return sendJSON(res, 200, {
            session_id: sessionId,
            model: fbResult.model || fb.model,
            content: fbResult.content,
            reasoning: fbResult.reasoning || '',
            usage: fbResult.usage,
            steps: fbResult.steps,
            tool_log: fbResult.tool_log,
            fallback: true,
            original_model: model,
          });
        }
      }
      clearInterval(keepAlive);
      return sendJSON(res, 502, { error: result.error, message: result.message, detail: result.detail, session_id: sessionId });
    }

    // Save assistant response to session
    addMessage(sessionId, 'assistant', result.content);

    sendJSON(res, 200, {
      session_id: sessionId,
      model: result.model || model,
      content: result.content,
      reasoning: result.reasoning || '',
      usage: result.usage,
      steps: result.steps,
      tool_log: result.tool_log,
    });
  } catch (e) {
    clearInterval(keepAlive);
    logger.error('Agent', `Agent chat error: ${e.message}`);
    sendError(res, 500, 'agent_error', e.message);
  }
});

// ─── Tool API Endpoints (direct tool calls from UI) ──────────────────────────

route('GET', '/api/tools', (req, res) => {
  sendJSON(res, 200, {
    tools: TOOL_DEFINITIONS.map(t => ({
      name: t.function.name,
      description: t.function.description,
      category: t.category || 'core',
    })),
    count: TOOL_DEFINITIONS.length,
  });
});

route('POST', '/api/tools/execute', async (req, res) => {
  let body;
  try { body = await parseBody(req); } catch (e) { return sendError(res, 400, 'bad_request', e.message); }
  const name = body.tool || body.name;
  const args = body.args || body.arguments || {};
  if (!name) return sendError(res, 400, 'missing_tool', 'Field "tool" is required');
  
  try {
    const result = await executeTool(name, args);
    sendJSON(res, 200, { tool: name, result });
  } catch (e) {
    sendError(res, 500, 'tool_error', e.message);
  }
});

// Run!
main();
