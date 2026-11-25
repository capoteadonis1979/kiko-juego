package com.capote.guardacontactos;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pantalla principal: permisos, interruptor de "preguntar al colgar" y botón HABLAR. */
public class MainActivity extends Activity {
    private TextView bPermisos, bEncima, bPreguntar, bMarisol, bGemini, sub;
    private static final String LEMA = "Guarda números hablando, sin tocar el teléfono";
    private final android.os.Handler reloj = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Enseña en la línea de arriba lo que Marisol va oyendo (o su fallo). */
    private final Runnable tic = new Runnable() {
        public void run() {
            String e = EscuchaService.estado;
            sub.setText(e.length() > 0 ? "Marisol: " + e : LEMA);
            reloj.postDelayed(this, 500);
        }
    };
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(CallReceiver.PREFS, Context.MODE_PRIVATE);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Ui.FONDO);
        int m = Ui.dp(this, 16);
        raiz.setPadding(m, m, m, m);

        raiz.addView(Ui.texto(this, "Guarda Contactos", 28, Ui.BLANCO, true), Ui.fila(this, 1f, 0));
        sub = Ui.texto(this, LEMA, 14, Ui.GRIS, false);
        sub.setMaxLines(2);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        raiz.addView(sub, Ui.fila(this, 0.6f, 0));

        bPermisos = Ui.boton(this, "", 16, Ui.TARJETA);
        raiz.addView(bPermisos, Ui.fila(this, 1f, 8));
        bEncima = Ui.boton(this, "", 16, Ui.TARJETA);
        raiz.addView(bEncima, Ui.fila(this, 1f, 10));
        bPreguntar = Ui.boton(this, "", 16, Ui.TARJETA);
        raiz.addView(bPreguntar, Ui.fila(this, 1f, 10));
        bMarisol = Ui.boton(this, "", 16, Ui.TARJETA);
        raiz.addView(bMarisol, Ui.fila(this, 1f, 10));
        bGemini = Ui.boton(this, "", 16, Ui.TARJETA);
        raiz.addView(bGemini, Ui.fila(this, 1f, 10));

        TextView hablar = Ui.boton(this, "🎤  HABLAR", 34, Ui.VERDE);
        raiz.addView(hablar, Ui.fila(this, 2.2f, 14));

        TextView ayuda = Ui.tarjeta(this,
                "Llámame «Marisol», «mi secretaria» o «mi asistente» y pídeme lo que quieras: "
                        + "«revisa los últimos cinco números a ver si falta alguno por guardar».\n"
                        + "Al colgar una llamada de un número sin nombre te pregunto sola.",
                14, Ui.GRIS, 7);
        ayuda.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        raiz.addView(ayuda, Ui.fila(this, 2.2f, 14));

        LinearLayout pie = new LinearLayout(this);
        TextView salir = Ui.boton(this, "SALIR", 18, Ui.ROJO);
        TextView ver = Ui.texto(this, Ui.version(this), 12, Ui.GRIS, false);
        ver.setGravity(Gravity.BOTTOM | Gravity.RIGHT);
        pie.addView(salir, Ui.columna(this, 3f, 0));
        pie.addView(ver, Ui.columna(this, 1f, 8));
        raiz.addView(pie, Ui.fila(this, 1f, 14));

        setContentView(raiz);

        bPermisos.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (!Contactos.tienePermisos(MainActivity.this)) pedirPermisos(); }
        });
        bEncima.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (!Settings.canDrawOverlays(MainActivity.this)) pedirEncima(); }
        });
        bPreguntar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putBoolean("preguntar", !prefs.getBoolean("preguntar", true)).apply();
                pintar();
            }
        });
        bMarisol.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putBoolean("marisol", !prefs.getBoolean("marisol", true)).apply();
                pintar();
                escucha();
            }
        });
        bGemini.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pedirClave(); } });
        hablar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!Contactos.tienePermisos(MainActivity.this)) { pedirPermisos(); return; }
                Intent i = new Intent(MainActivity.this, AskActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                i.putExtra("manual", true);
                startActivity(i);
            }
        });
        salir.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
    }

    @Override
    protected void onResume() {
        super.onResume();
        pintar();
        escucha();
        reloj.removeCallbacks(tic);
        reloj.post(tic);
        mirarVersion();
    }

    @Override
    protected void onPause() {
        reloj.removeCallbacks(tic);
        super.onPause();
    }

    /** Cambia solo texto y color: los botones no se mueven ni cambian de tamaño. */
    private void pintar() {
        if (Contactos.tienePermisos(this)) {
            bPermisos.setText("✔  Permisos listos");
            bPermisos.setTextColor(Ui.VERDE);
            Ui.fondoBoton(bPermisos, Ui.TARJETA);
        } else {
            bPermisos.setText("1.  TOCA AQUÍ: DAR PERMISOS");
            bPermisos.setTextColor(Ui.BLANCO);
            Ui.fondoBoton(bPermisos, Ui.AMBAR);
        }
        if (Settings.canDrawOverlays(this)) {
            bEncima.setText("✔  Puede abrirse sola al colgar");
            bEncima.setTextColor(Ui.VERDE);
            Ui.fondoBoton(bEncima, Ui.TARJETA);
        } else {
            bEncima.setText("2.  TOCA AQUÍ: DEJAR QUE SE ABRA SOLA");
            bEncima.setTextColor(Ui.BLANCO);
            Ui.fondoBoton(bEncima, Ui.AMBAR);
        }
        boolean on = prefs.getBoolean("preguntar", true);
        bPreguntar.setText(on ? "Preguntar al colgar:  SÍ" : "Preguntar al colgar:  NO");
        bPreguntar.setTextColor(on ? Ui.VERDE : Ui.GRIS);
        String clave = prefs.getString("iaClave", "");
        String ie = prefs.getString("iaEstado", "");
        if (clave.length() == 0) {
            bGemini.setText("3.  TOCA AQUÍ: PEGAR CLAVE DE GEMINI");
            bGemini.setTextColor(Ui.BLANCO);
            Ui.fondoBoton(bGemini, Ui.AMBAR);
        } else if (ie.equals("ok")) {
            bGemini.setText("✔  Gemini conectada");
            bGemini.setTextColor(Ui.VERDE);
            Ui.fondoBoton(bGemini, Ui.TARJETA);
        } else if (ie.equals("comprobando")) {
            bGemini.setText("Gemini: comprobando la clave…");
            bGemini.setTextColor(Ui.GRIS);
            Ui.fondoBoton(bGemini, Ui.TARJETA);
        } else {
            bGemini.setText("Gemini: " + ie + " Toca para cambiarla.");
            bGemini.setTextColor(Ui.BLANCO);
            Ui.fondoBoton(bGemini, Ui.AMBAR);
        }
        boolean mar = prefs.getBoolean("marisol", true);
        bMarisol.setText(mar ? "Llamarla diciendo «Marisol»:  SÍ" : "Llamarla diciendo «Marisol»:  NO");
        bMarisol.setTextColor(mar ? Ui.VERDE : Ui.GRIS);
    }

    /** Enciende o apaga la escucha de la palabra clave según el interruptor y los permisos. */
    private void escucha() {
        Intent s = new Intent(this, EscuchaService.class);
        if (prefs.getBoolean("marisol", true) && Contactos.tienePermisos(this)) {
            try { startService(s); } catch (Exception e) { /* nada */ }
            if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("pidioAviso", false)
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("pidioAviso", true).apply();
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
            }
        } else {
            stopService(s);
        }
    }

    /** Casilla para pegar la clave de Gemini (se guarda solo en este teléfono). */
    private void pedirClave() {
        final android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setHint("Pega aquí la clave");
        e.setText(prefs.getString("iaClave", ""));
        new android.app.AlertDialog.Builder(this)
                .setTitle("Clave de Gemini")
                .setMessage("Es la clave de Google AI Studio. Se guarda solo en este teléfono. Déjala vacía para quitarla.")
                .setView(e)
                .setPositiveButton("GUARDAR", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { guardarClave(e.getText().toString().trim()); }
                })
                .setNegativeButton("CANCELAR", null)
                .show();
    }

    private void guardarClave(final String k) {
        if (k.length() == 0) {
            prefs.edit().remove("iaClave").remove("iaModelo").remove("iaEstado").apply();
            pintar();
            return;
        }
        prefs.edit().putString("iaClave", k).putString("iaEstado", "comprobando").apply();
        pintar();
        new Thread(new Runnable() {
            public void run() {
                try {
                    String m = Gemini.elegirModelo(k);
                    prefs.edit().putString("iaModelo", m).putString("iaEstado", "ok").apply();
                } catch (Exception ex) {
                    prefs.edit().putString("iaEstado", Gemini.motivo(ex.getMessage())).apply();
                }
                runOnUiThread(new Runnable() { public void run() { pintar(); } });
            }
        }).start();
    }

    private static final String BASE_DESCARGA = "https://github.com/capoteadonis1979/kiko-juego/releases/download/marisol/";
    private static boolean miroVersion;

    private static boolean esMasNueva(String otra, String mia) {
        String[] a = otra.trim().split("\\."), b = mia.trim().split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = 0, y = 0;
            try { if (i < a.length) x = Integer.parseInt(a[i].trim()); } catch (Exception e) { return false; }
            try { if (i < b.length) y = Integer.parseInt(b[i].trim()); } catch (Exception e) { return false; }
            if (x != y) return x > y;
        }
        return false;
    }

    /** Una vez por arranque mira en internet si hay versión nueva y ofrece bajarla. No manda ningún dato. */
    private void mirarVersion() {
        if (miroVersion) return;
        miroVersion = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(BASE_DESCARGA + "version.txt").openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                    final String nueva = r.readLine();
                    r.close();
                    c.disconnect();
                    final String mia = Ui.version(MainActivity.this).replace("v", "");
                    if (nueva == null || !esMasNueva(nueva, mia)) return;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (isFinishing()) return;
                            new android.app.AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Hay una versión nueva")
                                    .setMessage("Tienes la " + mia + " y ya salió la " + nueva.trim() + ". ¿La bajo ahora?")
                                    .setPositiveButton("BAJAR", new android.content.DialogInterface.OnClickListener() {
                                        public void onClick(android.content.DialogInterface d, int w) {
                                            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BASE_DESCARGA + "GuardaContactos.apk"))); }
                                            catch (Exception e) { /* sin navegador */ }
                                        }
                                    })
                                    .setNegativeButton("DESPUÉS", null)
                                    .show();
                        }
                    });
                } catch (Exception e) { /* sin internet: se mira otro día */ }
            }
        }).start();
    }

    private void pedirPermisos() {
        requestPermissions(Contactos.PERMISOS, 1);
    }

    private void pedirEncima() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            abrirAjustes();
        }
    }

    private void abrirAjustes() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) { /* nada */ }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permisos, int[] res) {
        pintar();
        escucha();
        if (code != 1) return;
        if (Contactos.tienePermisos(this)) return;
        // Si el teléfono ya no deja preguntar (se negó antes), hay que darlos en Ajustes.
        for (String p : Contactos.PERMISOS) {
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    && !shouldShowRequestPermissionRationale(p)) {
                abrirAjustes();
                return;
            }
        }
    }
}
