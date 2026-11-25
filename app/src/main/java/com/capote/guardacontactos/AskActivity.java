package com.capote.guardacontactos;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * La conversación por voz: pregunta, escucha, repite cómo va a quedar y guarda.
 * Se abre sola al colgar (número sin nombre) o desde el botón HABLAR.
 */
public class AskActivity extends Activity implements RecognitionListener, TextToSpeech.OnInitListener {
    private static final int E_INICIO = 0, E_MANUAL = 1, E_NOMBRE = 2, E_CONFIRMA = 3, E_FIN = 4, E_IA = 5;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final HashMap<String, Runnable> pendientes = new HashMap<String, Runnable>();
    private int seq;

    private int estado = E_INICIO;
    private boolean manual;
    private String numero = "";
    private boolean numeroDictado;
    private boolean pendCliente;
    private boolean esCliente;
    private String nombre = "", hablado = "";
    private int intentos;
    private String textoInicial = "";
    private Gemini ia;            // la inteligencia (solo si hay clave puesta)
    private boolean pensando;
    private int turnos;
    static volatile boolean viva;

    private TextToSpeech tts;
    private boolean ttsListo, ttsFallo, esperaLista, arrancado;
    private SpeechRecognizer sr;

    private TextView txtNumero, txtApp, txtEstado, txtTu, txtNombre;

