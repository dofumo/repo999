package com.amadeus.companion;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Gemini text-to-speech (default model: gemini-3.8-flash-lite-tts).
 * Only the final "reply" text is sent: no history, memory, sprites or recordings.
 * Gemini 3.8 TTS treats the text as a verbatim transcript, so the optional delivery "style"
 * goes into speech_metadata.style (see assets/tts_prompt.txt), never into the spoken text.
 * The unary response is a WAV (24 kHz, mono, 16-bit); it is played with AudioTrack.
 * No Android TextToSpeech engine is involved.
 */
public class TtsManager {

    public interface Listener {
        /** Audio is about to be heard: switch the character to TALKING. (main thread) */
        void onPlaybackStarted();
        /** Playback ended normally: switch the character back to IDLE. (main thread) */
        void onPlaybackFinished();
        /** TTS failed (request or playback). The reply text is still shown. (main thread) */
        void onError(String message);
    }

    private final Context context;
    private final AppSettings settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> styles = new HashMap<>();
    private String defaultVoice = AppSettings.DEFAULT_TTS_VOICE;

    private volatile int generation = 0;
    private volatile AudioTrack currentTrack;
    private volatile boolean busy = false;

    public TtsManager(Context context, AppSettings settings) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        loadConfig();
    }

    private void loadConfig() {
        BufferedReader br = null;
        try {
            InputStream is = context.getAssets().open("tts_prompt.txt");
            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if (k.equals("voice") && !v.isEmpty()) defaultVoice = v;
                else if (k.startsWith("style.")) styles.put(k.substring(6), v);
            }
        } catch (IOException ignored) {
            // keep defaults
        } finally {
            if (br != null) try { br.close(); } catch (IOException ignored) { }
        }
    }

    public boolean isBusy() { return busy; }

    /** Cancels any request/playback in progress. Callbacks of the cancelled job are not delivered. */
    public void stop() {
        generation++;
        busy = false;
        AudioTrack t = currentTrack;
        if (t != null) {
            try { t.pause(); t.flush(); } catch (Exception ignored) { }
        }
    }

    public void speak(final String replyText, final String emotionKey, final Listener listener) {
        stop();
        final int myGen = generation;
        busy = true;
        final String text = sanitize(replyText);
        if (text.isEmpty()) {
            busy = false;
            postFinished(myGen, listener);
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] audio = requestAudio(text, emotionKey);
                    if (myGen != generation) return;
                    play(audio, myGen, listener);
                } catch (GeminiClient.GeminiException e) {
                    postError(myGen, listener, e.kind == GeminiClient.Kind.QUOTA
                            ? "Voice unavailable: Gemini rate limit reached."
                            : "Voice unavailable. " + e.userMessage());
                } catch (Exception e) {
                    postError(myGen, listener, "Audio playback failed.");
                } finally {
                    if (myGen == generation) busy = false;
                }
            }
        }, "tts").start();
    }

    // ---------------------------------------------------------------------------------------

    private byte[] requestAudio(String text, String emotionKey) throws GeminiClient.GeminiException {
        String key = settings.getApiKey();
        if (key.isEmpty()) throw new GeminiClient.GeminiException(GeminiClient.Kind.NO_KEY, 0, "no key");

        String voice = settings.getTtsVoice();
        if (voice.isEmpty()) voice = defaultVoice;
        String style = styles.get(emotionKey == null ? "neutral" : emotionKey.toLowerCase(Locale.ROOT));

        try {
            JSONObject textBlock = new JSONObject().put("type", "text").put("text", text);
            if (style != null && !style.isEmpty()) {
                textBlock.put("annotations", new JSONArray().put(new JSONObject()
                        .put("type", "speech_metadata")
                        .put("style", style)));
            }
            JSONObject root = new JSONObject();
            root.put("model", settings.getTtsModel());
            root.put("input", new JSONArray().put(new JSONObject()
                    .put("type", "user_input")
                    .put("content", new JSONArray().put(textBlock))));
            root.put("response_format", new JSONObject().put("type", "audio"));
            root.put("generation_config", new JSONObject()
                    .put("speech_config", new JSONArray().put(new JSONObject().put("voice", voice))));
            root.put("store", false);

            String resp = GeminiClient.postJson(GeminiClient.INTERACTIONS_URL, key, root.toString(), 60000);

            JSONObject r = new JSONObject(resp);
            StringBuilder b64 = new StringBuilder();
            GeminiClient.collect(r.optJSONArray("steps"), "model_output", "audio", "data", b64);
            if (b64.length() == 0) GeminiClient.collect(r.optJSONArray("outputs"), null, "audio", "data", b64);
            if (b64.length() == 0) {
                throw new GeminiClient.GeminiException(GeminiClient.Kind.BAD_RESPONSE, 200, "no audio in response");
            }
            return Base64.decode(b64.toString(), Base64.DEFAULT);
        } catch (JSONException e) {
            throw new GeminiClient.GeminiException(GeminiClient.Kind.BAD_RESPONSE, 200, "unreadable TTS response");
        } catch (IllegalArgumentException e) {
            throw new GeminiClient.GeminiException(GeminiClient.Kind.BAD_RESPONSE, 200, "bad audio data");
        }
    }

    /** Removes markdown, emoji and *action* markers: the text is read out verbatim. */
    static String sanitize(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\*[^*\\n]{0,80}\\*", " ");
        s = s.replace("*", "").replace("`", "").replace("#", "").replace("_", " ");
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            boolean emoji = cp >= 0x1F000 || (cp >= 0x2600 && cp <= 0x27BF) || cp == 0xFE0F || cp == 0x200D;
            if (!emoji) sb.appendCodePoint(cp);
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    // ---------------------------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------------------------

    private void play(byte[] data, int myGen, Listener listener) throws IOException {
        int sampleRate = 24000;
        int channels = 1;
        int offset = 0;
        int length = data.length;

        if (data.length > 44 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E') {
            int pos = 12;
            boolean foundData = false;
            while (pos + 8 <= data.length) {
                String id = new String(data, pos, 4, "US-ASCII");
                long size = le32(data, pos + 4);
                int body = pos + 8;
                if (id.equals("fmt ") && body + 16 <= data.length) {
                    channels = le16(data, body + 2);
                    sampleRate = (int) le32(data, body + 4);
                    int bits = le16(data, body + 14);
                    if (bits != 16) throw new IOException("unsupported bit depth " + bits);
                } else if (id.equals("data")) {
                    offset = body;
                    long remaining = data.length - body;
                    length = (int) ((size <= 0 || size > remaining) ? remaining : size);
                    foundData = true;
                    break;
                }
                long next = body + size + (size & 1);
                if (size < 0 || next > data.length) break;
                pos = (int) next;
            }
            if (!foundData) throw new IOException("no data chunk");
        }
        // Otherwise: headerless 16-bit little-endian PCM, 24 kHz mono (audio/l16 default).

        if (length < 2) throw new IOException("empty audio");
        if (channels < 1 || channels > 2) channels = 1;
        length -= length % (2 * channels);

        int chMask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) throw new IOException("audio output unavailable");
        int bufSize = Math.max(minBuf * 2, sampleRate * 2 * channels / 5);

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(chMask)
                        .build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IOException("audio output not initialised");
        }

        currentTrack = track;
        try {
            if (myGen != generation) return;
            track.play();
            postStarted(myGen, listener);

            int written = 0;
            while (written < length && myGen == generation) {
                int n = track.write(data, offset + written, Math.min(4096, length - written));
                if (n < 0) throw new IOException("audio write error " + n);
                written += n;
            }
            if (myGen != generation) return;

            track.stop(); // streaming track: plays what is already buffered, then stops
            long totalFrames = (long) length / (2 * channels);
            long deadline = System.currentTimeMillis() + (totalFrames * 1000L / sampleRate) + 1500;
            while (myGen == generation && System.currentTimeMillis() < deadline) {
                long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                if (head >= totalFrames) break;
                try { Thread.sleep(30); } catch (InterruptedException e) { break; }
            }
            postFinished(myGen, listener);
        } finally {
            if (currentTrack == track) currentTrack = null;
            try { track.release(); } catch (Exception ignored) { }
        }
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    // ---------------------------------------------------------------------------------------

    private void postStarted(final int g, final Listener l) {
        main.post(new Runnable() { @Override public void run() { if (g == generation && l != null) l.onPlaybackStarted(); } });
    }

    private void postFinished(final int g, final Listener l) {
        main.post(new Runnable() { @Override public void run() {
            if (g == generation) { busy = false; if (l != null) l.onPlaybackFinished(); }
        } });
    }

    private void postError(final int g, final Listener l, final String msg) {
        main.post(new Runnable() { @Override public void run() {
            if (g == generation) { busy = false; if (l != null) l.onError(msg); }
        } });
    }
}
