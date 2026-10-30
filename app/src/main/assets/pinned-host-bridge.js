// Trusted parser isolate only. Evaluator gets a separate isolate and never receives this port.
(function(){
  'use strict';
  globalThis.createPinnedHostBridge=function(port){
    const pending=new Map();let serial=0,closed=false;
    function close(reason='CLOSED'){
      if(closed)return;closed=true;port.onmessage=null;
      for(const entry of pending.values())entry.reject(Error(reason));pending.clear();
      try{port.postMessage(JSON.stringify({v:1,op:'close'}));}catch{}
    }
    port.onmessage=event=>{
      if(closed)return;
      try{
        if(typeof event.data!=='string'||event.data.length>262144)throw Error();
        const reply=JSON.parse(event.data);
        if(reply?.v!==1||!Number.isSafeInteger(reply.id)||typeof reply.ok!=='boolean')throw Error();
        const entry=pending.get(reply.id);if(!entry)return;
        if(!reply.ok&&!['DENIED','LIMIT','BAD_REQUEST','TIMEOUT','EVAL_FAILED','BAD_RESULT','CANCELLED'].includes(reply.error))throw Error();
        if(reply.ok&&(reply.value===null||typeof reply.value!=='object'||Array.isArray(reply.value)))throw Error();
        pending.delete(reply.id);reply.ok?entry.resolve(reply.value):entry.reject(Error(reply.error));
      }catch{close('BAD_RESPONSE');}
    };
    function request(op,value){
      if(closed)return {promise:Promise.reject(Error('CLOSED')),cancel(){}};
      if(serial>=8||pending.size>=2)return {promise:Promise.reject(Error('LIMIT')),cancel(){}};
      const id=++serial;const text=JSON.stringify({v:1,id,op,...value});
      if(text.length>131072)return {promise:Promise.reject(Error('LIMIT')),cancel(){}};
      const promise=new Promise((resolve,reject)=>{
        pending.set(id,{resolve,reject});try{port.postMessage(text);}catch{pending.delete(id);reject(Error('TRANSPORT'));}
      });
      return {promise,cancel(){const entry=pending.get(id);if(!entry)return;pending.delete(id);entry.reject(Error('CANCELLED'));try{port.postMessage(JSON.stringify({v:1,op:'cancel',id}));}catch{}}};
    }
    return {fixture(name){return request('fixture',{name});},evaluate(source){return request('evaluate',{source});},close};
  };
})();
