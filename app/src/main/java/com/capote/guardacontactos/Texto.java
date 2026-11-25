package com.capote.guardacontactos;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Entiende lo que se dijo y arma el nombre del contacto.
 * Clientes:  "C Nombre 20.50 dirección"  (C, nombre, cantidad.precio, dirección)
 * Los demás: el nombre tal cual se dijo.
 * No depende de Android: se puede probar en el ordenador.
 */
public final class Texto {
    private Texto() {}

    public static final int NADA = 0, SI = 1, NO = 2, DATOS = 3;

    public static final class Res {
        public int tipo = NADA;
        public boolean cliente;      // se guarda con la C delante
        public boolean noCliente;    // dijo expresamente que NO es cliente
        public boolean explicito;    // traía "no", "como", "guárdalo", "cliente"...
        public boolean ultimo;       // pidió "el último número"
        public String telefono = ""; // número dictado (solo modo manual)
        public String nombre = "";   // nombre final del contacto
        public String hablado = "";  // el mismo nombre, para decirlo en voz
    }

    private static final Map<String, Integer> UNI = new HashMap<String, Integer>();
    private static final Map<String, Integer> DEC = new HashMap<String, Integer>();
    private static final Map<String, Integer> CEN = new HashMap<String, Integer>();
    static {
        String[] u = {"cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
                "diez", "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete",
                "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidos", "veintitres",
                "veinticuatro", "veinticinco", "veintiseis", "veintisiete", "veintiocho", "veintinueve"};
        for (int i = 0; i < u.length; i++) UNI.put(u[i], i);
        UNI.put("un", 1); UNI.put("una", 1); UNI.put("veintiun", 21); UNI.put("veintiuna", 21);
        String[] d = {"treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa"};
        for (int i = 0; i < d.length; i++) DEC.put(d[i], 30 + 10 * i);
        String[] c = {"doscient", "trescient", "cuatrocient", "quinient", "seiscient", "setecient",
                "ochocient", "novecient"};
        for (int i = 0; i < c.length; i++) { CEN.put(c[i] + "os", 200 + 100 * i); CEN.put(c[i] + "as", 200 + 100 * i); }
        CEN.put("cien", 100); CEN.put("ciento", 100);
    }

    private static Set<String> set(String... s) { return new HashSet<String>(Arrays.asList(s)); }

    /** Palabras de arranque que no forman parte del nombre. */
    private static final Set<String> ARRANQUE = set(
            "si", "sii", "sip", "dale", "claro", "ok", "okay", "okey", "bueno", "pues", "eh", "ah", "mira",
            "oye", "ya", "por", "favor", "que", "lo", "me", "eso", "ese", "esa", "este", "esta", "ahi",
            "alli", "numero", "contacto", "es", "como", "nombre", "y", "entonces", "quiero", "puedes",
            "ultimo", "ultima", "llamada", "llamo", "nuevo", "nueva",
            "ahora", "mejor", "todavia", "despues", "luego", "hoy",
            "bien", "correcto", "perfecto", "exacto", "exactamente", "aja", "anja", "vale", "va", "listo",
            "hecho", "asi", "mismo", "afirmativo", "positivo", "gracias", "todo", "muy", "ta", "esta",
            "guarda", "guardalo", "guardame", "guardamelo", "guardar", "guardarlo", "guarde", "guardelo",
            "guardes", "guardas", "guardala", "pon", "ponlo", "ponle", "ponme", "ponmelo", "pone",
            "anota", "anotalo", "anotame", "agrega", "agregalo", "salva", "salvalo", "graba", "grabalo",
            "registra", "registralo", "crea", "crealo", "llamalo", "llamale");
    private static final Set<String> VERBOS = set(
            "como", "guarda", "guardalo", "guardame", "guardamelo", "guardar", "guardarlo", "guarde",
            "guardelo", "guardes", "guardas", "guardala", "pon", "ponlo", "ponle", "ponme", "ponmelo",
            "anota", "anotalo", "anotame", "agrega", "agregalo", "salva", "salvalo", "graba", "grabalo",
            "registra", "registralo", "crea", "crealo", "llamalo", "llamale", "nombre");
    private static final Set<String> ART = set("el", "la", "al", "a", "de", "del", "un", "una");
    private static final Set<String> TRAS_ART = set("numero", "contacto", "ultimo", "ultima", "llamada", "acuerdo");
    private static final Set<String> NEG = set("no", "nop", "nel", "negativo", "cancela", "cancelar",
            "cancelalo", "dejalo", "olvidalo", "olvida", "tampoco", "nada");
    private static final Set<String> ANTES_CLIENTE = set("es", "un", "una", "como", "mi", "de", "que",
            "eso", "el", "la", "son", "nuevo", "nueva", "otro", "otra", "este", "ese", "al", "a");
    private static final Set<String> MENORES = set("de", "del", "la", "las", "el", "los", "y", "da", "do");
    private static final Set<String> COLA_NOMBRE = set("de", "con", "que", "quiere", "pide", "son", "lleva",
            "a", "para", "y", "unos", "unas", "por", "necesita", "compra", "el", "la", "los", "tiene");
    private static final Set<String> LITROS = set("litros", "litro", "l", "lts", "lt");
    private static final Set<String> UNION = set("punto", "coma", "en", "por", "a", "x", "de", "con",
            "precio", "y", "al");
    private static final Set<String> MONEDA = set("kwanzas", "kwanza", "kz", "pesos", "dolares", "mil");

