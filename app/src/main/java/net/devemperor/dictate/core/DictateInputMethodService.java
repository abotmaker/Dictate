package net.devemperor.dictate.core;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.Transcription;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;


import org.json.JSONObject;
import org.json.JSONArray;

import net.devemperor.dictate.BuildConfig;
import net.devemperor.dictate.DictateUtils;
import net.devemperor.dictate.rewording.PromptModel;
import net.devemperor.dictate.rewording.PromptsDatabaseHelper;
import net.devemperor.dictate.rewording.PromptsKeyboardAdapter;
import net.devemperor.dictate.rewording.PromptsOverviewActivity;
import net.devemperor.dictate.settings.DictateSettingsActivity;
import net.devemperor.dictate.R;
import net.devemperor.dictate.bluetooth.BluetoothManager;
import net.devemperor.dictate.usage.UsageDatabaseHelper;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.util.TypedValue;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// MAIN CLASS
public class DictateInputMethodService extends InputMethodService {

    // define handlers and runnables for background tasks
    private Handler mainHandler;
    private Handler deleteHandler;
    private Handler recordTimeHandler;
    private Runnable deleteRunnable;
    private Runnable recordTimeRunnable;

    // define variables and objects
    private long elapsedTime;
    private boolean isDeleting = false;
    private long startDeleteTime = 0;
    private int currentDeleteDelay = 50;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private boolean instantPrompt = false;
    private boolean vibrationEnabled = true;
    private boolean audioFocusEnabled = true;
    private TextView selectedCharacter = null;
    private boolean spaceButtonUserHasSwiped = false;
    private int currentInputLanguagePos;
    private String currentInputLanguageValue;

    private MediaRecorder recorder;
    // Chunked recording fields
    private ScheduledExecutorService chunkingScheduler;
    private final AtomicInteger chunkCounter = new AtomicInteger(0);
    private final AtomicBoolean isChunkedRecording = new AtomicBoolean(false);
    private final List<File> audioChunks = new ArrayList<>();
    private MediaRecorder currentChunkRecorder;
    private final Object recordingLock = new Object();
    private Handler chunkHandler;
    private StringBuilder accumulatedText = new StringBuilder();
    private final AtomicInteger consecutiveSilentChunks = new AtomicInteger(0);
    
    // Chunked recording configuration
    private static final int CHUNK_DURATION_MS = 3000; // 3 seconds
    
    // OpenAI Realtime API fields
    private boolean isOpenAIRealtimeStreaming = false;
    private StringBuilder openaiRealtimeAccumulatedText = new StringBuilder();
    private ExecutorService openaiRealtimeThread;
    private final Object openaiRealtimeLock = new Object();
    private OpenAIRealtimeClient realtimeClient;
    private String accumulatedRealtimeText = "";
    
    private ExecutorService speechApiThread;
    private ExecutorService rewordingApiThread;
    private File audioFile;
    private Vibrator vibrator;
    private SharedPreferences sp;
    private AudioManager am;
    private AudioFocusRequest audioFocusRequest;
    private BluetoothManager bluetoothManager;

    // define views
    private ConstraintLayout dictateKeyboardView;
    private MaterialButton settingsButton;
    private MaterialButton recordButton;
    private MaterialButton resendButton;
    private MaterialButton backspaceButton;
    private MaterialButton switchButton;
    private MaterialButton trashButton;
    private MaterialButton spaceButton;
    private MaterialButton pauseButton;
    private MaterialButton enterButton;
    private ConstraintLayout infoCl;
    private TextView infoTv;
    private Button infoYesButton;
    private Button infoNoButton;
    private ConstraintLayout promptsCl;
    private RecyclerView promptsRv;
    private TextView runningPromptTv;
    private ProgressBar runningPromptPb;
    private MaterialButton editSelectAllButton;
    private MaterialButton editUndoButton;
    private MaterialButton editRedoButton;
    private MaterialButton editCutButton;
    private MaterialButton editCopyButton;
    private MaterialButton editPasteButton;
    private LinearLayout overlayCharactersLl;

    PromptsDatabaseHelper promptsDb;
    PromptsKeyboardAdapter promptsAdapter;

    UsageDatabaseHelper usageDb;
    
    // Voice Command Processing
    VoiceCommandProcessor voiceCommandProcessor;

    // start method that is called when user opens the keyboard
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {
        Context context = new ContextThemeWrapper(this, R.style.Theme_Dictate);

        // initialize some stuff
        mainHandler = new Handler(Looper.getMainLooper());
        deleteHandler = new Handler();
        recordTimeHandler = new Handler(Looper.getMainLooper());
        chunkHandler = new Handler(Looper.getMainLooper());

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);
        promptsDb = new PromptsDatabaseHelper(this);
        usageDb = new UsageDatabaseHelper(this);
        voiceCommandProcessor = new VoiceCommandProcessor(this);
        vibrationEnabled = sp.getBoolean("net.devemperor.dictate.vibration", true);
        currentInputLanguagePos = sp.getInt("net.devemperor.dictate.input_language_pos", 0);

