package com.amadeus.companion;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Talks to the Gemini Developer API using the Interactions API (the endpoint the current
 * Gemini docs recommend): POST https://generativelanguage.googleapis.com/v1beta/interactions
 * with the "x-goog-api-key" header.
 *
 * One voice turn = exactly ONE call to {@link #sendAudio}: the recorded microphone audio is sent
 * to Gemini directly (inline base64 WAV) and Gemini understands the speech itself. There is no
 * Android speech recognition and no separate transcription / emotion / memory request.
 * Requests use store=false, so Google does not keep the interaction for server-side state; the
 * app sends its own short history inside the system instruction instead.
 */
public class GeminiClient {

    public static final String INTERACTIONS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public enum Kind { NO_KEY, NETWORK, TIMEOUT, AUTH, QUOTA, BAD_REQUEST, SERVER, BAD_RESPONSE, OTHER }

    /** Any Gemini / network failure. Carries a short message that is safe to show to the user. */
    public static class GeminiException extends Exception {
        public final Kind kind;
        public final int httpStatus;

        public GeminiException(Kind kind, int httpStatus, String detail) {
            super(detail);
            this.kind = kind;
            this.httpStatus = httpStatus;
        }

        public String userMessage() {
            switch (kind) {
                case NO_KEY:       return "Set your Gemini API key in Settings.";
                case NETWORK:      return "Network error. Check the internet connection.";
                case TIMEOUT:      return "Gemini took too long to answer.";
                case AUTH:         return "The Gemini API key is invalid or not allowed.";
                case QUOTA:        return "Gemini rate limit or free-tier quota reached. Try again in a minute.";
                case BAD_REQUEST:  return "Gemini rejected the request: " + shorten(getMessage());
                case SERVER:       return "Gemini is having trouble right now. Try again later.";
                case BAD_RESPONSE: return "Gemini sent an answer I could not read.";
                default:           return "Gemini error: " + shorten(getMessage());
            }
        }

        private static String shorten(String s) {
            if (s == null) return "unknown";
            s = s.replace('\n', ' ').trim();
            return s.length() > 140 ? s.substring(0, 140) + "..." : s;
        }
    }

    private final AppSettings settings;

    public GeminiClient(AppSettings settings) {
        this.settings = settings;
    }

    /** Sends the recorded speech (16 kHz mono 16-bit WAV bytes) directly to Gemini. */
    public GeminiResponse sendAudio(byte[] wavBytes, String systemInstruction) throws GeminiException {
        return chat(systemInstruction, wavBytes, null);
    }

    /** Text fallback (typed message). Same single-request design. */
    public GeminiResponse sendText(String text, String systemInstruction) throws GeminiException {
        return chat(systemInstruction, null, text);
    }

    // ---------------------------------------------------------------------------------------

    private GeminiResponse chat(String systemInstruction, byte[] wav, String text) throws GeminiException {
        String key = settings.getApiKey();
        if (key.isEmpty()) throw new GeminiException(Kind.NO_KEY, 0, "no key");

        String body;
        String audioB64 = null;
        if (wav != null) audioB64 = Base64.encodeToString(wav, Base64.NO_WRAP);

        try {
            body = buildChatBody(systemInstruction, audioB64, text, true);
        } catch (JSONException e) {
            throw new GeminiException(Kind.OTHER, 0, "could not build request");
        }

        String json;
        try {
            json = postJson(INTERACTIONS_URL, key, body, 60000);
        } catch (GeminiException e) {
            // If the structured-output schema itself is rejected, retry exactly once without it
            // (the system instruction already demands JSON). Never retry anything else.
            if (e.kind != Kind.BAD_REQUEST) throw e;
            try {
                body = buildChatBody(systemInstruction, audioB64, text, false);
            } catch (JSONException je) {
                throw e;
            }
            json = postJson(INTERACTIONS_URL, key, body, 60000);
        }

        String modelText = extractText(json);
        if (modelText.isEmpty()) throw new GeminiException(Kind.BAD_RESPONSE, 200, "empty model output");
        try {
            return GeminiResponse.parse(modelText);
        } catch (JSONException e) {
            // Model ignored the JSON contract. Do not fail the turn: use the raw text as the reply.
            String plain = modelText.trim();
            if (plain.length() < 2 || plain.startsWith("{")) {
                throw new GeminiException(Kind.BAD_RESPONSE, 200, "malformed JSON");
            }
            GeminiResponse r = new GeminiResponse();
            r.reply = plain.length() > 600 ? plain.substring(0, 600) : plain;
            return r;
        }
    }

    private String buildChatBody(String system, String audioB64, String text, boolean withSchema) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("model", settings.getChatModel());
        root.put("system_instruction", system);

        JSONArray input = new JSONArray();
        if (audioB64 != null) {
            input.put(new JSONObject().put("type", "text")
                    .put("text", "Here is the person's new voice message. Understand it and reply in the required JSON format."));
            input.put(new JSONObject().put("type", "audio")
                    .put("data", audioB64)
                    .put("mime_type", "audio/wav"));
        } else {
            input.put(new JSONObject().put("type", "text").put("text", text));
        }
        root.put("input", input);

        if (withSchema) root.put("response_format", buildSchema());
        root.put("store", false);
        return root.toString();
    }

    private static JSONObject buildSchema() throws JSONException {
        JSONObject props = new JSONObject();
        props.put("heard", new JSONObject().put("type", "string"));
        props.put("reply", new JSONObject().put("type", "string"));
        props.put("emotion", new JSONObject().put("type", "string")
                .put("enum", new JSONArray().put("neutral").put("joy").put("embarrassment")
                        .put("pride").put("serious").put("disgust")));
        props.put("speak", new JSONObject().put("type", "boolean"));
        props.put("memory_update", new JSONObject().put("type", "object")
                .put("properties", new JSONObject()
                        .put("add", new JSONObject().put("type", "string"))
                        .put("remove", new JSONObject().put("type", "string"))));
        return new JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", new JSONArray().put("reply").put("emotion").put("speak"));
    }

    /** Collects all text blocks of the model_output step(s). */
    static String extractText(String responseJson) throws GeminiException {
        try {
            JSONObject root = new JSONObject(responseJson);
            StringBuilder sb = new StringBuilder();
            collect(root.optJSONArray("steps"), "model_output", "text", "text", sb);
            if (sb.length() == 0) collect(root.optJSONArray("outputs"), null, "text", "text", sb); // older shape
            if (sb.length() == 0) sb.append(root.optString("output_text", ""));
            return sb.toString().trim();
        } catch (JSONException e) {
            throw new GeminiException(Kind.BAD_RESPONSE, 200, "response is not JSON");
        }
    }

    /**
     * Walks steps[] -> content[] and appends the field {@code field} of every content block whose
     * "type" equals {@code contentType}. {@code stepType} == null accepts any step / a flat list.
     */
    static void collect(JSONArray steps, String stepType, String contentType, String field, StringBuilder out) {
        if (steps == null) return;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            if (stepType != null && !stepType.equals(step.optString("type"))) continue;
            JSONArray content = step.optJSONArray("content");
            if (content == null) {
                if (contentType.equals(step.optString("type"))) out.append(step.optString(field, ""));
                continue;
            }
            for (int j = 0; j < content.length(); j++) {
                JSONObject c = content.optJSONObject(j);
                if (c != null && contentType.equals(c.optString("type"))) out.append(c.optString(field, ""));
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Shared HTTP helper (also used by TtsManager). Single attempt, no retry loops.
    // ---------------------------------------------------------------------------------------

    static String postJson(String url, String apiKey, String jsonBody, int readTimeoutMs) throws GeminiException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("x-goog-api-key", apiKey);

            byte[] payload = jsonBody.getBytes(UTF8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = is == null ? "" : readAll(is);

            if (code >= 200 && code < 300) return resp;
            throw mapHttpError(code, resp);
        } catch (SocketTimeoutException e) {
            throw new GeminiException(Kind.TIMEOUT, 0, "timeout");
        } catch (IOException e) {
            throw new GeminiException(Kind.NETWORK, 0, String.valueOf(e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static GeminiException mapHttpError(int code, String body) {
        String msg = "HTTP " + code;
        try {
            JSONObject err = new JSONObject(body).optJSONObject("error");
            if (err != null && err.optString("message").length() > 0) msg = err.optString("message");
        } catch (JSONException ignored) { }

        String lower = msg.toLowerCase();
        if (code == 401 || code == 403 || lower.contains("api key not valid") || lower.contains("api_key_invalid")) {
            return new GeminiException(Kind.AUTH, code, msg);
        }
        if (code == 429) return new GeminiException(Kind.QUOTA, code, msg);
        if (code == 408 || code == 504) return new GeminiException(Kind.TIMEOUT, code, msg);
        if (code >= 500) return new GeminiException(Kind.SERVER, code, msg);
        if (code == 400 || code == 404 || code == 422) return new GeminiException(Kind.BAD_REQUEST, code, msg);
        return new GeminiException(Kind.OTHER, code, msg);
    }

    private static String readAll(InputStream is) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), UTF8);
        } finally {
            try { is.close(); } catch (IOException ignored) { }
        }
    }
}