    /** Minúsculas y sin acentos, para comparar. */
    public static String plano(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }

    static List<String> palabras(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        s = s.replaceAll("(\\d),(\\d)", "$1.$2");
        s = s.replaceAll("[,;:¿?¡!\"()]", " ");
        for (String t : s.trim().split("\\s+")) {
            t = t.replaceAll("^\\.+", "").replaceAll("\\.+$", "");
            if (t.length() > 0) out.add(t);
        }
        return out;
    }

    private static boolean esEntero(String t) { return t.matches("\\d+"); }

    /** Número de menos de mil escrito en palabras, empezando en i. Devuelve {valor, palabras usadas}. */
    private static int[] menosDeMil(List<String> p, int i) {
        int v = 0, n = 0;
        if (i < p.size() && CEN.containsKey(p.get(i))) { v += CEN.get(p.get(i)); n++; }
        if (i + n < p.size() && DEC.containsKey(p.get(i + n))) {
            v += DEC.get(p.get(i + n)); n++;
            if (i + n + 1 < p.size() && p.get(i + n).equals("y")) {
                Integer u = UNI.get(p.get(i + n + 1));
                if (u != null && u >= 1 && u <= 9) { v += u; n += 2; }
            }
        } else if (i + n < p.size() && UNI.containsKey(p.get(i + n))) {
            String w = p.get(i + n);
            boolean articulo = w.equals("un") || w.equals("una");
            if (!articulo || n > 0) { v += UNI.get(w); n++; }
        }
        return new int[]{v, n};
    }

    /** Cambia los números dichos en palabras por cifras ("veinte punto cincuenta" -> "20 punto 50"). */
    static List<String> aCifras(List<String> tok) {
        List<String> pl = new ArrayList<String>();
        for (String t : tok) pl.add(plano(t));
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (i < tok.size()) {
            int[] a = menosDeMil(pl, i);
            int n = a[1];
            long v = a[0];
            if (i + n < pl.size() && pl.get(i + n).equals("mil")) {
                v = (n == 0 ? 1 : v) * 1000; n++;
                int[] b = menosDeMil(pl, i + n);
                v += b[0]; n += b[1];
            }
            if (n > 0) { out.add(String.valueOf(v)); i += n; }
            else { out.add(tok.get(i)); i++; }
        }
        // "20.000" -> 20000 ; "20 mil" -> 20000
        List<String> out2 = new ArrayList<String>();
        for (int k = 0; k < out.size(); k++) {
            String t = out.get(k);
            if (t.matches("\\d{1,3}(\\.\\d{3})+")) t = t.replace(".", "");
            if (esEntero(t) && k + 1 < out.size() && esEntero(out.get(k + 1)) && out.get(k + 1).equals("1000")
                    && Long.parseLong(t) < 1000) {
                t = String.valueOf(Long.parseLong(t) * 1000); k++;
            }
            out2.add(t);
        }
        return out2;
    }

    /** 20000 -> 20 (él dice "veinte mil litros" pero escribe 20). */
    private static String sinMiles(String entero) {
        try {
            long v = Long.parseLong(entero);
            if (v >= 1000 && v % 1000 == 0) v = v / 1000;
            return String.valueOf(v);
        } catch (NumberFormatException e) { return entero; }
    }

    private static String mayuscula(String t) {
        if (t.length() == 0) return t;
        return t.substring(0, 1).toUpperCase(new Locale("es")) + t.substring(1);
    }

