package com.amadeus.companion;

import android.content.Context;
import android.content.SharedPreferences;

/** User configuration stored in SharedPreferences. No API key is ever hardcoded. */
public class AppSettings {

    public static final String DEFAULT_CHAT_MODEL = "gemini-3.1-flash-lite";
    public static final String DEFAULT_TTS_MODEL = "gemini-3.8-flash-lite-tts";
    public static final String DEFAULT_TTS_VOICE = "Kore";
    public static final int DEFAULT_TURNS = 8;
    public static final int DEFAULT_MAX_RECORD_SEC = 15;
    public static final int DEFAULT_MAX_REPLY_CHARS = 400;
    public static final int DEFAULT_FRAME_MS = 140;

    private static final String PREFS = "amadeus_settings";
    private static final String K_API_KEY = "api_key";
    private static final String K_CHAT_MODEL = "chat_model";
    private static final String K_TTS_MODEL = "tts_model";
    private static final String K_TTS_VOICE = "tts_voice";
    private static final String K_TURNS = "history_turns";
    private static final String K_MAX_RECORD = "max_record_sec";
    private static final String K_MAX_REPLY = "max_reply_chars";
    private static final String K_FRAME_MS = "frame_ms";

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getApiKey() { return prefs.getString(K_API_KEY, "").trim(); }
    public void setApiKey(String v) { prefs.edit().putString(K_API_KEY, v == null ? "" : v.trim()).apply(); }
    public boolean hasApiKey() { return getApiKey().length() > 0; }

    public String getChatModel() {
        String v = prefs.getString(K_CHAT_MODEL, DEFAULT_CHAT_MODEL).trim();
        return v.isEmpty() ? DEFAULT_CHAT_MODEL : v;
    }
    public void setChatModel(String v) { prefs.edit().putString(K_CHAT_MODEL, v == null ? "" : v.trim()).apply(); }

    public String getTtsModel() {
        String v = prefs.getString(K_TTS_MODEL, DEFAULT_TTS_MODEL).trim();
        return v.isEmpty() ? DEFAULT_TTS_MODEL : v;
    }
    public void setTtsModel(String v) { prefs.edit().putString(K_TTS_MODEL, v == null ? "" : v.trim()).apply(); }

    /** Empty string means "use the voice from assets/tts_prompt.txt". */
    public String getTtsVoice() { return prefs.getString(K_TTS_VOICE, "").trim(); }
    public void setTtsVoice(String v) { prefs.edit().putString(K_TTS_VOICE, v == null ? "" : v.trim()).apply(); }

    public int getHistoryTurns() { return clamp(prefs.getInt(K_TURNS, DEFAULT_TURNS), 0, 30); }
    public void setHistoryTurns(int v) { prefs.edit().putInt(K_TURNS, clamp(v, 0, 30)).apply(); }

    public int getMaxRecordSeconds() { return clamp(prefs.getInt(K_MAX_RECORD, DEFAULT_MAX_RECORD_SEC), 3, 60); }
    public void setMaxRecordSeconds(int v) { prefs.edit().putInt(K_MAX_RECORD, clamp(v, 3, 60)).apply(); }

    public int getMaxReplyChars() { return clamp(prefs.getInt(K_MAX_REPLY, DEFAULT_MAX_REPLY_CHARS), 60, 2000); }
    public void setMaxReplyChars(int v) { prefs.edit().putInt(K_MAX_REPLY, clamp(v, 60, 2000)).apply(); }

    /** Milliseconds per talking frame. Lower = faster mouth animation. */
    public int getFrameIntervalMs() { return clamp(prefs.getInt(K_FRAME_MS, DEFAULT_FRAME_MS), 60, 500); }
    public void setFrameIntervalMs(int v) { prefs.edit().putInt(K_FRAME_MS, clamp(v, 60, 500)).apply(); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
