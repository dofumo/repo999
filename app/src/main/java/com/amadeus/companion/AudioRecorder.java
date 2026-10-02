package com.amadeus.companion;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Records microphone audio ONLY (16 kHz, mono, 16-bit PCM) into a temporary WAV file.
 * It does not recognise speech: the WAV is sent as-is to Gemini by {@link GeminiClient}.
 * Android's SpeechRecognizer / RecognitionService / RecognizerIntent are never used.
 * Recording only happens between start() and stop(); nothing records in the background.
 */
public class AudioRecorder {

    public static final int SAMPLE_RATE = 16000;
    private static final int MIN_BYTES = (int) (SAMPLE_RATE * 2 * 0.4); // ignore clips shorter than 0.4 s

    public interface Listener {
        /** Called on the main thread when the maximum duration was reached (call stop() afterwards). */
        void onMaxDurationReached();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private volatile long pcmBytes;
    private File outFile;

    public synchronized boolean isRecording() {
        return running;
    }

    /** @throws IOException if the microphone cannot be opened */
    public synchronized void start(File out, final int maxSeconds, final Listener listener) throws IOException {
        if (running) return;
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) throw new IOException("microphone unavailable");
        final int bufSize = Math.max(minBuf * 2, 8192);

        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (RuntimeException e) {
            throw new IOException("microphone unavailable");
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            throw new IOException("microphone unavailable");
        }
        try {
            rec.startRecording();
        } catch (IllegalStateException e) {
            rec.release();
            throw new IOException("microphone unavailable");
        }
        if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release();
            throw new IOException("microphone is busy");
        }

        record = rec;
        outFile = out;
        pcmBytes = 0;
        running = true;
        final AudioRecord r = rec;
        final File f = out;
        final long maxBytes = (long) maxSeconds * SAMPLE_RATE * 2;

        thread = new Thread(new Runnable() {
            @Override public void run() {
                RandomAccessFile raf = null;
                boolean hitMax = false;
                try {
                    raf = new RandomAccessFile(f, "rw");
                    raf.setLength(0);
                    raf.write(new byte[44]); // header placeholder
                    byte[] chunk = new byte[3200]; // 100 ms
                    while (running) {
                        int n = r.read(chunk, 0, chunk.length);
                        if (n <= 0) {
                            if (n < 0) break; // error
                            continue;
                        }
                        raf.write(chunk, 0, n);
                        pcmBytes += n;
                        if (pcmBytes >= maxBytes) { hitMax = true; break; }
                    }
                    writeWavHeader(raf, pcmBytes);
                } catch (IOException ignored) {
                    pcmBytes = 0;
                } finally {
                    try { if (raf != null) raf.close(); } catch (IOException ignored) { }
                    try { r.stop(); } catch (IllegalStateException ignored) { }
                    r.release();
                }
                if (hitMax && listener != null) {
                    main.post(new Runnable() {
                        @Override public void run() { listener.onMaxDurationReached(); }
                    });
                }
            }
        }, "audio-recorder");
        thread.start();
    }

    /**
     * Stops recording and returns the finished WAV file, or null when nothing usable was recorded
     * (the temp file is deleted in that case). Safe to call more than once.
     */
    public File stop() {
        Thread t;
        File f;
        synchronized (this) {
            running = false;
            t = thread;
            f = outFile;
            thread = null;
        }
        if (t != null) {
            try { t.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (f == null) return null;
        if (pcmBytes < MIN_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        return f;
    }

    /** Stops and discards. */
    public void cancel() {
        File f = stop();
        if (f != null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static void writeWavHeader(RandomAccessFile raf, long dataLen) throws IOException {
        long totalLen = dataLen + 36;
        long byteRate = SAMPLE_RATE * 2;
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        le32(h, 4, totalLen);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        le32(h, 16, 16);          // fmt chunk size
        h[20] = 1; h[21] = 0;     // PCM
        h[22] = 1; h[23] = 0;     // mono
        le32(h, 24, SAMPLE_RATE);
        le32(h, 28, byteRate);
        h[32] = 2; h[33] = 0;     // block align
        h[34] = 16; h[35] = 0;    // bits per sample
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        le32(h, 40, dataLen);
        raf.seek(0);
        raf.write(h);
    }

    private static void le32(byte[] b, int off, long v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }
}
