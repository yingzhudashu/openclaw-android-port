#!/usr/bin/env node
/**
 * OpenClaw Android Engine — Full Engine Packaging Script
 * 
 * 策略：将已编译的 OpenClaw dist/ 打包成 Android 可用的形式
 * 
 * 不做单文件 bundle（OpenClaw 的 chunk 结构太复杂），
 * 而是创建一个包含所有必要文件的 zip 包：
 *   1. OpenClaw dist/ 中的所有 JS 文件（已编译）
 *   2. 必要的 node_modules（纯 JS 依赖）
 *   3. Android 专用入口脚本
 *   4. 原生模块 stub
 */

import { execSync } from "node:child_process";
import { 
  existsSync, mkdirSync, copyFileSync, readdirSync, 
  statSync, writeFileSync, readFileSync, createWriteStream
} from "node:fs";
import { join, resolve, relative, basename, extname } from "node:path";
import { createGzip } from "node:zlib";
import { pipeline } from "node:stream/promises";
import archiver from "archiver";

const ROOT = resolve(import.meta.dirname, "..");
const OPENCLAW_NPM = resolve(process.env.OPENCLAW_NPM_DIR || 
  join(process.env.APPDATA || "", "npm", "node_modules", "openclaw"));
const OUTPUT_DIR = join(ROOT, "dist", "android-full");
const OUTPUT_ZIP = join(ROOT, "dist", "openclaw-android-engine.zip");

console.log("📦 OpenClaw Android Full Engine Packager");
console.log(`   OpenClaw source: ${OPENCLAW_NPM}`);
console.log(`   Output dir: ${OUTPUT_DIR}`);

// Verify source exists
if (!existsSync(join(OPENCLAW_NPM, "dist", "entry.js"))) {
  console.error("❌ OpenClaw dist/entry.js not found at:", OPENCLAW_NPM);
  console.error("   Set OPENCLAW_NPM_DIR to the openclaw npm package directory");
  process.exit(1);
}

// Clean output
if (existsSync(OUTPUT_DIR)) {
  execSync(`rm -rf "${OUTPUT_DIR}"`, { stdio: "ignore" }).toString?.();
}
mkdirSync(OUTPUT_DIR, { recursive: true });

// === Step 1: Create Android entry wrapper ===
console.log("\n🔧 Step 1: Creating Android entry wrapper...");

const androidEntry = `#!/usr/bin/env node
/**
 * OpenClaw Android Engine Entry Point
 * 
 * This wraps the standard OpenClaw entry to:
 * 1. Set Android-specific environment variables
 * 2. Skip incompatible features (browser, PTY)
 * 3. Start gateway in local-only mode
 */

import process from "node:process";
import path from "node:path";
import { mkdirSync } from "node:fs";

// Android environment
process.env.OPENCLAW_ANDROID = "1";
process.env.OPENCLAW_SKIP_BROWSER = "1";
process.env.OPENCLAW_SKIP_NODE_PTY = "1";
process.env.OPENCLAW_SKIP_CANVAS_HOST = "1";
process.env.NODE_ENV = process.env.NODE_ENV || "production";

const DATA_DIR = process.env.OPENCLAW_ANDROID_DATA_DIR 
  || path.join(process.cwd(), "android-data");

// Set OpenClaw config paths
process.env.OPENCLAW_CONFIG_DIR = process.env.OPENCLAW_CONFIG_DIR 
  || path.join(DATA_DIR, "config");
process.env.OPENCLAW_STATE_DIR = process.env.OPENCLAW_STATE_DIR 
  || path.join(DATA_DIR, "state");
process.env.OPENCLAW_HOME = process.env.OPENCLAW_HOME || DATA_DIR;

// Ensure directories
for (const dir of [
  DATA_DIR,
  process.env.OPENCLAW_CONFIG_DIR,
  process.env.OPENCLAW_STATE_DIR,
  path.join(DATA_DIR, "workspace"),
  path.join(DATA_DIR, "sessions"),
]) {
  try { mkdirSync(dir, { recursive: true }); } catch {}
}

console.log("[openclaw-android] Starting engine...");
console.log("[openclaw-android] Data dir:", DATA_DIR);
console.log("[openclaw-android] PID:", process.pid);

// Simulate CLI args for gateway command
process.argv = [process.execPath, import.meta.filename, "gateway", "--bind", "127.0.0.1"];

// Import the real entry
import("./dist/entry.js").catch(err => {
  console.error("[openclaw-android] Failed to start:", err);
  process.exit(1);
});
`;

