// Original bounded URL subset for the offline Player fixture. NOT a browser URL polyfill.
// Absolute ASCII HTTPS URLs only; no credentials/ports/relative paths/IDN/opaque URLs.
(function(){
  'use strict';
  const limit=16384;
  function bounded(value){const text=String(value);if(text.length>limit||/[\u0000-\u0020\u007f]/.test(text))throw Error('URL_LIMIT');return text;}
  const encode=value=>encodeURIComponent(value).replace(/%20/g,'+').replace(/[!'()~]/g,c=>'%'+c.charCodeAt(0).toString(16).toUpperCase());
  const decode=value=>decodeURIComponent(value.replace(/\+/g,' '));
  class NarrowSearchParams {
    constructor(value=''){
      const text=String(value);if(text.length>limit)throw Error('URL_LIMIT');
      this.pairs=text.replace(/^\?/,'').split('&').filter(Boolean).map(part=>{const p=part.indexOf('=');return p<0?[decode(part),'']:[decode(part.slice(0,p)),decode(part.slice(p+1))];});
      if(this.pairs.length>128)throw Error('URL_LIMIT');
    }
    get(key){return this.pairs.find(pair=>pair[0]===String(key))?.[1]??null;}
    has(key){return this.get(key)!==null;}
    set(key,value){key=String(key);value=String(value);const first=this.pairs.findIndex(pair=>pair[0]===key);const next=this.pairs.filter(pair=>pair[0]!==key);next.splice(first<0?next.length:first,0,[key,value]);if(next.length>128)throw Error('URL_LIMIT');if(next.map(pair=>pair.map(encode).join('=')).join('&').length>limit)throw Error('URL_LIMIT');this.pairs=next;}
    delete(key){this.pairs=this.pairs.filter(pair=>pair[0]!==String(key));}
    toString(){return this.pairs.map(pair=>pair.map(encode).join('=')).join('&');}
    entries(){return this.pairs.map(pair=>[...pair])[Symbol.iterator]();}
    [Symbol.iterator](){return this.entries();}
  }
  class NarrowURL {
    constructor(value){
      const text=bounded(value),match=/^https:\/\/([A-Za-z0-9.-]+)(\/[^?#]*)?(\?[^#]*)?(#[^]*)?$/.exec(text);
      if(!match||match[1].includes('..')||match[1].startsWith('.')||match[1].endsWith('.'))throw Error('UNSUPPORTED_URL');
      this.protocol='https:';this.hostname=match[1].toLowerCase();this.pathname=match[2]||'/';this.hash=match[4]||'';this.username='';this.password='';
      this.searchParams=new NarrowSearchParams(match[3]||'');
    }
    get origin(){return 'https://'+this.hostname;}
    get search(){const value=this.searchParams.toString();return value?'?'+value:'';}
    get href(){return this.origin+this.pathname+this.search+this.hash;}
    toString(){return this.href;}
  }
  globalThis.URL=NarrowURL;globalThis.URLSearchParams=NarrowSearchParams;
})();
