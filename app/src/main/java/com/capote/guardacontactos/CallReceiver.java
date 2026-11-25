package com.capote.guardacontactos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.telephony.TelephonyManager;

/**
 * Vigila las llamadas. Cuando termina una llamada ENTRANTE que se contestó
 * y el número no está en contactos, abre la pantalla que pregunta por voz.
 */
public class CallReceiver extends BroadcastReceiver {
    static final String PREFS = "estado";

    @Override
    public void onReceive(Context c, Intent in) {
        if (in == null || !"android.intent.action.PHONE_STATE".equals(in.getAction())) return;
        String st = in.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (st == null) return;
        String num = in.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String antes = p.getString("st", "IDLE");
        SharedPreferences.Editor e = p.edit();

        EscuchaService.revisarYa();

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(st)) {
            if (!"RINGING".equals(antes)) {
                e.putBoolean("entrante", true).putBoolean("contestada", false).putString("num", "");
            }
            if (num != null && num.length() > 0) e.putString("num", num);
            e.putString("st", "RINGING").apply();

        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(st)) {
            if ("RINGING".equals(antes)) e.putBoolean("contestada", true);
            else if ("IDLE".equals(antes)) e.putBoolean("entrante", false).putBoolean("contestada", false);
            if (num != null && num.length() > 0 && p.getBoolean("entrante", false)
                    && p.getString("num", "").length() == 0) e.putString("num", num);
            e.putString("st", "OFFHOOK").apply();

        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(st)) {
            if ("IDLE".equals(antes)) return;
            boolean entrante = p.getBoolean("entrante", false);
            boolean contestada = p.getBoolean("contestada", false);
            String numero = p.getString("num", "");
            e.putString("st", "IDLE").putBoolean("entrante", false).putBoolean("contestada", false)
                    .putString("num", "").apply();

            if (!entrante || !contestada) return;
            if (!p.getBoolean("preguntar", true)) return;
            if (!Contactos.tienePermisos(c) || !Settings.canDrawOverlays(c)) return;
            if (numero.length() > 0 && Contactos.nombreDe(c, numero) != null) return; // ya está guardado

            Intent i = new Intent(c, AskActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra("numero", numero);
            i.putExtra("manual", false);
            try { c.startActivity(i); } catch (Exception ex) { /* el sistema no dejó abrir */ }
        }
    }
}
