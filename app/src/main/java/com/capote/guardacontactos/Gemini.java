package com.capote.guardacontactos;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La inteligencia de Marisol: habla con Gemini (modelo Flash-Lite, el barato)
 * y le deja usar tres "manos": ver las últimas llamadas, buscar contactos y guardar un contacto.
 * Solo se llama a Gemini cuando el usuario le pide algo; la escucha de la palabra clave no lo usa.
 * No depende de Android (salvo org.json), para poder probarlo en el ordenador.
 */
final class Gemini {
    /** Lo que Marisol puede hacer en el teléfono. */
    interface Manos { JSONObject usar(String nombre, JSONObject args) throws Exception; }

    static final class Respuesta {
        String texto = "";
        boolean cerrar;     // ella dio por terminada la conversación
        String error;       // null si todo fue bien
    }

    static String BASE = "https://generativelanguage.googleapis.com/v1beta/";
    static final String RESERVA = "gemini-3.5-flash-lite";

    private final String clave, modelo;
    private final Manos manos;
    private final JSONArray historia = new JSONArray();

    Gemini(String clave, String modelo, Manos manos) {
        this.clave = clave;
        this.modelo = (modelo == null || modelo.length() == 0) ? RESERVA : modelo;
        this.manos = manos;
    }

    // ---------- red ----------

