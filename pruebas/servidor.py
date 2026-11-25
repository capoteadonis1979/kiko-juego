import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
paso = {'n': 0}
fallos = []
def ok(c, m):
    if not c: fallos.append(m)
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def enviar(self, code, obj):
        b = json.dumps(obj).encode(); self.send_response(code); self.send_header('Content-Type','application/json'); self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        if self.path.startswith('/fallos'): return self.enviar(200, {'fallos': fallos, 'pasos': paso['n']})
        if self.headers.get('x-goog-api-key') != 'CLAVE': return self.enviar(400, {'error': {'message': 'API key not valid'}})
        self.enviar(200, {'models': [
            {'name': 'models/gemini-3.1-flash-lite', 'supportedGenerationMethods': ['generateContent']},
            {'name': 'models/gemini-3.5-flash-lite', 'supportedGenerationMethods': ['generateContent']},
            {'name': 'models/gemini-3.8-flash-lite-tts', 'supportedGenerationMethods': ['generateContent']},
            {'name': 'models/gemini-3.6-flash', 'supportedGenerationMethods': ['generateContent']},
            {'name': 'models/gemini-embedding-2', 'supportedGenerationMethods': ['embedContent']}]})
    def do_POST(self):
        d = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        paso['n'] += 1; n = paso['n']; c = d['contents']
        ok(self.path.endswith('/models/gemini-3.5-flash-lite:generateContent'), 'ruta ' + self.path)
        ok(self.headers.get('x-goog-api-key') == 'CLAVE', 'cabecera clave')
        ok('C Juan 20.50' in d['system_instruction']['parts'][0]['text'], 'instrucciones')
        ok(len(d['tools'][0]['functionDeclarations']) == 4, 'herramientas')
        if n == 1:
            ok(c[-1]['role'] == 'user' and 'cinco' in c[-1]['parts'][0]['text'], 'p1 texto usuario')
            return self.enviar(200, {'candidates': [{'content': {'role': 'model', 'parts': [
                {'functionCall': {'id': 'abc1', 'name': 'ultimas_llamadas', 'args': {'cantidad': 5}}, 'thoughtSignature': 'FIRMA1'}]}}]})
        if n == 2:
            ok(c[-2]['parts'][0].get('thoughtSignature') == 'FIRMA1', 'p2 firma devuelta')
            fr = c[-1]['parts'][0]['functionResponse']
            ok(c[-1]['role'] == 'user' and fr['id'] == 'abc1' and fr['name'] == 'ultimas_llamadas', 'p2 respuesta de funcion')
            ok(fr['response']['llamadas'][1]['nombre'] is None, 'p2 nombre null')
            return self.enviar(200, {'candidates': [{'content': {'role': 'model', 'parts': [
                {'text': 'pensando', 'thought': True}, {'text': 'Hay **uno** sin nombre: el 923 456 789. ¿Lo guardo?'}]}}]})
        if n == 3:
            ok('cliente' in c[-1]['parts'][0]['text'], 'p3 texto usuario'); ok(len(c) == 5, 'p3 historia %d' % len(c))
            return self.enviar(200, {'candidates': [{'content': {'parts': [
                {'functionCall': {'id': 'g1', 'name': 'guardar_contacto', 'args': {'numero': '923456789', 'nombre': 'C Juan 20.50 cantina'}}}]}}]})
        if n == 4:
            fr = c[-1]['parts'][0]['functionResponse']
            ok(fr['id'] == 'g1' and fr['response'].get('ok') is True, 'p4 guardado')
            return self.enviar(200, {'candidates': [{'content': {'role': 'model', 'parts': [
                {'text': 'Guardado. Hasta luego.'}, {'functionCall': {'id': 't1', 'name': 'terminar', 'args': {}}}]}}]})
        return self.enviar(429, {'error': {'message': 'quota'}})
HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
