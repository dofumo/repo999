package com.amadeus.companion;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * Chooses, lazily loads and animates the character sprites.
 *
 * Gemini only picks the {@link Emotion}; Android owns the {@link State}
 * (IDLE / THINKING / TALKING). Every frame list below is an explicit mapping of the supplied
 * assets, so the order never depends on file-system or AssetManager.list() order:
 *   talking : numeric suffix 00 -> 01 -> 02 -> 00 ...
 *   idle    : neutral = suffix letter a -> b -> a ...; every other idle expression is ONE image.
 * The "- Copy" / "- Copy (2)" files in sprites/idle_all are byte-identical duplicates. They are
 * never referenced here, never decoded and never cached.
 *
 * Memory: frames are decoded on a background thread only when needed (the current state +
 * expression), scaled to the on-screen size and kept in a small byte-limited LruCache. The
 * aspect ratio of the sprite is never changed (uniform scaling only).
 */
public class SpriteManager {

    public enum State { IDLE, THINKING, TALKING }

    public enum Emotion {
        NEUTRAL("neutral"), JOY("joy"), EMBARRASSMENT("embarrassment"),
        PRIDE("pride"), SERIOUS("serious"), DISGUST("disgust");

        public final String key;
        Emotion(String key) { this.key = key; }

        public static Emotion from(String s) {
            if (s != null) {
                String t = s.trim().toLowerCase(java.util.Locale.ROOT);
                for (Emotion e : values()) if (e.key.equals(t)) return e;
            }
            return NEUTRAL;
        }
    }

    // ------------------------------------------------------------------ explicit asset mapping

    private static final String IDLE = "sprites/idle_all/";
    private static final String TALK = "sprites/talking_all/";
    private static final String THINKING_FRAME = "sprites/thinking/CRS_JLE_40000700.png";

    private static final Map<Emotion, String[]> IDLE_FRAMES = new EnumMap<>(Emotion.class);
    private static final Map<Emotion, String[]> TALKING_FRAMES = new EnumMap<>(Emotion.class);

    private static final String[] HOURGLASS = {
            "sprites/loading_hour_glass/1.png",
            "sprites/loading_hour_glass/2.png",
            "sprites/loading_hour_glass/3.png",
            "sprites/loading_hour_glass/4.png"
    };

    static {
        // IDLE. Only neutral has two genuinely different frames (a -> b).
        IDLE_FRAMES.put(Emotion.NEUTRAL, new String[]{
                IDLE + "idle_neutral/CRS_JLD_40000a00.png",
                IDLE + "idle_neutral/CRS_JLD_40000b00.png"});
        // Single canonical image each (the "- Copy" duplicates are intentionally ignored).
        IDLE_FRAMES.put(Emotion.JOY, new String[]{IDLE + "idle_joy/CRS_JLD_40000600.png"});
        IDLE_FRAMES.put(Emotion.EMBARRASSMENT, new String[]{IDLE + "idle_embarrassment/CRS_JLD_40000800.png"});
        IDLE_FRAMES.put(Emotion.PRIDE, new String[]{IDLE + "idle_pride/CRS_JLD_40000200.png"});
        IDLE_FRAMES.put(Emotion.SERIOUS, new String[]{IDLE + "idle_serious/CRS_JLD_40000300.png"});
        // Canonical file for disgust is the "- Copy" one: the directory contains no other name.
        IDLE_FRAMES.put(Emotion.DISGUST, new String[]{IDLE + "idle_disgust/CRS_JLD_40000c00 - Copy.png"});

        // TALKING: three real frames per expression, suffix 00 -> 01 -> 02.
        TALKING_FRAMES.put(Emotion.NEUTRAL, new String[]{
                TALK + "talking_neutral/CRS_JLD_40000b00.png",
                TALK + "talking_neutral/CRS_JLD_40000b01.png",
                TALK + "talking_neutral/CRS_JLD_40000b02.png"});
        TALKING_FRAMES.put(Emotion.JOY, new String[]{
                TALK + "talking_joy/CRS_JLD_40000600.png",
                TALK + "talking_joy/CRS_JLD_40000601.png",
                TALK + "talking_joy/CRS_JLD_40000602.png"});
        TALKING_FRAMES.put(Emotion.EMBARRASSMENT, new String[]{
                TALK + "talking_embarrassment/CRS_JLD_40000800.png",
                TALK + "talking_embarrassment/CRS_JLD_40000801.png",
                TALK + "talking_embarrassment/CRS_JLD_40000802.png"});
        TALKING_FRAMES.put(Emotion.PRIDE, new String[]{
                TALK + "talking_pride/CRS_JLD_40000200.png",
                TALK + "talking_pride/CRS_JLD_40000201.png",
                TALK + "talking_pride/CRS_JLD_40000202.png"});
        TALKING_FRAMES.put(Emotion.SERIOUS, new String[]{
                TALK + "talking_serious/CRS_JLD_40000300.png",
                TALK + "talking_serious/CRS_JLD_40000301.png",
                TALK + "talking_serious/CRS_JLD_40000302.png"});
        TALKING_FRAMES.put(Emotion.DISGUST, new String[]{
                TALK + "talking_disgust/CRS_JLD_40000c00.png",
                TALK + "talking_disgust/CRS_JLD_40000c01.png",
                TALK + "talking_disgust/CRS_JLD_40000c02.png"});
    }

