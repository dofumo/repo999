package com.amadeus.companion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coordinates the UI and the application state.
 *
 * Voice flow (one Gemini conversation request + one Gemini TTS request per turn):
 *   mic down -> AudioRecorder.start -> mic up -> AudioRecorder.stop -> SpriteManager.setThinking
 *   -> GeminiClient.sendAudio (Gemini understands the speech itself) -> JSON reply
 *   -> ConversationManager.update -> MemoryManager.update -> SpriteManager.setExpression
 *   -> TtsManager.speak -> (playback starts) SpriteManager.setTalking -> (ends) SpriteManager.setIdle
 * Android speech recognition is not used anywhere.
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 1001;
    private static final int MAX_LINES = 10;

    private AppSettings settings;
    private MemoryManager memory;
    private ConversationManager conversation;
    private GeminiClient gemini;
    private AudioRecorder recorder;
    private TtsManager tts;
    private SpriteManager sprites;

    private TextView statusView;
    private TextView conversationView;
    private ScrollView scrollView;
    private EditText textInput;
    private ImageView micButton;
    private Typeface consoleFont;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<CharSequence> lines = new ArrayList<>();

    private String characterPrompt = "";
    private boolean recording = false;
    private boolean busy = false; // waiting for Gemini or for the TTS audio to arrive
    private Runnable fakeTalkEnd;

    // ------------------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        settings = new AppSettings(this);
        memory = new MemoryManager(this);
        conversation = new ConversationManager(this);
        gemini = new GeminiClient(settings);
        recorder = new AudioRecorder();
        tts = new TtsManager(this, settings);

        statusView = (TextView) findViewById(R.id.status);
        conversationView = (TextView) findViewById(R.id.conversation);
        scrollView = (ScrollView) findViewById(R.id.scroll);
        textInput = (EditText) findViewById(R.id.text_input);
        micButton = (ImageView) findViewById(R.id.mic_button);
        final ImageView characterView = (ImageView) findViewById(R.id.character);
        ImageView hourglass = (ImageView) findViewById(R.id.hourglass);

        try {
            consoleFont = Typeface.createFromAsset(getAssets(), "fonts/console.ttf");
            applyTypeface(findViewById(android.R.id.content));
        } catch (RuntimeException e) {
            consoleFont = null; // keep the default font
        }

        characterPrompt = readAssetText("character_prompt.txt");

        sprites = new SpriteManager(this, characterView, hourglass);
        sprites.setFrameIntervalMs(settings.getFrameIntervalMs());
        characterView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                int h = b - t;
                if (h > 0) sprites.setTargetHeight(h);
            }
        });

        loadStaticImages();
        setupMicButton();

        findViewById(R.id.settings_button).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        findViewById(R.id.send_button).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendTypedText(); }
        });
        textInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    sendTypedText();
                    return true;
                }
                return false;
            }
        });

        setStatus(getString(settings.hasApiKey() ? R.string.status_ready : R.string.status_no_key), !settings.hasApiKey());
    }

    @Override
    protected void onResume() {
        super.onResume();
        sprites.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (recording) {
            recorder.cancel();
            recording = false;
            micButton.setActivated(false);
        }
        tts.stop();
        cancelFakeTalk();
        busy = false;
        sprites.setIdle();
        sprites.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recorder.cancel();
        tts.stop();
        sprites.release();
        worker.shutdownNow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setStatus(getString(R.string.status_ready), false);
            } else {
                setStatus(getString(R.string.err_mic_denied), true);
            }
        }
    }

    // --------------------------------------------------------------------------------- setup

    private void loadStaticImages() {
        // Small mic icon (24x40): shown without smoothing to keep the pixel art crisp.
        Bitmap mic = decodeAsset("sprites/buttons/mic_icon.png");
        if (mic != null) {
            BitmapDrawable d = new BitmapDrawable(getResources(), mic);
            d.setFilterBitmap(false);
            micButton.setImageDrawable(d);
        }
        // Large background: decoded off the UI thread.
        final ImageView bg = (ImageView) findViewById(R.id.background);
        worker.execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = decodeAsset("sprites/background/background.png");
                if (b != null) {
                    main.post(new Runnable() {
                        @Override public void run() { bg.setImageBitmap(b); }
                    });
                }
            }
        });
    }

    private Bitmap decodeAsset(String path) {
        InputStream is = null;
        try {
            is = getAssets().open(path);
            return BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (IOException ignored) { }
        }
    }

    private String readAssetText(String path) {
        InputStream is = null;
        try {
            is = getAssets().open(path);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return "You are Amadeus, a sharp, sarcastic, slightly tsundere scientist. Keep replies short.";
        } finally {
            if (is != null) try { is.close(); } catch (IOException ignored) { }
        }
    }

    private void applyTypeface(View v) {
        if (consoleFont == null) return;
        if (v instanceof TextView) ((TextView) v).setTypeface(consoleFont);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyTypeface(g.getChildAt(i));
        }
    }

    private void setupMicButton() {
        micButton.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        onMicDown();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        onMicUp();
                        return true;
                    default:
                        return true;
                }
            }
        });
        // Keyboard / remote support: hold OK/Enter to talk.
        micButton.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent ev) {
                if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER
                        && keyCode != KeyEvent.KEYCODE_BUTTON_A && keyCode != KeyEvent.KEYCODE_SPACE) return false;
                if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                    onMicDown();
                    return true;
                }
                if (ev.getAction() == KeyEvent.ACTION_UP) {
                    onMicUp();
                    return true;
                }
                return true;
            }
        });
    }

    // ------------------------------------------------------------------------------ recording

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void onMicDown() {
        if (recording || busy) return;
        if (!settings.hasApiKey()) {
            setStatus(getString(R.string.status_no_key), true);
            return;
        }
        // Barge-in: pressing the mic while she is talking stops the voice.
        if (sprites.getState() == SpriteManager.State.TALKING || tts.isBusy()) {
            tts.stop();
            cancelFakeTalk();
            sprites.setIdle();
        }
        if (!hasMicPermission()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        File f = new File(getCacheDir(), "voice_input.wav");
        try {
            recorder.start(f, settings.getMaxRecordSeconds(), new AudioRecorder.Listener() {
                @Override public void onMaxDurationReached() { onMicUp(); }
            });
        } catch (IOException e) {
            setStatus(getString(R.string.err_mic_unavailable), true);
            return;
        }
        recording = true;
        micButton.setActivated(true);
        setStatus(getString(R.string.status_recording), false);
    }

    private void onMicUp() {
        if (!recording) return;
        recording = false;
        micButton.setActivated(false);
        micButton.setPressed(false);
        File wav = recorder.stop();
        if (wav == null) {
            setStatus(getString(R.string.err_empty_recording), true);
            return;
        }
        sendVoice(wav);
    }

    // ---------------------------------------------------------------------------- Gemini turn

    private void sendVoice(final File wav) {
        busy = true;
        sprites.setThinking();
        setStatus(getString(R.string.status_thinking), false);
        worker.execute(new Runnable() {
            @Override public void run() {
                byte[] bytes;
                try {
                    bytes = readFile(wav);
                } catch (IOException e) {
                    postFailure(getString(R.string.err_empty_recording));
                    return;
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    wav.delete(); // temporary audio is always deleted
                }
                try {
                    GeminiResponse r = gemini.sendAudio(bytes, buildSystemInstruction());
                    postSuccess(r, null);
                } catch (GeminiClient.GeminiException e) {
                    postFailure(e.userMessage());
                } catch (RuntimeException e) {
                    postFailure("Unexpected error. Try again.");
                }
            }
        });
    }

    private void sendTypedText() {
        final String text = textInput.getText().toString().trim();
        if (text.isEmpty() || recording || busy) return;
        if (!settings.hasApiKey()) {
            setStatus(getString(R.string.status_no_key), true);
            return;
        }
        if (sprites.getState() == SpriteManager.State.TALKING || tts.isBusy()) {
            tts.stop();
            cancelFakeTalk();
        }
        textInput.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(textInput.getWindowToken(), 0);

        busy = true;
        sprites.setThinking();
        setStatus(getString(R.string.status_thinking), false);
        appendLine(getString(R.string.you), text, R.color.text_you);
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    GeminiResponse r = gemini.sendText(text, buildSystemInstruction());
                    postSuccess(r, text);
                } catch (GeminiClient.GeminiException e) {
                    postFailure(e.userMessage());
                } catch (RuntimeException e) {
                    postFailure("Unexpected error. Try again.");
                }
            }
        });
    }

    private String buildSystemInstruction() {
        return conversation.buildSystemInstruction(characterPrompt, memory.asPromptBlock(),
                settings.getHistoryTurns(), settings.getMaxReplyChars());
    }

    private void postFailure(final String message) {
        main.post(new Runnable() {
            @Override public void run() {
                busy = false;
                sprites.setIdle(); // expression stays as it was (neutral or the previous one)
                setStatus(message, true);
            }
        });
    }

    /** @param typedText the user's typed message, or null for a voice turn */
    private void postSuccess(final GeminiResponse r, final String typedText) {
        main.post(new Runnable() {
            @Override public void run() { handleResponse(r, typedText); }
        });
    }

    private void handleResponse(final GeminiResponse r, String typedText) {
        String userText = typedText != null ? typedText : r.heard;
        if (typedText == null && userText != null && !userText.isEmpty()) {
            appendLine(getString(R.string.you), userText, R.color.text_you);
        }

        String reply = r.reply;
        if (reply.length() > settings.getMaxReplyChars() * 2) reply = reply.substring(0, settings.getMaxReplyChars() * 2);
        final String finalReply = reply;

        conversation.update(userText, finalReply);   // ConversationManager.update()
        memory.update(r);                            // MemoryManager.update()
        final SpriteManager.Emotion emotion = SpriteManager.Emotion.from(r.emotion);
        sprites.setExpression(emotion);              // SpriteManager.setExpression(emotion)

        if (!r.speak) {
            busy = false;
            appendLine(getString(R.string.amadeus), finalReply, R.color.text_ai);
            sprites.setIdle();
            setStatus(getString(R.string.status_ready), false);
            return;
        }

        tts.speak(finalReply, emotion.key, new TtsManager.Listener() {
            @Override public void onPlaybackStarted() {
                busy = false;
                appendLine(getString(R.string.amadeus), finalReply, R.color.text_ai);
                sprites.setTalking();
                setStatus(getString(R.string.status_speaking), false);
            }

            @Override public void onPlaybackFinished() {
                busy = false;
                sprites.setIdle();
                setStatus(getString(R.string.status_ready), false);
            }

            @Override public void onError(String message) {
                // TTS failed: the text answer is still shown; mouth animates briefly instead.
                busy = false;
                appendLine(getString(R.string.amadeus), finalReply, R.color.text_ai);
                setStatus(message, true);
                sprites.setTalking();
                scheduleFakeTalkEnd(Math.min(8000, 500 + finalReply.length() * 55));
            }
        });
    }

    private void scheduleFakeTalkEnd(long ms) {
        cancelFakeTalk();
        fakeTalkEnd = new Runnable() {
            @Override public void run() {
                fakeTalkEnd = null;
                sprites.setIdle();
            }
        };
        main.postDelayed(fakeTalkEnd, ms);
    }

    private void cancelFakeTalk() {
        if (fakeTalkEnd != null) {
            main.removeCallbacks(fakeTalkEnd);
            fakeTalkEnd = null;
        }
    }

    private static byte[] readFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------------------------ UI

    private void setStatus(String text, boolean error) {
        statusView.setText(text);
        statusView.setTextColor(getResources().getColor(error ? R.color.text_error : R.color.text_status));
    }

    private void appendLine(String who, String text, int colorRes) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(who).append(": ");
        int end = sb.length();
        sb.setSpan(new ForegroundColorSpan(getResources().getColor(colorRes)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(text);
        lines.add(sb);
        while (lines.size() > MAX_LINES) lines.remove(0);
        renderLines();
    }

    private void renderLines() {
        SpannableStringBuilder all = new SpannableStringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) all.append("\n\n");
            all.append(lines.get(i));
        }
        conversationView.setText(all);
        scrollView.post(new Runnable() {
            @Override public void run() { scrollView.fullScroll(View.FOCUS_DOWN); }
        });
    }

    // -------------------------------------------------------------------------------- settings

    private void showSettings() {
        final View v = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        applyTypeface(v);
        final EditText key = (EditText) v.findViewById(R.id.set_api_key);
        final EditText chat = (EditText) v.findViewById(R.id.set_chat_model);
        final EditText ttsModel = (EditText) v.findViewById(R.id.set_tts_model);
        final EditText voice = (EditText) v.findViewById(R.id.set_tts_voice);
        final EditText turns = (EditText) v.findViewById(R.id.set_turns);
        final EditText maxRec = (EditText) v.findViewById(R.id.set_max_record);
        final EditText maxReply = (EditText) v.findViewById(R.id.set_max_reply);
        final EditText anim = (EditText) v.findViewById(R.id.set_anim_speed);

        key.setText(settings.getApiKey());
        chat.setText(settings.getChatModel());
        ttsModel.setText(settings.getTtsModel());
        voice.setText(settings.getTtsVoice());
        voice.setHint("default from tts_prompt.txt");
        turns.setText(String.valueOf(settings.getHistoryTurns()));
        maxRec.setText(String.valueOf(settings.getMaxRecordSeconds()));
        maxReply.setText(String.valueOf(settings.getMaxReplyChars()));
        anim.setText(String.valueOf(settings.getFrameIntervalMs()));

        v.findViewById(R.id.btn_clear_conversation).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                conversation.clear();
                lines.clear();
                renderLines();
                Toast.makeText(MainActivity.this, R.string.conversation_cleared, Toast.LENGTH_SHORT).show();
            }
        });
        v.findViewById(R.id.btn_clear_memory).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                memory.clear();
                Toast.makeText(MainActivity.this, R.string.memory_cleared, Toast.LENGTH_SHORT).show();
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.settings_title)
                .setView(v)
                .setPositiveButton(R.string.settings_save, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        settings.setApiKey(key.getText().toString());
                        settings.setChatModel(chat.getText().toString());
                        settings.setTtsModel(ttsModel.getText().toString());
                        settings.setTtsVoice(voice.getText().toString());
                        settings.setHistoryTurns(parseInt(turns, AppSettings.DEFAULT_TURNS));
                        settings.setMaxRecordSeconds(parseInt(maxRec, AppSettings.DEFAULT_MAX_RECORD_SEC));
                        settings.setMaxReplyChars(parseInt(maxReply, AppSettings.DEFAULT_MAX_REPLY_CHARS));
                        settings.setFrameIntervalMs(parseInt(anim, AppSettings.DEFAULT_FRAME_MS));
                        sprites.setFrameIntervalMs(settings.getFrameIntervalMs());
                        Toast.makeText(MainActivity.this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
                        if (!busy && !recording) {
                            setStatus(getString(settings.hasApiKey() ? R.string.status_ready : R.string.status_no_key),
                                    !settings.hasApiKey());
                        }
                    }
                })
                .setNegativeButton(R.string.settings_cancel, null)
                .create();
        dialog.show();
    }

    private static int parseInt(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
