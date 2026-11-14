/* ===== Secretario de Capote Apps =====
   Cobra y activa solo las apps (Dominó, Parchís...). Vive en la cuenta capoteapps@gmail.com.
   - PayPal: lee los correos de "has recibido un pago" de PayPal en esta misma cuenta.
   - USDT (TRC20): mira la cartera en la red Tron.
   - BAI: te manda un correo con un botón APROBAR; al tocarlo, la app del cliente se activa.
   Datos privados (no van en el código): Propiedades del proyecto → ADMIN_KEY, USDT_ADDR, BAI_IBAN, BAI_TITULAR, AVISO_EMAIL */

var APPS = {
  domino:  { nombre: 'Dominó Cubano TV', lsec: 'e67515d177dd76cef63b7383', M: 5, P: 20 },
  parchis: { nombre: 'Parchís Cubano TV', lsec: '9c41f0a7be23d58e16a0c7d4', M: 5, P: 20 }
};
var PAYPAL_EMAIL = 'capoteapps@gmail.com';
var USDT_CONTRACT = 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t';   // USDT en Tron (TRC20)
var B32 = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
var HOJA = 'Pedidos';
var COLS = ['ref','app','dev','plan','met','importe','creado','estado','codigo','pagadoEn','prueba'];

function P_(k){ return PropertiesService.getScriptProperties().getProperty(k) || ''; }
function json_(o){ return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }

/* ---------- códigos (igual que en las apps y en el Activador) ---------- */
function hcode_(str, n){
  var b = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, str, Utilities.Charset.UTF_8), o = '';
  for (var i = 0; i < n; i++) o += B32[(b[i] & 255) & 31];
  return o;
}
function codigo_(app, plan, dev){
  var s = APPS[app].lsec, id = String(dev).replace('-', '');
  if (plan === 'P') return 'P' + hcode_(s + '|P|' + id, 8);
  var d = new Date(); d.setDate(1); d.setMonth(d.getMonth() + 1);   // hasta fin del mes que viene (como "+1 mes" del Activador)
  var ym = ('0' + (d.getFullYear() % 100)).slice(-2) + ('0' + (d.getMonth() + 1)).slice(-2);
  return 'M' + ym + hcode_(s + '|M' + ym + '|' + id, 8);
}

/* ---------- hoja de pedidos ---------- */
function hoja_(){
  var ss, id = P_('SHEET_ID');
  if (id) ss = SpreadsheetApp.openById(id);
  else { ss = SpreadsheetApp.create('Capote Apps - Ventas'); PropertiesService.getScriptProperties().setProperty('SHEET_ID', ss.getId()); }
  var sh = ss.getSheetByName(HOJA);
  if (!sh) { sh = ss.getSheets()[0]; sh.setName(HOJA); sh.getRange(1, 1, 1, COLS.length).setValues([COLS]).setFontWeight('bold'); sh.setFrozenRows(1); }
  return sh;
}
function filas_(){
  var sh = hoja_(), n = sh.getLastRow();
  if (n < 2) return [];
  return sh.getRange(2, 1, n - 1, COLS.length).getValues().map(function(r, i){
    var o = { fila: i + 2 }; COLS.forEach(function(c, j){ o[c] = r[j]; }); return o; });
}
function guarda_(o){
  var sh = hoja_(), row = COLS.map(function(c){ return o[c] === undefined ? '' : o[c]; });
  if (o.fila) sh.getRange(o.fila, 1, 1, COLS.length).setValues([row]); else { sh.appendRow(row); o.fila = sh.getLastRow(); }
}
function nuevaRef_(){ var s = ''; for (var i = 0; i < 6; i++) s += B32[Math.floor(Math.random() * 32)]; return s; }

