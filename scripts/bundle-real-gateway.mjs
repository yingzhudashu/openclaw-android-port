/**
 * OpenClaw Android Engine — Real Gateway Bundle
 * 
 * Strategy: use esbuild to bundle the real OpenClaw gateway from TS source.
 * The key challenge is that OpenClaw uses `.js` extensions in imports but
 * the actual files are `.ts`. We handle this with a resolver plugin.
 */

import { build } from "esbuild";
import { resolve, dirname, join, relative, extname } from "node:path";
import { fileURLToPath } from "node:url";
import { existsSync, statSync, readdirSync } from "node:fs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, "..");
const OC = resolve(ROOT, "..", "openclaw-main");
const OC_SRC = resolve(OC, "src");
const OC_EXT = resolve(OC, "extensions");

console.log("🔧 Bundling REAL OpenClaw Android Engine...");
console.log(`   Root: ${ROOT}`);
console.log(`   OpenClaw: ${OC}`);

if (!existsSync(resolve(OC_SRC, "gateway", "server.impl.ts"))) {
  console.error("❌ Cannot find openclaw-main/src/gateway/server.impl.ts");
  process.exit(1);
}

// Collect all native/problematic modules that need stubs
const STUB_MODULES = {
  // Native modules
  "@lydell/node-pty": "node-pty-stub.ts",
  "node-pty": "node-pty-stub.ts",
  "sharp": "sharp-stub.ts",
  "playwright-core": "playwright-stub.ts",
  "playwright": "playwright-stub.ts",
  "better-sqlite3": "noop-stub.ts",
  "sqlite-vec": "noop-stub.ts",
  "sqlite3": "noop-stub.ts",
  "koffi": "noop-stub.ts",
  "authenticate-pam": "noop-stub.ts",
  "@napi-rs/canvas": "noop-stub.ts",
  "node-llama-cpp": "noop-stub.ts",
  "@node-llama-cpp/core": "noop-stub.ts",
  
  // Platform-specific
  "fsevents": "noop-stub.ts",
  "cpu-features": "noop-stub.ts",
  "keytar": "noop-stub.ts",
  
  // Desktop-only features
  "@electron/remote": "noop-stub.ts",
  "electron": "noop-stub.ts",
  
  // Heavy optional deps
  "@homebridge/ciao": "noop-stub.ts",
  "bonjour-service": "noop-stub.ts",
  "@anthropic-ai/sdk": "noop-stub.ts",  // we'll use HTTP directly
};

// These modules should be kept external (Node.js builtins + some special cases)
const EXTERNAL = [
  // Node.js builtins
  "node:*",
  "fs", "path", "os", "crypto", "http", "https", "net", "tls",
  "url", "util", "events", "stream", "buffer", "child_process",
  "worker_threads", "cluster", "dns", "dgram", "readline",
  "zlib", "assert", "querystring", "string_decoder", "timers",
  "process", "v8", "vm", "module", "perf_hooks", "async_hooks",
  "inspector", "trace_events", "constants", "tty", "punycode",
  "domain", "sys",
];

/**
 * Plugin: resolve .js imports to .ts source files in openclaw-main
 */
function openclawTsResolver() {
  return {
    name: "openclaw-ts-resolver",
    setup(build) {
      // Resolve .js imports to .ts in openclaw-main/src and extensions
      build.onResolve({ filter: /\.js$/ }, (args) => {
        if (args.kind === "entry-point") return;
        
        const dir = args.resolveDir;
        // Only handle files within openclaw-main
        if (!dir.includes("openclaw-main")) return;
        
        const tsPath = resolve(dir, args.path.replace(/\.js$/, ".ts"));
        if (existsSync(tsPath)) {
          return { path: tsPath };
        }
        
        // Try index.ts
        const indexPath = resolve(dir, args.path.replace(/\.js$/, ""), "index.ts");
        if (existsSync(indexPath)) {
          return { path: indexPath };
        }
        
        // Keep as-is if not found
        return undefined;
      });
      
      // Handle bare imports that might be internal modules
      build.onResolve({ filter: /^\.\.?\// }, (args) => {
        if (args.kind === "entry-point") return;
        const dir = args.resolveDir;
        if (!dir.includes("openclaw-main")) return;
        
        const base = resolve(dir, args.path);
        
        // Try .ts extension
        if (existsSync(base + ".ts")) {
          return { path: base + ".ts" };
        }
        // Try /index.ts
        if (existsSync(join(base, "index.ts"))) {
          return { path: join(base, "index.ts") };
        }
        // Try .js
        if (existsSync(base + ".js")) {
          return { path: base + ".js" };
        }
        
        return undefined;
      });
    },
  };
}

