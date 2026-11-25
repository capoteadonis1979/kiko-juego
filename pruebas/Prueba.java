import com.capote.guardacontactos.Texto;

public class Prueba {
    static int mal = 0, total = 0;

    static void nombre(String dicho, String esperado) { nombre(dicho, esperado, false, false); }

    static void nombre(String dicho, String esperado, boolean manual, boolean forzar) {
        total++;
        Texto.Res r = Texto.analizar(dicho, manual, forzar);
        boolean ok = r.tipo == Texto.DATOS && esperado.equals(r.nombre);
        if (!ok) mal++;
        System.out.println((ok ? "OK   " : "MAL  ") + dicho + "\n       -> [" + r.nombre + "]"
                + (ok ? "" : "   esperado [" + esperado + "] tipo=" + r.tipo)
                + (r.telefono.length() > 0 ? "  tel=" + r.telefono : "") + "   voz: " + r.hablado);
    }

    static void tipo(String dicho, int esperado) {
        total++;
        Texto.Res r = Texto.analizar(dicho, false, false);
        boolean ok = r.tipo == esperado;
        if (!ok) mal++;
        System.out.println((ok ? "OK   " : "MAL  ") + dicho + " -> tipo " + r.tipo + (ok ? "" : " esperado " + esperado)
                + (r.nombre.length() > 0 ? " [" + r.nombre + "]" : "") + (r.cliente ? " (cliente)" : ""));
    }

    static void tel(String dicho, String telEsperado, String nombreEsperado) {
        total++;
        Texto.Res r = Texto.analizar(dicho, true, false);
        boolean ok = telEsperado.equals(r.telefono) && nombreEsperado.equals(r.nombre);
        if (!ok) mal++;
        System.out.println((ok ? "OK   " : "MAL  ") + dicho + "\n       -> tel=" + r.telefono + " [" + r.nombre + "] ultimo=" + r.ultimo
                + (ok ? "" : "   esperado tel=" + telEsperado + " [" + nombreEsperado + "]"));
    }

    static void clave(String dicho, String esperado) {
        total++;
        String r = Texto.trasMarisol(dicho);
        boolean ok = esperado == null ? r == null : esperado.equals(r);
        if (!ok) mal++;
        System.out.println((ok ? "OK   " : "MAL  ") + dicho + " -> " + (r == null ? "(no es para ella)" : "[" + r + "]"));
    }

    public static void main(String[] a) {
        System.out.println("--- clientes ---");
        nombre("sí, es un cliente, guárdalo como Juan veinte punto cincuenta cantina próximo a la bomba",
                "C Juan 20.50 cantina próximo a la bomba");
        nombre("Sí eso es un cliente guárdalo como María de 20 mil litros", "C María 20");
        nombre("guárdalo como C Juan 20.50 cantina próximo a tal lugar", "C Juan 20.50 cantina próximo a tal lugar");
        nombre("sí que lo guarde como se María 20,50 Benfica", "C María 20.50 Benfica");
        nombre("cliente Pedro veinte litros en cincuenta dirección cantina del mercado", "C Pedro 20.50 cantina del mercado");
        nombre("es un cliente Juan Carlos 20.000 litros a 50.000 kwanzas vive en Luanda Azul", "C Juan Carlos 20.50 Luanda Azul");
        nombre("guárdalo como cliente maría 20 50 la cantina", "C María 20.50 la cantina");
        nombre("cliente José diez punto treinta y cinco próximo a la escuela", "C José 10.35 próximo a la escuela");
        nombre("guárdalo como Ana que es una clienta veinte mil litros por cincuenta mil en Talatona", "C Ana 20.50 Talatona");
        nombre("cliente Manuel 20 punto 50", "C Manuel 20.50");
        nombre("cliente Manuel", "C Manuel");
        nombre("como cliente, Luis, cinco mil litros, en la cantina", "C Luis 5 la cantina");
        nombre("Juan veinte punto cincuenta cantina", "C Juan 20.50 cantina", false, true);
        nombre("guárdalo como doña Rosa 15.45 al lado de una farmacia", "C Doña Rosa 15.45 al lado de una farmacia", false, true);

        System.out.println("--- no clientes ---");
        nombre("guárdame eso ahí como mi amigo Ricardo", "Ricardo");
        nombre("guárdalo como Ricardo cubano", "Ricardo Cubano");
        nombre("sí, guárdalo como el mecánico de la Hyundai", "El Mecánico de la Hyundai");
        nombre("no es un cliente, guárdalo como Ricardo cubano", "Ricardo Cubano");
        nombre("ponle Yainet", "Yainet");
        nombre("se llama Pedro Pablo", "Pedro Pablo");
        nombre("no, guárdalo como María del Carmen", "María del Carmen");

        System.out.println("--- sí / no ---");
        tipo("sí", Texto.SI);
        tipo("Sí, guárdalo", Texto.SI);
        tipo("dale", Texto.SI);
        tipo("está bien", Texto.SI);
        tipo("correcto", Texto.SI);
        tipo("sí es un cliente", Texto.SI);
        tipo("no", Texto.NO);
        tipo("no lo guardes", Texto.NO);
        tipo("ahora no", Texto.NO);
        tipo("déjalo", Texto.NO);
        tipo("no gracias", Texto.NO);
        tipo("", Texto.NADA);

        System.out.println("--- modo manual ---");
        tel("guarda el número 923456789 como cliente Juan veinte punto cincuenta cantina", "923456789", "C Juan 20.50 cantina");
        tel("guarda el número nueve dos tres cuatro cinco seis siete ocho nueve como Ricardo cubano", "923456789", "Ricardo Cubano");
        tel("guarda el número 923 456 789 como María", "923456789", "María");
        tel("guarda el último número como cliente Ana 20.50 Benfica", "", "C Ana 20.50 Benfica");
        tel("guarda la última llamada como Ricardo", "", "Ricardo");

        System.out.println("--- Marisol ---");
        clave("Marisol", "");
        clave("oye Marisol guarda el último número como cliente Juan 20.50 cantina", "guarda el último número como cliente Juan 20.50 cantina");
        clave("mari sol guárdalo como Ricardo", "guárdalo como Ricardo");
        clave("María Sol", "");
        clave("pon la radio que hace sol", null);
        clave("María viene mañana", null);
        clave("mi secretaria revisa los últimos cinco números", "revisa los últimos cinco números");
        clave("oye mi asistente", "");
        clave("la secretaria del banco", null);
        clave("le dije a mi asistente que viniera mañana", null);
        clave("la aplicación se llama Marisol", null);
        clave("eso sí con Marisol por detrás", null);
        clave("a ver Marisol apágate", "apágate");
        clave("mar y sol", "");
        clave("marisol [unk] [unk]", "[unk] [unk]");
        clave("[unk] marisol", null);
        clave("[unk]", null);
        total++; if (!Texto.esApagar("apágate") || !Texto.esApagar("deja de escuchar un rato") || !Texto.esApagar("Cállate.")
                || Texto.esApagar("apaga la luz") || Texto.esApagar("guarda el último número")) { mal++; System.out.println("MAL  esApagar"); }
        else System.out.println("OK   órdenes de apagarse");

        System.out.println("\n" + (total - mal) + " de " + total + " bien");
        if (mal > 0) System.exit(1);
    }
}
