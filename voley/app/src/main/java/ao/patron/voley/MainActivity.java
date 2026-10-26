package ao.patron.voley;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.app.UiModeManager;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import android.net.wifi.WifiManager;
import org.json.JSONObject;

import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity implements SensorEventListener {
    private WebView web;
    private boolean tv;
    private final ArrayList<Integer> devs = new ArrayList<>();
    private final boolean[][] ax = new boolean[2][4];

    // ===== Mover el móvil a los lados (solo el desplazamiento, sin contar la inclinación) =====
    private SensorManager sm;
    private boolean tiltOn = false;
    private Sensor linSensor; private boolean linReal = false;
    private float grav = 0, vel = 0;
    private int moveDir = 0;
    private long lastNs = 0;
    private int evCount = 0;
    private float quiet = 0, rawMax = 0; private boolean cool = false; private long lastDbg = 0;

    private Sensor pickSensor() {
        Sensor l = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        linReal = l != null;
        return l != null ? l : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    private void tilt(boolean on) {
        runOnUiThread(() -> {
            if (sm == null) sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            if (linSensor == null) linSensor = pickSensor();
            if (on && !tiltOn && linSensor != null) { moveDir = 0; lastNs = 0; cool = false; quiet = 0; cnt = 0; sm.registerListener(this, linSensor, SensorManager.SENSOR_DELAY_GAME); tiltOn = true;
                js("window.kikoMoveInfo&&kikoMoveInfo(" + JSONObject.quote((linReal ? "Sensor de movimiento: " : "Acelerometro: ") + linSensor.getName()) + ")");
                evCount = 0;
                // si el sensor de movimiento no manda nada, pasar al acelerómetro normal
                if (web != null) web.postDelayed(() -> {
                    if (tiltOn && evCount == 0 && linReal) {
                        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                        if (acc != null) { sm.unregisterListener(this); linSensor = acc; linReal = false; lastNs = 0; sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
                            js("window.kikoMoveInfo&&kikoMoveInfo(" + JSONObject.quote("Acelerometro: " + acc.getName()) + ")"); }
                    }
                }, 1200); }
            else if (on && linSensor == null) js("window.kikoMoveInfo&&kikoMoveInfo('Este movil no tiene sensor de movimiento')");
            else if (!on && tiltOn) { sm.unregisterListener(this); tiltOn = false; sendMove(0); }
        });
    }

    private void sendMove(int d) {
        if (d == moveDir) return;
        moveDir = d;
        js("window.kikoMove&&kikoMove(" + d + ")");
    }

    // detecta el arranque del movimiento (empujón hacia un lado) y el frenazo al pararlo;
    // resta el empuje fijo que algunos sensores mandan aunque el móvil esté quieto
    private float base = 0; private boolean baseSet = false, braked = false;
    private int cnt = 0, ignDir = 0;
    private float moving = 0, warm = 0, ign = 0;

    @Override
    public void onSensorChanged(SensorEvent e) {
        evCount++;
        float a = e.values[0];
        if (!linReal) { grav = grav * 0.92f + a * 0.08f; a = a - grav; }
        if (lastNs == 0) { lastNs = e.timestamp; baseSet = false; warm = 0.4f; return; }
        float dt = Math.min(0.05f, (e.timestamp - lastNs) / 1e9f); lastNs = e.timestamp;
        final float A = 1.0f; final int K = 3;
        if (!baseSet) { base = a; baseSet = true; }
        if (moveDir == 0 && !cool && Math.abs(a - base) < A) base = base * 0.99f + a * 0.01f;
        float ax = a - base;
        rawMax = Math.max(rawMax * 0.9f, Math.abs(ax));
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastDbg > 100) { lastDbg = nowMs; js("window.kikoMoveVal&&kikoMoveVal(" + String.format(java.util.Locale.US, "%.2f,%.2f", ax, base) + ")"); }
        if (warm > 0) { warm -= dt; return; }
        quiet = Math.abs(ax) < 0.5f ? quiet + dt : 0;
        if (ign > 0) ign -= dt;
        if (cool) { if (quiet >= 0.15f) cool = false; return; }
        if (moveDir == 0) {
            if (ax < -A) cnt = cnt <= 0 ? cnt - 1 : -1;
            else if (ax > A) cnt = cnt >= 0 ? cnt + 1 : 1;
            else cnt = 0;
            int nd = cnt <= -K ? -1 : cnt >= K ? 1 : 0;
            if (nd != 0 && ign > 0 && nd == ignDir) { cnt = 0; nd = 0; }
            if (nd != 0) { sendMove(nd); moving = 0; braked = false; cnt = 0; }
        } else {
            moving += dt;
            if (ax * moveDir < -A) braked = true;
            if ((braked && quiet >= 0.08f) || (!braked && quiet >= 0.9f) || moving > 2.0f) {
                if (!braked) { ign = 0.8f; ignDir = -moveDir; }
                sendMove(0); cool = true;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) { }

    // ===== Móvil como mando por Wi-Fi =====
    private static final int TCP = 47800, UDP = 47801;
    private volatile boolean running = true;
    private ServerSocket server;
    private final AtomicInteger nClients = new AtomicInteger();
    private int clientSeq = 0;
    private Socket ctrl;
    private volatile OutputStream ctrlOut;
    private final ExecutorService tx = Executors.newSingleThreadExecutor();
    private volatile int ctrlGen = 0;

    private void startHost() {
        new Thread(() -> {
            try {
                server = new ServerSocket(TCP);
                while (running) {
                    Socket c = server.accept();
                    c.setTcpNoDelay(true);
                    final int id = -1000 - (clientSeq++);
                    nClients.incrementAndGet(); notifyNet();
                    new Thread(() -> handle(c, id)).start();
                }
            } catch (Exception ignored) { }
        }).start();
        new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                byte[] msg = "VOLEYBOLAS".getBytes();
                while (running) {
                    try { ds.send(new DatagramPacket(msg, msg.length, InetAddress.getByName("255.255.255.255"), UDP)); } catch (Exception ignored) { }
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) { }
        }).start();
    }

    private void handle(Socket c, int id) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split(" ");
                if (p.length < 2 || !p[0].matches("left|right|up|down|a|b|start")) continue;
                final String k = p[0];
                final boolean down = p[1].equals("1");
                final int sl = slot(id, k.equals("a") && down);
                if (sl < 0) continue;
                runOnUiThread(() -> tecla(k, down, sl));
            }
        } catch (Exception ignored) { }
        try { c.close(); } catch (Exception ignored) { }
        nClients.decrementAndGet(); notifyNet();
    }

    private void notifyNet() { runOnUiThread(() -> js("window.kikoNet&&kikoNet(" + nClients.get() + ",'" + myIp() + "')")); }

    private String myIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) return a.getHostAddress();
        } catch (Exception ignored) { }
        return "";
    }

    private void ctrlStatus(String m) { runOnUiThread(() -> js("window.kikoCtrl&&kikoCtrl(" + JSONObject.quote(m) + ")")); }

    // cada mando físico es un jugador: el primero que se usa es el 1, el segundo el 2
    // al elegir mandos, un mando nuevo solo entra cuando pulsa A (el primero es la izquierda)
    private volatile boolean picking = false;
    private synchronized int slot(int dev, boolean aDown) {
        int i = devs.indexOf(dev);
        if (i < 0) {
            if (picking && !aDown) return -1;
            devs.add(dev); i = devs.size() - 1;
        }
        return i > 1 ? 1 : i;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        UiModeManager um = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        tv = um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(0xFF07090F);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        web.addJavascriptInterface(new Puente(), "KikoApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override
            public void onPageFinished(WebView v, String url) { notifyNet(); buscarActualizacion(); }
        });
        web.setFocusable(false);
        setContentView(web);
        pantallaCompleta();
        startHost();
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html" + (tv ? "?tv=1" : ""));
    }

    public class Puente {
        @JavascriptInterface
        public boolean isTV() { return tv; }

        @JavascriptInterface
        public void ctrlStart(String manualIp) {
            final int gen = ++ctrlGen;
            closeCtrl();
            new Thread(() -> {
                WifiManager.MulticastLock lock = null;
                try {
                    String ip = manualIp == null ? "" : manualIp.trim();
                    if (ip.isEmpty()) {
                        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        if (wm != null) { lock = wm.createMulticastLock("voley"); lock.setReferenceCounted(false); lock.acquire(); }
                        String mine = myIp();
                        try (DatagramSocket ds = new DatagramSocket(null)) {
                            ds.setReuseAddress(true); ds.setBroadcast(true);
                            ds.bind(new InetSocketAddress(UDP));
                            ds.setSoTimeout(15000);
                            long end = System.currentTimeMillis() + 15000;
                            while (System.currentTimeMillis() < end && gen == ctrlGen) {
                                DatagramPacket pk = new DatagramPacket(new byte[64], 64);
                                ds.receive(pk);
                                String from = pk.getAddress().getHostAddress();
                                if (new String(pk.getData(), 0, pk.getLength()).equals("VOLEYBOLAS") && !from.equals(mine)) { ip = from; break; }
                            }
                        }
                    }
                    if (gen != ctrlGen) return;
                    if (ip.isEmpty()) throw new Exception("sin tele");
                    ctrlStatus("Conectando a " + ip + "...");
                    Socket s2 = new Socket();
                    s2.connect(new InetSocketAddress(ip, TCP), 5000);
                    s2.setTcpNoDelay(true);
                    if (gen != ctrlGen) { s2.close(); return; }
                    ctrl = s2; ctrlOut = s2.getOutputStream();
                    ctrlStatus("OK:" + ip);
                } catch (Exception e) {
                    if (gen == ctrlGen) ctrlStatus("ERR:No encuentro la tele. Abre Voley Bolas en la tele, mira la misma Wi-Fi o escribe la direccion que sale en la tele.");
                } finally { if (lock != null) try { lock.release(); } catch (Exception ignored) { } }
            }).start();
        }

        @JavascriptInterface
        public void ctrlSend(String k, boolean down) {
            final String line = k + " " + (down ? 1 : 0) + "\n";
            tx.execute(() -> {
                OutputStream o = ctrlOut;
                if (o == null) return;
                try { o.write(line.getBytes()); o.flush(); }
                catch (Exception e) { ctrlOut = null; ctrlStatus("ERR:Se perdio la conexion con la tele."); }
            });
        }

        @JavascriptInterface
        public void ctrlStop() { ctrlGen++; closeCtrl(); }

        // al empezar un partido de 2, el primero que pulse es el jugador 1
        @JavascriptInterface
        public void resetPlayers() { synchronized (MainActivity.this) { devs.clear(); picking = true; for (boolean[] a : ax) java.util.Arrays.fill(a, false); } }

        @JavascriptInterface
        public void endPick() { picking = false; }

        // el mando de inclinar se usa con el móvil en vertical
        @JavascriptInterface
        public void setPortrait(boolean p) {
            if (tv) return;
            runOnUiThread(() -> setRequestedOrientation(p ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
        }
    }

    private void pantallaCompleta() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) pantallaCompleta();
    }

    // ================= ACTUALIZAR =================
    // al abrir, mira en GitHub si hay versión nueva; si la hay, pregunta, la descarga y abre el instalador
    private static final String UPD = "https://github.com/capoteadonis1979/kiko-juego/releases/download/voley/";
    private File apkNueva = null;
    private boolean esperaPermiso = false, avisando = false;
    private long ultBusqueda = 0;

    private String miVersion() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "0"; } }
    private static int num(String t) { try { return Integer.parseInt(t.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; } }
    private static int compara(String a, String b) {
        String[] x = a.trim().split("\\."), y = b.trim().split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) { int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0; if (p != q) return p < q ? -1 : 1; }
        return 0;
    }
    private void buscarActualizacion() {
        long ahora = System.currentTimeMillis(); if (avisando || ahora - ultBusqueda < 10 * 60 * 1000L) return; ultBusqueda = ahora;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(UPD + "version.txt").openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setUseCaches(false); c.setInstanceFollowRedirects(true);
                if (c.getResponseCode() != 200) return;
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                String v = r.readLine(); r.close();
                if (v == null) return; final String nv = v.trim();
                if (nv.length() > 0 && compara(nv, miVersion()) > 0) runOnUiThread(() -> ofrecer(nv));
            } catch (Exception ignored) { }
        }).start();
    }
    private void ofrecer(String v) {
        if (isFinishing() || avisando) return; avisando = true;
        new AlertDialog.Builder(this)
            .setTitle("Versión nueva " + v)
            .setMessage("Hay una versión nueva del Voley Bolas (tienes la " + miVersion() + ").\n¿La instalo ahora?")
            .setPositiveButton("ACTUALIZAR", (d, w) -> descargar())
            .setNegativeButton("DESPUÉS", null)
            .setOnDismissListener(d -> avisando = false)
            .show();
    }
    private void descargar() {
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Actualizando").setMessage("Descargando… 0%").setCancelable(false).create();
        dlg.show();
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "upd"); dir.mkdirs();
                File f = new File(dir, "VoleyBolas.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(UPD + "VoleyBolas.apk").openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true);
                int total = c.getContentLength(), hecho = 0, ult = -1;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[32768]; int n;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); hecho += n;
                        if (total > 0) { final int pc = (int) (hecho * 100L / total); if (pc != ult) { ult = pc; runOnUiThread(() -> dlg.setMessage("Descargando… " + pc + "%")); } } }
                }
                runOnUiThread(() -> { dlg.dismiss(); apkNueva = f; instalar(); });
            } catch (Exception e) {
                runOnUiThread(() -> { dlg.dismiss(); new AlertDialog.Builder(this).setMessage("No se pudo descargar la versión nueva. Revisa el internet y vuelve a abrir el juego.").setPositiveButton("OK", null).show(); });
            }
        }).start();
    }
    private void instalar() {
        if (apkNueva == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            esperaPermiso = true;
            new AlertDialog.Builder(this)
                .setMessage("Para actualizar, activa \"Permitir de esta fuente\" (instalar apps desconocidas) para Voley Bolas y vuelve atrás.")
                .setPositiveButton("ABRIR AJUSTES", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
                    catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); } catch (Exception ignored) { } }
                })
                .setNegativeButton("DESPUÉS", (d, w) -> esperaPermiso = false)
                .show();
            return;
        }
        try {
            Uri u = Uri.parse("content://ao.patron.voley.apk/VoleyBolas.apk");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            new AlertDialog.Builder(this).setMessage("No se pudo abrir el instalador.").setPositiveButton("OK", null).show();
        }
    }

    private void js(String code) { if (web != null) web.evaluateJavascript(code, null); }

    private void closeCtrl() {
        try { if (ctrl != null) ctrl.close(); } catch (Exception ignored) { }
        ctrl = null; ctrlOut = null;
    }

    private void tecla(String k, boolean down, int s) {
        js("window.kikoKey&&kikoKey('" + k + "'," + down + "," + s + ")");
    }

    private String nombre(KeyEvent e) {
        int c = e.getKeyCode();
        boolean pad = (e.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        switch (c) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_BUTTON_A:
                return "a";
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                return "b";
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU:
                return "start";
            case KeyEvent.KEYCODE_BACK:
                // Algunos mandos baratos mandan "Atrás" en el botón B
                return pad ? "b" : null;
        }
        return null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String k = nombre(e);
        if (k != null) {
            int s = slot(e.getDeviceId(), k.equals("a") && e.getAction() == KeyEvent.ACTION_DOWN);
            if (s < 0) return true;
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) tecla(k, true, s);
            else if (e.getAction() == KeyEvent.ACTION_UP) tecla(k, false, s);
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            if (e.getAction() == KeyEvent.ACTION_UP && web != null) {
                web.evaluateJavascript("window.kikoBack?kikoBack():false", v -> {
                    if (!"true".equals(v)) finish();
                });
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && e.getAction() == MotionEvent.ACTION_MOVE) {
            float x = e.getAxisValue(MotionEvent.AXIS_X), hx = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            float y = e.getAxisValue(MotionEvent.AXIS_Y), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            boolean l = x < -0.4f || hx < -0.5f, r = x > 0.4f || hx > 0.5f, d = y > 0.5f || hy > 0.5f, u = y < -0.5f || hy < -0.5f;
            int s = slot(e.getDeviceId(), false);
            if (s < 0) return true;
            boolean[] a = ax[s];
            if (l != a[0]) { a[0] = l; tecla("left", l, s); }
            if (r != a[1]) { a[1] = r; tecla("right", r, s); }
            if (d != a[2]) { a[2] = d; tecla("down", d, s); }
            if (u != a[3]) { a[3] = u; tecla("up", u, s); }
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    protected void onPause() {
        if (tiltOn && sm != null) sm.unregisterListener(this);
        js("window.kikoPause&&kikoPause()");
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        if (tiltOn && sm != null && linSensor != null) { vel = 0; lastNs = 0; sm.registerListener(this, linSensor, SensorManager.SENSOR_DELAY_GAME); }
        pantallaCompleta();
        if (esperaPermiso && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) { esperaPermiso = false; instalar(); }
        else if (!esperaPermiso && web != null && web.getProgress() >= 100) buscarActualizacion();
    }

    @Override
    protected void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        closeCtrl();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
