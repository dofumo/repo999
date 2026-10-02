package com.amadeus.companion;

import org.json.JSONException;
import org.json.JSONObject;

/** Structured answer returned by Gemini in a single request. */
public class GeminiResponse {

    public String heard = "";          // short transcript of what the user said (extra field, see README)
    public String reply = "";
    public String emotion = "neutral"; // neutral|joy|embarrassment|pride|serious|disgust
    public boolean speak = true;
    public String memoryAdd = null;
    public String memoryRemove = null;

    public boolean hasMemoryUpdate() {
        return (memoryAdd != null && !memoryAdd.isEmpty()) || (memoryRemove != null && !memoryRemove.isEmpty());
    }

    /**
     * Parses the model text. Tolerates ```json fences and text around the object.
     * @throws JSONException when no usable JSON object with a non-empty "reply" is found
     */
    public static GeminiResponse parse(String raw) throws JSONException {
        if (raw == null) throw new JSONException("empty");
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
            s = s.trim();
        }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) throw new JSONException("no json object");
        JSONObject o = new JSONObject(s.substring(a, b + 1));

        GeminiResponse r = new GeminiResponse();
        r.reply = o.optString("reply", "").trim();
        if (r.reply.isEmpty()) throw new JSONException("empty reply");
        r.heard = o.isNull("heard") ? "" : o.optString("heard", "").trim();
        r.emotion = SpriteManager.Emotion.from(o.optString("emotion", "neutral")).key;
        r.speak = o.optBoolean("speak", true);

        JSONObject mu = o.optJSONObject("memory_update");
        if (mu != null) {
            String add = mu.isNull("add") ? "" : mu.optString("add", "").trim();
            String rem = mu.isNull("remove") ? "" : mu.optString("remove", "").trim();
            r.memoryAdd = add.isEmpty() ? null : add;
            r.memoryRemove = rem.isEmpty() ? null : rem;
        }
        return r;
    }
}