/**
 * Plugin: stub out native/incompatible modules
 */
function nativeStubPlugin() {
  return {
    name: "native-stub",
    setup(build) {
      for (const [mod, stubFile] of Object.entries(STUB_MODULES)) {
        const escaped = mod.replace(/[.*+?^${}()|[\]\\\/]/g, "\\$&");
        const filter = new RegExp(`^${escaped}$`);
        build.onResolve({ filter }, () => ({
          path: resolve(ROOT, "engine", "stubs", stubFile),
        }));
      }
      
      // Stub any openclaw/plugin-sdk imports
      build.onResolve({ filter: /^openclaw\/plugin-sdk/ }, () => ({
        path: resolve(ROOT, "engine", "stubs", "noop-stub.ts"),
      }));
    },
  };
}

/**
 * Plugin: resolve our virtual "openclaw-gateway-entry" import
 */
function gatewayEntryPlugin() {
  return {
    name: "gateway-entry",
    setup(build) {
      build.onResolve({ filter: /^openclaw-gateway-entry$/ }, () => ({
        path: resolve(OC_SRC, "gateway", "server.impl.ts"),
      }));
    },
  };
}

async function bundle() {
  const t0 = Date.now();
  
  try {
    const result = await build({
      entryPoints: [resolve(ROOT, "engine", "android-entry-real.ts")],
      bundle: true,
      platform: "node",
      target: "node18",
      format: "esm",
      outfile: resolve(ROOT, "dist", "android-engine-real.mjs"),
      minify: false,
      sourcemap: true,
      
      plugins: [
        gatewayEntryPlugin(),
        nativeStubPlugin(),
        openclawTsResolver(),
      ],
      
      // Resolve from openclaw-main's node_modules
      nodePaths: [
        resolve(OC, "node_modules"),
      ],
      
      external: EXTERNAL,
      
      resolveExtensions: [".ts", ".tsx", ".js", ".jsx", ".mjs", ".json"],
      treeShaking: true,
      
      define: {
        "process.env.OPENCLAW_ANDROID": '"1"',
        "process.env.OPENCLAW_SKIP_BROWSER": '"1"',
        "process.env.OPENCLAW_SKIP_NODE_PTY": '"1"',
        "process.env.OPENCLAW_SKIP_CANVAS_HOST": '"1"',
      },
      
      logLevel: "warning",
      metafile: true,
      
      // Increase limits
      logLimit: 50,
    });
    
    const elapsed = ((Date.now() - t0) / 1000).toFixed(1);
    const outPath = resolve(ROOT, "dist", "android-engine-real.mjs");
    const outSize = existsSync(outPath) ? statSync(outPath).size : 0;
    
    console.log(`\n✅ Bundle complete in ${elapsed}s!`);
    console.log(`   Output: ${(outSize / 1024 / 1024).toFixed(2)} MB`);
    console.log(`   Errors: ${result.errors.length}`);
    console.log(`   Warnings: ${result.warnings.length}`);
    
    if (result.metafile) {
      const inputs = result.metafile.inputs;
      const count = Object.keys(inputs).length;
      const sorted = Object.entries(inputs)
        .sort((a, b) => b[1].bytes - a[1].bytes)
        .slice(0, 20);
      
      console.log(`\n📦 Total modules bundled: ${count}`);
      console.log(`📊 Top 20 by size:`);
      for (const [p, info] of sorted) {
        const kb = (info.bytes / 1024).toFixed(1);
        const short = p.length > 80 ? "..." + p.slice(-77) : p;
        console.log(`   ${kb.padStart(8)} KB  ${short}`);
      }
    }
    
    if (result.warnings.length > 0) {
      console.log(`\n⚠️ First 20 warnings:`);
      for (const w of result.warnings.slice(0, 20)) {
        console.log(`   ${w.text}`);
      }
    }
    
  } catch (error) {
    const elapsed = ((Date.now() - t0) / 1000).toFixed(1);
    console.error(`\n❌ Bundle FAILED after ${elapsed}s`);
    
    if (error.errors) {
      console.error(`\n${error.errors.length} error(s):`);
      for (const e of error.errors.slice(0, 30)) {
        const loc = e.location ? ` [${e.location.file}:${e.location.line}]` : "";
        console.error(`  ❌ ${e.text}${loc}`);
      }
      if (error.errors.length > 30) {
        console.error(`  ... and ${error.errors.length - 30} more`);
      }
    } else {
      console.error(error.message || error);
    }
    
    process.exit(1);
  }
}

bundle();
