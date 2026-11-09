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
open(p,'w',encoding='utf-8').write(s)
a=json.load(open('dist/appinfo.json')); parts=(re.findall(r'\d+',v)+['0','0','0'])[:3]; a['version']='.'.join(parts); json.dump(a,open('dist/appinfo.json','w'),ensure_ascii=False,indent=2)
P
grep -q 'src="lg.js"' dist/index.html
ares-package dist -o out
ls -la out