    /** Ordered frame list for a state + expression (THINKING ignores the expression). */
    public static String[] framesFor(State state, Emotion emotion) {
        switch (state) {
            case THINKING: return new String[]{THINKING_FRAME};
            case TALKING:  return TALKING_FRAMES.get(emotion);
            default:       return IDLE_FRAMES.get(emotion);
        }
    }

    // ------------------------------------------------------------------------------ runtime

    private static final int CACHE_BYTES = 14 * 1024 * 1024;
    private static final int DEFAULT_TARGET_HEIGHT = 800;

    private final AssetManager assets;
    private final ImageView characterView;
    private final ImageView hourglassView; // may be null
    private final Handler ui = new Handler(Looper.getMainLooper());    // results from the decoder thread
    private final Handler tick = new Handler(Looper.getMainLooper());  // frame animation timer
    private final Handler glass = new Handler(Looper.getMainLooper()); // hourglass timer
    private final HandlerThread decodeThread = new HandlerThread("sprite-decoder");
    private final Handler decoder;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };

    private State state = State.IDLE;
    private Emotion emotion = Emotion.NEUTRAL;
    private int frameIntervalMs = AppSettings.DEFAULT_FRAME_MS;
    private int targetHeight = DEFAULT_TARGET_HEIGHT;

    private int generation = 0;     // bumped on every state/expression change
    private String[] activeFrames = new String[0];
    private int frameIndex = 0;
    private boolean running = false; // activity resumed
    private boolean tickerRunning = false;

    private Bitmap[] hourglassBitmaps;
    private int hourglassIndex = 0;
    private boolean hourglassRunning = false;

    public SpriteManager(Context context, ImageView characterView, ImageView hourglassView) {
        this.assets = context.getApplicationContext().getAssets();
        this.characterView = characterView;
        this.hourglassView = hourglassView;
        decodeThread.start();
        decoder = new Handler(decodeThread.getLooper());
        characterView.setAdjustViewBounds(true);
        characterView.setScaleType(ImageView.ScaleType.FIT_CENTER); // uniform scale only
    }

    // ---------------------------------------------------------------------- public controls

    public State getState() { return state; }
    public Emotion getEmotion() { return emotion; }

    public void setFrameIntervalMs(int ms) { frameIntervalMs = Math.max(40, ms); }

    /** Called once the character view is measured, so sprites are decoded at on-screen size. */
    public void setTargetHeight(int px) {
        if (px > 0 && px != targetHeight) {
            targetHeight = px;
            cache.evictAll();
            refresh();
        }
    }

    public void setIdle()     { setState(State.IDLE); }
    public void setThinking() { setState(State.THINKING); }
    public void setTalking()  { setState(State.TALKING); }

    /** Sets the expression chosen by Gemini; the current state (idle/talking) is kept. */
    public void setExpression(Emotion e) {
        if (e == null) e = Emotion.NEUTRAL;
        if (e == emotion) return;
        emotion = e;
        refresh();
    }

    public void onResume() { running = true; refresh(); }
    public void onPause()  { running = false; stopTicker(); stopHourglass(); }

    public void release() {
        running = false;
        generation++;
        stopTicker();
        stopHourglass();
        decodeThread.quitSafely();
        cache.evictAll();
    }

    // -------------------------------------------------------------------------- internals

    private void setState(State s) {
        if (s == state) return;
        state = s;
        refresh();
    }

    private void refresh() {
        generation++;
        final int gen = generation;
        stopTicker();
        updateHourglass();

        final String[] frames = framesFor(state, emotion);
        activeFrames = frames;
        frameIndex = 0;
        if (!running || frames == null || frames.length == 0) return;

        final int height = targetHeight;
        Bitmap first = cache.get(key(frames[0], height));
        if (first != null) {
            characterView.setImageBitmap(first);
        }
        decoder.post(new Runnable() {
            @Override public void run() {
                // Decode frame 0 first so it can be shown as early as possible,
                // then the remaining frames of this one animation.
                for (int i = 0; i < frames.length; i++) {
                    if (gen != generation) return; // superseded: stop decoding
                    final Bitmap b = obtain(frames[i], height);
                    if (i == 0 && b != null) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (gen == generation) characterView.setImageBitmap(b);
                            }
                        });
                    }
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (gen == generation) startTicker(gen);
                    }
                });
            }
        });
    }

    private void startTicker(final int gen) {
        if (activeFrames.length < 2 || tickerRunning) return; // single image: no timer, no CPU
        tickerRunning = true;
        tick.postDelayed(new Runnable() {
            @Override public void run() {
                if (gen != generation || !running) { tickerRunning = false; return; }
                frameIndex = (frameIndex + 1) % activeFrames.length;
                Bitmap b = cache.get(key(activeFrames[frameIndex], targetHeight));
                if (b != null) characterView.setImageBitmap(b);
                tick.postDelayed(this, intervalFor(state));
            }
        }, intervalFor(state));
    }

    private void stopTicker() {
        tickerRunning = false;
        tick.removeCallbacksAndMessages(null);
    }

    /** Talking uses the configured speed; the neutral idle a<->b swap is much calmer. */
    private int intervalFor(State s) {
        return s == State.TALKING ? frameIntervalMs : Math.max(900, frameIntervalMs * 12);
    }

    private static String key(String path, int height) { return path + "@" + height; }

    /** Returns the cached bitmap or decodes (and caches) it. Runs on the decoder thread. */
    private Bitmap obtain(String path, int height) {
        String k = key(path, height);
        Bitmap b = cache.get(k);
        if (b != null) return b;
        try {
            b = decodeScaled(path, height);
        } catch (OutOfMemoryError oom) {
            cache.evictAll();
            System.gc();
            try { b = decodeScaled(path, height); } catch (OutOfMemoryError e2) { b = null; }
        }
        if (b != null) cache.put(k, b);
        return b;
    }

    private Bitmap decodeScaled(String path, int targetH) {
        InputStream is = null;
        try {
            is = new BufferedInputStream(assets.open(path), 64 * 1024);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            // Largest power-of-two subsampling that still leaves at least targetH pixels.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, bounds);
            is.close();
            int sample = 1;
            while (bounds.outHeight / (sample * 2) >= targetH) sample *= 2;
            o.inSampleSize = sample;

            is = new BufferedInputStream(assets.open(path), 64 * 1024);
            Bitmap full = BitmapFactory.decodeStream(is, null, o);
            if (full == null) return null;
            if (full.getHeight() > targetH * 1.05f) {
                // Uniform scale: both dimensions use the same factor, aspect ratio is preserved.
                float f = (float) targetH / full.getHeight();
                int w = Math.max(1, Math.round(full.getWidth() * f));
                Bitmap scaled = Bitmap.createScaledBitmap(full, w, targetH, true);
                if (scaled != full) full.recycle();
                return scaled;
            }
            return full;
        } catch (IOException e) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (IOException ignored) { }
        }
    }

    // ----------------------------------------------------------------------- hourglass

    private void updateHourglass() {
        if (hourglassView == null) return;
        if (state == State.THINKING && running) startHourglass(); else stopHourglass();
    }

    private void startHourglass() {
        if (hourglassView == null || hourglassRunning) return;
        if (hourglassBitmaps == null) {
            hourglassBitmaps = new Bitmap[HOURGLASS.length];
            for (int i = 0; i < HOURGLASS.length; i++) {
                InputStream is = null;
                try {
                    is = assets.open(HOURGLASS[i]);
                    hourglassBitmaps[i] = BitmapFactory.decodeStream(is);
                } catch (IOException ignored) {
                } finally {
                    if (is != null) try { is.close(); } catch (IOException ignored) { }
                }
            }
        }
        hourglassRunning = true;
        hourglassView.setVisibility(View.VISIBLE);
        glass.post(new Runnable() {
            @Override public void run() {
                if (!hourglassRunning) return;
                Bitmap b = hourglassBitmaps[hourglassIndex % hourglassBitmaps.length];
                hourglassIndex = (hourglassIndex + 1) % hourglassBitmaps.length; // 1 -> 2 -> 3 -> 4 -> 1
                if (b != null) {
                    BitmapDrawable d = new BitmapDrawable(hourglassView.getResources(), b);
                    d.setFilterBitmap(false); // keep the pixel art crisp
                    hourglassView.setImageDrawable(d);
                }
                glass.postDelayed(this, 250);
            }
        });
    }

    private void stopHourglass() {
        hourglassRunning = false;
        glass.removeCallbacksAndMessages(null);
        if (hourglassView != null) hourglassView.setVisibility(View.GONE);
    }
}
