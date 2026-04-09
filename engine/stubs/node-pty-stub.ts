/**
 * Stub for node-pty on Android
 * 
 * node-pty requires Unix PTY which is not available in Android's
 * sandboxed environment. This stub provides a compatible API that
 * delegates to Android's ProcessBuilder via the Kotlin bridge.
 * 
 * Limitations:
 * - No true PTY (no terminal resize, no raw mode)
 * - Limited to simple command execution
 * - No interactive shell sessions
 */

import { EventEmitter } from "node:events";
import { spawn, type ChildProcess } from "node:child_process";

export interface IPtyForkOptions {
  name?: string;
  cols?: number;
  rows?: number;
  cwd?: string;
  env?: NodeJS.ProcessEnv;
  encoding?: BufferEncoding | null;
}

export interface IPty extends EventEmitter {
  pid: number;
  cols: number;
  rows: number;
  process: string;
  handleFlowControl: boolean;
  write(data: string): void;
  resize(cols: number, rows: number): void;
  clear(): void;
  kill(signal?: string): void;
  pause(): void;
  resume(): void;
  onData: (callback: (data: string) => void) => { dispose: () => void };
  onExit: (callback: (exitCode: { exitCode: number; signal?: number }) => void) => { dispose: () => void };
}

class AndroidPty extends EventEmitter implements IPty {
  pid: number;
  cols: number;
  rows: number;
  process: string;
  handleFlowControl = false;
  private childProcess: ChildProcess;
  private _onDataListeners: Array<(data: string) => void> = [];
  private _onExitListeners: Array<(exit: { exitCode: number; signal?: number }) => void> = [];

  constructor(file: string, args: string[], options: IPtyForkOptions) {
    super();
    this.cols = options.cols || 80;
    this.rows = options.rows || 24;
    this.process = file;

    // Use Node.js child_process.spawn as fallback
    // On Android, this will use /system/bin/sh or whatever shell is available
    this.childProcess = spawn(file, args, {
      cwd: options.cwd,
      env: options.env || process.env,
      shell: true,
      stdio: ["pipe", "pipe", "pipe"],
    });

    this.pid = this.childProcess.pid || -1;

    this.childProcess.stdout?.on("data", (data: Buffer) => {
      const str = data.toString(options.encoding || "utf8");
      this.emit("data", str);
      this._onDataListeners.forEach(cb => cb(str));
    });

    this.childProcess.stderr?.on("data", (data: Buffer) => {
      const str = data.toString(options.encoding || "utf8");
      this.emit("data", str);
      this._onDataListeners.forEach(cb => cb(str));
    });

    this.childProcess.on("exit", (code, signal) => {
      const exitInfo = { 
        exitCode: code ?? 1, 
        signal: signal ? parseInt(signal.replace("SIG", ""), 10) || undefined : undefined 
      };
      this.emit("exit", exitInfo);
      this._onExitListeners.forEach(cb => cb(exitInfo));
    });

    this.childProcess.on("error", (err) => {
      this.emit("data", `\r\n[Android PTY stub] Error: ${err.message}\r\n`);
      const exitInfo = { exitCode: 1 };
      this.emit("exit", exitInfo);
      this._onExitListeners.forEach(cb => cb(exitInfo));
    });
  }

  write(data: string): void {
    this.childProcess.stdin?.write(data);
  }

  resize(_cols: number, _rows: number): void {
    // No-op on Android — no real PTY to resize
    this.cols = _cols;
    this.rows = _rows;
  }

  clear(): void {
    // No-op
  }

  kill(signal?: string): void {
    this.childProcess.kill(signal as NodeJS.Signals);
  }

  pause(): void {
    this.childProcess.stdout?.pause();
  }

  resume(): void {
    this.childProcess.stdout?.resume();
  }

  onData(callback: (data: string) => void) {
    this._onDataListeners.push(callback);
    return {
      dispose: () => {
        const idx = this._onDataListeners.indexOf(callback);
        if (idx >= 0) this._onDataListeners.splice(idx, 1);
      },
    };
  }

  onExit(callback: (exitCode: { exitCode: number; signal?: number }) => void) {
    this._onExitListeners.push(callback);
    return {
      dispose: () => {
        const idx = this._onExitListeners.indexOf(callback);
        if (idx >= 0) this._onExitListeners.splice(idx, 1);
      },
    };
  }
}

export function spawn_pty(
  file: string, 
  args: string[] = [], 
  options: IPtyForkOptions = {}
): IPty {
  return new AndroidPty(file, args, options);
}

// Default export to match node-pty API
export default { spawn: spawn_pty };
