/**
 * OpenClaw Android Engine Bundle Script
 * 
 * Uses esbuild to create a single-file JS bundle for the Android engine.
 * This replaces the need to ship the entire node_modules directory.
 * 
 * Usage: node scripts/bundle-android-engine.mjs
 * 
 * Output: dist/android-engine.mjs (~10-30MB estimated)
 */

import { build } from "esbuild";
import { resolve, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { existsSync, statSync, readFileSync } from "node:fs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, "..");
const OPENCLAW_SRC = resolve(ROOT, "..", "openclaw-main");

console.log("🔧 Bundling OpenClaw Android Engine...");
console.log(`   Project root: ${ROOT}`);
console.log(`   OpenClaw source: ${OPENCLAW_SRC}`);

// Verify openclaw-main exists
if (!existsSync(resolve(OPENCLAW_SRC, "src", "gateway", "server.impl.ts"))) {
  console.error("❌ Cannot find openclaw-main/src/gateway/server.impl.ts");
  console.error("   Make sure openclaw-main is at:", OPENCLAW_SRC);
  process.exit(1);
}

// Plugin to resolve .js imports to .ts files in the openclaw source
function tsResolverPlugin() {
  return {
    name: 'ts-resolver',
    setup(build) {
      // Intercept relative imports from openclaw-main
      build.onResolve({ filter: /^\.\.?\// }, async (args) => {
        // Only handle imports from within openclaw-main source
        const resolveDir = args.resolveDir.replace(/\\/g, '/');
        const isOpenclaw = resolveDir.includes('openclaw-main/src');
        
        if (!isOpenclaw) {
          return undefined;
        }

        // If it ends in .js, try to resolve to .ts
        if (args.path.endsWith('.js')) {
          const tsPath = args.path.replace(/\.js$/, '.ts');
          const resolvedTs = resolve(args.resolveDir, tsPath);
          
          if (existsSync(resolvedTs)) {
            return { path: resolvedTs };
          }
          
          // Also try .tsx
          const resolvedTsx = resolve(args.resolveDir, tsPath + 'x');
          if (existsSync(resolvedTsx)) {
            return { path: resolvedTsx };
          }
        }
        
        // Let esbuild handle it normally
        return undefined;
      });
      
      // Handle openclaw plugin-sdk imports by creating empty stubs
      build.onResolve({ filter: /^openclaw\/plugin-sdk\// }, (args) => {
        return { path: `virtual:${args.path}`, namespace: 'virtual-plugin-sdk' };
      });
      
      build.onLoad({ filter: /^virtual:/, namespace: 'virtual-plugin-sdk' }, (args) => {
        return {
          contents: 'export {};',
          loader: 'js'
        };
      });
    }
  };
}

async function bundleAndroidEngine() {
  const startTime = Date.now();
  
  try {
    const result = await build({
      entryPoints: [resolve(ROOT, "engine", "android-entry.ts")],
      bundle: true,
      platform: "node",
      target: "node18",  // nodejs-mobile is Node 18
      format: "esm",
      outfile: resolve(ROOT, "dist", "android-engine.mjs"),
      minify: false, // Keep readable for debugging during development
      sourcemap: true,
      
      // Add our custom resolver plugin
      plugins: [tsResolverPlugin()],
      
      // Resolve modules from openclaw-main's node_modules
      nodePaths: [
        resolve(OPENCLAW_SRC, "node_modules"),
      ],
      
      // Map the main entry point to the actual implementation
      alias: {
        // Main OpenClaw gateway entry point
        "openclaw-gateway": resolve(OPENCLAW_SRC, "src", "gateway", "server.impl.ts"),
        
        // Native modules that need stubs
        "@lydell/node-pty": resolve(ROOT, "engine", "stubs", "node-pty-stub.ts"),
        "node-pty": resolve(ROOT, "engine", "stubs", "node-pty-stub.ts"),
        "sharp": resolve(ROOT, "engine", "stubs", "sharp-stub.ts"),
        "playwright-core": resolve(ROOT, "engine", "stubs", "playwright-stub.ts"),
        "playwright": resolve(ROOT, "engine", "stubs", "playwright-stub.ts"),
        
        // Additional native modules that won't work on Android
        "node-llama-cpp": resolve(ROOT, "engine", "stubs", "noop-stub.ts"),
        "@node-llama-cpp/core": resolve(ROOT, "engine", "stubs", "noop-stub.ts"),
        "koffi": resolve(ROOT, "engine", "stubs", "noop-stub.ts"),
        "authenticate-pam": resolve(ROOT, "engine", "stubs", "noop-stub.ts"),
        "@napi-rs/canvas": resolve(ROOT, "engine", "stubs", "noop-stub.ts"),
        "better-sqlite3": resolve(ROOT, "engine", "stubs", "noop-stub.ts"),
        "sqlite-vec": resolve(ROOT, "engine", "stubs", "noop-stub.ts"),
      },
      
      // External modules that should NOT be bundled
      external: [
        // Node.js builtins (with node: prefix)
        "node:*",
        // Node.js builtins (bare)
        "fs", "path", "os", "crypto", "http", "https", "net", "tls",
        "url", "util", "events", "stream", "buffer", "child_process",
        "worker_threads", "cluster", "dns", "dgram", "readline",
        "zlib", "assert", "querystring", "string_decoder", "timers",
        "process", "v8", "vm", "module", "perf_hooks", "async_hooks",
        "inspector", "trace_events", "constants", "tty", "punycode",
        "domain", "sys",
        
        // Heavy optional deps we don't need on Android
        "fsevents",       // macOS only
        "cpu-features",   // optional native
        "keytar",         // desktop keychain
        
        // Any remaining plugin-sdk imports (should be caught by plugin above)
        "openclaw/plugin-sdk/*",
      ],
      
      // Resolve paths - include openclaw-main src for .ts resolution
      resolveExtensions: [".ts", ".tsx", ".js", ".jsx", ".mjs", ".json"],
      
      // Tree-shake unused code
      treeShaking: true,
      
      // Define compile-time constants
      define: {
        "process.env.OPENCLAW_ANDROID": '"1"',
        "process.env.OPENCLAW_SKIP_BROWSER": '"1"',
        "process.env.OPENCLAW_SKIP_NODE_PTY": '"1"',
        "process.env.OPENCLAW_SKIP_CANVAS_HOST": '"1"',
      },
      
      // Log level - show warnings
      logLevel: "info",
      
      // Metafile for analysis
      metafile: true,
    });

    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
    
    // Check output size
    const outPath = resolve(ROOT, "dist", "android-engine.mjs");
    const mapPath = resolve(ROOT, "dist", "android-engine.mjs.map");
    
    const outSize = existsSync(outPath) ? statSync(outPath).size : 0;
    const mapSize = existsSync(mapPath) ? statSync(mapPath).size : 0;
    
    console.log(`\n✅ Bundle complete in ${elapsed}s!`);
    console.log(`   Output: dist/android-engine.mjs (${(outSize / 1024 / 1024).toFixed(2)} MB)`);
    console.log(`   Source map: dist/android-engine.mjs.map (${(mapSize / 1024 / 1024).toFixed(2)} MB)`);
    
    if (result.errors.length > 0) {
      console.error("❌ Build errors:", result.errors.length);
      // Show first 10 errors
      for (const e of result.errors.slice(0, 10)) {
        console.error(`   - ${e.text}`);
        if (e.location) {
          console.error(`     at ${e.location.file}:${e.location.line}:${e.location.column}`);
        }
      }
      if (result.errors.length > 10) {
        console.error(`   ... and ${result.errors.length - 10} more errors`);
      }
    }
    if (result.warnings.length > 0) {
      console.warn(`⚠️  ${result.warnings.length} warnings`);
      // Show first 5 warnings
      for (const w of result.warnings.slice(0, 5)) {
        console.warn(`   - ${w.text}`);
      }
      if (result.warnings.length > 5) {
        console.warn(`   ... and ${result.warnings.length - 5} more warnings`);
      }
    }
    
    // Show top modules by size if metafile available
    if (result.metafile) {
      const metafile = result.metafile;
      const inputs = metafile.inputs;
      const sorted = Object.entries(inputs)
        .sort((a, b) => b[1].bytes - a[1].bytes)
        .slice(0, 15);
      
      console.log(`\n📊 Top 15 modules by size:`);
      for (const [path, info] of sorted) {
        const sizeMB = (info.bytes / 1024 / 1024).toFixed(2);
        const sizeKB = (info.bytes / 1024).toFixed(1);
        const display = info.bytes > 1024 * 1024 ? `${sizeMB} MB` : `${sizeKB} KB`;
        console.log(`   ${display.padStart(10)} ${path.split('/').pop() || path}`);
      }
      
      console.log(`\n📦 Total input modules: ${Object.keys(inputs).length}`);
    }
    
  } catch (error) {
    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
    console.error(`\n❌ Bundle failed after ${elapsed}s:`);
    console.error(error.message || error);
    
    // If there are specific errors, show them
    if (error.errors) {
      console.error(`\n${error.errors.length} error(s):`);
      for (const e of error.errors.slice(0, 20)) {
        console.error(`  ${e.text}`);
        if (e.location) {
          console.error(`    at ${e.location.file}:${e.location.line}:${e.location.column}`);
        }
      }
      if (error.errors.length > 20) {
        console.error(`  ... and ${error.errors.length - 20} more errors`);
      }
    }
    
    process.exit(1);
  }
}

bundleAndroidEngine();