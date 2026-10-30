// Uses actual pinned parser and Player with synthetic data. No Innertube session or HTTP fetch.
(async function(){
  const bridge=createPinnedHostBridge(await android.getNamedPort('pinned-parser-proof'));
  const lib=PinnedYouTube;
  try{
    lib.Platform.load({...lib.Platform.shim,eval:data=>bridge.evaluate(data.output).promise});
    const search=await bridge.fixture('search').promise;
    const parsed=lib.Parser.parseResponse(search);
    const tracks=parsed.contents_memo.getType(lib.YTNodes.Video);
    if(tracks.length!==1||tracks[0].id!=='abcdefghijk'||String(tracks[0].title)!=='Quiet evening · 夜')throw Error('PARSER_MISMATCH');
    const envelope=await bridge.fixture('player').promise;
    if(envelope.response?.videoDetails?.videoId!==tracks[0].id)throw Error('SOURCE_ID_MISMATCH');
    const info=lib.Parser.parseResponse(envelope.response);
    if(info.playability_status?.status!=='OK')throw Error('SOURCE_ACCESS_STOP');
    const audio=info.streaming_data?.adaptive_formats?.find(format=>format.has_audio&&!format.has_video&&format.url);
    if(!audio)throw Error('NO_AUDIO');
    const owned={output:'class FixtureURL {get(k){return k==="n"?"fixture-output":undefined;}} var exportedVars={nsigFunction:function(){return new FixtureURL();}};',exported:['nsigFunction'],exportedRawValues:{signatureTimestampVar:12345}};
    const player=await lib.Player.fromSource('fixture-revision',{signature_timestamp:12345,data:owned});
    const uri=await player.decipher(audio.url);
    if(new URL(uri).searchParams.get('n')!=='fixture-output')throw Error('EVAL_MISMATCH');
    // This internal wire reply is validated by native code, never printed in the UI or diagnostics.
    return JSON.stringify({contract:'pinned-parser-v1',parser:true,evaluator:true,lease:{id:tracks[0].id,uri,headers:envelope.audioHeaders,expiresAtMs:Number(new URL(uri).searchParams.get('expire'))*1000,mimeType:audio.mime_type.split(';')[0]}});
  }finally{bridge.close();}
})();
