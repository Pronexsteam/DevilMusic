// Application-owned adapter only. Not a fetch polyfill and never attached to untrusted player code.
(function () {
  'use strict';
  globalThis.createOfflineJsonBridge = function (port) {
    const pending = new Map(); let nextId = 0, closed = false;
    function close(reason = 'CLOSED') {
      if (closed) return;
      closed = true;
      for (const task of pending.values()) task.reject(new Error(reason));
      pending.clear(); port.onmessage = null;
    }
    port.onmessage = function (event) {
      if (closed) return;
      try {
        if (typeof event.data !== 'string' || event.data.length > 8192) throw new Error();
        const response = JSON.parse(event.data);
        if (!response || response.v !== 1 || !Number.isSafeInteger(response.id) || typeof response.ok !== 'boolean') throw new Error();
        const task = pending.get(response.id);
        if (!task) return; // Cancelled request or duplicate/late response cannot affect a new request.
        if (response.ok ? response.status !== 200 || typeof response.body !== 'object' || response.body === null : !['DENIED', 'BAD_REQUEST', 'LIMIT'].includes(response.error)) throw new Error();
        pending.delete(response.id);
        if (response.ok) task.resolve(response.body); else task.reject(new Error(response.error));
      } catch (_) { close('BAD_RESPONSE'); }
    };
    return {
      requestJson(path) {
        if (closed) return Promise.reject(new Error('CLOSED'));
        if (typeof path !== 'string' || path.length > 256) return Promise.reject(new Error('BAD_REQUEST'));
        if (nextId >= 8 || pending.size >= 4) return Promise.reject(new Error('LIMIT'));
        const id = ++nextId;
        return new Promise((resolve, reject) => {
          pending.set(id, {resolve, reject});
          try { port.postMessage(JSON.stringify({v: 1, id, op: 'GET', path})); }
          catch (_) { pending.delete(id); reject(new Error('TRANSPORT')); }
        });
      },
      close
    };
  };
})();
