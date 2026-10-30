/* Application-owned, network-free diagnostic. Does not load or execute player/token code. */
(async () => {
  const names = ['fetch', 'Request', 'Response', 'Headers', 'URL', 'URLSearchParams',
    'ReadableStream', 'TextEncoder', 'TextDecoder', 'EventTarget', 'CustomEvent',
    'AbortController', 'FormData', 'File', 'setTimeout', 'clearTimeout', 'atob', 'btoa'];
  const apis = Object.fromEntries(names.map(name => [name, typeof globalThis[name] === 'function']));
  apis['crypto.getRandomValues'] = typeof globalThis.crypto?.getRandomValues === 'function';
  apis['crypto.subtle.digest'] = typeof globalThis.crypto?.subtle?.digest === 'function';
  const port = await android.getNamedPort('runtime-proof');
  port.onmessage = event => {
    if (event.data === 'native-challenge-v1') port.postMessage('isolate-response-v1');
  };
  // Resolving this promise confirms the handler is installed before the host posts.
  return JSON.stringify({ kind: 'api-presence-only', apis });
})();
