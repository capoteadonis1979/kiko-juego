package ao.patron.domino;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
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
        if (tv) startHost();
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html" + (tv ? "?tv=1" : ""));
    }

    private void js(String code) { runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    // ================= TELE =================
    private void startHost() {
        new Thread(() -> {
            try {
                server = new ServerSocket(TCP);
                while (running) {
                    Socket c = server.accept();
                    c.setTcpNoDelay(true);
                    final int id;
                    synchronized (this) { id = clientSeq++; }
                    clients.put(id, c.getOutputStream());
                    js("window.kikoPeer&&kikoPeer(" + id + ",true)");
                    notifyNet();
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
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) { }
        }).start();
    }

    private void readClient(Socket c, int id) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() > 4000) continue;
                js("window.kikoMsg&&kikoMsg(" + id + "," + JSONObject.quote(line) + ")");
            }
        } catch (Exception ignored) { }
        clients.remove(id);
        try { c.close(); } catch (Exception ignored) { }
        js("window.kikoPeer&&kikoPeer(" + id + ",false)");
        notifyNet();
    }

    private void notifyNet() { js("window.kikoNet&&kikoNet(" + clients.size() + ",'" + myIp() + "')"); }

    private String myIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) return a.getHostAddress();
        } catch (Exception ignored) { }
        return "";
    }

    private void closeCtrl() {
        try { if (ctrl != null) ctrl.close(); } catch (Exception ignored) { }
        ctrl = null; ctrlOut = null;
    }

    private void ctrlStatus(String m) { js("window.kikoCtrl&&kikoCtrl(" + JSONObject.quote(m) + ")"); }

    public class Puente {
        @JavascriptInterface
        public boolean isTV() { return tv; }

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
                                if (new String(pk.getData(), 0, pk.getLength()).equals(BEACON) && !from.equals(mine)) { ip = from; break; }
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
                    // escuchar lo que manda la tele
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(s2.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) js("window.kikoFromTV&&kikoFromTV(" + JSONObject.quote(line) + ")");
                    } catch (Exception ignored) { }
                    if (gen == ctrlGen) ctrlStatus("ERR:Se perdió la conexión con la tele. Pulsa EMPAREJAR.");
                } catch (Exception e) {
                    if (gen == ctrlGen) ctrlStatus("ERR:No encuentro la tele. Abre Dominó Cubano en la tele, usa la misma Wi-Fi o escribe la dirección que sale en la tele.");
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