writeFileSync(join(OUTPUT_DIR, "android-entry.mjs"), androidEntry);
console.log("   ✅ android-entry.mjs created");

// === Step 2: Copy OpenClaw dist/ ===
console.log("\n📁 Step 2: Copying OpenClaw dist/...");

const distSrc = join(OPENCLAW_NPM, "dist");
const distDst = join(OUTPUT_DIR, "dist");
mkdirSync(distDst, { recursive: true });

let fileCount = 0;
let totalSize = 0;

function copyDirRecursive(src, dst) {
  mkdirSync(dst, { recursive: true });
  for (const entry of readdirSync(src, { withFileTypes: true })) {
    const srcPath = join(src, entry.name);
    const dstPath = join(dst, entry.name);
    
    if (entry.isDirectory()) {
      // Skip test files, source maps, and non-essential dirs
      if (entry.name === "node_modules" || entry.name === ".git") continue;
      copyDirRecursive(srcPath, dstPath);
    } else if (entry.isFile()) {
      // Skip source maps and test files to reduce size
      if (entry.name.endsWith(".map")) continue;
      if (entry.name.endsWith(".test.js")) continue;
      if (entry.name.endsWith(".spec.js")) continue;
      if (entry.name.endsWith(".d.ts")) continue;
      if (entry.name.endsWith(".d.mts")) continue;
      
      copyFileSync(srcPath, dstPath);
      const size = statSync(dstPath).size;
      totalSize += size;
      fileCount++;
    }
  }
}

