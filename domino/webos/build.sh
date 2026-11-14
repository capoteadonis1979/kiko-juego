#!/bin/bash
# Arma la copia para teles LG (webOS) a partir del juego de Android (no lo toca) y la empaqueta en .ipk
set -e
cd "$(dirname "$0")"
rm -rf dist && mkdir -p dist out
cp -r ../app/src/main/assets/www/* dist/
cp appinfo.json icon.png largeIcon.png lg.js dist/
V=$(grep -o "versionName '[^']*'" ../app/build.gradle | cut -d"'" -f2)
python3 - "$V" <<'P'
import sys,re,json
v=sys.argv[1]; p='dist/index.html'; s=open(p,encoding='utf-8').read()
s=s.replace('<head><meta charset="utf-8">','<head><meta charset="utf-8"><script src="lg.js"></script>',1)
import re as _re
lgbuy='''<h3>4 · Comprarla</h3>
      <ul>
        <li><b>3 partidas gratis al día</b>. Para jugar sin límite: <b>5 USD al mes</b> o <b>20 USD para siempre</b>.</li>
        <li>Toca <b>ACTIVAR / COMPRAR</b>, elige el plan y cómo pagas. En la tele sale un <b>código QR</b>: escanéalo con el cel y paga desde ahí. Con <b>PayPal</b> y <b>USDT</b> la tele se activa sola en un par de minutos; con <b>transferencia BAI</b> (Angola), manda el comprobante por WhatsApp y se activa en cuanto lo aprobemos.</li>
        <li>También puedes pedir un <b>código de activación</b>: manda el <b>código de tu equipo</b> (sale en ACTIVAR / COMPRAR) por <b>WhatsApp +244 926 815 829</b>. Solo paga la tele; los que juegan con el teléfono no pagan.</li>
      </ul>
      '''
s,n=_re.subn(r'<h3>4 · Bajar la app y comprarla</h3>.*?(?=<h3>Créditos</h3>)',lambda m: lgbuy,s,count=1,flags=_re.S)
assert n==1
open(p,'w',encoding='utf-8').write(s)
a=json.load(open('dist/appinfo.json')); parts=(re.findall(r'\d+',v)+['0','0','0'])[:3]; a['version']='.'.join(parts); json.dump(a,open('dist/appinfo.json','w'),ensure_ascii=False,indent=2)
P
grep -q 'src="lg.js"' dist/index.html
ares-package dist -o out
ls -la out
