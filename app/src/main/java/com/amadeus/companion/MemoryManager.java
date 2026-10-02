package com.amadeus.companion;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small persistent long-term memory (a short list of facts) stored as a JSON array in
 * SharedPreferences. Only changes requested by Gemini in the same response are applied;
 * no separate request is ever made for memory.
 */
public class MemoryManager {

    private static final String PREFS = "amadeus_memory";
    private static final String KEY = "facts";
    private static final int MAX_FACTS = 30;
    private static final int MAX_FACT_CHARS = 200;

    private final SharedPreferences prefs;
    private final List<String> facts = new ArrayList<>();

    public MemoryManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        facts.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) facts.add(s);
            }
        } catch (JSONException ignored) {
            facts.clear();
        }
    }

    private void save() {
        JSONArray arr = new JSONArray();
        for (String f : facts) arr.put(f);
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    public synchronized List<String> getFacts() { return new ArrayList<>(facts); }

    /** Applies "add" and/or "remove" from a Gemini response. Returns true if anything changed. */
    public synchronized boolean update(GeminiResponse r) {
        if (r == null || !r.hasMemoryUpdate()) return false;
        boolean changed = false;

        if (r.memoryRemove != null) {
            String target = norm(r.memoryRemove);
            for (int i = facts.size() - 1; i >= 0; i--) {
                String f = norm(facts.get(i));
                if (f.equals(target) || f.contains(target) || target.contains(f)) {
                    facts.remove(i);
                    changed = true;
                }
            }
        }
        if (r.memoryAdd != null) {
            String add = r.memoryAdd.length() > MAX_FACT_CHARS ? r.memoryAdd.substring(0, MAX_FACT_CHARS) : r.memoryAdd;
            boolean dup = false;
            for (String f : facts) if (norm(f).equals(norm(add))) { dup = true; break; }
            if (!dup) {
                facts.add(add);
                while (facts.size() > MAX_FACTS) facts.remove(0); // drop the oldest
                changed = true;
            }
        }
        if (changed) save();
        return changed;
    }

    public synchronized void clear() {
        facts.clear();
        prefs.edit().remove(KEY).apply();
    }

    /** Text block placed in the system instruction; empty string when there is nothing to remember. */
    public synchronized String asPromptBlock() {
        if (facts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String f : facts) sb.append("- ").append(f).append('\n');
        return sb.toString();
    }

    private static String norm(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
