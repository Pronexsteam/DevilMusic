// Loaded only in a fresh evaluator isolate. No ports, network, native objects or parser namespace.
(function(){
  'use strict';
  globalThis.runBoundedPlayerEvaluation=function(source){
    if(typeof source!=='string'||source.length>120000)throw Error('EVAL_LIMIT');
    const value=(new Function(source))();
    if(value===null||typeof value!=='object'||Array.isArray(value))throw Error('BAD_RESULT');
    const keys=Object.keys(value);if(keys.length>2||keys.some(key=>key!=='sig'&&key!=='n'))throw Error('BAD_RESULT');
    const clean={};
    for(const key of keys){const item=value[key];if(item===undefined)continue;if(typeof item!=='string'||item.length>8192)throw Error('BAD_RESULT');clean[key]=item;}
    if(!Object.keys(clean).length)throw Error('BAD_RESULT');
    const text=JSON.stringify(clean);if(text.length>16384)throw Error('BAD_RESULT');return text;
  };
})();
