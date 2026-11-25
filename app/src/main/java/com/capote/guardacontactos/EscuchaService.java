package com.capote.guardacontactos;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.TelephonyManager;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * El oído de Marisol: se queda escuchando la palabra clave ("Marisol", "mi secretaria", "mi asistente")
 * con un motor de voz que va DENTRO de la app (Vosk), sin internet y sin cortes:
 * el micrófono queda abierto de corrido, no se enciende y apaga.
 * Cuando oye la palabra clave suelta el micrófono y abre la pantalla de voz.
 * Se calla sola durante las llamadas y mientras la pantalla de voz está abierta.
 */
public class EscuchaService extends Service {
    private static final String MODELO = "model-es";
    private static final int MODELO_VERSION = 1;
    /** Solo estas frases (y "otra cosa"): así gasta poco y no transcribe lo demás. */
    private static final String GRAMATICA = "[\"marisol\", \"mar y sol\", \"mi secretaria\", \"mi asistente\", \"[unk]\"]";

    /** Lo último que pasó, para verlo en el aviso y en la pantalla principal. */
    static volatile String estado = "";
    private static volatile long descansoHasta;

    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean correr;
    private volatile long esperaHasta;
    private Thread hilo;
    private long ultimoAviso;
    private String avisoPuesto = "";

    /** Tras cerrar la pantalla de voz, no volver a despertarse enseguida. */
    static void descansar(long ms) { descansoHasta = SystemClock.elapsedRealtime() + ms; }

