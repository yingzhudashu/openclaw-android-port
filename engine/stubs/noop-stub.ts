/**
 * No-op stub for native modules that are not available on Android.
 * Returns empty objects/functions for any property access.
 */

const handler: ProxyHandler<any> = {
  get(_target, prop) {
    if (prop === "__esModule") return true;
    if (prop === "default") return new Proxy({}, handler);
    if (typeof prop === "symbol") return undefined;
    // Return a no-op function that itself returns a proxy
    return function noopStub(..._args: any[]) {
      return new Proxy({}, handler);
    };
  },
};

const stub = new Proxy({}, handler);
export default stub;
export const __esModule = true;
