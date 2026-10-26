package ao.patron.domino;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.provider.Settings;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
            public void onPageFinished(WebView v, String url) { notifyNet(); buscarActualizacion(); }
        });
        setContentView(web);
        pantallaCompleta();
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html" + (tv ? "?tv=1" : ""));
    }

    // ================= ACTUALIZAR =================
    // al abrir, mira en GitHub si hay versión nueva; si la hay, pregunta, la descarga y abre el instalador
    private static final String UPD = "https://github.com/capoteadonis1979/kiko-juego/releases/download/domino/";
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
            .setMessage("Hay una versión nueva del Dominó Cubano (tienes la " + miVersion() + ").\n¿La instalo ahora?")
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
                File f = new File(dir, "DominoCubano.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(UPD + "DominoCubano.apk").openConnection();
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
                .setMessage("Para actualizar, activa \"Permitir de esta fuente\" (instalar apps desconocidas) para Dominó Cubano y vuelve atrás.")
                .setPositiveButton("ABRIR AJUSTES", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
                    catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); } catch (Exception ignored) { } }
                })
                .setNegativeButton("DESPUÉS", (d, w) -> esperaPermiso = false)
                .show();
            return;
        }
        try {
            Uri u = Uri.parse("content://ao.patron.domino.apk/DominoCubano.apk");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            new AlertDialog.Builder(this).setMessage("No se pudo abrir el instalador.").setPositiveButton("OK", null).show();
        }
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

    private void notifyNet() { js("window.kikoNet&&kikoNet(" + clients.size() + ",'" + myIp() + "','" + allIps() + "')"); }

    // todas las direcciones de este aparato en la red local (para el QR de conectar)
    private String allIps() {
        StringBuilder sb = new StringBuilder(myIp());
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String h = a.getHostAddress();
                    if (sb.indexOf(h) < 0) { if (sb.length() > 0) sb.append(','); sb.append(h); }
                }
            }
        } catch (Exception ignored) { }
        return sb.toString();
    }

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
        public void askText(String title, String value, String tag) {
            // cuadro nativo de Android para escribir (el teclado del TV funciona bien aquí)
            runOnUiThread(() -> {
                try {
                    final android.widget.EditText et = new android.widget.EditText(MainActivity.this);
                    et.setText(value == null ? "" : value); et.setSingleLine(true); et.setSelectAllOnFocus(true);
                    et.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(14) });
                    et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
                    android.widget.FrameLayout box = new android.widget.FrameLayout(MainActivity.this);
                    int p = (int) (20 * getResources().getDisplayMetrics().density); box.setPadding(p, p / 2, p, 0); box.addView(et);
                    final AlertDialog d = new AlertDialog.Builder(MainActivity.this).setTitle(title).setView(box)
                        .setPositiveButton("GUARDAR", (x, w) -> js("window.kikoText&&kikoText(" + JSONObject.quote(tag) + "," + JSONObject.quote(et.getText().toString()) + ")"))
                        .setNegativeButton("CANCELAR", null).create();
                    et.setOnEditorActionListener((v, id, ev) -> { if (id == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { d.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); return true; } return false; });
                    if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                    d.setOnShowListener(x -> { et.requestFocus(); et.selectAll(); });
                    d.show();
                } catch (Exception ignored) { }
            });
        }

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
                    Socket s2 = null;
                    for (String one : ip.split(",")) {          // el QR puede traer varias direcciones: se prueba cada una
                        one = one.trim(); if (one.isEmpty()) continue;
                        ctrlStatus("Conectando a " + one + "...");
                        Socket t = new Socket();
                        try { t.connect(new InetSocketAddress(one, TCP), 4000); s2 = t; ip = one; break; }
                        catch (Exception x) { try { t.close(); } catch (Exception ignored) { } }
                        if (gen != ctrlGen) return;
                    }
                    if (s2 == null) throw new Exception("no conecta");
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
                    if (gen == ctrlGen) ctrlStatus("ERR:No encuentro la mesa. La mesa y este cel tienen que estar en la MISMA red Wi-Fi. Toca ESCANEAR QR y apunta al QR CONECTAR A LA MESA.");
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

        // escáner de QR (el de Google, sin pedir permiso de cámara)
        @JavascriptInterface
        public void scanQR() {
            runOnUiThread(() -> {
                try {
                    com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions o = new com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build();
                    com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(MainActivity.this, o).startScan()
                            .addOnSuccessListener(bc -> js("window.kikoQR&&kikoQR(" + JSONObject.quote(bc.getRawValue() == null ? "" : bc.getRawValue()) + ")"))
                            .addOnCanceledListener(() -> js("window.kikoQR&&kikoQR('')"))
                            .addOnFailureListener(e -> js("window.kikoQR&&kikoQR('ERR')"));
                } catch (Throwable e) { js("window.kikoQR&&kikoQR('ERR')"); }
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
            runOnUiThread(() -> { setRequestedOrientation(p ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE); pantallaCompleta(); web.postDelayed(MainActivity.this::pantallaCompleta, 600); });
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); pantallaCompleta(); if (web != null) web.postDelayed(this::pantallaCompleta, 500); }

    // pantalla completa: sin barra de notificaciones arriba ni botones abajo (también en Android 11 o más nuevo)
    private void pantallaCompleta() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                getWindow().setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController ic = getWindow().getInsetsController();
                if (ic != null) {
                    ic.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                    ic.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } catch (Exception ignored) { }
        }
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
    protected void onResume() { super.onResume(); if (web != null) web.onResume(); pantallaCompleta();
        if (esperaPermiso && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) { esperaPermiso = false; instalar(); }
        else if (!esperaPermiso && web != null && web.getProgress() >= 100) buscarActualizacion(); }

    @Override
    protected void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        closeCtrl();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