    /** (El hilo del oído mira solo cada medio segundo; no hace falta avisarle.) */
    static void revisarYa() {}

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!ponerDelante()) { estado = "No pudo arrancar: abre la app"; stopSelf(); return; }
        correr = true;
        hilo = new Thread(new Runnable() { public void run() { oido(); } }, "oido-marisol");
        hilo.start();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && "apagar".equals(i.getAction())) {       // botón APAGAR del aviso
            getSharedPreferences(CallReceiver.PREFS, MODE_PRIVATE).edit().putBoolean("marisol", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        correr = false;
        if (hilo != null) hilo.interrupt();
        estado = "";
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------- aviso fijo ----------

    private Notification aviso(String texto) throws Exception {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b = new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 26) {
            Class<?> ch = Class.forName("android.app.NotificationChannel");
            Object canal = ch.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance("escucha", "Marisol escuchando", 2);
            NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, canal);
            Notification.Builder.class.getMethod("setChannelId", String.class).invoke(b, "escucha");
        }
        Intent abrir = new Intent(this, MainActivity.class);
        b.setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Marisol está escuchando")
                .setContentText(texto)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_mic, "APAGAR",
                        PendingIntent.getService(this, 1, new Intent(this, EscuchaService.class).setAction("apagar"),
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setContentIntent(PendingIntent.getActivity(this, 0, abrir,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }

    /** Aviso fijo obligatorio para poder usar el micrófono con la app cerrada. */
    private boolean ponerDelante() {
        try {
            Notification n = aviso("Di «Marisol» y pídele lo que quieras");
            if (Build.VERSION.SDK_INT >= 29) {
                Service.class.getMethod("startForeground", int.class, Notification.class, int.class)
                        .invoke(this, 1, n, 0x80); // tipo micrófono
            } else {
                startForeground(1, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Apunta lo que pasa: en la pantalla principal siempre, y en el aviso como mucho una vez por segundo. */
    private void decir(String t) {
        estado = t;
        long ahora = SystemClock.elapsedRealtime();
        if (t.equals(avisoPuesto) || ahora - ultimoAviso < 1000) return;
        ultimoAviso = ahora;
        avisoPuesto = t;
        try {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(1, aviso(t));
        } catch (Exception e) { /* el aviso es solo informativo */ }
    }

    // ---------- cuándo escuchar ----------

    private boolean enLlamada() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            return tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE;
        } catch (Exception e) {
            return false;
        }
    }

    /** Devuelve el motivo de la pausa, o null si puede escuchar. */
    private String pausa() {
        if (AskActivity.viva) return "En pausa: pantalla de voz abierta";
        long ahora = SystemClock.elapsedRealtime();
        if (ahora < esperaHasta) return "Abriendo…";
        if (ahora < descansoHasta) return "Un momento…";
        if (enLlamada()) return "En pausa: hay una llamada";
        return null;
    }

    // ---------- el modelo de voz (viene dentro del APK; se copia una vez al teléfono) ----------

    private void copiar(AssetManager am, String origen, File destino) throws Exception {
        String[] hijos = am.list(origen);
        if (hijos != null && hijos.length > 0) {
            if (!destino.isDirectory() && !destino.mkdirs()) throw new Exception("no pude crear " + destino);
            for (String s : hijos) copiar(am, origen + "/" + s, new File(destino, s));
            return;
        }
        InputStream in = am.open(origen);
        OutputStream out = new FileOutputStream(destino);
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.close();
        in.close();
    }

    private static void borrar(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) borrar(x);
        f.delete();
    }

    private Model cargarModelo() throws Exception {
        File dir = new File(getFilesDir(), MODELO);
        File marca = new File(dir, "listo-" + MODELO_VERSION);
        if (!marca.exists()) {
            decir("Preparando el oído (solo la primera vez)…");
            borrar(dir);
            copiar(getAssets(), MODELO, dir);
            if (!marca.createNewFile() && !marca.exists()) throw new Exception("no pude terminar la copia");
        }
        decir("Cargando el oído…");
        return new Model(dir.getAbsolutePath());
    }

    // ---------- el hilo que oye ----------

    private static String campo(String json, String nombre) {
        try { return new JSONObject(json).optString(nombre, "").trim(); } catch (Exception e) { return ""; }
    }

    private void oido() {
        Model modelo = null;
        Recognizer rec = null;
        AudioRecord mic = null;
        try {
            modelo = cargarModelo();
            rec = new Recognizer(modelo, 16000f, GRAMATICA);
            short[] buf = new short[1600];                 // 0,1 segundos
            int seguidas = 0;
            while (correr) {
                String p = pausa();
                if (p == null && checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED)
                    p = "Falta el permiso del micrófono";
                if (p != null) {                               // soltar el micrófono y esperar
                    if (mic != null) { try { mic.stop(); } catch (Exception e) { /* nada */ } mic.release(); mic = null; }
                    decir(p);
                    Thread.sleep(400);
                    continue;
                }
                if (mic == null) {
                    int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    AudioRecord a = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 16000));
                    if (a.getState() != AudioRecord.STATE_INITIALIZED) {
                        a.release();
                        decir("No pude abrir el micrófono (¿otra app lo tiene?)");
                        Thread.sleep(3000);
                        continue;
                    }
                    a.startRecording();
                    mic = a;
                    rec.reset();
                    seguidas = 0;
                    decir("Escuchando…");
                }
                int n = mic.read(buf, 0, buf.length);
                if (n <= 0) {
                    try { mic.stop(); } catch (Exception e) { /* nada */ }
                    mic.release();
                    mic = null;
                    decir("El micrófono se cortó; reintentando…");
                    Thread.sleep(1000);
                    continue;
                }
                boolean llamo;
                if (rec.acceptWaveForm(buf, n)) {              // terminó una frase
                    llamo = Texto.trasMarisol(campo(rec.getResult(), "text")) != null;
                    seguidas = 0;
                } else {                                       // va oyendo: vale si se mantiene 0,3 s
                    seguidas = Texto.trasMarisol(campo(rec.getPartialResult(), "partial")) != null ? seguidas + 1 : 0;
                    llamo = seguidas >= 3;
                }
                if (llamo) {
                    try { mic.stop(); } catch (Exception e) { /* nada */ }
                    mic.release();
                    mic = null;
                    rec.reset();
                    seguidas = 0;
                    despertar();
                }
            }
        } catch (InterruptedException e) {
            // se apagó el servicio
        } catch (Throwable e) {
            decir("Fallo del oído: " + e.getClass().getSimpleName() + (e.getMessage() != null ? " " + e.getMessage() : ""));
        } finally {
            if (mic != null) { try { mic.stop(); } catch (Exception e) { /* nada */ } mic.release(); }
            if (rec != null) { try { rec.close(); } catch (Exception e) { /* nada */ } }
            if (modelo != null) { try { modelo.close(); } catch (Exception e) { /* nada */ } }
        }
    }

    /** Oyó la palabra clave: abre la pantalla de voz (el micrófono ya está suelto). */
    private void despertar() {
        decir("¡Marisol! Abriendo…");
        esperaHasta = SystemClock.elapsedRealtime() + 6000;   // si la pantalla no llega a abrirse, sigue oyendo después
        h.post(new Runnable() {
            public void run() {
                Intent i = new Intent(EscuchaService.this, AskActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                i.putExtra("manual", true);
                i.putExtra("texto", "");
                try { startActivity(i); } catch (Exception e) { estado = "El teléfono no dejó abrir la pantalla"; }
            }
        });
    }
}
