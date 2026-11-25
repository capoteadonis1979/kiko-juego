package com.capote.guardacontactos;

import org.json.JSONArray;
import org.json.JSONObject;

public class PruebaGemini {
    static int mal = 0;
    static void ok(boolean c, String m) { System.out.println((c ? "OK   " : "MAL  ") + m); if (!c) mal++; }

    public static void main(String[] a) throws Exception {
        Gemini.BASE = "http://127.0.0.1:" + a[0] + "/v1beta/";
        String modelo = Gemini.elegirModelo("CLAVE");
        ok("gemini-3.5-flash-lite".equals(modelo), "elige el Flash-Lite estable más nuevo: " + modelo);
        try { Gemini.elegirModelo("MALA"); ok(false, "clave mala debía fallar"); }
        catch (Exception e) { ok(Gemini.motivo(e.getMessage()).startsWith("La clave"), "clave mala -> " + Gemini.motivo(e.getMessage())); }

        final String[] guardado = {""};
        Gemini g = new Gemini("CLAVE", modelo, new Gemini.Manos() {
            public JSONObject usar(String n, JSONObject args) throws Exception {
                if (n.equals("ultimas_llamadas")) {
                    JSONArray l = new JSONArray();
                    l.put(new JSONObject().put("numero", "912000111").put("nombre", "C Ana 20.50 Benfica"));
                    l.put(new JSONObject().put("numero", "923456789").put("nombre", JSONObject.NULL));
                    return new JSONObject().put("llamadas", l);
                }
                if (n.equals("guardar_contacto")) { guardado[0] = args.getString("nombre") + " / " + args.getString("numero"); return new JSONObject().put("ok", true); }
                return null;
            }
        });
        Gemini.Respuesta r = g.turno("revisa los últimos cinco números a ver si falta alguno por guardar");
        ok(r.error == null && r.texto.equals("Hay uno sin nombre: el 923 456 789. ¿Lo guardo?") && !r.cerrar, "turno 1 -> " + r.texto);
        r = g.turno("sí, es un cliente, Juan, veinte punto cincuenta, cantina");
        ok(r.error == null && r.texto.equals("Guardado. Hasta luego.") && r.cerrar, "turno 2 -> " + r.texto + " cerrar=" + r.cerrar);
        ok(guardado[0].equals("C Juan 20.50 cantina / 923456789"), "guardó: " + guardado[0]);
        r = g.turno("otra cosa");
        ok(r.error != null && r.texto.startsWith("Gemini dice que se acabó"), "sin cupo -> " + r.texto);

        JSONObject f = new JSONObject(Gemini.http("GET", "http://127.0.0.1:" + a[0] + "/fallos", "x", null));
        ok(f.getJSONArray("fallos").length() == 0, "lo que mandó la app al servidor: " + f);
        System.out.println(Gemini.instrucciones().substring(0, 200) + "…");
        if (mal > 0) System.exit(1);
    }
}
