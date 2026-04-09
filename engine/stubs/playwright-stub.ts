/**
 * Stub for playwright-core on Android
 * 
 * Playwright requires Chromium/Firefox/WebKit binaries which cannot
 * run on Android. This stub exports the expected API shape but
 * throws clear errors when browser automation is attempted.
 * 
 * The browser tool in OpenClaw will be disabled on Android.
 * Users can use Android's WebView for basic web interaction.
 */

const ANDROID_ERROR = "Browser automation (Playwright) is not available on Android. " +
  "Use the Android WebView for web browsing.";

class StubBrowser {
  async close() {}
  async newContext() { throw new Error(ANDROID_ERROR); }
  async newPage() { throw new Error(ANDROID_ERROR); }
  isConnected() { return false; }
}

class StubBrowserType {
  name() { return "chromium"; }
  executablePath() { return ""; }
  async launch() { throw new Error(ANDROID_ERROR); }
  async launchPersistentContext() { throw new Error(ANDROID_ERROR); }
  async connect() { throw new Error(ANDROID_ERROR); }
  async connectOverCDP() { throw new Error(ANDROID_ERROR); }
}

class StubPage {
  async goto() { throw new Error(ANDROID_ERROR); }
  async close() {}
  async screenshot() { throw new Error(ANDROID_ERROR); }
  async content() { throw new Error(ANDROID_ERROR); }
  async evaluate() { throw new Error(ANDROID_ERROR); }
}

export const chromium = new StubBrowserType();
export const firefox = new StubBrowserType();
export const webkit = new StubBrowserType();

export const devices = {};
export const errors = {
  TimeoutError: class TimeoutError extends Error {
    constructor(message?: string) {
      super(message || "Timeout");
      this.name = "TimeoutError";
    }
  },
};

export default {
  chromium,
  firefox,
  webkit,
  devices,
  errors,
};
