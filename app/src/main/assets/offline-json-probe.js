(async function () {
  const port = await android.getNamedPort('offline-json-proof');
  const bridge = createOfflineJsonBridge(port);
  try {
    const catalog = await bridge.requestJson('proof://fixtures/catalog');
    if (catalog.items?.length !== 2 || catalog.items[0].title !== '\u0422\u0438\u0445\u0438\u0439 \u0432\u0435\u0447\u0435\u0440 · 夜') throw new Error('FIXTURE_MISMATCH');
    let denied = false;
    try { await bridge.requestJson('https://example.invalid/not-allowed'); }
    catch (error) { denied = error.message === 'DENIED'; }
    if (!denied) throw new Error('ALLOWLIST_FAILURE');
    return JSON.stringify({contract: 'offline-json-v1', fixture: true, unicode: true, denied: true});
  } finally { bridge.close(); }
})();