    private static String leer(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    static String http(String metodo, String url, String clave, String cuerpo) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(metodo);
            c.setConnectTimeout(12000);
            c.setReadTimeout(30000);
            c.setRequestProperty("x-goog-api-key", clave);
            if (cuerpo != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream o = c.getOutputStream();
                o.write(cuerpo.getBytes("UTF-8"));
                o.close();
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) return leer(c.getInputStream());
            String err = leer(c.getErrorStream());
            String msg = "";
            try { msg = new JSONObject(err).getJSONObject("error").optString("message", ""); } catch (Exception e) { /* sin detalle */ }
            throw new IOException("HTTP " + code + (msg.length() > 0 ? ": " + msg : ""));
        } finally {
            c.disconnect();
        }
    }

    /** Frase corta, para decir en voz, de por qué falló. */
    static String motivo(String error) {
        if (error == null) return "";
        if (error.startsWith("HTTP 400") || error.startsWith("HTTP 401") || error.startsWith("HTTP 403"))
            return "La clave de Gemini no sirve o no tiene permiso.";
        if (error.startsWith("HTTP 429")) return "Gemini dice que se acabó el cupo por ahora.";
        if (error.startsWith("HTTP 404")) return "Gemini no encuentra el modelo.";
        if (error.startsWith("HTTP 5")) return "Gemini está fallando ahora mismo.";
        if (error.startsWith("HTTP")) return "Gemini contestó con un error.";
        return "No hay conexión con Gemini.";
    }

    /**
     * Mira qué modelos tiene la clave y elige el Flash-Lite estable más nuevo
     * (si no hay, el Flash). Sirve también para comprobar que la clave funciona.
     */
    static String elegirModelo(String clave) throws Exception {
        JSONObject r = new JSONObject(http("GET", BASE + "models?pageSize=1000", clave, null));
        JSONArray m = r.optJSONArray("models");
        String mejorLite = null, mejorFlash = null;
        double vLite = -1, vFlash = -1;
        Pattern p = Pattern.compile("^models/(gemini-(\\d+(?:\\.\\d+)?)-flash(-lite)?)$");
        for (int i = 0; m != null && i < m.length(); i++) {
            JSONObject o = m.getJSONObject(i);
            JSONArray met = o.optJSONArray("supportedGenerationMethods");
            boolean genera = met == null;
            for (int k = 0; met != null && k < met.length(); k++) if ("generateContent".equals(met.optString(k))) genera = true;
            if (!genera) continue;
            Matcher x = p.matcher(o.optString("name"));
            if (!x.matches()) continue;
            double v = Double.parseDouble(x.group(2));
            if (x.group(3) != null) { if (v > vLite) { vLite = v; mejorLite = x.group(1); } }
            else if (v > vFlash) { vFlash = v; mejorFlash = x.group(1); }
        }
        if (mejorLite != null) return mejorLite;
        if (mejorFlash != null) return mejorFlash;
        return RESERVA;
    }

    // ---------- lo que se le explica a Gemini ----------

    private static JSONObject prop(String tipo, String desc) throws Exception {
        return new JSONObject().put("type", tipo).put("description", desc);
    }

    private static JSONObject funcion(String nombre, String desc, JSONObject props, String... obligatorios) throws Exception {
        JSONObject par = new JSONObject().put("type", "object").put("properties", props);
        JSONArray req = new JSONArray();
        for (String s : obligatorios) req.put(s);
        if (req.length() > 0) par.put("required", req);
        return new JSONObject().put("name", nombre).put("description", desc).put("parameters", par);
    }

    private static JSONArray herramientas() throws Exception {
        JSONArray f = new JSONArray();
        f.put(funcion("ultimas_llamadas",
                "Devuelve las últimas llamadas del teléfono, de la más nueva a la más vieja. Cada una trae numero, tipo "
                        + "(entrante, saliente, perdida, rechazada), cuando, duracion_seg y nombre. Si nombre es null, ese número NO está guardado en contactos.",
                new JSONObject().put("cantidad", prop("integer", "Cuántas llamadas mirar, de 1 a 30.")), "cantidad"));
        f.put(funcion("buscar_contacto",
                "Busca en los contactos guardados por nombre o por número. Devuelve una lista con nombre y numero.",
                new JSONObject().put("texto", prop("string", "Parte del nombre o del número.")), "texto"));
        f.put(funcion("guardar_contacto",
                "Crea un contacto nuevo en el teléfono. Usar solo después de que el usuario confirme el nombre.",
                new JSONObject().put("numero", prop("string", "Número de teléfono, solo cifras (puede llevar + delante)."))
                        .put("nombre", prop("string", "Nombre exacto con que queda guardado.")), "numero", "nombre"));
        f.put(funcion("terminar",
                "Cierra la conversación. Usar cuando el usuario ya no necesita nada más o se despide.",
                new JSONObject()));
        return new JSONArray().put(new JSONObject().put("functionDeclarations", f));
    }

    static String instrucciones() {
        String ahora = new SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", new Locale("es")).format(new Date());
        return "Eres Marisol, la secretaria por voz de Adonis. Él reparte agua con un camión cisterna en Luanda y casi siempre "
                + "te habla mientras maneja, así que no puede mirar ni tocar el teléfono. Ahora es " + ahora + ".\n\n"
                + "CÓMO HABLAS: en español, corto y natural, como una persona; una o dos frases. Tu respuesta se lee en voz alta: "
                + "nada de listas, símbolos, asteriscos ni emojis. Los teléfonos dilos en grupos de tres cifras. "
                + "Lo que él dice te llega por un reconocedor de voz y puede venir con errores: interprétalo con sentido común.\n\n"
                + "QUÉ PUEDES HACER: ver las últimas llamadas, buscar contactos y guardar contactos nuevos. Usa las funciones para "
                + "mirar los datos de verdad; nunca inventes números ni nombres. Si te pide algo que no puedes hacer, dilo en una frase.\n\n"
                + "CÓMO SE ESCRIBE EL NOMBRE AL GUARDAR:\n"
                + "- Si es un CLIENTE: la letra C mayúscula, espacio, el nombre, espacio, cantidad punto precio, espacio, la dirección. "
                + "Ejemplo exacto: «C Juan 20.50 cantina próximo a la bomba». La cantidad y el precio van en miles y sin ceros: "
                + "veinte mil litros a cincuenta mil se escribe 20.50. Si no dio precio va solo la cantidad; si no dio dirección, se omite.\n"
                + "- Si NO es cliente: el nombre tal cual lo diga, con mayúsculas de nombre propio y sin la C. Ejemplo: «Ricardo Cubano».\n\n"
                + "ANTES DE GUARDAR: dile cómo va a quedar y pregúntale si está bien; llama a guardar_contacto solo cuando diga que sí. "
                + "Si pide revisar varias llamadas, dile cuáles números no tienen nombre y ve preguntándole uno por uno.\n\n"
                + "AL TERMINAR: cuando diga gracias, ya, nada más o se despida, contesta con dos o tres palabras y llama a terminar.";
    }

    // ---------- un turno de conversación (bloquea: llamar desde un hilo aparte) ----------

    Respuesta turno(String dicho) {
        Respuesta r = new Respuesta();
        try {
            historia.put(new JSONObject().put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", dicho))));
            JSONArray tools = herramientas();
            JSONObject sis = new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", instrucciones())));
            String texto = "";
            for (int vuelta = 0; vuelta < 6; vuelta++) {
                JSONObject req = new JSONObject()
                        .put("system_instruction", sis)
                        .put("contents", historia)
                        .put("tools", tools)
                        .put("generationConfig", new JSONObject().put("temperature", 0.3));
                JSONObject res = new JSONObject(http("POST", BASE + "models/" + modelo + ":generateContent", clave, req.toString()));
                JSONArray cands = res.optJSONArray("candidates");
                JSONObject content = (cands == null || cands.length() == 0) ? null : cands.getJSONObject(0).optJSONObject("content");
                JSONArray parts = content == null ? null : content.optJSONArray("parts");
                if (parts == null || parts.length() == 0) {
                    if (texto.length() > 0 || r.cerrar) break;
                    r.error = "vacio";
                    r.texto = "Gemini no me contestó. Dímelo otra vez.";
                    return r;
                }
                if (!content.has("role")) content.put("role", "model");
                historia.put(content);   // tal cual vino, con sus firmas, como pide la API

                StringBuilder sb = new StringBuilder();
                JSONArray resp = new JSONArray();
                boolean soloCerrar = true;
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.getJSONObject(i);
                    if (p.has("text") && !p.optBoolean("thought", false)) sb.append(p.optString("text"));
                    JSONObject fc = p.optJSONObject("functionCall");
                    if (fc == null) continue;
                    String nombre = fc.optString("name");
                    JSONObject args = fc.optJSONObject("args");
                    if (args == null) args = new JSONObject();
                    JSONObject out;
                    if ("terminar".equals(nombre)) {
                        r.cerrar = true;
                        out = new JSONObject().put("ok", true);
                    } else {
                        soloCerrar = false;
                        try { out = manos.usar(nombre, args); }
                        catch (Exception e) { out = new JSONObject().put("error", String.valueOf(e.getMessage())); }
                        if (out == null) out = new JSONObject().put("error", "funcion desconocida");
                    }
                    JSONObject fr = new JSONObject().put("name", nombre).put("response", out);
                    if (fc.has("id")) fr.put("id", fc.get("id"));
                    resp.put(new JSONObject().put("functionResponse", fr));
                }
                if (sb.toString().trim().length() > 0) texto = sb.toString().trim();
                if (resp.length() == 0) break;                                   // ya contestó
                historia.put(new JSONObject().put("role", "user").put("parts", resp));
                if (soloCerrar && texto.length() > 0) break;                     // se despidió y cerró
            }
            r.texto = limpiar(texto.length() > 0 ? texto : (r.cerrar ? "Listo." : "Hecho."));
        } catch (Exception e) {
            r.error = String.valueOf(e.getMessage());
            r.texto = motivo(r.error);
        }
        return r;
    }

    /** Quita símbolos que no se deben leer en voz. */
    static String limpiar(String t) {
        return t.replaceAll("[*#_`]+", "").replaceAll("\\s+", " ").trim();
    }
}