    private static String nombrePropio(List<String> t) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.size(); i++) {
            String w = t.get(i);
            if (i > 0 && MENORES.contains(plano(w))) w = w.toLowerCase(new Locale("es"));
            else w = mayuscula(w);
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
    }

    private static String unir(List<String> t) {
        StringBuilder sb = new StringBuilder();
        for (String w : t) { if (sb.length() > 0) sb.append(' '); sb.append(w); }
        return sb.toString();
    }

    /** Quita del principio las palabras de arranque. Devuelve true si quitó alguna. */
    private static boolean quitarArranque(List<String> t, Res r, boolean manual) {
        boolean quito = false;
        while (!t.isEmpty()) {
            String p = plano(t.get(0));
            String p2 = t.size() > 1 ? plano(t.get(1)) : "";
            if (p.equals("se") && (p2.equals("llama") || p2.equals("llaman"))) {
                t.remove(0); t.remove(0); quito = true; r.explicito = true; continue;
            }
            if (ART.contains(p) && TRAS_ART.contains(p2)) { t.remove(0); quito = true; continue; }
            if (ARRANQUE.contains(p)) {
                if (VERBOS.contains(p)) r.explicito = true;
                if (p.startsWith("ultim")) r.ultimo = true;
                t.remove(0); quito = true; continue;
            }
            break;
        }
        return quito;
    }

    /**
     * @param dicho          lo que reconoció el teléfono
     * @param manual         true si puede traer un número de teléfono dictado
     * @param forzarCliente  true si ya había dicho antes que es un cliente
     */
    public static Res analizar(String dicho, boolean manual, boolean forzarCliente) {
        Res r = new Res();
        List<String> t = aCifras(palabras(dicho));
        if (t.isEmpty()) return r;

        // "es un cliente", "como cliente", "no es cliente"
        for (int i = 0; i < t.size(); i++) {
            if (!plano(t.get(i)).startsWith("client")) continue;
            t.remove(i);
            int quitadas = 0;
            while (i > 0 && quitadas < 4 && ANTES_CLIENTE.contains(plano(t.get(i - 1)))) {
                t.remove(i - 1); i--; quitadas++;
            }
            if (i > 0 && plano(t.get(i - 1)).equals("no")) {
                t.remove(i - 1); i--; r.noCliente = true; r.cliente = false;
            } else if (!r.noCliente) {
                r.cliente = true;
            }
            r.explicito = true;
            i--;
        }

        boolean neg = false;
        if (!t.isEmpty()) {
            if (NEG.contains(plano(t.get(0)))) { neg = true; t.remove(0); }
            else if (t.size() <= 3) {
                for (int i = 0; i < t.size(); i++) if (plano(t.get(i)).equals("no")) { neg = true; t.remove(i); break; }
            }
        }
        if (neg) r.explicito = true;

        boolean arranque = quitarArranque(t, r, manual);

        if (manual) {
            StringBuilder tel = new StringBuilder();
            int n = 0;
            while (n < t.size() && t.get(n).matches("\\+?\\d+")) { tel.append(t.get(n).replace("+", "")); n++; }
            if (tel.length() >= 7) {
                r.telefono = tel.toString();
                for (int i = 0; i < n; i++) t.remove(0);
                arranque = true;
                quitarArranque(t, r, manual);
            }
        }

        if (forzarCliente && !r.noCliente) r.cliente = true;

        // "C María ..." dicho con la letra
        if (t.size() >= 2) {
            String p = plano(t.get(0));
            if (p.equals("c") || p.equals("ce") || (p.equals("se") && !r.noCliente)) {
                t.remove(0);
                if (!r.noCliente) r.cliente = true;
                r.explicito = true;
            }
        }

        // "mi amigo Ricardo" -> "Ricardo"
        if (t.size() >= 3) {
            String a = plano(t.get(0)), b = plano(t.get(1));
            if ((a.equals("mi") || a.equals("un") || a.equals("una") || a.equals("el") || a.equals("la"))
                    && (b.equals("amigo") || b.equals("amiga"))) { t.remove(0); t.remove(0); }
        }

        if (t.isEmpty()) {
            if (neg) r.tipo = NO;
            else if (arranque || r.cliente || r.noCliente) r.tipo = SI;
            else r.tipo = NADA;
            return r;
        }

        r.tipo = DATOS;
        if (r.cliente) armarCliente(t, r);
        else { r.nombre = nombrePropio(t); r.hablado = r.nombre; }
        return r;
    }

    private static void armarCliente(List<String> t, Res r) {
        int i = -1;
        for (int k = 0; k < t.size(); k++) if (t.get(k).matches("\\d+(\\.\\d+)?")) { i = k; break; }
        if (i < 0) {
            // sin cantidad: "C" + lo dicho
            List<String> c = new ArrayList<String>(t);
            c.set(0, mayuscula(c.get(0)));
            r.nombre = "C " + unir(c);
            r.hablado = "C, " + unir(c);
            return;
        }
        String q, pr = "";
        int fin;
        String tk = t.get(i);
        if (tk.contains(".")) {
            int d = tk.indexOf('.');
            q = tk.substring(0, d); pr = tk.substring(d + 1); fin = i + 1;
        } else {
            q = sinMiles(tk);
            int j = i + 1;
            if (j < t.size() && LITROS.contains(plano(t.get(j)))) j++;
            int finSinPrecio = j;
            int saltos = 0;
            while (j < t.size() && saltos < 3 && UNION.contains(plano(t.get(j)))) { j++; saltos++; }
            if (j < t.size() && esEntero(t.get(j))) { pr = sinMiles(t.get(j)); fin = j + 1; }
            else fin = finSinPrecio;
        }
        while (fin < t.size() && (MONEDA.contains(plano(t.get(fin))) || LITROS.contains(plano(t.get(fin))))) fin++;

        List<String> nom = new ArrayList<String>(t.subList(0, i));
        while (!nom.isEmpty() && COLA_NOMBRE.contains(plano(nom.get(nom.size() - 1)))) nom.remove(nom.size() - 1);

        List<String> dir = new ArrayList<String>(t.subList(fin, t.size()));
        while (!dir.isEmpty()) {
            String a = plano(dir.get(0));
            String b = dir.size() > 1 ? plano(dir.get(1)) : "";
            if (a.equals("direccion") || a.equals("es") || a.equals("en") || a.equals("y") || a.equals("vive")
                    || a.equals("queda") || a.equals("esta") || a.equals("ubicado") || a.equals("ubicada")) {
                dir.remove(0);
            } else if ((a.equals("la") || a.equals("su") || a.equals("de") || a.equals("con")) && b.equals("direccion")) {
                dir.remove(0); dir.remove(0);
            } else if (a.equals("que") && (b.equals("vive") || b.equals("queda") || b.equals("esta"))) {
                dir.remove(0);
            } else break;
        }

        String cant = pr.length() > 0 ? q + "." + pr : q;
        String cantHablada = pr.length() > 0 ? q + " punto " + pr : q;
        StringBuilder n = new StringBuilder("C");
        StringBuilder h = new StringBuilder("C");
        if (!nom.isEmpty()) { n.append(' ').append(nombrePropio(nom)); h.append(", ").append(nombrePropio(nom)); }
        n.append(' ').append(cant); h.append(", ").append(cantHablada);
        if (!dir.isEmpty()) { n.append(' ').append(unir(dir)); h.append(", ").append(unir(dir)); }
        r.nombre = n.toString();
        r.hablado = h.toString();
    }

    private static final Set<String> ANTES_CLAVE = set("oye", "oiga", "hey", "ey", "eh", "ok", "okay", "hola",
            "a", "ver", "mira", "bueno", "y", "pues", "escucha", "dime");

    /**
     * Busca cómo se la llama: "Marisol", "mi secretaria" o "mi asistente".
     * Solo vale si viene al PRINCIPIO de la frase (para que no se despierte cuando
     * se la nombra en medio de una conversación). Devuelve null si no la llamó;
     * si la llamó, devuelve lo que vino después (puede ser "").
     */
    public static String trasMarisol(String dicho) {
        if (dicho == null) return null;
        List<String> t = palabras(dicho);
        for (int i = 0; i < t.size(); i++) {
            String p = plano(t.get(i));
            String p2 = i + 1 < t.size() ? plano(t.get(i + 1)) : "";
            int salto = 0;
            if (p.equals("marisol") || p.equals("marysol")) salto = 1;
            else if ((p.equals("mari") || p.equals("mary") || p.equals("maria")) && p2.equals("sol")) salto = 2;
            else if (p.equals("mi") && (p2.equals("secretaria") || p2.equals("asistente"))) salto = 2;
            else if (p.equals("mar") && p2.equals("y") && i + 2 < t.size() && plano(t.get(i + 2)).equals("sol")) salto = 3;
            if (salto > 0) return unir(t.subList(i + salto, t.size()));
            if (!ANTES_CLAVE.contains(p)) return null;   // ya empezó a decir otra cosa: no es para ella
        }
        return null;
    }

    /** ¿Le está diciendo que deje de escuchar? ("apágate", "cállate", "deja de escuchar"...) */
    public static boolean esApagar(String dicho) {
        if (dicho == null) return false;
        String p = " " + plano(dicho).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim() + " ";
        return p.contains(" apagate ") || p.contains(" apaga te ") || p.contains(" callate ") || p.contains(" calla te ")
                || p.contains(" deja de escuchar") || p.contains(" dejes de escuchar") || p.contains(" no escuches")
                || p.contains(" no me escuches") || p.contains(" desactivate ") || p.contains(" duermete ");
    }

    /** Número de teléfono para leerlo en voz, en grupos de tres. */
    public static String telefonoHablado(String num) {
        if (num == null) return "";
        String d = num.replaceAll("[^0-9]", "");
        if (d.length() > 9 && d.startsWith("244")) d = d.substring(3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length(); i += 3) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(d.substring(i, Math.min(d.length(), i + 3)));
        }
        return sb.toString();
    }
}