/* ---------- la web que llaman las apps ---------- */
function doGet(e){
  var q = (e && e.parameter) || {};
  try {
    if (q.a === 'ok') return aprobar_(q);
    if (q.a === 'cfg') return json_({ ok: true, met: { pp: true, usdt: !!P_('USDT_ADDR'), bai: !!P_('BAI_IBAN') } });
    var app = q.app, dev = String(q.id || '').toUpperCase();
    if (!APPS[app] || !/^[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(dev)) return json_({ ok: false, err: 'datos' });
    if (q.a === 'new') return json_(nuevo_(app, dev, q.plan, q.met));
    if (q.a === 'st' || q.a === 'paid') return json_(estado_(app, dev, q.a === 'paid'));
    return json_({ ok: false, err: 'accion' });
  } catch (err) { return json_({ ok: false, err: String(err) }); }
}

function nuevo_(app, dev, plan, met){
  if (plan !== 'M' && plan !== 'P') return { ok: false, err: 'plan' };
  if (['pp', 'usdt', 'bai'].indexOf(met) < 0) return { ok: false, err: 'met' };
  if ((met === 'usdt' && !P_('USDT_ADDR')) || (met === 'bai' && !P_('BAI_IBAN'))) return { ok: false, err: 'met' };
  var lock = LockService.getScriptLock(); lock.waitLock(20000);
  try {
    var base = APPS[app][plan], ahora = Date.now(), L = filas_(), usados = {};
    // si ya hay un pedido igual sin pagar de este equipo, se reutiliza
    for (var i = L.length - 1; i >= 0; i--) { var r = L[i];
      if (r.app === app && r.dev === dev && r.plan === plan && r.met === met && r.estado === 'pendiente' && ahora - new Date(r.creado).getTime() < 48 * 3600e3) return respuesta_(r); }
    L.forEach(function(r){ if (r.estado === 'pendiente' && r.met === met && ahora - new Date(r.creado).getTime() < 72 * 3600e3) usados[Number(r.importe).toFixed(2)] = 1; });
    var imp = base;
    if (met !== 'bai') { // céntimos únicos para reconocer cada pago
      for (var t = 0; t < 200; t++) { var c = base + (1 + Math.floor(Math.random() * 98)) / 100; if (!usados[c.toFixed(2)]) { imp = c; break; } } }
    var o = { ref: nuevaRef_(), app: app, dev: dev, plan: plan, met: met, importe: Number(imp.toFixed(2)), creado: new Date(), estado: 'pendiente', codigo: '', pagadoEn: '', prueba: '' };
    guarda_(o);
    if (met === 'bai') avisoBai_(o);
    return respuesta_(o);
  } finally { lock.releaseLock(); }
}

function respuesta_(o){
  var a = APPS[o.app], imp = Number(o.importe).toFixed(2), plan = o.plan === 'P' ? 'para siempre' : '1 mes';
  var r = { ok: true, ref: o.ref, importe: imp + ' USD', plan: o.plan };
  if (o.met === 'pp') {
    r.nota = 'Paga EXACTAMENTE esta cantidad por PayPal a:';
    r.a = PAYPAL_EMAIL;
    r.url = 'https://www.paypal.com/cgi-bin/webscr?cmd=_xclick&business=' + encodeURIComponent(PAYPAL_EMAIL) +
      '&amount=' + imp + '&currency_code=USD&no_shipping=1&item_name=' + encodeURIComponent(a.nombre + ' - ' + plan + ' - ' + o.ref);
    r.qr = 'Escanea con el teléfono para pagar con PayPal';
  } else if (o.met === 'usdt') {
    r.nota = 'Envía EXACTAMENTE esta cantidad de USDT (red TRON · TRC20) a:';
    r.a = P_('USDT_ADDR');
    r.url = r.a;
    r.qr = 'Escanea la dirección con tu cartera (red TRC20)';
  } else {
    var kz = P_('BAI_KZ_' + o.plan);
    r.nota = 'Transferencia al BAI' + (kz ? ' de ' + kz + ' Kz' : ' (' + imp + ' USD en kwanzas)') + ' al IBAN:';
    if (kz) r.importe = kz + ' Kz';
    r.a = P_('BAI_IBAN');
    r.titular = P_('BAI_TITULAR');
    r.url = 'https://wa.me/244926815829?text=' + encodeURIComponent('Hola, pagué ' + a.nombre + ' (' + plan + ') por BAI. Pedido ' + o.ref + ', equipo ' + o.dev + '. Te mando el comprobante.');
    r.qr = 'Después manda el comprobante: escanea para abrir WhatsApp';
  }
  return r;
}

function estado_(app, dev, revisarYa){
  var L = filas_(), ult = null;
  for (var i = L.length - 1; i >= 0; i--) if (L[i].app === app && L[i].dev === dev) { if (L[i].estado === 'pagado') return { ok: true, est: 'pagado', code: L[i].codigo }; if (!ult) ult = L[i]; }
  if (!ult) return { ok: true, est: 'nada' };
  if (revisarYa) { revisarPagos(); return estado_(app, dev, false); }
  return { ok: true, est: ult.estado };
}

function marcarPagado_(o, prueba){
  o.estado = 'pagado'; o.codigo = codigo_(o.app, o.plan, o.dev); o.pagadoEn = new Date(); o.prueba = prueba; guarda_(o);
  try { MailApp.sendEmail(P_('AVISO_EMAIL') || PAYPAL_EMAIL, '💰 Venta: ' + APPS[o.app].nombre + ' ' + (o.plan === 'P' ? 'para siempre' : '1 mes') + ' (' + o.met.toUpperCase() + ')',
    'Pedido ' + o.ref + '\nEquipo ' + o.dev + '\nImporte ' + o.importe + '\nCódigo enviado: ' + o.codigo + '\nPrueba: ' + prueba); } catch (_) {}
}

/* ---------- BAI: aviso con botón APROBAR ---------- */
function avisoBai_(o){
  var url = ScriptApp.getService().getUrl() + '?a=ok&ref=' + o.ref + '&k=' + encodeURIComponent(P_('ADMIN_KEY'));
  try { MailApp.sendEmail({ to: P_('AVISO_EMAIL') || PAYPAL_EMAIL, subject: '🏦 Pedido BAI ' + o.ref + ' — ' + APPS[o.app].nombre,
    htmlBody: 'Pedido <b>' + o.ref + '</b> · equipo <b>' + o.dev + '</b> · ' + (o.plan === 'P' ? 'para siempre' : '1 mes') +
      '<br><br>Cuando veas la transferencia en el BAI, toca:<br><br><a href="' + url + '" style="background:#1f8f4e;color:#fff;padding:12px 22px;border-radius:8px;text-decoration:none;font-weight:bold">✅ APROBAR Y ACTIVAR</a>' }); } catch (_) {}
}
function aprobar_(q){
  var msg;
  if (!P_('ADMIN_KEY') || q.k !== P_('ADMIN_KEY')) msg = 'Clave incorrecta.';
  else { var o = filas_().filter(function(r){ return r.ref === q.ref; })[0];
    if (!o) msg = 'No existe el pedido ' + q.ref;
    else if (o.estado === 'pagado') msg = 'El pedido ' + o.ref + ' ya estaba activado (código ' + o.codigo + ').';
    else { marcarPagado_(o, 'BAI aprobado a mano'); msg = '✅ Activado. Pedido ' + o.ref + ', equipo ' + o.dev + ', código ' + o.codigo + '. La app del cliente se activa sola en un momento.'; } }
  return HtmlService.createHtmlOutput('<meta name="viewport" content="width=device-width,initial-scale=1"><div style="font:20px Arial;padding:24px">' + msg + '</div>');
}

/* ---------- revisar pagos (lo llama el reloj cada minuto) ---------- */
function revisarPagos(){
  var lock = LockService.getScriptLock(); if (!lock.tryLock(25000)) return;
  try {
    var ahora = Date.now(), pend = filas_().filter(function(r){ return r.estado === 'pendiente' && ahora - new Date(r.creado).getTime() < 72 * 3600e3; });
    if (!pend.length) return;
    var pp = pend.filter(function(r){ return r.met === 'pp'; }), us = pend.filter(function(r){ return r.met === 'usdt'; });
    if (pp.length) revisarPayPal_(pp);
    if (us.length) revisarUsdt_(us);
  } finally { lock.releaseLock(); }
}

function revisarPayPal_(pend){
  var lab = GmailApp.getUserLabelByName('Secretario-visto') || GmailApp.createLabel('Secretario-visto');
  var hilos = GmailApp.search('from:(paypal.com) newer_than:4d -label:Secretario-visto', 0, 30);
  hilos.forEach(function(h){
    var usado = false;
    h.getMessages().forEach(function(m){
      if (usado) return;
      var txt = (m.getSubject() + '\n' + m.getPlainBody()).replace(/ /g, ' ');
      if (!/recib|receiv|recebe|got money|has money|dinero|money/i.test(m.getSubject())) return;
      if (/enviaste|you sent|you paid|pagaste|enviou|pagou/i.test(m.getSubject())) return;
      for (var i = 0; i < pend.length; i++) { var o = pend[i]; if (o.estado !== 'pendiente') continue;
        var imp = Number(o.importe).toFixed(2), alt = imp.replace('.', ',');
        var re = new RegExp('(^|[^0-9])(' + imp.replace('.', '\\.') + '|' + alt + ')([^0-9]|$)');
        if (txt.indexOf(o.ref) >= 0 || (re.test(txt) && /USD|US\$|\$/.test(txt))) {
          if (new Date(m.getDate()).getTime() < new Date(o.creado).getTime() - 5 * 60e3) continue;   // correo anterior al pedido
          marcarPagado_(o, 'PayPal: ' + m.getSubject() + ' (' + m.getDate() + ')'); usado = true; break; } }
    });
    if (usado) h.addLabel(lab);
  });
}

function revisarUsdt_(pend){
  var addr = P_('USDT_ADDR'); if (!addr) return;
  var desde = Math.min.apply(null, pend.map(function(o){ return new Date(o.creado).getTime(); })) - 10 * 60e3;
  var url = 'https://api.trongrid.io/v1/accounts/' + addr + '/transactions/trc20?only_to=true&limit=50&contract_address=' + USDT_CONTRACT + '&min_timestamp=' + desde;
  var res = UrlFetchApp.fetch(url, { muteHttpExceptions: true, headers: P_('TRON_KEY') ? { 'TRON-PRO-API-KEY': P_('TRON_KEY') } : {} });
  if (res.getResponseCode() !== 200) return;
  var vistos = JSON.parse(P_('TX_VISTAS') || '[]');
  (JSON.parse(res.getContentText()).data || []).forEach(function(t){
    if (vistos.indexOf(t.transaction_id) >= 0) return;
    var v = Number(t.value) / Math.pow(10, Number((t.token_info && t.token_info.decimals) || 6));
    for (var i = 0; i < pend.length; i++) { var o = pend[i];
      if (o.estado === 'pendiente' && Math.abs(v - Number(o.importe)) < 0.005 && t.block_timestamp >= new Date(o.creado).getTime() - 5 * 60e3) {
        marcarPagado_(o, 'USDT tx ' + t.transaction_id); vistos.push(t.transaction_id); break; } }
  });
  PropertiesService.getScriptProperties().setProperty('TX_VISTAS', JSON.stringify(vistos.slice(-300)));
}

/* ---------- se ejecuta UNA vez al instalar ---------- */
function instalar(){
  var pr = PropertiesService.getScriptProperties();
  if (!pr.getProperty('ADMIN_KEY')) pr.setProperty('ADMIN_KEY', Utilities.getUuid().replace(/-/g, '').slice(0, 20));
  if (!pr.getProperty('BAI_IBAN')) pr.setProperty('BAI_IBAN', 'AO06 0040 0000 2008 3817 1018 1');
  if (!pr.getProperty('USDT_ADDR')) pr.setProperty('USDT_ADDR', 'TBcdoujQZaQaQgc2D1w6rksyfVg2cwvnas');
  if (!pr.getProperty('BAI_TITULAR')) pr.setProperty('BAI_TITULAR', 'Adonis Capote Araujo');
  hoja_();
  ScriptApp.getProjectTriggers().forEach(function(t){ if (t.getHandlerFunction() === 'revisarPagos') ScriptApp.deleteTrigger(t); });
  ScriptApp.newTrigger('revisarPagos').timeBased().everyMinutes(1).create();
  GmailApp.getUserLabelByName('Secretario-visto') || GmailApp.createLabel('Secretario-visto');
  Logger.log('Listo. Hoja: ' + SpreadsheetApp.openById(pr.getProperty('SHEET_ID')).getUrl());
}
