package ao.patron.voley;

import android.app.Activity;
import android.app.UiModeManager;
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

import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
    private WebView web;
    private boolean tv;
    private final ArrayList<Integer> devs = new ArrayList<>();
    private final boolean[][] ax = new boolean[2][4];

    // cada mando físico es un jugador: el primero que se usa es el 1, el segundo el 2
    private synchronized int slot(int dev) {
        int i = devs.indexOf(dev);
        if (i < 0) { devs.add(dev); i = devs.size() - 1; }
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
        });
        web.setFocusable(false);
        setContentView(web);
        pantallaCompleta();
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html" + (tv ? "?tv=1" : ""));
    }

    public class Puente {
        @JavascriptInterface
        public boolean isTV() { return tv; }

        // al empezar un partido de 2, el primero que pulse es el jugador 1
        @JavascriptInterface
        public void resetPlayers() { synchronized (MainActivity.this) { devs.clear(); for (boolean[] a : ax) java.util.Arrays.fill(a, false); } }
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

    private void js(String code) { if (web != null) web.evaluateJavascript(code, null); }

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
            int s = slot(e.getDeviceId());
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
            int s = slot(e.getDeviceId());
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
        js("window.kikoPause&&kikoPause()");
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        pantallaCompleta();
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
