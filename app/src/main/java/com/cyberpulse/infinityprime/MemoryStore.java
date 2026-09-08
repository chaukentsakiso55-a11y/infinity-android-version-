package com.cyberpulse.infinityprime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class MemoryStore extends SQLiteOpenHelper {
    public static final class Message {
        public final String role;
        public final String text;
        public final long ts;
        Message(String role, String text, long ts) { this.role = role; this.text = text; this.ts = ts; }
    }

    public MemoryStore(Context context) {
        super(context.getApplicationContext(), "infinity_prime.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE timeline(id INTEGER PRIMARY KEY AUTOINCREMENT, event TEXT NOT NULL, detail TEXT NOT NULL, ts INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE project_files(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, uri TEXT NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE provider_stats(provider TEXT PRIMARY KEY, calls INTEGER NOT NULL DEFAULT 0, success INTEGER NOT NULL DEFAULT 0, latency_ms INTEGER NOT NULL DEFAULT 0)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public synchronized void addMessage(String role, String text) {
        ContentValues v = new ContentValues();
        v.put("role", role); v.put("text", text); v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insert("messages", null, v);
        getWritableDatabase().execSQL("DELETE FROM messages WHERE id NOT IN (SELECT id FROM messages ORDER BY id DESC LIMIT 250)");
    }

    public synchronized List<Message> recentMessages(int limit) {
        ArrayList<Message> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT role,text,ts FROM messages ORDER BY id DESC LIMIT ?", new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) out.add(0, new Message(c.getString(0), c.getString(1), c.getLong(2)));
        } finally { c.close(); }
        return out;
    }

    public synchronized void timeline(String event, String detail) {
        ContentValues v = new ContentValues();
        v.put("event", event); v.put("detail", detail == null ? "" : detail); v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insert("timeline", null, v);
        getWritableDatabase().execSQL("DELETE FROM timeline WHERE id NOT IN (SELECT id FROM timeline ORDER BY id DESC LIMIT 500)");
    }

    public synchronized String recentTimeline(int limit) {
        StringBuilder sb = new StringBuilder();
        Cursor c = getReadableDatabase().rawQuery("SELECT event,detail,ts FROM timeline ORDER BY id DESC LIMIT ?", new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                sb.append("• ").append(c.getString(0));
                String d = c.getString(1);
                if (d != null && !d.isEmpty()) sb.append(" — ").append(d);
                sb.append('\n');
            }
        } finally { c.close(); }
        return sb.toString();
    }

    public synchronized void clearProjectIndex() {
        getWritableDatabase().delete("project_files", null, null);
    }

    public synchronized void addProjectFile(String name, String uri, String text) {
        ContentValues v = new ContentValues();
        v.put("name", name); v.put("uri", uri); v.put("text", text); v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insert("project_files", null, v);
    }

    public synchronized int projectFileCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM project_files", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    public synchronized String searchProject(String q, int limit) {
        if (q == null) q = "";
        String like = "%" + q.replace("%", "") + "%";
        StringBuilder sb = new StringBuilder();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT name,text FROM project_files WHERE name LIKE ? OR text LIKE ? ORDER BY id DESC LIMIT ?",
                new String[]{like, like, String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                String text = c.getString(1);
                if (text.length() > 1800) text = text.substring(0, 1800);
                sb.append("\n--- ").append(c.getString(0)).append(" ---\n").append(text).append('\n');
            }
        } finally { c.close(); }
        return sb.toString();
    }

    public synchronized void recordProvider(String provider, boolean ok, long latencyMs) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT calls,success,latency_ms FROM provider_stats WHERE provider=?", new String[]{provider});
        int calls = 0, success = 0; long latency = 0;
        try {
            if (c.moveToFirst()) { calls = c.getInt(0); success = c.getInt(1); latency = c.getLong(2); }
        } finally { c.close(); }
        calls++;
        if (ok) success++;
        latency = calls == 1 ? latencyMs : ((latency * (calls - 1)) + latencyMs) / calls;
        ContentValues v = new ContentValues();
        v.put("provider", provider); v.put("calls", calls); v.put("success", success); v.put("latency_ms", latency);
        db.insertWithOnConflict("provider_stats", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized String providerReport() {
        StringBuilder sb = new StringBuilder();
        Cursor c = getReadableDatabase().rawQuery("SELECT provider,calls,success,latency_ms FROM provider_stats ORDER BY success*1.0/MAX(calls,1) DESC", null);
        try {
            while (c.moveToNext()) {
                int calls = c.getInt(1), success = c.getInt(2);
                int rate = calls == 0 ? 0 : (success * 100 / calls);
                sb.append(c.getString(0)).append(": ").append(rate).append("% success, ").append(c.getLong(3)).append(" ms avg\n");
            }
        } finally { c.close(); }
        return sb.length() == 0 ? "No provider history yet." : sb.toString();
    }
}