        dictateKeyboardView = (ConstraintLayout) LayoutInflater.from(context).inflate(R.layout.activity_dictate_keyboard_view, null);
        ViewCompat.setOnApplyWindowInsetsListener(dictateKeyboardView, (v, insets) -> {
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom);
            return insets;  // fix for overlapping with navigation bar on Android 15+
        });

        // set background of dictateKeyboardView according to device theme (default in layout is already light)
        if ((getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
            TypedValue typedValue = new TypedValue();
            context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorSecondary, typedValue, true);
            dictateKeyboardView.setBackgroundColor(typedValue.data);
        }

        settingsButton = dictateKeyboardView.findViewById(R.id.settings_btn);
        recordButton = dictateKeyboardView.findViewById(R.id.record_btn);
        resendButton = dictateKeyboardView.findViewById(R.id.resend_btn);
        backspaceButton = dictateKeyboardView.findViewById(R.id.backspace_btn);
        switchButton = dictateKeyboardView.findViewById(R.id.switch_btn);
        trashButton = dictateKeyboardView.findViewById(R.id.trash_btn);
        spaceButton = dictateKeyboardView.findViewById(R.id.space_btn);
        pauseButton = dictateKeyboardView.findViewById(R.id.pause_btn);
        enterButton = dictateKeyboardView.findViewById(R.id.enter_btn);

        infoCl = dictateKeyboardView.findViewById(R.id.info_cl);
        infoTv = dictateKeyboardView.findViewById(R.id.info_tv);
        infoYesButton = dictateKeyboardView.findViewById(R.id.info_yes_btn);
        infoNoButton = dictateKeyboardView.findViewById(R.id.info_no_btn);

        promptsCl = dictateKeyboardView.findViewById(R.id.prompts_keyboard_cl);
        promptsRv = dictateKeyboardView.findViewById(R.id.prompts_keyboard_rv);
        runningPromptPb = dictateKeyboardView.findViewById(R.id.prompts_keyboard_running_pb);
        runningPromptTv = dictateKeyboardView.findViewById(R.id.prompts_keyboard_running_prompt_tv);

        editSelectAllButton = dictateKeyboardView.findViewById(R.id.edit_select_all_btn);
        editUndoButton = dictateKeyboardView.findViewById(R.id.edit_undo_btn);
        editRedoButton = dictateKeyboardView.findViewById(R.id.edit_redo_btn);
        editCutButton = dictateKeyboardView.findViewById(R.id.edit_cut_btn);
        editCopyButton = dictateKeyboardView.findViewById(R.id.edit_copy_btn);
        editPasteButton = dictateKeyboardView.findViewById(R.id.edit_paste_btn);

        overlayCharactersLl = dictateKeyboardView.findViewById(R.id.overlay_characters_ll);

        promptsRv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothManager = new BluetoothManager(this);
        }

        // if user id is not set, set a random number as user id
        if (sp.getString("net.devemperor.dictate.user_id", "null").equals("null")) {
            sp.edit().putString("net.devemperor.dictate.user_id", String.valueOf((int) (Math.random() * 1000000))).apply();
        }

        recordTimeRunnable = new Runnable() {  // runnable to update the record button time text
            @Override
            public void run() {
                elapsedTime += 100;
                recordButton.setText(getString(R.string.dictate_send,
                        String.format(Locale.getDefault(), "%02d:%02d", (int) (elapsedTime / 60000), (int) (elapsedTime / 1000) % 60)));
                recordTimeHandler.postDelayed(this, 100);
            }
        };

        // initialize audio manager to stop and start background audio
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChange -> {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        if (isRecording) pauseButton.performClick();
                    }
                })
                .build();

        settingsButton.setOnClickListener(v -> {
            if (isRecording) trashButton.performClick();
            infoCl.setVisibility(View.GONE);
            openSettingsActivity();
        });

        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
        recordButton.setOnClickListener(v -> {
            vibrate();

            infoCl.setVisibility(View.GONE);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                openSettingsActivity();
            } else if (!isRecording) {
                startRecording();
            } else {
                stopRecording();
            }
        });

        recordButton.setOnLongClickListener(v -> {
            vibrate();

            if (!isRecording) {  // open real settings activity to start file picker
                Intent intent = new Intent(this, DictateSettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra("net.devemperor.dictate.open_file_picker", true);
                startActivity(intent);
            }
            return true;
        });

        resendButton.setOnClickListener(v -> {
            vibrate();
            // if user clicked on resendButton without error before, audioFile is default audio
            if (audioFile == null) audioFile = new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a"));
            startWhisperApiRequest();
        });

        backspaceButton.setOnClickListener(v -> {
            vibrate();
            deleteOneCharacter();
        });

        backspaceButton.setOnLongClickListener(v -> {
            isDeleting = true;
            startDeleteTime = System.currentTimeMillis();
            currentDeleteDelay = 50;
            deleteRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isDeleting) {
                        deleteOneCharacter();
                        long diff = System.currentTimeMillis() - startDeleteTime;
                        if (diff > 1500 && currentDeleteDelay == 50) {
                            vibrate();
                            currentDeleteDelay = 25;
                        } else if (diff > 3000 && currentDeleteDelay == 25) {
                            vibrate();
                            currentDeleteDelay = 10;
                        } else if (diff > 5000 && currentDeleteDelay == 10) {
                            vibrate();
                            currentDeleteDelay = 5;
                        }
                        deleteHandler.postDelayed(this, currentDeleteDelay);
                    }
                }
            };
            deleteHandler.post(deleteRunnable);
            return true;
        });

        backspaceButton.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                isDeleting = false;
                deleteHandler.removeCallbacks(deleteRunnable);
            }
            return false;
        });

        switchButton.setOnClickListener(v -> {
            vibrate();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod();
            }
        });

        switchButton.setOnLongClickListener(v -> {
            vibrate();

            currentInputLanguagePos++;
            recordButton.setText(getDictateButtonText());
            return true;
        });

        // trash button to abort the recording and reset all variables and views
        trashButton.setOnClickListener(v -> {
            vibrate();
            
            // Clean up traditional recording
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;

                if (recordTimeRunnable != null) {
                    recordTimeHandler.removeCallbacks(recordTimeRunnable);
                }
            }
            
            // Clean up chunked recording
            cleanupChunkedRecording();
            
            if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);

            isRecording = false;
            isPaused = false;
            instantPrompt = false;
            recordButton.setText(getDictateButtonText());
            recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
            recordButton.setEnabled(true);
            pauseButton.setVisibility(View.GONE);
            pauseButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_pause_24));
            trashButton.setVisibility(View.GONE);
        });

        // space button that changes cursor position if user swipes over it
        spaceButton.setOnTouchListener((v, event) -> {
            InputConnection inputConnection = getCurrentInputConnection();
            if (inputConnection != null) {
                spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_keyboard_double_arrow_left_24,
                        0, R.drawable.ic_baseline_keyboard_double_arrow_right_24, 0);
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        spaceButtonUserHasSwiped = false;
                        spaceButton.setTag(event.getX());
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float x = (float) spaceButton.getTag();
                        if (event.getX() - x > 30) {
                            vibrate();
                            inputConnection.commitText("", 2);
                            spaceButton.setTag(event.getX());
                            spaceButtonUserHasSwiped = true;
                        } else if (x - event.getX() > 30) {
                            vibrate();
                            inputConnection.commitText("", -1);
                            spaceButton.setTag(event.getX());
                            spaceButtonUserHasSwiped = true;
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                        if (!spaceButtonUserHasSwiped) {
                            vibrate();
                            inputConnection.commitText(" ", 1);
                        }
                        spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
                        break;
                }
            } else {
                spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
            }
            return false;
        });

        pauseButton.setOnClickListener(v -> {
            vibrate();
            
            // Handle traditional recording pause/resume
            if (recorder != null) {
                if (isPaused) {
                    if (audioFocusEnabled) am.requestAudioFocus(audioFocusRequest);
                    recorder.resume();
                    recordTimeHandler.post(recordTimeRunnable);
                    pauseButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_pause_24));
                    isPaused = false;
                } else {
                    if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
                    recorder.pause();
                    recordTimeHandler.removeCallbacks(recordTimeRunnable);
                    pauseButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_mic_24));
                    isPaused = true;
                }
            }
            
            // Handle chunked recording pause/resume
            if (isChunkedRecording.get()) {
                synchronized (recordingLock) {
                    if (isPaused) {
                        // Resume chunked recording
                        if (audioFocusEnabled) am.requestAudioFocus(audioFocusRequest);
                        if (currentChunkRecorder != null) {
                            currentChunkRecorder.resume();
                        }
                        recordTimeHandler.post(recordTimeRunnable);
                        
                        // Resume chunk scheduling
                        if (chunkingScheduler == null || chunkingScheduler.isShutdown()) {
                            chunkingScheduler = Executors.newSingleThreadScheduledExecutor();
                            chunkingScheduler.scheduleAtFixedRate(() -> {
                                if (isChunkedRecording.get() && !isPaused) {
                                    chunkHandler.post(this::switchToNextChunk);
                                }
                            }, CHUNK_DURATION_MS, CHUNK_DURATION_MS, TimeUnit.MILLISECONDS);
                        }
                        
                        pauseButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_pause_24));
                        isPaused = false;
                    } else {
                        // Pause chunked recording
                        if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
                        if (currentChunkRecorder != null) {
                            currentChunkRecorder.pause();
                        }
                        recordTimeHandler.removeCallbacks(recordTimeRunnable);
                        
                        // Pause chunk scheduling
                        if (chunkingScheduler != null && !chunkingScheduler.isShutdown()) {
                            chunkingScheduler.shutdown();
                        }
                        
                        pauseButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_mic_24));
                        isPaused = true;
                    }
                }
            }
        });

        enterButton.setOnClickListener(v -> {
            vibrate();

            InputConnection inputConnection = getCurrentInputConnection();
            if (inputConnection != null) {
                EditorInfo editorInfo = getCurrentInputEditorInfo();
                if ((editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
                    inputConnection.commitText("\n", 1);
                } else {
                    inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                }
            }
        });

        enterButton.setOnLongClickListener(v -> {
            vibrate();
            overlayCharactersLl.setVisibility(View.VISIBLE);
            return true;
        });

        enterButton.setOnTouchListener((v, event) -> {
            if (overlayCharactersLl.getVisibility() == View.VISIBLE) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_MOVE:
                        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
                            TextView charView = (TextView) overlayCharactersLl.getChildAt(i);
                            if (isPointInsideView(event.getRawX(), charView)) {
                                if (selectedCharacter != charView) {
                                    selectedCharacter = charView;
                                    highlightSelectedCharacter(selectedCharacter);
                                }
                                break;
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (selectedCharacter != null) {
                            InputConnection inputConnection = getCurrentInputConnection();
                            if (inputConnection != null) {
                                inputConnection.commitText(selectedCharacter.getText(), 1);
                            }
                            selectedCharacter.setBackground(AppCompatResources.getDrawable(this, R.drawable.border_textview));
                            selectedCharacter = null;
                        }
                        overlayCharactersLl.setVisibility(View.GONE);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        overlayCharactersLl.setVisibility(View.GONE);
                        return true;
                }
            }
            return false;
        });

        editSelectAllButton.setOnClickListener(v -> {
            vibrate();

            InputConnection inputConnection = getCurrentInputConnection();
            if (inputConnection != null) {
                ExtractedText extractedText = inputConnection.getExtractedText(new ExtractedTextRequest(), 0);

                if (inputConnection.getSelectedText(0) == null && extractedText.text.length() > 0) {
                    inputConnection.performContextMenuAction(android.R.id.selectAll);
                    editSelectAllButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_deselect_24));
                } else {
                    inputConnection.clearMetaKeyStates(0);
                    if (extractedText == null || extractedText.text == null) {
                        inputConnection.setSelection(0, 0);
                    } else {
                        inputConnection.setSelection(extractedText.text.length(), extractedText.text.length());
                    }
                    editSelectAllButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_select_all_24));
                }
            }
        });

        // initialize all edit buttons
        Object[][] buttonsActions = {
                { editUndoButton, android.R.id.undo },
                { editRedoButton, android.R.id.redo },
                { editCutButton,  android.R.id.cut },
                { editCopyButton, android.R.id.copy },
                { editPasteButton, android.R.id.paste }
        };

        for (Object[] pair : buttonsActions) {
            ((Button) pair[0]).setOnClickListener(v -> {
                vibrate();
                InputConnection inputConnection = getCurrentInputConnection();
                if (inputConnection != null) {
                    inputConnection.performContextMenuAction((int) pair[1]);
                }
            });
        }

        // initialize overlay characters
        for (int i = 0; i < 8; i++) {
            TextView charView = (TextView) LayoutInflater.from(context).inflate(R.layout.item_overlay_characters, overlayCharactersLl, false);
            overlayCharactersLl.addView(charView);
        }

        return dictateKeyboardView;
    }

    // method is called if the user closed the keyboard
    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);

        // Clean up traditional recording
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) { }
            recorder.release();
            recorder = null;

            if (recordTimeRunnable != null) {
                recordTimeHandler.removeCallbacks(recordTimeRunnable);
            }
        }
        
        // Clean up chunked recording
        cleanupChunkedRecording();

        if (speechApiThread != null) speechApiThread.shutdownNow();
        if (rewordingApiThread != null) rewordingApiThread.shutdownNow();
        
        // Cleanup voice command processor
        if (voiceCommandProcessor != null) {
            voiceCommandProcessor.cleanup();
        }

        if (bluetoothManager != null) {
            bluetoothManager.close();
            bluetoothManager = null;
        }

        pauseButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_pause_24));
        pauseButton.setVisibility(View.GONE);
        trashButton.setVisibility(View.GONE);
        resendButton.setVisibility(View.GONE);
        infoCl.setVisibility(View.GONE);
        isRecording = false;
        isPaused = false;
        instantPrompt = false;
        if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
        recordButton.setText(R.string.dictate_record);
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
        recordButton.setEnabled(true);
    }
    
    private void cleanupChunkedRecording() {
        synchronized (recordingLock) {
            isChunkedRecording.set(false);
            
            // Clean up current chunk recorder
            if (currentChunkRecorder != null) {
                try {
                    currentChunkRecorder.stop();
                    currentChunkRecorder.release();
                } catch (RuntimeException ignored) { }
                currentChunkRecorder = null;
            }
            
            // Shutdown chunking scheduler
            if (chunkingScheduler != null && !chunkingScheduler.isShutdown()) {
                chunkingScheduler.shutdown();
                try {
                    if (!chunkingScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                        chunkingScheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    chunkingScheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                chunkingScheduler = null;
            }
            
            // Clear chunk data
            audioChunks.clear();
            chunkCounter.set(0);
            accumulatedText.setLength(0);
            
            // Remove any pending callbacks
            if (recordTimeRunnable != null) {
                recordTimeHandler.removeCallbacks(recordTimeRunnable);
            }
            chunkHandler.removeCallbacksAndMessages(null);
        }
    }

    // method is called if the keyboard appears again
    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);

        if (sp.getBoolean("net.devemperor.dictate.rewording_enabled", true)) {
            promptsCl.setVisibility(View.VISIBLE);

            // collect all prompts from database
            List<PromptModel> data;
            InputConnection inputConnection = getCurrentInputConnection();
            if (inputConnection != null && inputConnection.getSelectedText(0) == null) {
                data = promptsDb.getAll(false);
                editSelectAllButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_select_all_24));
            } else {
                data = promptsDb.getAll(true);
                editSelectAllButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_deselect_24));
            }

            promptsAdapter = new PromptsKeyboardAdapter(data, position -> {
                vibrate();
                PromptModel model = data.get(position);

                if (model.getId() == -1) {  // instant prompt clicked
                    instantPrompt = true;
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        openSettingsActivity();
                    } else if (!isRecording) {
                        startRecording();
                    } else {
                        stopRecording();
                    }
                } else if (model.getId() == -2) {  // add prompt clicked
                    Intent intent = new Intent(this, PromptsOverviewActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } else {
                    startGPTApiRequest(model);  // another normal prompt clicked
                }
            });
            promptsRv.setAdapter(promptsAdapter);
        } else {
            promptsCl.setVisibility(View.GONE);
        }

        // enable resend button if previous audio file still exists in cache
        if (new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a")).exists()
                && sp.getBoolean("net.devemperor.dictate.resend_button", false)) {
            resendButton.setVisibility(View.VISIBLE);
        } else {
            resendButton.setVisibility(View.GONE);
        }

        // fill all overlay characters
        String charactersString = sp.getString("net.devemperor.dictate.overlay_characters", "()-:!?,.");
        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
            TextView charView = (TextView) overlayCharactersLl.getChildAt(i);
            if (i >= charactersString.length()) {
                charView.setVisibility(View.GONE);
            } else {
                charView.setVisibility(View.VISIBLE);
                charView.setText(charactersString.substring(i, i + 1));
            }
        }

        // get the currently selected input language
        recordButton.setText(getDictateButtonText());

        // check if user enabled audio focus
        audioFocusEnabled = sp.getBoolean("net.devemperor.dictate.audio_focus", true);

        // show infos for updates, ratings or donations
        long totalAudioTime = usageDb.getTotalAudioTime();
        if (sp.getInt("net.devemperor.dictate.last_version_code", 0) < BuildConfig.VERSION_CODE) {
            showInfo("update");
        } else if (totalAudioTime > 180 && totalAudioTime <= 600 && !sp.getBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", false)) {
            showInfo("rate");  // in case someone had Dictate installed before, he shouldn't get both messages
        } else if (totalAudioTime > 600 && !sp.getBoolean("net.devemperor.dictate.flag_has_donated", false)) {
            showInfo("donate");
        }

        // start audio file transcription if user selected an audio file
        if (!sp.getString("net.devemperor.dictate.transcription_audio_file", "").isEmpty()) {
            audioFile = new File(getCacheDir(), sp.getString("net.devemperor.dictate.transcription_audio_file", ""));
            sp.edit().putString("net.devemperor.dictate.last_file_name", audioFile.getName()).apply();

            sp.edit().remove("net.devemperor.dictate.transcription_audio_file").apply();
            startWhisperApiRequest();

        } else if (sp.getBoolean("net.devemperor.dictate.instant_recording", false)) {
            recordButton.performClick();
        }
    }

    // method is called if user changed text selection
    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onUpdateSelection (int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);

        // refill all prompts
        if (sp != null && sp.getBoolean("net.devemperor.dictate.rewording_enabled", true)) {
            List<PromptModel> data;
            if (getCurrentInputConnection().getSelectedText(0) == null) {
                data = promptsDb.getAll(false);
                editSelectAllButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_select_all_24));
            } else {
                data = promptsDb.getAll(true);
                editSelectAllButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_deselect_24));
            }

            promptsAdapter.getData().clear();
            promptsAdapter.getData().addAll(data);
            promptsAdapter.notifyDataSetChanged();
        }
    }

    private void vibrate() {
        if (vibrationEnabled) if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void openSettingsActivity() {
        Intent intent = new Intent(this, DictateSettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void startRecording() {
        Log.d("DictateFlow", "=== startRecording() called ===");
        int transcriptionProvider = sp.getInt("net.devemperor.dictate.transcription_provider", 0);
        boolean isOpenAIRealtime = (transcriptionProvider == 3);
        boolean useOpenAIRealtimeStreaming = sp.getBoolean("net.devemperor.dictate.openai_realtime_streaming", true);
        
        Log.d("DictateFlow", "transcriptionProvider: " + transcriptionProvider);
        Log.d("DictateFlow", "isOpenAIRealtime: " + isOpenAIRealtime);
        Log.d("DictateFlow", "useOpenAIRealtimeStreaming: " + useOpenAIRealtimeStreaming);
        
        if (isOpenAIRealtime && useOpenAIRealtimeStreaming) {
            Log.d("DictateFlow", "Starting OpenAI Realtime Recording (WebSocket)");
            startOpenAIRealtimeRecording();
        } else if (sp.getBoolean("net.devemperor.dictate.chunked_recording", false)) {
            Log.d("DictateFlow", "Starting Chunked Recording");
            startChunkedRecording();
        } else {
            Log.d("DictateFlow", "Starting Traditional Recording");
            startTraditionalRecording();
        }
    }
    
    private void startTraditionalRecording() {
        audioFile = new File(getCacheDir(), "audio.m4a");
        sp.edit().putString("net.devemperor.dictate.last_file_name", audioFile.getName()).apply();

        recorder = new MediaRecorder();
        if (bluetoothManager != null && bluetoothManager.isHeadsetConnected()) {
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        } else {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(64000);
        recorder.setAudioSamplingRate(44100);
        recorder.setOutputFile(audioFile);

        if (audioFocusEnabled) am.requestAudioFocus(audioFocusRequest);

        try {
            recorder.prepare();
            recorder.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        recordButton.setText(R.string.dictate_send);
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_send_20, 0, 0, 0);
        pauseButton.setVisibility(View.VISIBLE);
        trashButton.setVisibility(View.VISIBLE);
        resendButton.setVisibility(View.GONE);
        isRecording = true;

        elapsedTime = 0;
        recordTimeHandler.post(recordTimeRunnable);
    }
    
    private void startChunkedRecording() {
        synchronized (recordingLock) {
            // Reset state
            audioChunks.clear();
            chunkCounter.set(0);
            accumulatedText.setLength(0);
            consecutiveSilentChunks.set(0);
            isChunkedRecording.set(true);
            
            // Create scheduling executor for chunk management
            chunkingScheduler = Executors.newSingleThreadScheduledExecutor();
            
            if (audioFocusEnabled) am.requestAudioFocus(audioFocusRequest);
            
            // Start first chunk
            startNewChunk();
            
            // Schedule chunk switching
            chunkingScheduler.scheduleAtFixedRate(() -> {
                if (isChunkedRecording.get()) {
                    chunkHandler.post(this::switchToNextChunk);
                }
            }, CHUNK_DURATION_MS, CHUNK_DURATION_MS, TimeUnit.MILLISECONDS);
            
            // Update UI
            recordButton.setText(R.string.dictate_send);
            recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_send_20, 0, 0, 0);
            pauseButton.setVisibility(View.VISIBLE);
            trashButton.setVisibility(View.VISIBLE);
            resendButton.setVisibility(View.GONE);
            isRecording = true;
            
            elapsedTime = 0;
            recordTimeHandler.post(recordTimeRunnable);
        }
    }

    private void stopRecording() {
        if (isOpenAIRealtimeStreaming) {
            stopOpenAIRealtimeRecording();
        } else if (isChunkedRecording.get()) {
            stopChunkedRecording();
        } else {
            stopTraditionalRecording();
        }
    }
    
    private void stopTraditionalRecording() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) { }
            recorder.release();
            recorder = null;

            if (recordTimeRunnable != null) {
                recordTimeHandler.removeCallbacks(recordTimeRunnable);
            }

            startWhisperApiRequest();
        }
    }
    
    private void stopChunkedRecording() {
        synchronized (recordingLock) {
            isChunkedRecording.set(false);
            
            // Stop the current chunk recorder
            if (currentChunkRecorder != null) {
                try {
                    currentChunkRecorder.stop();
                    currentChunkRecorder.release();
                } catch (RuntimeException ignored) { }
                currentChunkRecorder = null;
            }
            
            // Stop the chunking scheduler
            if (chunkingScheduler != null && !chunkingScheduler.isShutdown()) {
                chunkingScheduler.shutdown();
                chunkingScheduler = null;
            }
            
            if (recordTimeRunnable != null) {
                recordTimeHandler.removeCallbacks(recordTimeRunnable);
            }
            
            // Process final accumulated text if any
            if (accumulatedText.length() > 0) {
                processFinalText();
            } else {
                // If no accumulated text, start API request for the last chunk
                if (!audioChunks.isEmpty()) {
                    audioFile = audioChunks.get(audioChunks.size() - 1);
                    startWhisperApiRequest();
                }
            }
        }
    }
    
    private void startNewChunk() {
        synchronized (recordingLock) {
            int chunkNumber = chunkCounter.getAndIncrement();
            File chunkFile = new File(getCacheDir(), "audio_chunk_" + chunkNumber + ".m4a");
            audioChunks.add(chunkFile);
            
            // Create new MediaRecorder for this chunk
            currentChunkRecorder = new MediaRecorder();
            if (bluetoothManager != null && bluetoothManager.isHeadsetConnected()) {
                currentChunkRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            } else {
                currentChunkRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            currentChunkRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            currentChunkRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            currentChunkRecorder.setAudioEncodingBitRate(64000);
            currentChunkRecorder.setAudioSamplingRate(44100);
            currentChunkRecorder.setOutputFile(chunkFile);
            
            try {
                currentChunkRecorder.prepare();
                currentChunkRecorder.start();
            } catch (IOException e) {
                throw new RuntimeException("Failed to start chunk recording: " + e.getMessage(), e);
            }
        }
    }
    
    private void switchToNextChunk() {
        synchronized (recordingLock) {
            if (!isChunkedRecording.get()) return;
            
            // Stop current chunk
            if (currentChunkRecorder != null) {
                try {
                    currentChunkRecorder.stop();
                    currentChunkRecorder.release();
                } catch (RuntimeException ignored) { }
                currentChunkRecorder = null;
                
                // Process the completed chunk in background
                if (!audioChunks.isEmpty()) {
                    File completedChunk = audioChunks.get(audioChunks.size() - 1);
                    processChunkInBackground(completedChunk);
                }
            }
            
            // Start next chunk if still recording
            if (isChunkedRecording.get()) {
                startNewChunk();
            }
        }
    }
    
    private void processChunkInBackground(File chunkFile) {
        ExecutorService chunkProcessor = Executors.newSingleThreadExecutor();
        chunkProcessor.execute(() -> {
            try {
                // Check if chunk contains meaningful audio before sending to API
                if (!isChunkSilent(chunkFile)) {
                    consecutiveSilentChunks.set(0); // Reset silent chunk counter
                    String chunkText = transcribeChunk(chunkFile);
                    if (chunkText != null && !chunkText.trim().isEmpty()) {
                        synchronized (accumulatedText) {
                            accumulatedText.append(chunkText).append(" ");
                            
                            // Update UI with partial text on main thread
                            chunkHandler.post(() -> updatePartialText(chunkText));
                        }
                    }
                } else {
                    int silentCount = consecutiveSilentChunks.incrementAndGet();
                    Log.d("DictateService", "Skipping silent chunk: " + chunkFile.getName() + 
                          " (consecutive silent: " + silentCount + ")");
                    
                    // If too many consecutive silent chunks, consider processing one anyway
                    // to check for very quiet speech (prevents getting "stuck" on quiet speakers)
                    if (silentCount >= 5) { // After 15 seconds of silence (5 * 3-second chunks)
                        Log.d("DictateService", "Processing chunk anyway after " + silentCount + " silent chunks");
                        consecutiveSilentChunks.set(0);
                        String chunkText = transcribeChunk(chunkFile);
                        if (chunkText != null && !chunkText.trim().isEmpty()) {
                            synchronized (accumulatedText) {
                                accumulatedText.append(chunkText).append(" ");
                                chunkHandler.post(() -> updatePartialText(chunkText));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Log error but don't interrupt the recording flow
                e.printStackTrace();
            } finally {
                chunkProcessor.shutdown();
            }
        });
    }
    
    private String transcribeChunk(File chunkFile) {
        try {
            int transcriptionProvider = sp.getInt("net.devemperor.dictate.transcription_provider", 0);
            String apiHost = getResources().getStringArray(R.array.dictate_api_providers_values)[transcriptionProvider];
            if (apiHost.equals("custom_server")) apiHost = sp.getString("net.devemperor.dictate.transcription_custom_host", getString(R.string.dictate_custom_server_host_hint));

            String apiKey = sp.getString("net.devemperor.dictate.transcription_api_key", sp.getString("net.devemperor.dictate.api_key", "NO_API_KEY")).replaceAll("[^ -~]", "");
            String proxyHost = sp.getString("net.devemperor.dictate.proxy_host", getString(R.string.dictate_settings_proxy_hint));

            String transcriptionModel = "";
            switch (transcriptionProvider) {
                case 0: transcriptionModel = sp.getString("net.devemperor.dictate.transcription_openai_model", sp.getString("net.devemperor.dictate.transcription_model", "gpt-4o-mini-transcribe")); break;
                case 1: transcriptionModel = sp.getString("net.devemperor.dictate.transcription_groq_model", "whisper-large-v3-turbo"); break;
                case 2: transcriptionModel = sp.getString("net.devemperor.dictate.transcription_custom_model", getString(R.string.dictate_custom_transcription_model_hint)); break;
                case 3: transcriptionModel = sp.getString("net.devemperor.dictate.transcription_openai_realtime_model", "gpt-4o-mini-transcribe"); break;
            }

            OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl(apiHost)
                    .timeout(Duration.ofSeconds(30)); // Shorter timeout for chunks

            TranscriptionCreateParams.Builder transcriptionBuilder = TranscriptionCreateParams.builder()
                    .file(chunkFile.toPath())
                    .model(transcriptionModel)
                    .responseFormat(AudioResponseFormat.JSON);

            if (!currentInputLanguageValue.equals("detect")) transcriptionBuilder.language(currentInputLanguageValue);
            if (sp.getBoolean("net.devemperor.dictate.proxy_enabled", false)) {
                if (DictateUtils.isValidProxy(proxyHost)) DictateUtils.applyProxy(clientBuilder, sp);
            }

                         Transcription transcription = clientBuilder.build().audio().transcriptions().create(transcriptionBuilder.build()).asTranscription();
             return transcription.text().trim();
            
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Analyzes an audio file to determine if it contains meaningful audio or is mostly silent.
     * This helps prevent sending silent chunks to the transcription API, which can cause hallucination.
     * 
     * @param audioFile The audio file to analyze
     * @return true if the chunk is considered silent, false if it contains meaningful audio
     */
    private boolean isChunkSilent(File audioFile) {
        try {
            if (!audioFile.exists() || audioFile.length() < 1000) {
                return true; // File too small to contain meaningful audio
            }
            
            RandomAccessFile file = new RandomAccessFile(audioFile, "r");
            long fileLength = file.length();
            
            // Skip M4A header (approximately first 1KB) to get to audio data
            long headerSize = Math.min(1024, fileLength / 10);
            file.seek(headerSize);
            
            // Read audio data in chunks and calculate RMS (Root Mean Square) volume
            byte[] buffer = new byte[4096];
            long totalSamples = 0;
            double sumSquares = 0;
            int maxSamples = 50000; // Limit analysis to prevent slow processing
            
            while (file.getFilePointer() < fileLength && totalSamples < maxSamples) {
                int bytesRead = file.read(buffer);
                if (bytesRead <= 0) break;
                
                // Convert bytes to 16-bit samples and calculate RMS
                for (int i = 0; i < bytesRead - 1; i += 2) {
                    if (totalSamples >= maxSamples) break;
                    
                    // Convert two bytes to a 16-bit sample (little-endian)
                    short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                    sumSquares += sample * sample;
                    totalSamples++;
                }
            }
            
            file.close();
            
            if (totalSamples == 0) {
                return true; // No samples analyzed
            }
            
            // Calculate RMS volume level
            double rms = Math.sqrt(sumSquares / totalSamples);
            
            // Threshold for silence detection (adjustable via settings)
            // Lower threshold = more sensitive (catches quieter speech)
            // Higher threshold = less sensitive (may miss quiet speech but prevents more hallucination)
            int sensitivitySetting = sp.getInt("net.devemperor.dictate.silence_threshold", 50); // 10-100 range
            double silenceThreshold = sensitivitySetting * 10.0; // Convert to 100-1000 range
            
            boolean isSilent = rms < silenceThreshold;
            Log.d("DictateService", String.format("Audio analysis: RMS=%.2f, Threshold=%.2f, Silent=%b", 
                  rms, silenceThreshold, isSilent));
            
            return isSilent;
            
        } catch (Exception e) {
            // If analysis fails, err on the side of caution and process the chunk
            Log.e("DictateService", "Error analyzing audio chunk", e);
            return false;
        }
    }
    
    private void updatePartialText(String newText) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null && !newText.trim().isEmpty()) {
            if (sp.getBoolean("net.devemperor.dictate.instant_output", false)) {
                inputConnection.commitText(newText + " ", 1);
            } else {
                // Animate text output for partial results
                int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
                for (int i = 0; i < newText.length(); i++) {
                    char character = newText.charAt(i);
                    mainHandler.postDelayed(() -> inputConnection.commitText(String.valueOf(character), 1), 
                                           (long) (i * (20L / (speed / 5f))));
                }
                // Add space after animated text
                mainHandler.postDelayed(() -> inputConnection.commitText(" ", 1), 
                                       (long) (newText.length() * (20L / (speed / 5f))));
            }
        }
    }
    
    private void processFinalText() {
        String finalText = accumulatedText.toString().trim();
        if (!finalText.isEmpty()) {
            // Check if auto-rewording should be applied
            boolean autoRewordingEnabled = sp.getBoolean("net.devemperor.dictate.rewording_enabled", true) 
                                        && sp.getBoolean("net.devemperor.dictate.auto_rewording_enabled", false);
            
            if (autoRewordingEnabled && !instantPrompt) {
                String autoPromptIdStr = sp.getString("net.devemperor.dictate.auto_rewording_prompt_id", null);
                if (autoPromptIdStr != null) {
                    try {
                        int autoPromptId = Integer.parseInt(autoPromptIdStr);
                        PromptModel autoPrompt = promptsDb.get(autoPromptId);
                        if (autoPrompt != null) {
                            startGPTApiRequest(new PromptModel(-2, autoPrompt.getPos(), String.valueOf(autoPrompt.getId()), finalText, autoPrompt.requiresSelection()));
                            return;
                        }
                    } catch (NumberFormatException e) {
                        // Invalid prompt ID, fall through to normal processing
                    }
                }
            }
        }
        
        // Final cleanup
        mainHandler.post(() -> {
            recordButton.setText(getDictateButtonText());
            recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
            recordButton.setEnabled(true);
            resendButton.setVisibility(View.VISIBLE);
        });
    }

    private void startOpenAIRealtimeRecording() {
        Log.d("OpenAIRealtime", "=== startOpenAIRealtimeRecording() called ===");
        Log.d("OpenAIRealtime", "Starting OpenAI Realtime streaming");
        
        String apiKey = sp.getString("net.devemperor.dictate.transcription_api_key", "").replaceAll("[^ -~]", "");
        String model = sp.getString("net.devemperor.dictate.transcription_openai_realtime_model", "gpt-4o-mini-transcribe");
        
        Log.d("OpenAIRealtime", "API Key length: " + apiKey.length() + " (first 10 chars: " + (apiKey.length() > 10 ? apiKey.substring(0, 10) + "..." : apiKey) + ")");
        Log.d("OpenAIRealtime", "Model: " + model);
        Log.d("OpenAIRealtime", "Current language: " + currentInputLanguageValue);
        
        if (apiKey.isEmpty()) {
            Log.e("OpenAIRealtime", "No API key provided - cannot start realtime recording");
            return;
        }
        
        Log.d("OpenAIRealtime", "Creating OpenAIRealtimeClient...");
        // Initialize the realtime client
        realtimeClient = new OpenAIRealtimeClient(new OpenAIRealtimeClient.RealtimeCallback() {
            @Override
            public void onTranscriptionDelta(String delta) {
                Log.d("OpenAIRealtime", "Received transcription delta: '" + delta + "'");
                mainHandler.post(() -> {
                    accumulatedRealtimeText += delta;
                    // Show partial transcription in real-time
                    if (sp.getBoolean("net.devemperor.dictate.show_partial_transcription", true)) {
                        updatePartialRealtimeText(accumulatedRealtimeText);
                    }
                });
            }

            @Override
            public void onTranscriptionCompleted(String transcript) {
                Log.d("OpenAIRealtime", "Transcription completed: '" + transcript + "'");
                mainHandler.post(() -> {
                    Log.d("OpenAIRealtime", "Processing completed transcription on main thread");
                    accumulatedRealtimeText = transcript;
                    handleRealtimeTranscriptionResult(transcript);
                });
            }

            @Override
            public void onError(String error) {
                Log.e("OpenAIRealtime", "Realtime error occurred: " + error);
                mainHandler.post(() -> {
                    Log.e("OpenAIRealtime", "Handling error on main thread, falling back to traditional recording");
                    
                    // Check if it's a model compatibility issue
                    if (error.contains("invalid_model") || error.contains("not supported in realtime mode")) {
                        Log.e("OpenAIRealtime", "Model compatibility issue detected");
                        showInfo("realtime_model_not_supported");
                    } else {
                        Log.e("OpenAIRealtime", "Other realtime error, falling back silently");
                        // Fall back to traditional recording for other errors
                        startTraditionalRecording();
                    }
                });
            }

            @Override
            public void onConnectionEstablished() {
                Log.d("OpenAIRealtime", "WebSocket connection established successfully");
                mainHandler.post(() -> {
                    Log.d("OpenAIRealtime", "Updating UI for established connection");
                    isOpenAIRealtimeStreaming = true;
                    recordButton.setText(R.string.dictate_sending);
                    recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_stop_24, 0, 0, 0);
                    pauseButton.setVisibility(View.VISIBLE);
                    trashButton.setVisibility(View.VISIBLE);
                    isRecording = true;
                    
                    Log.d("OpenAIRealtime", "Starting audio streaming...");
                    // Start streaming audio
                    realtimeClient.startStreaming();
                });
            }

            @Override
            public void onConnectionClosed() {
                Log.d("OpenAIRealtime", "WebSocket connection closed");
                mainHandler.post(() -> {
                    Log.d("OpenAIRealtime", "Connection closed - updating state");
                    isOpenAIRealtimeStreaming = false;
                });
            }
        });
        
        Log.d("OpenAIRealtime", "Connecting to OpenAI Realtime API...");
        // Connect to OpenAI Realtime API
        openaiRealtimeThread = Executors.newSingleThreadExecutor();
        openaiRealtimeThread.execute(() -> {
            Log.d("OpenAIRealtime", "Running connection attempt in background thread");
            boolean connectionResult = realtimeClient.connect(apiKey, model, currentInputLanguageValue);
            Log.d("OpenAIRealtime", "Connection attempt result: " + connectionResult);
            
            if (!connectionResult) {
                Log.e("OpenAIRealtime", "Failed to connect to OpenAI Realtime API");
                mainHandler.post(() -> {
                    Log.e("OpenAIRealtime", "Connection failed, falling back to traditional recording");
                    startTraditionalRecording();
                });
            }
        });
    }

    private void startTraditionalOpenAIRealtimeRecording() {
        // Use same recording approach as traditional recording
        startTraditionalRecording();
    }

    private void stopOpenAIRealtimeRecording() {
        Log.d("OpenAIRealtime", "Stopping OpenAI Realtime streaming");
        
        if (realtimeClient != null) {
            realtimeClient.stopStreaming();
            realtimeClient.disconnect();
            realtimeClient = null;
        }
        
        if (openaiRealtimeThread != null && !openaiRealtimeThread.isShutdown()) {
            openaiRealtimeThread.shutdown();
        }
        
        isOpenAIRealtimeStreaming = false;
        isRecording = false;
        isPaused = false;
        
        // Update UI
        recordButton.setText(getDictateButtonText());
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_24, 0, 0, 0);
        recordButton.setEnabled(true);
        pauseButton.setVisibility(View.GONE);
        trashButton.setVisibility(View.GONE);
        infoCl.setVisibility(View.GONE);
    }

    private String processOpenAIRealtimeAPI(String apiKey, String model, File audioFile) {
        try {
            Log.d("OpenAIRealtimeHTTP", "=== processOpenAIRealtimeAPI() called ===");
            // For now, use the same transcription logic as regular OpenAI
            // This will be enhanced later with WebSocket streaming implementation
            Log.d("OpenAIRealtimeHTTP", "Processing audio with OpenAI Realtime API - using traditional transcription for now");
            Log.d("OpenAIRealtimeHTTP", "API Key length: " + apiKey.length());
            Log.d("OpenAIRealtimeHTTP", "Model: " + model);
            Log.d("OpenAIRealtimeHTTP", "Audio file: " + audioFile.getAbsolutePath());
            Log.d("OpenAIRealtimeHTTP", "Audio file size: " + audioFile.length() + " bytes");
            
            // Use regular OpenAI transcription endpoint for now
            Log.d("OpenAIRealtimeHTTP", "Creating OpenAI HTTP client...");
            OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl("https://api.openai.com/v1/")
                    .timeout(Duration.ofSeconds(120));

            TranscriptionCreateParams.Builder transcriptionBuilder = TranscriptionCreateParams.builder()
                    .file(audioFile.toPath())
                    .model(model)
                    .responseFormat(AudioResponseFormat.JSON);

            if (!currentInputLanguageValue.equals("detect")) {
                Log.d("OpenAIRealtimeHTTP", "Setting language: " + currentInputLanguageValue);
                transcriptionBuilder.language(currentInputLanguageValue);
            }
            
            Log.d("OpenAIRealtimeHTTP", "Sending HTTP transcription request...");
            Transcription transcription = clientBuilder.build().audio().transcriptions().create(transcriptionBuilder.build()).asTranscription();
            String result = transcription.text().trim();
            
            Log.d("OpenAIRealtimeHTTP", "HTTP transcription result: '" + result + "'");
            return result;
            
        } catch (Exception e) {
            Log.e("OpenAIRealtimeHTTP", "OpenAI Realtime API HTTP processing failed", e);
            throw new RuntimeException("OpenAI Realtime API error: " + e.getMessage(), e);
        }
    }
    
    private void handleTranscriptionResult(String resultText, String model, int provider) {
        mainHandler.post(() -> {
            usageDb.edit(model, DictateUtils.getAudioDuration(audioFile), 0, 0, provider);
            
            if (sp.getBoolean("net.devemperor.dictate.instant_prompt", false)) {
                instantPrompt = true;
            }

            if (instantPrompt || !sp.getBoolean("net.devemperor.dictate.auto_rewording_enabled", false)) {
                InputConnection inputConnection = getCurrentInputConnection();
                if (inputConnection != null) {
                    if (sp.getBoolean("net.devemperor.dictate.instant_output", false)) {
                        inputConnection.commitText(resultText, 1);
                    } else {
                        int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
                        for (int i = 0; i < resultText.length(); i++) {
                            char character = resultText.charAt(i);
                            mainHandler.postDelayed(() -> inputConnection.commitText(String.valueOf(character), 1), 
                                                   (long) (i * (20L / (speed / 5f))));
                        }
                    }
                }
            } else {
                // continue with ChatGPT API request (live prompting)
                instantPrompt = false;
                startGPTApiRequest(new PromptModel(-1, Integer.MIN_VALUE, "", resultText, false));
            }

            if (new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a")).exists()
                    && sp.getBoolean("net.devemperor.dictate.resend_button", false)) {
                resendButton.setVisibility(View.VISIBLE);
            }
        });
    }

    private void startWhisperApiRequest() {
        Log.d("DictateAPI", "=== startWhisperApiRequest() called ===");
        recordButton.setText(R.string.dictate_sending);
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_send_20, 0, 0, 0);
        recordButton.setEnabled(false);
        pauseButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_pause_24));
        pauseButton.setVisibility(View.GONE);
        trashButton.setVisibility(View.GONE);
        resendButton.setVisibility(View.GONE);
        infoCl.setVisibility(View.GONE);
        isRecording = false;
        isPaused = false;

        if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);

        String stylePrompt;
        switch (sp.getInt("net.devemperor.dictate.style_prompt_selection", 1)) {
            case 1:
                stylePrompt = DictateUtils.PROMPT_PUNCTUATION_CAPITALIZATION;
                break;
            case 2:
                stylePrompt = sp.getString("net.devemperor.dictate.style_prompt_custom_text", "");
                break;
            default:
                stylePrompt = "";
        }

        Log.d("DictateAPI", "Style prompt: " + stylePrompt);
        Log.d("DictateAPI", "Audio file exists: " + (audioFile != null && audioFile.exists()));
        if (audioFile != null) {
            Log.d("DictateAPI", "Audio file path: " + audioFile.getAbsolutePath());
            Log.d("DictateAPI", "Audio file size: " + audioFile.length() + " bytes");
        }

        speechApiThread = Executors.newSingleThreadExecutor();
        speechApiThread.execute(() -> {
            try {
                Log.d("DictateAPI", "Starting API request processing in background thread");
                int transcriptionProvider = sp.getInt("net.devemperor.dictate.transcription_provider", 0);
                String apiHost = getResources().getStringArray(R.array.dictate_api_providers_values)[transcriptionProvider];
                if (apiHost.equals("custom_server")) apiHost = sp.getString("net.devemperor.dictate.transcription_custom_host", getString(R.string.dictate_custom_server_host_hint));

                String apiKey = sp.getString("net.devemperor.dictate.transcription_api_key", sp.getString("net.devemperor.dictate.api_key", "NO_API_KEY")).replaceAll("[^ -~]", "");
                String proxyHost = sp.getString("net.devemperor.dictate.proxy_host", getString(R.string.dictate_settings_proxy_hint));

                Log.d("DictateAPI", "Transcription provider: " + transcriptionProvider);
                Log.d("DictateAPI", "API host: " + apiHost);
                Log.d("DictateAPI", "API key length: " + apiKey.length() + " (first 10 chars: " + (apiKey.length() > 10 ? apiKey.substring(0, 10) + "..." : apiKey) + ")");
                Log.d("DictateAPI", "Proxy enabled: " + sp.getBoolean("net.devemperor.dictate.proxy_enabled", false));

                String transcriptionModel = "";
                switch (transcriptionProvider) {  // for upgrading: use old transcription_model preference
                    case 0: transcriptionModel = sp.getString("net.devemperor.dictate.transcription_openai_model", sp.getString("net.devemperor.dictate.transcription_model", "gpt-4o-mini-transcribe")); break;
                    case 1: transcriptionModel = sp.getString("net.devemperor.dictate.transcription_groq_model", "whisper-large-v3-turbo"); break;
                    case 2: transcriptionModel = sp.getString("net.devemperor.dictate.transcription_custom_model", getString(R.string.dictate_custom_transcription_model_hint)); break;
                    case 3: transcriptionModel = sp.getString("net.devemperor.dictate.transcription_openai_realtime_model", "gpt-4o-mini-transcribe"); break;
                }

                Log.d("DictateAPI", "Transcription model: " + transcriptionModel);
                Log.d("DictateAPI", "Current input language: " + currentInputLanguageValue);

                // Handle OpenAI Realtime API separately
                if (transcriptionProvider == 3) {
                    Log.d("DictateAPI", "Processing OpenAI Realtime API via HTTP endpoint");
                    String resultText = processOpenAIRealtimeAPI(apiKey, transcriptionModel, audioFile);
                    Log.d("DictateAPI", "OpenAI Realtime API result: '" + resultText + "'");
                    handleTranscriptionResult(resultText, transcriptionModel, transcriptionProvider);
                    return;
                }

                Log.d("DictateAPI", "Creating OpenAI HTTP client...");
                OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                        .apiKey(apiKey)
                        .baseUrl(apiHost)
                        .timeout(Duration.ofSeconds(120));

                TranscriptionCreateParams.Builder transcriptionBuilder = TranscriptionCreateParams.builder()
                        .file(audioFile.toPath())
                        .model(transcriptionModel)
                        .responseFormat(AudioResponseFormat.JSON);  // gpt-4o-transcribe only supports json

                if (!currentInputLanguageValue.equals("detect")) transcriptionBuilder.language(currentInputLanguageValue);
                if (!stylePrompt.isEmpty()) transcriptionBuilder.prompt(stylePrompt);
                if (sp.getBoolean("net.devemperor.dictate.proxy_enabled", false)) {
                    Log.d("DictateAPI", "Applying proxy configuration");
                    if (DictateUtils.isValidProxy(proxyHost)) DictateUtils.applyProxy(clientBuilder, sp);
                }

                Log.d("DictateAPI", "Sending transcription request...");
                Transcription transcription = clientBuilder.build().audio().transcriptions().create(transcriptionBuilder.build()).asTranscription();
                String resultText = transcription.text().trim();  // Groq sometimes adds leading whitespace

                Log.d("DictateAPI", "Transcription result received: '" + resultText + "'");
                
                usageDb.edit(transcriptionModel, DictateUtils.getAudioDuration(audioFile), 0, 0, transcriptionProvider);

                if (!instantPrompt) {
                    Log.d("DictateAPI", "Processing normal transcription result");
                    // Check if auto-rewording is enabled
                    boolean autoRewordingEnabled = sp.getBoolean("net.devemperor.dictate.rewording_enabled", true) 
                                                && sp.getBoolean("net.devemperor.dictate.auto_rewording_enabled", false);
                    
                    Log.d("DictateAPI", "Auto-rewording enabled: " + autoRewordingEnabled);
                    
                    if (autoRewordingEnabled) {
                        String autoPromptIdStr = sp.getString("net.devemperor.dictate.auto_rewording_prompt_id", null);
                        if (autoPromptIdStr != null) {
                            try {
                                int autoPromptId = Integer.parseInt(autoPromptIdStr);
                                PromptModel autoPrompt = promptsDb.get(autoPromptId);
                                if (autoPrompt != null) {
                                    Log.d("DictateAPI", "Starting auto-rewording with prompt ID: " + autoPromptId);
                                    // Apply auto-rewording with the selected prompt
                                    startGPTApiRequest(new PromptModel(-2, autoPrompt.getPos(), String.valueOf(autoPrompt.getId()), resultText, autoPrompt.requiresSelection()));
                                    return; // Exit early, don't commit text directly
                                }
                            } catch (NumberFormatException e) {
                                Log.w("DictateAPI", "Invalid auto-rewording prompt ID: " + autoPromptIdStr);
                                // Invalid prompt ID, fall through to normal text output
                            }
                        }
                    }
                    
                    Log.d("DictateAPI", "Processing voice input with intelligent command detection");
                    
                    // Check if voice command processing is enabled
                    boolean voiceCommandsEnabled = sp.getBoolean("net.devemperor.dictate.voice_commands_enabled", true);
                    
                    if (voiceCommandsEnabled && voiceCommandProcessor != null) {
                        // Use intelligent voice command processing
                        String currentText = getCurrentInputText();
                        
                        voiceCommandProcessor.processVoiceInput(resultText, currentText, new VoiceCommandProcessor.VoiceCommandCallback() {
                            @Override
                            public void onCommandProcessed(String editedText) {
                                Log.d("VoiceCommand", "Command processed, replacing text with: '" + editedText + "'");
                                mainHandler.post(() -> {
                                    if (sp.getBoolean("net.devemperor.dictate.instant_output", false)) {
                                        replaceCurrentText(editedText);
                                    } else {
                                        // Gradual replacement for commands
                                        replaceCurrentText(""); // Clear first
                                        int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
                                        for (int i = 0; i < editedText.length(); i++) {
                                            char character = editedText.charAt(i);
                                            mainHandler.postDelayed(() -> {
                                                InputConnection inputConnection = getCurrentInputConnection();
                                                if (inputConnection != null) {
                                                    inputConnection.commitText(String.valueOf(character), 1);
                                                }
                                            }, (long) (i * (20L / (speed / 5f))));
                                        }
                                    }
                                });
                            }
                            
                            @Override
                            public void onDictationDetected(String text) {
                                Log.d("VoiceCommand", "Dictation detected, inserting text: '" + text + "'");
                                mainHandler.post(() -> {
                                    InputConnection inputConnection = getCurrentInputConnection();
                                    if (inputConnection != null) {
                                        if (sp.getBoolean("net.devemperor.dictate.instant_output", false)) {
                                            inputConnection.commitText(text, 1);
                                        } else {
                                            int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
                                            for (int i = 0; i < text.length(); i++) {
                                                char character = text.charAt(i);
                                                mainHandler.postDelayed(() -> inputConnection.commitText(String.valueOf(character), 1), (long) (i * (20L / (speed / 5f))));
                                            }
                                        }
                                    }
                                });
                            }
                            
                            @Override
                            public void onError(String error) {
                                Log.e("VoiceCommand", "Voice command processing failed: " + error);
                                // Fallback to normal dictation
                                mainHandler.post(() -> {
                                    InputConnection inputConnection = getCurrentInputConnection();
                                    if (inputConnection != null) {
                                        inputConnection.commitText(resultText, 1);
                                    }
                                });
                            }
                        });
                    } else {
                        Log.d("DictateAPI", "Voice commands disabled, using normal text output");
                        // Fallback to normal text output
                        InputConnection inputConnection = getCurrentInputConnection();
                        if (inputConnection != null) {
                            Log.d("DictateAPI", "Input connection available, committing text");
                            if (sp.getBoolean("net.devemperor.dictate.instant_output", false)) {
                                Log.d("DictateAPI", "Using instant output");
                                inputConnection.commitText(resultText, 1);
                            } else {
                                Log.d("DictateAPI", "Using gradual output");
                                int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
                                for (int i = 0; i < resultText.length(); i++) {
                                    char character = resultText.charAt(i);
                                    mainHandler.postDelayed(() -> inputConnection.commitText(String.valueOf(character), 1), (long) (i * (20L / (speed / 5f))));
                                }
                            }
                        } else {
                            Log.w("DictateAPI", "No input connection available - cannot output text");
                        }
                    }
                } else {
                    Log.d("DictateAPI", "Processing instant prompt request");
                    // continue with ChatGPT API request (live prompting)
                    instantPrompt = false;
                    startGPTApiRequest(new PromptModel(-1, Integer.MIN_VALUE, "", resultText, false));
                }

                if (new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a")).exists()
                        && sp.getBoolean("net.devemperor.dictate.resend_button", false)) {
                    Log.d("DictateAPI", "Showing resend button");
                    mainHandler.post(() -> resendButton.setVisibility(View.VISIBLE));
                }

            } catch (RuntimeException e) {
                Log.e("DictateAPI", "API request failed with RuntimeException", e);
                if (!(e.getCause() instanceof InterruptedIOException)) {
                    if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    mainHandler.post(() -> {
                        resendButton.setVisibility(View.VISIBLE);
                        String message = Objects.requireNonNull(e.getMessage()).toLowerCase();
                        Log.e("DictateAPI", "Error message: " + message);
                        if (message.contains("api key")) {
                            Log.e("DictateAPI", "Showing invalid API key error");
                            showInfo("invalid_api_key");
                        } else if (message.contains("quota")) {
                            Log.e("DictateAPI", "Showing quota exceeded error");
                            showInfo("quota_exceeded");
                        } else if (message.contains("audio duration") || message.contains("content size limit")) {  // gpt-o-transcribe and whisper have different limits
                            Log.e("DictateAPI", "Showing content size limit error");
                            showInfo("content_size_limit");
                        } else if (message.contains("format")) {
                            Log.e("DictateAPI", "Showing format not supported error");
                            showInfo("format_not_supported");
                        } else {
                            Log.e("DictateAPI", "Showing generic internet error");
                            showInfo("internet_error");
                        }
                    });
                } else if (e.getCause().getMessage() != null && e.getCause().getMessage().contains("timeout")) {
                    Log.e("DictateAPI", "Request timed out");
                    if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    mainHandler.post(() -> {
                        resendButton.setVisibility(View.VISIBLE);
                        showInfo("timeout");
                    });
                }
            } catch (Exception e) {
                Log.e("DictateAPI", "API request failed with unexpected exception", e);
            }

            Log.d("DictateAPI", "Resetting UI state");
            mainHandler.post(() -> {
                recordButton.setText(getDictateButtonText());
                recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
                recordButton.setEnabled(true);
            });
        });
    }

    private void startGPTApiRequest(PromptModel model) {
        mainHandler.post(() -> {
            promptsRv.setVisibility(View.GONE);
            runningPromptTv.setVisibility(View.VISIBLE);
            runningPromptTv.setText(model.getId() == -1 ? getString(R.string.dictate_live_prompt) : model.getName());
            runningPromptPb.setVisibility(View.VISIBLE);
            infoCl.setVisibility(View.GONE);
        });

        rewordingApiThread = Executors.newSingleThreadExecutor();
        rewordingApiThread.execute(() -> {
            try {
                int rewordingProvider = sp.getInt("net.devemperor.dictate.rewording_provider", 0);
                String apiHost = getResources().getStringArray(R.array.dictate_api_providers_values)[rewordingProvider];
                if (apiHost.equals("custom_server")) apiHost = sp.getString("net.devemperor.dictate.rewording_custom_host", getString(R.string.dictate_custom_server_host_hint));

                String apiKey = sp.getString("net.devemperor.dictate.rewording_api_key", sp.getString("net.devemperor.dictate.api_key", "NO_API_KEY")).replaceAll("[^ -~]", "");
                String proxyHost = sp.getString("net.devemperor.dictate.proxy_host", getString(R.string.dictate_settings_proxy_hint));

                String rewordingModel = "";
                switch (rewordingProvider) {
                    case 0: rewordingModel = sp.getString("net.devemperor.dictate.rewording_openai_model", sp.getString("net.devemperor.dictate.rewording_model", "gpt-4o-mini")); break;
                    case 1: rewordingModel = sp.getString("net.devemperor.dictate.rewording_groq_model", "llama-3.3-70b-versatile"); break;
                    case 2: rewordingModel = sp.getString("net.devemperor.dictate.rewording_custom_model", getString(R.string.dictate_custom_rewording_model_hint));
                }

                OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                        .apiKey(apiKey)
                        .baseUrl(apiHost)
                        .timeout(Duration.ofSeconds(120));

                if (sp.getBoolean("net.devemperor.dictate.proxy_enabled", false)) {
                    clientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost.split(":")[0], Integer.parseInt(proxyHost.split(":")[1]))));
                }

                String prompt = model.getPrompt();
                String rewordedText;
                if (prompt.startsWith("[") && prompt.endsWith("]")) {
                    rewordedText = prompt.substring(1, prompt.length() - 1);
                } else {
                    prompt += "\n\n" + DictateUtils.PROMPT_REWORDING_BE_PRECISE;
                    
                    // For auto-rewording (special ID), use the transcribed text passed in the prompt field
                    if (model.getId() == -2) {
                        // Auto-rewording: the "prompt" field actually contains the transcribed text to be reworded
                        String transcribedText = model.getPrompt();
                        String actualPrompt = promptsDb.get(Integer.parseInt(model.getName())).getPrompt(); // name field contains the prompt ID
                        prompt = actualPrompt + "\n\n" + DictateUtils.PROMPT_REWORDING_BE_PRECISE + "\n\n" + transcribedText;
                    } else if (model.getId() == -1) {
                        // Live prompting: use the text from the prompt field
                        prompt += "\n\n" + model.getPrompt();
                    } else {
                        // Regular prompting: use selected text
                        if (getCurrentInputConnection().getSelectedText(0) != null) {
                            prompt += "\n\n" + getCurrentInputConnection().getSelectedText(0).toString();
                        }
                    }

                    ChatCompletionCreateParams chatCompletionCreateParams = ChatCompletionCreateParams.builder()
                            .addUserMessage(prompt)
                            .model(rewordingModel)
                            .build();
                    ChatCompletion chatCompletion = clientBuilder.build().chat().completions().create(chatCompletionCreateParams);
                    rewordedText = chatCompletion.choices().get(0).message().content().orElse("");

                    if (chatCompletion.usage().isPresent()) {
                        usageDb.edit(rewordingModel, 0, chatCompletion.usage().get().promptTokens(), chatCompletion.usage().get().completionTokens(), rewordingProvider);
                    }
                }

                InputConnection inputConnection = getCurrentInputConnection();
                if (inputConnection != null) {
                    if (sp.getBoolean("net.devemperor.dictate.instant_output", false)) {
                        inputConnection.commitText(rewordedText, 1);
                    } else {
                        int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
                        for (int i = 0; i < rewordedText.length(); i++) {
                            char character = rewordedText.charAt(i);
                            mainHandler.postDelayed(() -> inputConnection.commitText(String.valueOf(character), 1), (long) (i * (20L / (speed / 5f))));
                        }
                    }
                }
            } catch (RuntimeException e) {
                if (!(e.getCause() instanceof InterruptedIOException)) {
                    if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    mainHandler.post(() -> {
                        resendButton.setVisibility(View.VISIBLE);
                        String message = Objects.requireNonNull(e.getMessage()).toLowerCase();
                        if (message.contains("api key")) {
                            showInfo("invalid_api_key");
                        } else if (message.contains("quota")) {
                            showInfo("quota_exceeded");
                        } else {
                            showInfo("internet_error");
                        }
                    });
                } else if (e.getCause().getMessage() != null && e.getCause().getMessage().contains("timeout")) {
                    if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    mainHandler.post(() -> {
                        resendButton.setVisibility(View.VISIBLE);
                        showInfo("timeout");
                    });
                }
            }

            mainHandler.post(() -> {
                promptsRv.setVisibility(View.VISIBLE);
                runningPromptTv.setVisibility(View.GONE);
                runningPromptPb.setVisibility(View.GONE);
            });
        });
    }

    private void showInfo(String type) {
        infoCl.setVisibility(View.VISIBLE);
        infoNoButton.setVisibility(View.VISIBLE);
        infoTv.setTextColor(getResources().getColor(R.color.dictate_red, getTheme()));
        switch (type) {
            case "update":
                TypedValue typedValue = new TypedValue();
                getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true);
                infoTv.setTextColor(typedValue.data);
                infoTv.setText(R.string.dictate_update_installed_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    openSettingsActivity();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> {
                    sp.edit().putInt("net.devemperor.dictate.last_version_code", BuildConfig.VERSION_CODE).apply();
                    infoCl.setVisibility(View.GONE);
                });
                break;
            case "rate":
                TypedValue typedValue2 = new TypedValue();
                getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue2, true);
                infoTv.setTextColor(typedValue2.data);
                infoTv.setText(R.string.dictate_rate_app_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=net.devemperor.dictate"));
                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(browserIntent);
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> {
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply();
                    infoCl.setVisibility(View.GONE);
                });
                break;
            case "donate":
                TypedValue typedValue3 = new TypedValue();
                getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue3, true);
                infoTv.setTextColor(typedValue3.data);
                infoTv.setText(R.string.dictate_donate_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/DevEmperor"));
                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(browserIntent);
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_donated", true)  // in case someone had Dictate installed before, he shouldn't get both messages
                            .putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> {
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_donated", true)
                            .putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply();
                    infoCl.setVisibility(View.GONE);
                });
                break;
            case "timeout":
                infoTv.setText(R.string.dictate_timeout_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "invalid_api_key":
                infoTv.setText(R.string.dictate_invalid_api_key_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    openSettingsActivity();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "quota_exceeded":
                infoTv.setText(R.string.dictate_quota_exceeded_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.openai.com/settings/organization/billing/overview"));
                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(browserIntent);
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "content_size_limit":
                infoTv.setText(R.string.dictate_content_size_limit_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "format_not_supported":
                infoTv.setText(R.string.dictate_format_not_supported_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "internet_error":
                infoTv.setText(R.string.dictate_internet_error_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "realtime_model_not_supported":
                infoTv.setText(R.string.dictate_realtime_model_error_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setText(R.string.dictate_settings);
                infoYesButton.setOnClickListener(v -> {
                    openSettingsActivity();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
        }
    }

    private String getDictateButtonText() {
        Set<String> currentInputLanguagesValues = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.dictate_default_input_languages)));
        currentInputLanguagesValues = sp.getStringSet("net.devemperor.dictate.input_languages", currentInputLanguagesValues);
        List<String> allLanguagesValues = Arrays.asList(getResources().getStringArray(R.array.dictate_input_languages_values));
        List<String> recordDifferentLanguages = Arrays.asList(getResources().getStringArray(R.array.dictate_record_different_languages));

        if (currentInputLanguagePos >= currentInputLanguagesValues.size()) currentInputLanguagePos = 0;
        sp.edit().putInt("net.devemperor.dictate.input_language_pos", currentInputLanguagePos).apply();

        currentInputLanguageValue = currentInputLanguagesValues.toArray()[currentInputLanguagePos].toString();
        return recordDifferentLanguages.get(allLanguagesValues.indexOf(currentInputLanguagesValues.toArray()[currentInputLanguagePos].toString()));
    }

    private void deleteOneCharacter() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) {
            CharSequence selectedText = inputConnection.getSelectedText(0);

            if (selectedText != null) {
                inputConnection.commitText("", 1);
            } else {
                inputConnection.deleteSurroundingText(1, 0);
            }
        }
    }

    // checks whether a point is inside a view based on its horizontal position
    private boolean isPointInsideView(float x, View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return x > location[0] && x < location[0] + view.getWidth();
    }

    private void highlightSelectedCharacter(TextView selectedView) {
        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
            TextView charView = (TextView) overlayCharactersLl.getChildAt(i);
            if (charView == selectedView) {
                charView.setBackground(AppCompatResources.getDrawable(this, R.drawable.border_textview_selected));
            } else {
                charView.setBackground(AppCompatResources.getDrawable(this, R.drawable.border_textview));
            }
        }
    }

    private void updatePartialRealtimeText(String partialText) {
        // Update a TextView or display area with partial transcription
        // This could be shown in the info area or a special partial text display
        if (!partialText.isEmpty()) {
            // For now, we'll just log it. In a full implementation, 
            // you might want to show this in a dedicated UI element
            Log.d("OpenAIRealtime", "Partial text: " + partialText);
        }
    }
    
    /**
     * Get current text from input field for voice command context
     */
    private String getCurrentInputText() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return "";
        
        try {
            // Try to get text before cursor (last 500 characters for context)
            CharSequence beforeCursor = inputConnection.getTextBeforeCursor(500, 0);
            String beforeText = beforeCursor != null ? beforeCursor.toString() : "";
            
            // Try to get selected text
            CharSequence selectedText = inputConnection.getSelectedText(0);
            String selected = selectedText != null ? selectedText.toString() : "";
            
            // Try to get text after cursor (next 100 characters for context)
            CharSequence afterCursor = inputConnection.getTextAfterCursor(100, 0);
            String afterText = afterCursor != null ? afterCursor.toString() : "";
            
            // Combine for full context
            String currentText = beforeText + selected + afterText;
            Log.d("VoiceCommand", "Current input text: '" + currentText + "'");
            return currentText;
            
        } catch (Exception e) {
            Log.w("VoiceCommand", "Failed to get current text", e);
            return "";
        }
    }
    
    /**
     * Replace current text with new text (for command execution)
     */
    private void replaceCurrentText(String newText) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;
        
        try {
            // Select all current text and replace it
            inputConnection.performContextMenuAction(android.R.id.selectAll);
            inputConnection.commitText(newText, 1);
            Log.d("VoiceCommand", "Replaced text with: '" + newText + "'");
        } catch (Exception e) {
            Log.w("VoiceCommand", "Failed to replace text", e);
            // Fallback: just append the new text
            inputConnection.commitText(newText, 1);
        }
    }

    private void handleRealtimeTranscriptionResult(String resultText) {
        Log.d("OpenAIRealtimeResult", "=== handleRealtimeTranscriptionResult() called ===");
        Log.d("OpenAIRealtimeResult", "Result text: '" + resultText + "'");
        
        String transcriptionModel = sp.getString("net.devemperor.dictate.transcription_openai_realtime_model", "gpt-4o-mini-transcribe");
        
        Log.d("OpenAIRealtimeResult", "Recording usage for model: " + transcriptionModel);
        // Log usage statistics
        usageDb.edit(transcriptionModel, 0, 0, 0, 3); // provider 3 = OpenAI Realtime
        
        boolean instantPromptEnabled = sp.getBoolean("net.devemperor.dictate.instant_prompt", false);
        boolean autoRewordingEnabled = sp.getBoolean("net.devemperor.dictate.auto_rewording_enabled", false);
        
        Log.d("OpenAIRealtimeResult", "Instant prompt enabled: " + instantPromptEnabled);
        Log.d("OpenAIRealtimeResult", "Auto rewording enabled: " + autoRewordingEnabled);
        
        if (instantPrompt || !autoRewordingEnabled) {
            Log.d("OpenAIRealtimeResult", "Processing voice input with intelligent command detection");
            
            // Check if voice command processing is enabled
            boolean voiceCommandsEnabled = sp.getBoolean("net.devemperor.dictate.voice_commands_enabled", true);
            
            if (voiceCommandsEnabled && voiceCommandProcessor != null) {
                // Use intelligent voice command processing for realtime results too
                String currentText = getCurrentInputText();
                
                voiceCommandProcessor.processVoiceInput(resultText, currentText, new VoiceCommandProcessor.VoiceCommandCallback() {
                    @Override
                    public void onCommandProcessed(String editedText) {
                        Log.d("VoiceCommand", "Realtime command processed, replacing text with: '" + editedText + "'");
                        mainHandler.post(() -> {
                            if (sp.getBoolean("net.devemperor.dictate.instant_output", false)) {
                                replaceCurrentText(editedText);
                            } else {
                                replaceCurrentText(""); // Clear first
                                int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
                                for (int i = 0; i < editedText.length(); i++) {
                                    char character = editedText.charAt(i);
                                    mainHandler.postDelayed(() -> {
                                        InputConnection inputConnection = getCurrentInputConnection();
                                        if (inputConnection != null) {
                                            inputConnection.commitText(String.valueOf(character), 1);
                                        }
                                    }, (long) (i * (20L / (speed / 5f))));
                                }
                            }
                        });
                    }
                    
                    @Override
                    public void onDictationDetected(String text) {
                        Log.d("VoiceCommand", "Realtime dictation detected, inserting text: '" + text + "'");
                        mainHandler.post(() -> {
                            InputConnection inputConnection = getCurrentInputConnection();
                            if (inputConnection != null) {
                                if (sp.getBoolean("net.devemperor.dictate.instant_output", false)) {
                                    inputConnection.commitText(text, 1);
                                } else {
                                    int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
                                    for (int i = 0; i < text.length(); i++) {
                                        char character = text.charAt(i);
                                        mainHandler.postDelayed(() -> inputConnection.commitText(String.valueOf(character), 1), (long) (i * (20L / (speed / 5f))));
                                    }
                                }
                            }
                        });
                    }
                    
                    @Override
                    public void onError(String error) {
                        Log.e("VoiceCommand", "Realtime voice command processing failed: " + error);
                        // Fallback to normal dictation
                        mainHandler.post(() -> {
                            InputConnection inputConnection = getCurrentInputConnection();
                            if (inputConnection != null) {
                                inputConnection.commitText(resultText, 1);
                            }
                        });
                    }
                });
            } else {
                Log.d("OpenAIRealtimeResult", "Voice commands disabled, using normal text output");
                InputConnection inputConnection = getCurrentInputConnection();
                if (inputConnection != null) {
                    Log.d("OpenAIRealtimeResult", "Input connection available");
                    if (sp.getBoolean("net.devemperor.dictate.instant_output", false)) {
                        Log.d("OpenAIRealtimeResult", "Using instant output");
                        inputConnection.commitText(resultText, 1);
                    } else {
                        Log.d("OpenAIRealtimeResult", "Using gradual output");
                        int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
                        for (int i = 0; i < resultText.length(); i++) {
                            char character = resultText.charAt(i);
                            mainHandler.postDelayed(() -> inputConnection.commitText(String.valueOf(character), 1), 
                                                   (long) (i * (20L / (speed / 5f))));
                        }
                    }
                } else {
                    Log.w("OpenAIRealtimeResult", "No input connection available - cannot output text");
                }
            }
        } else {
            Log.d("OpenAIRealtimeResult", "Starting GPT API request for live prompting");
            // Continue with ChatGPT API request (live prompting)
            instantPrompt = false;
            startGPTApiRequest(new PromptModel(-1, Integer.MIN_VALUE, "", resultText, false));
        }

        Log.d("OpenAIRealtimeResult", "Updating UI to show transcription complete");
        // Update UI to show transcription is complete
        recordButton.setText(getDictateButtonText());
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_24, 0, 0, 0);
        recordButton.setEnabled(true);
        pauseButton.setVisibility(View.GONE);
        trashButton.setVisibility(View.GONE);
        infoCl.setVisibility(View.GONE);
        
        Log.d("OpenAIRealtimeResult", "handleRealtimeTranscriptionResult() completed");
    }
}
