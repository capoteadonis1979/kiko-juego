package com.capote.guardacontactos;

import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;

import java.util.ArrayList;

/** Leer y guardar contactos, y mirar la última llamada. */
final class Contactos {
    private Contactos() {}

    static final String[] PERMISOS = {
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_CALL_LOG",
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.RECORD_AUDIO"};

    static boolean tienePermisos(Context c) {
        for (String p : PERMISOS) {
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /** Nombre con que está guardado ese número, o null si no está en contactos. */
    static String nombreDe(Context c, String numero) {
        if (numero == null || numero.length() == 0) return null;
        Cursor cur = null;
        try {
            Uri u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(numero));
            cur = c.getContentResolver().query(u,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                return n == null ? "" : n;
            }
        } catch (Exception e) {
            // sin permiso: se trata como "no está"
        } finally {
            if (cur != null) cur.close();
        }
        return null;
    }

    /** Número de la última llamada del registro (entrante o cualquiera). "" si no hay. */
    static String ultimoNumero(Context c, boolean soloEntrante) {
        Cursor cur = null;
        try {
            String donde = soloEntrante ? CallLog.Calls.TYPE + "=" + CallLog.Calls.INCOMING_TYPE : null;
            cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER}, donde, null, CallLog.Calls.DATE + " DESC");
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null && n.replaceAll("[^0-9]", "").length() >= 3) return n;
            }
        } catch (Exception e) {
            // sin permiso
        } finally {
            if (cur != null) cur.close();
        }
        return "";
    }

    /** Las últimas llamadas, para que Marisol las revise. nombre = null si el número no está guardado. */
    static org.json.JSONObject ultimasLlamadas(Context c, int cuantas) throws Exception {
        cuantas = Math.max(1, Math.min(30, cuantas));
        org.json.JSONArray l = new org.json.JSONArray();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEEE d 'de' MMMM, HH:mm", new java.util.Locale("es"));
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION},
                    null, null, CallLog.Calls.DATE + " DESC");
            while (cur != null && cur.moveToNext() && l.length() < cuantas) {
                String num = cur.getString(0);
                int tipo = cur.getInt(1);
                String t = tipo == CallLog.Calls.INCOMING_TYPE ? "entrante" : tipo == CallLog.Calls.OUTGOING_TYPE ? "saliente"
                        : tipo == CallLog.Calls.MISSED_TYPE ? "perdida" : tipo == 5 ? "rechazada" : "otra";
                boolean oculto = num == null || num.replaceAll("[^0-9]", "").length() < 3;
                String nombre = oculto ? null : nombreDe(c, num);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("numero", oculto ? "oculto" : num);
                o.put("tipo", t);
                o.put("cuando", f.format(new java.util.Date(cur.getLong(2))));
                o.put("duracion_seg", cur.getLong(3));
                o.put("nombre", nombre == null ? org.json.JSONObject.NULL : nombre);
                l.put(o);
            }
        } finally {
            if (cur != null) cur.close();
        }
        return new org.json.JSONObject().put("llamadas", l);
    }

    /** Busca contactos por nombre o número. */
    static org.json.JSONObject buscar(Context c, String texto) throws Exception {
        org.json.JSONArray l = new org.json.JSONArray();
        String q = texto == null ? "" : texto.trim();
        String cifras = q.replaceAll("[^0-9]", "");
        Cursor cur = null;
        try {
            String donde = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?";
            java.util.ArrayList<String> args = new java.util.ArrayList<String>();
            args.add("%" + q + "%");
            if (cifras.length() >= 3) {
                donde += " OR " + ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ? OR "
                        + ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER + " LIKE ?";
                args.add("%" + cifras + "%");
                args.add("%" + cifras + "%");
            }
            cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                    donde, args.toArray(new String[0]), ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            while (cur != null && cur.moveToNext() && l.length() < 15) {
                l.put(new org.json.JSONObject().put("nombre", cur.getString(0)).put("numero", cur.getString(1)));
            }
        } finally {
            if (cur != null) cur.close();
        }
        return new org.json.JSONObject().put("contactos", l);
    }

    /** Crea el contacto en el teléfono. */
    static boolean guardar(Context c, String nombre, String numero) {
        try {
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build());
            // Todo el texto va como nombre, para que se vea exactamente como se dijo.
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, nombre)
                    .build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, numero)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build());
            c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