copyDirRecursive(distSrc, distDst);
console.log(\`   ✅ Copied \${fileCount} files (\${(totalSize / 1024 / 1024).toFixed(1)} MB)\`);

// === Step 3: Copy essential package files ===
console.log("\n📋 Step 3: Copying package metadata...");

// Copy package.json (needed for module resolution)
if (existsSync(join(OPENCLAW_NPM, "package.json"))) {
  copyFileSync(
    join(OPENCLAW_NPM, "package.json"),
    join(OUTPUT_DIR, "package.json")
  );
  console.log("   ✅ package.json");
}

// Copy openclaw.mjs wrapper
if (existsSync(join(OPENCLAW_NPM, "openclaw.mjs"))) {
  copyFileSync(
    join(OPENCLAW_NPM, "openclaw.mjs"),
    join(OUTPUT_DIR, "openclaw.mjs")
  );
  console.log("   ✅ openclaw.mjs");
}

// === Step 4: Identify and copy required node_modules ===
console.log("\n📦 Step 4: Analyzing required dependencies...");

// Essential pure-JS dependencies that OpenClaw needs at runtime
const essentialDeps = [
  "ws",           // WebSocket
  "hono",         // HTTP framework
  "croner",       // Cron scheduling
  "zod",          // Schema validation
  "json5",        // JSON5 parsing
  "dotenv",       // Environment files
  "chalk",        // Terminal colors (may be needed)
  "semver",       // Version parsing
  "mime-types",   // MIME type detection
  "mime-db",      // MIME database
  "uuid",         // UUID generation
  "yaml",         // YAML parsing
  "debug",        // Debug logging
  "ms",           // Millisecond parsing
  "undici",       // HTTP client
];

// Dependencies to explicitly SKIP (native or not needed on Android)
const skipDeps = [
  "sharp",
  "@lydell/node-pty",
  "node-pty",
  "playwright",
  "playwright-core",
  "@napi-rs/canvas",
  "node-llama-cpp",
  "authenticate-pam",
  "koffi",
  "better-sqlite3",
  "@matrix-org",
];

const nodeModulesSrc = join(OPENCLAW_NPM, "node_modules");
const nodeModulesDst = join(OUTPUT_DIR, "node_modules");

if (existsSync(nodeModulesSrc)) {
  mkdirSync(nodeModulesDst, { recursive: true });
  
  let depCount = 0;
  let depSize = 0;
  
  for (const dep of readdirSync(nodeModulesSrc, { withFileTypes: true })) {
    if (!dep.isDirectory()) continue;
    
    const depName = dep.name;
    
    // Skip native/unwanted deps
    if (skipDeps.some(s => depName === s || depName.startsWith(s + "/"))) continue;
    
    // For scoped packages (@org/pkg)
    if (depName.startsWith("@")) {
      const scopeDir = join(nodeModulesSrc, depName);
      for (const pkg of readdirSync(scopeDir, { withFileTypes: true })) {
        if (!pkg.isDirectory()) continue;
        const fullName = depName + "/" + pkg.name;
        if (skipDeps.some(s => fullName === s || fullName.startsWith(s))) continue;
        
        const srcPkg = join(scopeDir, pkg.name);
        const dstPkg = join(nodeModulesDst, depName, pkg.name);
        copyDirRecursive(srcPkg, dstPkg);
        depCount++;
      }
    } else {
      copyDirRecursive(join(nodeModulesSrc, depName), join(nodeModulesDst, depName));
      depCount++;
    }
  }
  
  // Calculate total size
  let nmSize = 0;
  function calcSize(dir) {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, entry.name);
      if (entry.isDirectory()) calcSize(p);
      else nmSize += statSync(p).size;
    }
  }
  calcSize(nodeModulesDst);
  
  console.log(\`   ✅ Copied \${depCount} dependencies (\${(nmSize / 1024 / 1024).toFixed(1)} MB)\`);
} else {
  console.log("   ⚠️ No node_modules found in OpenClaw package");
}

// === Step 5: Create native module stubs ===
console.log("\n🔧 Step 5: Creating native module stubs...");

const stubDir = join(nodeModulesDst);

// sharp stub
const sharpStub = join(stubDir, "sharp");
mkdirSync(sharpStub, { recursive: true });
writeFileSync(join(sharpStub, "package.json"), JSON.stringify({
  name: "sharp",
  version: "0.0.0-android-stub",
  main: "index.js"
}));
writeFileSync(join(sharpStub, "index.js"), \`
module.exports = function sharp() {
  throw new Error("sharp is not available on Android");
};
module.exports.default = module.exports;
\`);

// node-pty stub
const ptyStub = join(stubDir, "@lydell", "node-pty");
mkdirSync(ptyStub, { recursive: true });
writeFileSync(join(ptyStub, "package.json"), JSON.stringify({
  name: "@lydell/node-pty",
  version: "0.0.0-android-stub",
  main: "index.js"
}));
writeFileSync(join(ptyStub, "index.js"), \`
module.exports.spawn = function() {
  throw new Error("node-pty is not available on Android");
};
\`);

// playwright stub
const pwStub = join(stubDir, "playwright-core");
mkdirSync(pwStub, { recursive: true });
writeFileSync(join(pwStub, "package.json"), JSON.stringify({
  name: "playwright-core",
  version: "0.0.0-android-stub",
  main: "index.js"
}));
writeFileSync(join(pwStub, "index.js"), \`
module.exports.chromium = { launch: () => { throw new Error("Playwright not available on Android"); } };
module.exports.firefox = module.exports.chromium;
module.exports.webkit = module.exports.chromium;
\`);

console.log("   ✅ Created stubs for sharp, node-pty, playwright-core");

// === Summary ===
console.log("\n" + "=".repeat(60));

// Calculate total output size
let outputSize = 0;
function calcOutputSize(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) calcOutputSize(p);
    else outputSize += statSync(p).size;
  }
}
calcOutputSize(OUTPUT_DIR);

console.log(\`📊 Android Engine Package Summary:\`);
console.log(\`   Output: \${OUTPUT_DIR}\`);
console.log(\`   Total size: \${(outputSize / 1024 / 1024).toFixed(1)} MB\`);
console.log(\`   Files: \${fileCount}+ JS files\`);
console.log(\`\`);
console.log(\`📱 To use on Android:\`);
console.log(\`   1. Zip the output directory\`);
console.log(\`   2. Place in APK assets as openclaw-engine.zip\`);
console.log(\`   3. NodeEngineService will extract and run android-entry.mjs\`);
console.log("=".repeat(60));
`;

writeFileSync(join(ROOT, "scripts", "package-full-engine.mjs"), androidEntry);
console.log("\\n✅ Packaging script created!");