    private final Runnable cierre = new Runnable() { public void run() { finish(); } };
    private final Runnable oir = new Runnable() { public void run() { escuchar(); } };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                Activity.class.getMethod("setShowWhenLocked", boolean.class).invoke(this, true);
                Activity.class.getMethod("setTurnScreenOn", boolean.class).invoke(this, true);
            } catch (Exception e) { /* con las banderas de arriba basta */ }
        }
        viva = true;
        EscuchaService.revisarYa();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        pantalla();
        leer(getIntent());

        tts = new TextToSpeech(getApplicationContext(), this);
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            sr = SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(this);
        }
        h.postDelayed(new Runnable() { public void run() { esperaLista = true; arrancar(); } }, manual ? 300 : 1500);
        h.postDelayed(new Runnable() { public void run() { if (!ttsListo) { ttsFallo = true; arrancar(); } } }, 5000);
        vida();
    }

    @Override
    protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        if (tts != null) tts.stop();
        if (sr != null) sr.cancel();
        pendientes.clear();
        leer(in);
        if (arrancado) h.postDelayed(new Runnable() { public void run() { empezar(); } }, manual ? 300 : 1500);
        vida();
    }

    private void leer(Intent in) {
        manual = in != null && in.getBooleanExtra("manual", false);
        String n = in == null ? null : in.getStringExtra("numero");
        numero = n == null ? "" : n;
        String tx = in == null ? null : in.getStringExtra("texto");
        textoInicial = tx == null ? "" : tx.trim();
        numeroDictado = false; pendCliente = false; esCliente = false;
        ia = null; pensando = false; turnos = 0;
        nombre = ""; hablado = ""; intentos = 0;
        estado = manual ? E_MANUAL : E_INICIO;
        txtNumero.setText(numero.length() > 0 ? numero : "—");
        txtApp.setText("");
        txtTu.setText("");
        txtNombre.setText("");
        estadoTxt("…", Ui.GRIS);
    }

    /** Si nadie habla ni toca nada en un rato, se cierra sola. */
    private void vida() {
        h.removeCallbacks(cierre);
        h.postDelayed(cierre, 90000);
    }

    // ---------- pantalla (todo cabe, sin scroll; nada cambia de tamaño) ----------

    private void pantalla() {
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Ui.FONDO);
        int m = Ui.dp(this, 16);
        raiz.setPadding(m, m, m, m);

        raiz.addView(Ui.texto(this, "NÚMERO", 14, Ui.GRIS, true), Ui.fila(this, 0.5f, 0));
        txtNumero = Ui.texto(this, "—", 30, Ui.BLANCO, true);
        txtNumero.setMaxLines(1);
        raiz.addView(txtNumero, Ui.fila(this, 1f, 0));

        txtApp = Ui.tarjeta(this, "", 22, Ui.BLANCO, 4);
        raiz.addView(txtApp, Ui.fila(this, 2.4f, 8));

        txtEstado = Ui.texto(this, "…", 18, Ui.GRIS, true);
        txtEstado.setMaxLines(1);
        raiz.addView(txtEstado, Ui.fila(this, 0.8f, 8));

        raiz.addView(Ui.texto(this, "TÚ DIJISTE", 12, Ui.GRIS, true), Ui.fila(this, 0.4f, 4));
        txtTu = Ui.tarjeta(this, "", 19, Ui.AMARILLO, 3);
        raiz.addView(txtTu, Ui.fila(this, 1.7f, 2));

        raiz.addView(Ui.texto(this, "SE GUARDA COMO", 12, Ui.GRIS, true), Ui.fila(this, 0.4f, 8));
        txtNombre = Ui.tarjeta(this, "", 21, Ui.VERDE, 3);
        raiz.addView(txtNombre, Ui.fila(this, 1.7f, 2));

        LinearLayout f1 = new LinearLayout(this);
        TextView si = Ui.boton(this, "SÍ", 24, Ui.VERDE);
        TextView hablar = Ui.boton(this, "🎤 HABLAR", 20, Ui.NEUTRO);
        f1.addView(si, Ui.columna(this, 1f, 0));
        f1.addView(hablar, Ui.columna(this, 1f, 12));
        raiz.addView(f1, Ui.fila(this, 1.5f, 12));

        LinearLayout f2 = new LinearLayout(this);
        TextView cerrar = Ui.boton(this, "CERRAR", 18, Ui.ROJO);
        TextView ver = Ui.texto(this, Ui.version(this), 12, Ui.GRIS, false);
        ver.setGravity(Gravity.BOTTOM | Gravity.RIGHT);
        f2.addView(cerrar, Ui.columna(this, 3f, 0));
        f2.addView(ver, Ui.columna(this, 1f, 8));
        raiz.addView(f2, Ui.fila(this, 1f, 12));

        setContentView(raiz);

        si.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { tocarSi(); } });
        hablar.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { tocarHablar(); } });
        cerrar.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
    }

    private void estadoTxt(String t, int color) {
        txtEstado.setText(t);
        txtEstado.setTextColor(color);
    }

    private void tocarSi() {
        vida();
        if (estado == E_FIN) return;
        if (tts != null) tts.stop();
        pendientes.clear();
        if (sr != null) sr.cancel();
        if (estado == E_IA) { if (!pensando) { txtTu.setText("Sí"); procesar("sí"); } }
        else if (estado == E_INICIO) { pendCliente = false; preguntarNombre(); }
        else if (estado == E_CONFIRMA) guardar();
        else escuchar();
    }

    private void tocarHablar() {
        vida();
        if (estado == E_FIN || pensando) return;
        if (tts != null) tts.stop();
        pendientes.clear();
        if (sr != null) sr.cancel();
        h.postDelayed(oir, 150);
    }

    // ---------- voz de la app ----------

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            Locale[] idiomas = {new Locale("es", "US"), new Locale("es", "MX"), new Locale("es", "ES"), new Locale("es")};
            for (Locale l : idiomas) {
                try {
                    int r = tts.setLanguage(l);
                    if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) break;
                } catch (Exception e) { /* probar el siguiente */ }
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) {}
                public void onDone(String id) { hecho(id); }
                public void onError(String id) { hecho(id); }
            });
            ttsListo = true;
        } else {
            ttsFallo = true;
        }
        h.post(new Runnable() { public void run() { arrancar(); } });
    }

    private void hecho(final String id) {
        h.post(new Runnable() {
            public void run() {
                Runnable r = pendientes.remove(id);
                if (r != null) r.run();
            }
        });
    }

    private void decir(String enPantalla, String enVoz, Runnable luego) {
        vida();
        txtApp.setText(enPantalla);
        estadoTxt("🔊 Hablando…", Ui.GRIS);
        pendientes.clear();
        if (ttsListo && tts != null) {
            String id = "u" + (seq++);
            if (luego != null) pendientes.put(id, luego);
            Bundle p = new Bundle();
            p.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
            int r = tts.speak(enVoz, TextToSpeech.QUEUE_FLUSH, p, id);
            if (r != TextToSpeech.SUCCESS) {
                pendientes.remove(id);
                if (luego != null) h.postDelayed(luego, 700);
            }
        } else if (luego != null) {
            h.postDelayed(luego, 900);
        }
    }

    private void decir(String t, Runnable luego) { decir(t, t, luego); }

    // ---------- oído de la app ----------

    private void escuchar() {
        if (estado == E_FIN || isFinishing()) return;
        if (sr == null) {
            estadoTxt("Este teléfono no tiene reconocimiento de voz", Ui.AMBAR);
            return;
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-US");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-US");
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
        try {
            sr.cancel();
            sr.startListening(i);
            txtTu.setText("");
            estadoTxt("🎤 Escuchando…", Ui.VERDE);
        } catch (Exception e) {
            estadoTxt("No pude abrir el micrófono", Ui.AMBAR);
        }
    }

    public void onReadyForSpeech(Bundle b) { estadoTxt("🎤 Habla ahora", Ui.VERDE); }
    public void onBeginningOfSpeech() { vida(); }
    public void onRmsChanged(float v) {}
    public void onBufferReceived(byte[] b) {}
    public void onEndOfSpeech() { estadoTxt("⏳ Entendiendo…", Ui.GRIS); }
    public void onEvent(int t, Bundle b) {}

    public void onPartialResults(Bundle b) {
        ArrayList<String> l = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (l != null && l.size() > 0 && l.get(0) != null && l.get(0).length() > 0) txtTu.setText(l.get(0));
    }

    public void onResults(Bundle b) {
        ArrayList<String> l = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String t = (l != null && l.size() > 0 && l.get(0) != null) ? l.get(0).trim() : "";
        if (t.length() == 0) { sinEntender(); return; }
        txtTu.setText(t);
        procesar(t);
    }

    public void onError(int code) {
        if (estado == E_FIN || isFinishing()) return;
        if (code == SpeechRecognizer.ERROR_CLIENT) return; // sale al cancelar, no es un fallo
        if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) { h.removeCallbacks(oir); h.postDelayed(oir, 500); return; }
        if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            estadoTxt("Falta el permiso del micrófono", Ui.AMBAR);
            return;
        }
        if (code == SpeechRecognizer.ERROR_NETWORK || code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
            decir("No hay internet para entenderte. Toca HABLAR para probar otra vez.", null);
            return;
        }
        sinEntender();
    }

    private String pregunta() {
        switch (estado) {
            case E_IA: return "Dime.";
            case E_MANUAL: return "Dime qué número guardo y cómo.";
            case E_NOMBRE: return "¿Cómo lo guardo?";
            case E_CONFIRMA: return "¿Lo guardo así? Sí o no.";
            default: return "¿Guardo este número?";
        }
    }

    private void sinEntender() {
        if (estado == E_IA) {                       // conversación: si ya no dice nada, se cierra sola
            if (turnos > 0 || intentos >= 1) { finish(); return; }
            intentos++;
            decir("No te escuché. Dime.", oir);
            return;
        }
        intentos++;
        if (intentos > 2) { terminar("No te escuché. Lo dejo sin guardar."); return; }
        decir("No te escuché. " + pregunta(), oir);
    }

    // ---------- la conversación ----------

    private void arrancar() {
        if (arrancado || !esperaLista || !(ttsListo || ttsFallo)) return;
        arrancado = true;
        empezar();
    }

    private void empezar() {
        if (isFinishing()) return;
        if (manual) {
            android.content.SharedPreferences pr = getSharedPreferences(CallReceiver.PREFS, MODE_PRIVATE);
            String clave = pr.getString("iaClave", "");
            if (clave.length() > 0) {
                estado = E_IA;
                turnos = 0;
                pensando = false;
                ia = new Gemini(clave, pr.getString("iaModelo", ""), manosIA());
                if (textoInicial.length() > 0) {
                    String t = textoInicial;
                    textoInicial = "";
                    txtTu.setText(t);
                    procesar(t);
                } else {
                    decir("Dime.", oir);
                }
                return;
            }
            estado = E_MANUAL;
            if (textoInicial.length() > 0) {           // dijo "Marisol" y la orden de un tirón
                String t = textoInicial;
                textoInicial = "";
                txtTu.setText(t);
                procesar(t);
            } else {
                decir("Dime.", oir);
            }
            return;
        }
        if (numero.length() == 0) {
            numero = Contactos.ultimoNumero(this, true);
            txtNumero.setText(numero.length() > 0 ? numero : "—");
        }
        if (numero.length() == 0) { finish(); return; }                 // número oculto: no hay nada que guardar
        if (Contactos.nombreDe(this, numero) != null) { finish(); return; } // ya estaba guardado
        estado = E_INICIO;
        decir("Número nuevo. ¿Lo guardo?", oir);
    }

    /** Lo que Marisol puede hacer en el teléfono cuando Gemini se lo pide. */
    private Gemini.Manos manosIA() {
        return new Gemini.Manos() {
            public org.json.JSONObject usar(String n, org.json.JSONObject a) throws Exception {
                if (n.equals("ultimas_llamadas")) return Contactos.ultimasLlamadas(AskActivity.this, a.optInt("cantidad", 5));
                if (n.equals("buscar_contacto")) return Contactos.buscar(AskActivity.this, a.optString("texto"));
                if (n.equals("guardar_contacto")) {
                    final String num = a.optString("numero").trim();
                    final String nom = a.optString("nombre").trim();
                    if (num.replaceAll("[^0-9]", "").length() < 3 || nom.length() == 0)
                        return new org.json.JSONObject().put("ok", false).put("motivo", "falta el número o el nombre");
                    String ya = Contactos.nombreDe(AskActivity.this, num);
                    if (ya != null) return new org.json.JSONObject().put("ok", false).put("motivo", "ese número ya está guardado como " + ya);
                    boolean ok = Contactos.guardar(AskActivity.this, nom, num);
                    if (ok) h.post(new Runnable() { public void run() { txtNumero.setText(num); txtNombre.setText(nom); } });
                    return new org.json.JSONObject().put("ok", ok);
                }
                return null;
            }
        };
    }

    /** Manda lo dicho a Gemini (en otro hilo) y dice en voz lo que conteste. */
    private void preguntarIA(final String t) {
        if (pensando || ia == null) return;
        pensando = true;
        if (sr != null) sr.cancel();
        estadoTxt("⏳ Pensando…", Ui.GRIS);
        final Gemini g = ia;
        new Thread(new Runnable() {
            public void run() {
                final Gemini.Respuesta r = g.turno(t);
                h.post(new Runnable() {
                    public void run() {
                        if (isFinishing() || g != ia) return;
                        pensando = false;
                        turnos++;
                        intentos = 0;
                        if (r.error != null && !"vacio".equals(r.error)) terminar(r.texto);
                        else if (r.cerrar) terminar(r.texto);
                        else decir(r.texto, oir);
                    }
                });
            }
        }).start();
    }

    private void procesar(String t) {
        vida();
        if ((estado == E_IA || estado == E_MANUAL) && Texto.esApagar(t)) {
            getSharedPreferences(CallReceiver.PREFS, MODE_PRIVATE).edit().putBoolean("marisol", false).apply();
            stopService(new Intent(this, EscuchaService.class));
            terminar("Me apago. Para encenderme otra vez, abre la app.");
            return;
        }
        if (estado == E_IA) { preguntarIA(t); return; }
        if (estado == E_MANUAL) {
            Texto.Res r = Texto.analizar(t, true, false);
            if (r.tipo == Texto.NO) { terminar("Está bien."); return; }
            if (r.tipo == Texto.NADA && r.telefono.length() == 0 && !r.ultimo && !r.explicito) { sinEntender(); return; }
            if (r.telefono.length() > 0) { numero = r.telefono; numeroDictado = true; }
            else numero = Contactos.ultimoNumero(this, false);
            if (numero.length() == 0) { terminar("No encuentro ningún número en las llamadas."); return; }
            txtNumero.setText(numero);
            String ya = Contactos.nombreDe(this, numero);
            if (ya != null) { terminar("Ese número ya está guardado como " + ya + "."); return; }
            if (r.tipo == Texto.DATOS) { poner(r); confirmar(); }
            else { pendCliente = r.cliente; preguntarNombre(); }

        } else if (estado == E_INICIO) {
            Texto.Res r = Texto.analizar(t, false, false);
            if (r.tipo == Texto.SI) { pendCliente = r.cliente; preguntarNombre(); }
            else if (r.tipo == Texto.NO) terminar("Está bien, no lo guardo.");
            else if (r.tipo == Texto.DATOS) { poner(r); confirmar(); }
            else sinEntender();

        } else if (estado == E_NOMBRE) {
            Texto.Res r = Texto.analizar(t, false, pendCliente);
            if (r.tipo == Texto.DATOS) { poner(r); confirmar(); }
            else if (r.tipo == Texto.NO) terminar("Está bien, no lo guardo.");
            else if (r.tipo == Texto.SI) {
                if (r.cliente) pendCliente = true;
                if (r.noCliente) pendCliente = false;
                intentos++;
                if (intentos > 3) terminar("No te entendí. Lo dejo sin guardar.");
                else decir("Dime el nombre.", oir);
            } else sinEntender();

        } else if (estado == E_CONFIRMA) {
            Texto.Res r = Texto.analizar(t, false, esCliente);
            if (r.tipo == Texto.SI) guardar();
            else if (r.tipo == Texto.NO) {
                estado = E_NOMBRE; intentos = 0; pendCliente = esCliente;
                decir("¿Cómo lo guardo entonces?", oir);
            } else if (r.tipo == Texto.DATOS && r.explicito) { poner(r); confirmar(); }
            else if (r.tipo == Texto.DATOS) {
                intentos++;
                if (intentos > 3) terminar("No te entendí. Lo dejo sin guardar.");
                else decir("¿Lo guardo así? Sí o no.", oir);
            } else sinEntender();
        }
    }

    private void poner(Texto.Res r) {
        nombre = r.nombre;
        hablado = r.hablado;
        esCliente = r.cliente;
        txtNombre.setText(nombre);
    }

    private void preguntarNombre() {
        estado = E_NOMBRE;
        intentos = 0;
        decir("¿Cómo lo guardo?", oir);
    }

    private void confirmar() {
        estado = E_CONFIRMA;
        intentos = 0;
        String antes = numeroDictado ? "El " + Texto.telefonoHablado(numero) + ". " : "";
        decir("¿Lo guardo así?", antes + "Lo guardo como " + hablado + ". ¿Está bien?", oir);
    }

    private void guardar() {
        if (nombre.length() == 0 || numero.length() == 0) { terminar("Me falta el nombre o el número."); return; }
        boolean ok = Contactos.guardar(this, nombre, numero);
        terminar(ok ? "Guardado." : "No pude guardarlo. Revisa los permisos de la app.");
    }

    private void terminar(String mensaje) {
        estado = E_FIN;
        if (sr != null) sr.cancel();
        decir(mensaje, cierre);
        h.removeCallbacks(cierre);
        h.postDelayed(cierre, 5000 + 90L * mensaje.length());   // por si la voz no avisa al acabar
    }

    @Override
    protected void onDestroy() {
        viva = false;
        EscuchaService.descansar(6000);
        EscuchaService.revisarYa();
        h.removeCallbacksAndMessages(null);
        pendientes.clear();
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception e) { /* nada */ } }
        if (sr != null) { try { sr.destroy(); } catch (Exception e) { /* nada */ } }
        super.onDestroy();
    }
}
