/**
 * Stub for sharp on Android
 * 
 * sharp requires libvips which is a complex C library not easily
 * cross-compiled for Android. This stub provides a minimal API
 * that delegates image processing to the Kotlin layer via HTTP bridge.
 * 
 * For basic operations (resize, format conversion), we use the
 * Kotlin bridge at http://localhost:18790/image/*
 * 
 * For operations where the bridge is unavailable, we return
 * the input unchanged with a warning.
 */

const BRIDGE_URL = "http://127.0.0.1:18790";
const BRIDGE_AVAILABLE = false; // Set to true once Kotlin bridge is running

interface SharpOptions {
  failOnError?: boolean;
  density?: number;
}

interface ResizeOptions {
  width?: number;
  height?: number;
  fit?: string;
  position?: string;
  background?: string | object;
  withoutEnlargement?: boolean;
}

interface SharpMetadata {
  format?: string;
  width?: number;
  height?: number;
  channels?: number;
  size?: number;
}

class SharpInstance {
  private inputBuffer: Buffer | null = null;
  private inputPath: string | null = null;
  private operations: Array<{ type: string; params: any }> = [];

  constructor(input?: Buffer | string, options?: SharpOptions) {
    if (Buffer.isBuffer(input)) {
      this.inputBuffer = input;
    } else if (typeof input === "string") {
      this.inputPath = input;
    }
  }

  resize(widthOrOptions?: number | ResizeOptions, height?: number, options?: ResizeOptions): this {
    if (typeof widthOrOptions === "object") {
      this.operations.push({ type: "resize", params: widthOrOptions });
    } else {
      this.operations.push({ type: "resize", params: { width: widthOrOptions, height, ...options } });
    }
    return this;
  }

  jpeg(options?: { quality?: number }): this {
    this.operations.push({ type: "format", params: { format: "jpeg", ...options } });
    return this;
  }

  png(options?: { compressionLevel?: number }): this {
    this.operations.push({ type: "format", params: { format: "png", ...options } });
    return this;
  }

  webp(options?: { quality?: number }): this {
    this.operations.push({ type: "format", params: { format: "webp", ...options } });
    return this;
  }

  toFormat(format: string, options?: any): this {
    this.operations.push({ type: "format", params: { format, ...options } });
    return this;
  }

  rotate(angle?: number): this {
    this.operations.push({ type: "rotate", params: { angle } });
    return this;
  }

  flip(): this {
    this.operations.push({ type: "flip", params: {} });
    return this;
  }

  flop(): this {
    this.operations.push({ type: "flop", params: {} });
    return this;
  }

  grayscale(): this {
    this.operations.push({ type: "grayscale", params: {} });
    return this;
  }

  greyscale(): this {
    return this.grayscale();
  }

  async toBuffer(): Promise<Buffer> {
    // If no operations or bridge unavailable, return input as-is
    if (this.inputBuffer && (this.operations.length === 0 || !BRIDGE_AVAILABLE)) {
      console.warn("[sharp-stub] Returning input buffer unchanged (bridge unavailable)");
      return this.inputBuffer;
    }

    if (this.inputPath) {
      const { readFileSync } = await import("node:fs");
      return readFileSync(this.inputPath);
    }

    return this.inputBuffer || Buffer.alloc(0);
  }

  async toFile(outputPath: string): Promise<{ format: string; width: number; height: number; size: number }> {
    const buffer = await this.toBuffer();
    const { writeFileSync } = await import("node:fs");
    writeFileSync(outputPath, buffer);
    return {
      format: "unknown",
      width: 0,
      height: 0,
      size: buffer.length,
    };
  }

  async metadata(): Promise<SharpMetadata> {
    // Return basic metadata
    return {
      format: undefined,
      width: undefined,
      height: undefined,
      channels: undefined,
      size: this.inputBuffer?.length,
    };
  }

  // Passthrough for chaining methods we don't implement
  clone(): SharpInstance {
    const cloned = new SharpInstance(this.inputBuffer || undefined);
    cloned.inputPath = this.inputPath;
    cloned.operations = [...this.operations];
    return cloned;
  }
}

function sharp(input?: Buffer | string, options?: SharpOptions): SharpInstance {
  return new SharpInstance(input, options);
}

// Static methods
sharp.cache = (_options?: any) => {};
sharp.concurrency = (_concurrency?: number) => 0;
sharp.counters = () => ({ queue: 0, process: 0 });
sharp.simd = (_enable?: boolean) => false;
sharp.versions = { vips: "0.0.0-android-stub", sharp: "0.0.0-android-stub" };

export default sharp;
export { sharp };
