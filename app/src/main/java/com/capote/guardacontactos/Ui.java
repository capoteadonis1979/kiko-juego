package com.capote.guardacontactos;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Piezas de la pantalla: mismos colores y tamaños en toda la app. */
final class Ui {
    private Ui() {}

    static final int FONDO = Color.parseColor("#0F1720");
    static final int TARJETA = Color.parseColor("#1B2733");
    static final int VERDE = Color.parseColor("#1FA855");
    static final int ROJO = Color.parseColor("#D93025");
    static final int NEUTRO = Color.parseColor("#34495E");
    static final int AMBAR = Color.parseColor("#E09A00");
    static final int BLANCO = Color.WHITE;
    static final int GRIS = Color.parseColor("#9FB0C0");
    static final int AMARILLO = Color.parseColor("#FFD866");

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private static GradientDrawable caja(Context c, int color, float radio) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radio));
        return g;
    }

    private static int oscuro(int color) {
        return Color.rgb((int) (Color.red(color) * 0.72f), (int) (Color.green(color) * 0.72f), (int) (Color.blue(color) * 0.72f));
    }

    static void fondoBoton(TextView v, int color) {
        Context c = v.getContext();
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, caja(c, oscuro(color), 16));
        s.addState(new int[]{}, caja(c, color, 16));
        v.setBackground(s);
    }

    /** Texto con tamaño fijo en dp (no cambia con el tamaño de letra del sistema, para que todo quepa). */
    static TextView texto(Context c, String t, float tam, int color, boolean negrita) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, tam);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setIncludeFontPadding(false);
        if (negrita) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    static TextView boton(Context c, String t, float tam, int color) {
        TextView v = texto(c, t, tam, BLANCO, true);
        fondoBoton(v, color);
        v.setClickable(true);
        v.setFocusable(true);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(dp(c, 8), 0, dp(c, 8), 0);
        return v;
    }

    static TextView tarjeta(Context c, String t, float tam, int color, int lineas) {
        TextView v = texto(c, t, tam, color, false);
        v.setBackground(caja(c, TARJETA, 16));
        v.setMaxLines(lineas);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        return v;
    }

    /** Parámetros para repartir la altura por pesos (así nada se sale y no hace falta scroll). */
    static LinearLayout.LayoutParams fila(Context c, float peso, int margenArriba) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, peso);
        p.topMargin = dp(c, margenArriba);
        return p;
    }

    static LinearLayout.LayoutParams columna(Context c, float peso, int margenIzq) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, peso);
        p.leftMargin = dp(c, margenIzq);
        return p;
    }

    static String version(Context c) {
        try {
            return "v" + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
