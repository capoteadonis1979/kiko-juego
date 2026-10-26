package ao.patron.domino;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.provider.MediaStore;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

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
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.net.InterfaceAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;

/**
 * Dominó Cubano: en la tele la app es la mesa; en el móvil, la mano de fichas.
 * La tele abre un servidor en la Wi-Fi y avisa por difusión; cada móvil se conecta
 * y se intercambian mensajes de texto (una línea JSON por mensaje) en los dos sentidos.
 */
public class MainActivity extends Activity {
    private static final int TCP = 47810, UDP = 47811;
    private static final String BEACON = "DOMINOCUBANO";

    private WebView web;
    private boolean tv;
    private volatile boolean running = true;

    // ----- tele: servidor -----
    private ServerSocket server;
    private boolean hosting = false;
    private final ConcurrentHashMap<Integer, OutputStream> clients = new ConcurrentHashMap<>();
    private int clientSeq = 1;
    private final ExecutorService tx = Executors.newSingleThreadExecutor();

    // ----- móvil: conexión con la tele -----
    private volatile Socket ctrl;
    private volatile OutputStream ctrlOut;
    private volatile int ctrlGen = 0;

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
        web.setBackgroundColor(0xFF0B1A12);
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
            public void onPageFinished(WebView v, String url) { notifyNet(); }
        });
        setContentView(web);
        pantallaCompleta();
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html" + (tv ? "?tv=1" : ""));
    }

    private void js(String code) { runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    // ================= TELE =================
    private synchronized void startHost() {
        if (hosting) return;
        hosting = true;
        new Thread(() -> {
            try {
                server = new ServerSocket(TCP);
                while (running) {
                    Socket c = server.accept();
                    c.setTcpNoDelay(true);
                    final int id;
                    synchronized (this) { id = clientSeq++; }
                    clients.put(id, c.getOutputStream());
                    new Thread(() -> readClient(c, id)).start();
                }
            } catch (Exception ignored) { }
        }).start();
        new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                byte[] msg = BEACON.getBytes();
                while (running) {
                    try { ds.send(new DatagramPacket(msg, msg.length, InetAddress.getByName("255.255.255.255"), UDP)); } catch (Exception ignored) { }
                    try {
                        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                            for (InterfaceAddress ia : ni.getInterfaceAddresses())
                                if (ia.getBroadcast() != null) try { ds.send(new DatagramPacket(msg, msg.length, ia.getBroadcast(), UDP)); } catch (Exception ignored) { }
                    } catch (Exception ignored) { }
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) { }
        }).start();
    }

    private void readClient(Socket c, int id) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line; boolean announced = false;
            while ((line = r.readLine()) != null) {
                if (line.length() > 120000) continue;
                if (!announced) { announced = true; js("window.kikoPeer&&kikoPeer(" + id + ",true)"); notifyNet(); }
                js("window.kikoMsg&&kikoMsg(" + id + "," + JSONObject.quote(line) + ")");
            }
            clients.remove(id);
            try { c.close(); } catch (Exception ignored) { }
            if (announced) { js("window.kikoPeer&&kikoPeer(" + id + ",false)"); notifyNet(); }
            return;
        } catch (Exception ignored) { }
        clients.remove(id);
        try { c.close(); } catch (Exception ignored) { }
        js("window.kikoPeer&&kikoPeer(" + id + ",false)");
        notifyNet();
    }

    private void notifyNet() { js("window.kikoNet&&kikoNet(" + clients.size() + ",'" + myIp() + "')"); }

    private String myIp() {
        String best = "", any = "";
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                String n = ni.getName() == null ? "" : ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.isLoopbackAddress() || !(a instanceof Inet4Address)) continue;
                    String h = a.getHostAddress();
                    if (any.isEmpty()) any = h;
                    if (a.isSiteLocalAddress() && (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth"))) return h;
                    if (a.isSiteLocalAddress() && best.isEmpty()) best = h;
                }
            }
        } catch (Exception ignored) { }
        return !best.isEmpty() ? best : any;
    }

    // si no llega el aviso, se prueba cada dirección de la red Wi-Fi
    private void scanSubnet(String mine, AtomicReference<String> found, int gen) {
        if (mine == null || mine.lastIndexOf('.') < 0) return;
        String pre = mine.substring(0, mine.lastIndexOf('.') + 1);
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i < 255; i++) {
            final String ip = pre + i;
            if (ip.equals(mine)) continue;
            pool.execute(() -> {
                if (found.get() != null || gen != ctrlGen) return;
                try (Socket s = new Socket()) { s.connect(new InetSocketAddress(ip, TCP), 700); found.compareAndSet(null, ip); } catch (Exception ignored) { }
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(20, TimeUnit.SECONDS); } catch (Exception ignored) { }
    }

    private void closeCtrl() {
        try { if (ctrl != null) ctrl.close(); } catch (Exception ignored) { }
        ctrl = null; ctrlOut = null;
    }

    private void ctrlStatus(String m) { js("window.kikoCtrl&&kikoCtrl(" + JSONObject.quote(m) + ")"); }

    public class Puente {
        @JavascriptInterface
        public boolean isTV() { return tv; }

        // abre el teclado de la tele / el cel para escribir en un cuadro de texto
        @JavascriptInterface
        public void showKeyboard() {
            runOnUiThread(() -> { try { web.requestFocus(); android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE); if (im != null) im.showSoftInput(web, 0); } catch (Exception ignored) {} });
        }

        // identificador fijo del aparato (para el código de activación)
        @JavascriptInterface
        public String deviceId() {
            try { String a = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID); return a == null ? "" : a; }
            catch (Exception e) { return ""; }
        }

        // tele → un móvil
        @JavascriptInterface
        public void sendTo(int id, String line) {
            final OutputStream o = clients.get(id);
            if (o == null) return;
            final byte[] data = (line.replace("\n", " ") + "\n").getBytes(StandardCharsets.UTF_8);
            tx.execute(() -> { try { o.write(data); o.flush(); } catch (Exception e) { clients.remove(id); } });
        }

        // móvil: buscar la tele y conectarse
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
                        if (wm != null) { lock = wm.createMulticastLock("domino"); lock.setReferenceCounted(false); lock.acquire(); }
                        final String mine = myIp();
                        final AtomicReference<String> found = new AtomicReference<>(null);
                        ctrlStatus("Buscando la mesa en tu Wi-Fi...");
                        new Thread(() -> scanSubnet(mine, found, gen)).start();
                        try (DatagramSocket ds = new DatagramSocket(null)) {
                            ds.setReuseAddress(true); ds.setBroadcast(true);
                            ds.bind(new InetSocketAddress(UDP));
                            ds.setSoTimeout(400);
                            long end = System.currentTimeMillis() + 20000;
                            while (System.currentTimeMillis() < end && gen == ctrlGen && found.get() == null) {
                                DatagramPacket pk = new DatagramPacket(new byte[64], 64);
                                try { ds.receive(pk); } catch (SocketTimeoutException t) { continue; }
                                String from = pk.getAddress().getHostAddress();
                                if (new String(pk.getData(), 0, pk.getLength()).equals(BEACON) && !from.equals(mine)) { found.compareAndSet(null, from); }
                            }
                        } catch (Exception e) {
                            long end = System.currentTimeMillis() + 20000;
                            while (System.currentTimeMillis() < end && gen == ctrlGen && found.get() == null) Thread.sleep(200);
                        }
                        if (found.get() != null) ip = found.get();
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
                    // escuchar lo que manda la tele
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(s2.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) js("window.kikoFromTV&&kikoFromTV(" + JSONObject.quote(line) + ")");
                    } catch (Exception ignored) { }
                    if (gen == ctrlGen) ctrlStatus("ERR:Se perdió la conexión con la tele. Pulsa EMPAREJAR.");
                } catch (Exception e) {
                    if (gen == ctrlGen) ctrlStatus("ERR:No encuentro la mesa. Abre Dominó Cubano en la tele o tablet y elige MESA, usa la misma Wi-Fi o escribe la dirección que sale en la mesa.");
                } finally { if (lock != null) try { lock.release(); } catch (Exception ignored) { } }
            }).start();
        }

        // móvil → tele
        @JavascriptInterface
        public void ctrlSendLine(String line) {
            final byte[] data = (line.replace("\n", " ") + "\n").getBytes(StandardCharsets.UTF_8);
            tx.execute(() -> {
                OutputStream o = ctrlOut;
                if (o == null) return;
                try { o.write(data); o.flush(); }
                catch (Exception e) { ctrlOut = null; ctrlStatus("ERR:Se perdió la conexión con la tele. Pulsa EMPAREJAR."); }
            });
        }

        @JavascriptInterface
        public void startHost() { MainActivity.this.startHost(); notifyNet(); }

        // foto del jugador con la cámara frontal (miniatura, sin permisos)
        @JavascriptInterface
        public void takePhoto() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    i.putExtra("android.intent.extras.CAMERA_FACING", 1);
                    i.putExtra("android.intent.extras.LENS_FACING_FRONT", 1);
                    i.putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
                    startActivityForResult(i, 77);
                } catch (Exception e) {
                    try {
                        Intent g = new Intent(Intent.ACTION_GET_CONTENT); g.setType("image/*");
                        startActivityForResult(g, 78);
                    } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void ctrlStop() { ctrlGen++; closeCtrl(); }

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
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        try {
            Bitmap bm = null;
            if (req == 77 && data.getExtras() != null) bm = (Bitmap) data.getExtras().get("data");
            else if (req == 78 && data.getData() != null) bm = MediaStore.Images.Media.getBitmap(getContentResolver(), data.getData());
            if (bm == null) return;
            int m = Math.min(bm.getWidth(), bm.getHeight());
            Bitmap sq = Bitmap.createBitmap(bm, (bm.getWidth() - m) / 2, (bm.getHeight() - m) / 2, m, m);
            Bitmap sm2 = Bitmap.createScaledBitmap(sq, 160, 160, true);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            sm2.compress(Bitmap.CompressFormat.JPEG, 80, bo);
            String url = "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
            js("window.kikoPhoto&&kikoPhoto(" + JSONObject.quote(url) + ")");
        } catch (Exception ignored) { }
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) pantallaCompleta();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int c = e.getKeyCode();
        if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_ESCAPE) {
            if (e.getAction() == KeyEvent.ACTION_UP && web != null)
                web.evaluateJavascript("window.kikoBack?kikoBack():false", v -> { if (!"true".equals(v)) finish(); });
            return true;
        }
        if (c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_BUTTON_A || c == KeyEvent.KEYCODE_BUTTON_START) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) js("window.kikoKey&&kikoKey('a',true)");
            return true;
        }
        // flechas del mando: las maneja el juego (así van siempre al botón de ese lado)
        String dir = c == KeyEvent.KEYCODE_DPAD_LEFT ? "left" : c == KeyEvent.KEYCODE_DPAD_RIGHT ? "right" : c == KeyEvent.KEYCODE_DPAD_UP ? "up" : c == KeyEvent.KEYCODE_DPAD_DOWN ? "down" : null;
        if (dir != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) js("window.kikoKey&&kikoKey('" + dir + "',true)");
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onPause() { if (web != null) web.onPause(); super.onPause(); }

    @Override
    protected void onResume() { super.onResume(); if (web != null) web.onResume(); pantallaCompleta(); }

    @Override
    protected void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        closeCtrl();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
