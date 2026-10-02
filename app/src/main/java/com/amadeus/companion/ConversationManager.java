package com.amadeus.companion;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the recent conversation (user text + Amadeus reply per turn). Only the last N turns
 * (configurable, default 8) are sent to Gemini; older turns are dropped.
 * User turns are stored as the short transcript Gemini returns in the "heard" field, because
 * the raw audio is deleted right after each request.
 */
public class ConversationManager {

    private static final String PREFS = "amadeus_conversation";
    private static final String KEY = "turns";
    private static final int HARD_LIMIT = 40; // never store more than this locally

    public static class Turn {
        public final String user;
        public final String assistant;
        Turn(String user, String assistant) { this.user = user; this.assistant = assistant; }
    }

    private final SharedPreferences prefs;
    private final List<Turn> turns = new ArrayList<>();

    public ConversationManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        turns.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                turns.add(new Turn(o.optString("u"), o.optString("a")));
            }
        } catch (JSONException ignored) {
            turns.clear();
        }
    }

    private void save() {
        JSONArray arr = new JSONArray();
        try {
            for (Turn t : turns) arr.put(new JSONObject().put("u", t.user).put("a", t.assistant));
        } catch (JSONException ignored) { }
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    /** Records one completed exchange. */
    public synchronized void update(String userText, String assistantText) {
        String u = (userText == null || userText.trim().isEmpty()) ? "(unclear audio)" : userText.trim();
        turns.add(new Turn(u, assistantText == null ? "" : assistantText.trim()));
        while (turns.size() > HARD_LIMIT) turns.remove(0);
        save();
    }

    public synchronized void clear() {
        turns.clear();
        prefs.edit().remove(KEY).apply();
    }

    public synchronized List<Turn> recent(int maxTurns) {
        int from = Math.max(0, turns.size() - Math.max(0, maxTurns));
        return new ArrayList<>(turns.subList(from, turns.size()));
    }

    /**
     * Builds the complete system instruction: character prompt + output contract + memory +
     * recent conversation. This is sent with the single audio/text request of each turn.
     */
    public String buildSystemInstruction(String characterPrompt, String memoryBlock, int maxTurns, int maxReplyChars) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(characterPrompt.trim()).append("\n\n");

        sb.append("LENGTH LIMIT\nYour spoken reply must be at most ").append(maxReplyChars)
          .append(" characters.\n\n");

        sb.append("OUTPUT FORMAT\n")
          .append("The user's newest message is the attached audio (or the text message). Understand it directly, ")
          .append("then answer. Respond with ONE JSON object and nothing else (no markdown fences) with these keys:\n")
          .append("  \"heard\": a short transcript of what the user just said, in their language (empty string if nothing intelligible),\n")
          .append("  \"reply\": what you say out loud,\n")
          .append("  \"emotion\": one of neutral, joy, embarrassment, pride, serious, disgust,\n")
          .append("  \"speak\": true (false only if the reply should not be spoken),\n")
          .append("  \"memory_update\": null, or {\"add\": \"short fact\"} or {\"remove\": \"short fact to forget\"}.\n\n");

        if (memoryBlock != null && !memoryBlock.isEmpty()) {
            sb.append("REMEMBERED FACTS ABOUT THE PERSON\n").append(memoryBlock).append('\n');
        }

        List<Turn> recent = recent(maxTurns);
        if (!recent.isEmpty()) {
            sb.append("RECENT CONVERSATION (oldest first)\n");
            for (Turn t : recent) {
                sb.append("Person: ").append(t.user).append('\n');
                sb.append("You: ").append(t.assistant).append('\n');
            }
        }
        return sb.toString();
    }
}
