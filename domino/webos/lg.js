/* Dominó Cubano para teles LG (webOS).
   La tele LG no puede recibir a los teléfonos: uno de los teléfonos (con el APK) hace de CENTRAL
   y esta tele se engancha a él por WebSocket (puerto 47812). Aquí se imita la parte "tele" de KikoApp. */
(function(){
  var PORT=47812;
  function lsg(k){ try{ return localStorage.getItem(k); }catch(_){ return null; } }
  function lss(k,v){ try{ localStorage.setItem(k,v); }catch(_){} }
  var did=lsg('lgDid'); if(!did){ did='lg'+Math.random().toString(36).slice(2,10)+Date.now().toString(36); lss('lgDid',did); }
  var ws=null, busy=false, known={}, wantHost=false;
  window.KIKO_LG=true;
  window.KikoApp={
    isTV:function(){ return true; },
    deviceId:function(){ return did; },
    startHost:function(){ wantHost=true; find(); },
    sendTo:function(id,l){ if(ws&&ws.readyState===1) try{ ws.send(JSON.stringify({k:'send',id:id,line:l})); }catch(_){} },
    exitApp:function(){ try{ window.close(); }catch(_){} },
    openUrl:function(u){ try{ window.open(u); }catch(_){} },
    showKeyboard:function(){}, setPortrait:function(){}
  };
  // aviso arriba: qué hacer para conectar los teléfonos
  var bar=null;
  function status(t,ok){ if(!document.body) return; if(!bar){ bar=document.createElement('div'); bar.id='lgbar';
      bar.style.cssText='position:fixed;left:50%;top:6px;transform:translateX(-50%);z-index:90;font:700 15px Fredoka,Arial,sans-serif;color:#fff;background:rgba(0,0,0,.72);border:2px solid #d6a24a;border-radius:12px;padding:6px 14px;max-width:80vw;text-align:center;pointer-events:none';
      document.body.appendChild(bar); }
    bar.style.borderColor=ok?'#2fbf71':'#d6a24a'; bar.innerHTML=t; bar.style.display=t?'block':'none'; }
  function lobbyVisible(){ var l=document.getElementById('lobby'); return !!(l&&!l.hidden&&l.offsetParent!==null); }
  setInterval(function(){ if(bar) bar.style.visibility=(lobbyVisible()||!ws)&&wantHost?'visible':'hidden'; },500);
  function attach(w,ip){ ws=w; lss('lgCentral',ip);
    status('✅ Conectado al teléfono central ('+ip+'). Los demás tocan TELÉFONO → CONECTAR CON TV.',true); setTimeout(function(){ if(ws===w) status(''); },8000);
    w.onmessage=function(e){ var o; try{ o=JSON.parse(e.data); }catch(_){ return; }
      if(o.k==='peer'){ if(o.on) known[o.id]=1; else delete known[o.id]; if(window.kikoPeer) kikoPeer(o.id,!!o.on); }
      else if(o.k==='msg'){ if(window.kikoMsg) kikoMsg(o.id,o.line); }
      else if(o.k==='net'){ if(window.kikoNet) kikoNet(o.n,o.ip,o.ips); } };
    w.onclose=function(){ if(ws!==w) return; ws=null; for(var id in known){ if(window.kikoPeer) kikoPeer(+id,false); } known={};
      status('⚠️ Se perdió el teléfono central. Buscándolo otra vez...'); setTimeout(find,1500); };
  }
  function tryIp(ip,ms){ return new Promise(function(r){ var w, done=false; try{ w=new WebSocket('ws://'+ip+':'+PORT); }catch(_){ r(null); return; }
      var t=setTimeout(function(){ if(done) return; done=true; try{ w.close(); }catch(_){} r(null); },ms);
      w.onopen=function(){ if(done){ try{ w.close(); }catch(_){} return; } done=true; clearTimeout(t); r(w); };
      w.onerror=function(){ if(done) return; done=true; clearTimeout(t); r(null); }; }); }
  function myIps(){ return new Promise(function(r){ var fin=false; function end(v){ if(!fin){ fin=true; r(v); } } setTimeout(function(){ end([]); },2500);
      try{ var b=new PalmServiceBridge(); b.onservicecallback=function(s){ try{ var j=JSON.parse(s), L=[]; ['wired','wifi'].forEach(function(k){ if(j[k]&&j[k].ipAddress) L.push(j[k].ipAddress); }); end(L); }catch(_){ end([]); } };
        b.call('luna://com.webos.service.connectionmanager/getStatus','{}'); }catch(_){ end([]); } }); }
  async function scan(pre){ for(var s=1;s<255&&!ws;s+=48){ var batch=[]; for(var i=s;i<Math.min(255,s+48);i++) batch.push(tryIp(pre+i,1800));
      var res=await Promise.all(batch), ok=-1; for(var k=0;k<res.length;k++){ if(res[k]&&ok<0) ok=k; else if(res[k]) try{ res[k].close(); }catch(_){} }
      if(ok>=0) return [res[ok],pre+(s+ok)]; } return null; }
  async function find(){ if(ws||busy) return; busy=true;
    try{
      status('📱 Abre el Dominó en un teléfono y toca <b>TELÉFONO → CONECTAR CON TV</b>: ese teléfono será la central. Buscándolo...');
      var last=lsg('lgCentral'); if(last){ var w=await tryIp(last,2500); if(w){ attach(w,last); return; } }
      var ips=await myIps(), pres=[];
      ips.forEach(function(ip){ var p=ip.slice(0,ip.lastIndexOf('.')+1); if(pres.indexOf(p)<0) pres.push(p); });
      ['192.168.1.','192.168.0.','192.168.8.','192.168.100.','192.168.43.','10.0.0.','192.168.2.','192.168.18.'].forEach(function(p){ if(pres.indexOf(p)<0) pres.push(p); });
      for(var q=0;q<pres.length&&!ws;q++){ var f=await scan(pres[q]); if(f){ attach(f[0],f[1]); return; } }
    } finally { busy=false; }
    if(!ws) setTimeout(find,3000);
  }
  // botón ATRÁS del mando LG (461)
  document.addEventListener('keydown',function(e){ if(e.keyCode===461){ e.preventDefault(); e.stopPropagation(); try{ if(window.kikoBack) kikoBack(); }catch(_){} } },true);
})();

/* ===== versión LG: estilos (lo demás va dentro del juego, activado con window.KIKO_LG) ===== */
window.addEventListener('DOMContentLoaded',function(){
  var css=document.createElement('style');
  css.textContent='#tiles .tile.kfoc{outline:5px solid #ffd23a;outline-offset:3px;transform:translateY(-16px);z-index:5} #sidebox button.kfoc{outline:5px solid #ffd23a;outline-offset:3px}';
  document.head.appendChild(css);
});
