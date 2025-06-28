package net.devemperor.dictate.core;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * WebSocket client for OpenAI Realtime API
 * Handles real-time audio streaming and transcription
 */
public class OpenAIRealtimeClient {
    private static final String TAG = "OpenAIRealtimeClient";
    private static final String REALTIME_API_URL = "wss://api.openai.com/v1/realtime?intent=transcription";
    
    private WebSocket webSocket;
    private OkHttpClient client;
    private RealtimeCallback callback;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean isRecording = false;
    private CountDownLatch connectionLatch;
    private String pendingModel = null;
    private String pendingLanguage = null;
    
    // Audio configuration for PCM16 format
    private static final int SAMPLE_RATE = 24000; // 24kHz as required by OpenAI
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

    public interface RealtimeCallback {
        void onTranscriptionDelta(String delta);
        void onTranscriptionCompleted(String transcript);
        void onError(String error);
        void onConnectionEstablished();
        void onConnectionClosed();
    }

    public OpenAIRealtimeClient(RealtimeCallback callback) {
        this.callback = callback;
        Log.d(TAG, "=== OpenAIRealtimeClient constructor called ===");
        try {
            Log.d(TAG, "Creating OkHttpClient...");
            this.client = new OkHttpClient.Builder()
                    .pingInterval(30, TimeUnit.SECONDS)
                    .build();
            Log.d(TAG, "OkHttpClient created successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create OkHttpClient", e);
            // Create a basic client as fallback
            this.client = new OkHttpClient();
            Log.d(TAG, "Created fallback OkHttpClient");
        }
    }



    public boolean connect(String apiKey, String model, String language) {
        Log.d(TAG, "=== connect() called ===");
        
        // Pre-flight checks
        Log.d(TAG, "Step 0: Pre-flight checks");
        if (client == null) {
            Log.e(TAG, "OkHttpClient is null - cannot proceed");
            return false;
        }
        Log.d(TAG, "OkHttpClient is available");
        
        if (apiKey == null || apiKey.trim().isEmpty()) {
            Log.e(TAG, "API key is null or empty - cannot proceed");
            return false;
        }
        Log.d(TAG, "API key is valid");
        
        if (model == null || model.trim().isEmpty()) {
            Log.e(TAG, "Model is null or empty - cannot proceed");
            return false;
        }
        Log.d(TAG, "Model is valid");
        
        try {
            Log.d(TAG, "Step 1: Logging parameters");
            Log.d(TAG, "Model: " + model);
            Log.d(TAG, "Language: " + language);
            Log.d(TAG, "API Key length: " + apiKey.length());
            
            Log.d(TAG, "Step 2: Creating ConnectionLatch");
            connectionLatch = new CountDownLatch(1);
            
            Log.d(TAG, "Step 3: Building WebSocket URL");
            String url = REALTIME_API_URL;
            Log.d(TAG, "WebSocket URL: " + url);
            
            Log.d(TAG, "Step 4: Building Request with API key");
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("OpenAI-Beta", "realtime=v1")
                    .build();

            Log.d(TAG, "Step 5: Creating WebSocket connection");
            Log.d(TAG, "Creating RealtimeWebSocketListener...");
            RealtimeWebSocketListener listener = new RealtimeWebSocketListener();
            Log.d(TAG, "RealtimeWebSocketListener created successfully");
            
            Log.d(TAG, "Creating WebSocket with OkHttpClient...");
            webSocket = client.newWebSocket(request, listener);
            Log.d(TAG, "WebSocket created successfully");
            
            Log.d(TAG, "Step 6: Waiting for connection (timeout: 10 seconds)");
            // Wait for connection to be established
            boolean connected = connectionLatch.await(10, TimeUnit.SECONDS);
            
            Log.d(TAG, "Step 7: Connection attempt result: " + connected);
            
            if (connected) {
                Log.d(TAG, "Step 8: Connection successful, will configure session via update");
                // Store model and language for session update
                this.pendingModel = model;
                this.pendingLanguage = language;
                // Configure session immediately with improved settings
                configureSession(model, language);
                return true;
            } else {
                Log.e(TAG, "Step 8: Connection timeout after 10 seconds");
                return false;
            }
            
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "IllegalArgumentException in connect()", e);
            return false;
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in connect() - network permission issue?", e);
            return false;
        } catch (InterruptedException e) {
            Log.e(TAG, "InterruptedException in connect() - thread interrupted", e);
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected exception in connect()", e);
            return false;
        }
    }

    private void configureSession(String model, String language) {
        try {
            Log.d(TAG, "=== configureSession() called ===");
            Log.d(TAG, "Configuring transcription session with model: " + model + ", language: " + language);
            
            // Use optimized transcription session update format
            JSONObject sessionUpdate = new JSONObject();
            sessionUpdate.put("type", "transcription_session.update");
            
            // Create nested session object with improved settings
            JSONObject sessionObj = new JSONObject();
            
            // Configure input audio transcription (matching working TypeScript implementation)
            JSONObject inputAudioTranscription = new JSONObject();
            inputAudioTranscription.put("model", "gpt-4o-mini-transcribe"); // Use working model from TypeScript
            inputAudioTranscription.put("language", "en"); // Use correct parameter name
            sessionObj.put("input_audio_transcription", inputAudioTranscription);
            
            // Voice Activity Detection (matching working TypeScript implementation)
            JSONObject turnDetection = new JSONObject();
            turnDetection.put("type", "server_vad");
            turnDetection.put("threshold", 0.5);
            turnDetection.put("prefix_padding_ms", 600);
            turnDetection.put("silence_duration_ms", 1800); // Use value from working TypeScript
            sessionObj.put("turn_detection", turnDetection);
            
            // Add session object to the main update
            sessionUpdate.put("session", sessionObj);
            
            Log.d(TAG, "Sending optimized transcription session configuration: " + sessionUpdate.toString());
            webSocket.send(sessionUpdate.toString());
            Log.d(TAG, "Transcription session configuration sent successfully");
            
        } catch (JSONException e) {
            Log.e(TAG, "Failed to configure transcription session", e);
        }
    }

    public void startStreaming() {
        Log.d(TAG, "=== startStreaming() called ===");
        if (isRecording) {
            Log.w(TAG, "Already recording, ignoring startStreaming call");
            return;
        }
        
        try {
            Log.d(TAG, "Creating AudioRecord with:");
            Log.d(TAG, "  Sample rate: " + SAMPLE_RATE);
            Log.d(TAG, "  Channel config: " + CHANNEL_CONFIG);
            Log.d(TAG, "  Audio format: " + AUDIO_FORMAT);
            Log.d(TAG, "  Buffer size: " + (BUFFER_SIZE * 4));
            
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE * 4
            );
            
            int state = audioRecord.getState();
            Log.d(TAG, "AudioRecord state: " + state + " (INITIALIZED=" + AudioRecord.STATE_INITIALIZED + ")");
            
            if (state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed - state: " + state);
                return;
            }
            
            Log.d(TAG, "Starting AudioRecord recording...");
            audioRecord.startRecording();
            isRecording = true;
            
            Log.d(TAG, "Creating audio streaming thread...");
            audioThread = new Thread(this::streamAudio);
            audioThread.start();
            
            Log.d(TAG, "Audio streaming started successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio streaming", e);
        }
    }

    private void streamAudio() {
        Log.d(TAG, "=== streamAudio() thread started ===");
        byte[] buffer = new byte[BUFFER_SIZE];
        int audioDataCount = 0;
        
        while (isRecording && audioRecord != null) {
            try {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                audioDataCount++;
                
                if (bytesRead > 0) {
                    // Log every 100th audio chunk to avoid spam
                    if (audioDataCount % 100 == 0) {
                        Log.d(TAG, "Streaming audio chunk #" + audioDataCount + " (" + bytesRead + " bytes)");
                    }
                    
                    // Encode audio data as base64
                    String audioBase64 = Base64.encodeToString(buffer, 0, bytesRead, Base64.NO_WRAP);
                    
                    // Send audio data to OpenAI
                    JSONObject audioAppend = new JSONObject();
                    audioAppend.put("type", "input_audio_buffer.append");
                    audioAppend.put("audio", audioBase64);
                    
                    webSocket.send(audioAppend.toString());
                } else {
                    Log.w(TAG, "AudioRecord read returned " + bytesRead + " bytes");
                }
                
            } catch (JSONException e) {
                Log.e(TAG, "Failed to send audio data (JSON error)", e);
            } catch (Exception e) {
                Log.e(TAG, "Audio streaming error", e);
                break;
            }
        }
        
        Log.d(TAG, "streamAudio() thread ended. Total audio chunks sent: " + audioDataCount);
    }

    public void stopStreaming() {
        isRecording = false;
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio record", e);
            }
        }
        
        if (audioThread != null) {
            try {
                audioThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Commit the audio buffer for transcription sessions
        try {
            JSONObject commit = new JSONObject();
            commit.put("type", "input_audio_buffer.commit");
            webSocket.send(commit.toString());
            
            Log.d(TAG, "Audio buffer committed for transcription");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to commit audio buffer", e);
        }
        
        Log.d(TAG, "Audio streaming stopped");
    }

    public void disconnect() {
        stopStreaming();
        
        if (webSocket != null) {
            webSocket.close(1000, "Client disconnecting");
            webSocket = null;
        }
        
        if (client != null) {
            client.dispatcher().executorService().shutdown();
        }
    }

    private class RealtimeWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(TAG, "=== WebSocket onOpen() ===");
            Log.d(TAG, "WebSocket connection opened successfully");
            Log.d(TAG, "Response code: " + response.code());
            Log.d(TAG, "Response message: " + response.message());
            
            connectionLatch.countDown();
            if (callback != null) {
                Log.d(TAG, "Calling callback.onConnectionEstablished()");
                callback.onConnectionEstablished();
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Log.d(TAG, "=== WebSocket onMessage() ===");
            Log.d(TAG, "Received message: " + text);
            
            try {
                JSONObject message = new JSONObject(text);
                String type = message.optString("type", "unknown");
                
                Log.d(TAG, "Message type: " + type);
                
                switch (type) {
                    case "session.created":
                    case "transcription_session.created":
                        Log.d(TAG, "Session/Transcription session created successfully (REST API approach)");
                        break;
                        
                    case "conversation.item.input_audio_transcription.delta":
                        String delta = message.optString("delta", "");
                        Log.d(TAG, "Received transcription delta: '" + delta + "'");
                        
                        // Monitor for incorrect language detection
                        String detectedLanguage = message.optString("language", "");
                        if (!detectedLanguage.isEmpty() && !detectedLanguage.equals("en")) {
                            Log.w(TAG, "Incorrect language detected: " + detectedLanguage + ". Forcing English.");
                            // Send correction to force English
                            try {
                                JSONObject correction = new JSONObject();
                                correction.put("type", "transcription_session.update");
                                JSONObject sessionObj = new JSONObject();
                                JSONObject inputAudioTranscription = new JSONObject();
                                inputAudioTranscription.put("language", "en");
                                sessionObj.put("input_audio_transcription", inputAudioTranscription);
                                correction.put("session", sessionObj);
                                webSocket.send(correction.toString());
                                Log.d(TAG, "Sent language correction to force English");
                            } catch (JSONException e) {
                                Log.e(TAG, "Failed to send language correction", e);
                            }
                        }
                        
                        if (callback != null && !delta.isEmpty()) {
                            callback.onTranscriptionDelta(delta);
                        }
                        break;
                        
                    case "conversation.item.input_audio_transcription.completed":
                        String transcript = message.optString("transcript", "");
                        String completedLanguage = message.optString("language", "");
                        Log.d(TAG, "Received completed transcription: '" + transcript + "' (language: " + completedLanguage + ")");
                        
                        // Monitor completed transcription language as well
                        if (!completedLanguage.isEmpty() && !completedLanguage.equals("en")) {
                            Log.w(TAG, "Completed transcription in wrong language: " + completedLanguage);
                        }
                        
                        if (callback != null) {
                            callback.onTranscriptionCompleted(transcript);
                        }
                        break;
                        
                    case "error":
                        JSONObject errorObj = message.optJSONObject("error");
                        String error;
                        if (errorObj != null) {
                            error = errorObj.toString();
                        } else {
                            error = message.optString("error", "Unknown error");
                        }
                        Log.e(TAG, "Received error message: " + error);
                        if (callback != null) {
                            callback.onError(error);
                        }
                        break;
                        
                    case "session.updated":
                    case "transcription_session.updated":
                        Log.d(TAG, "Session/Transcription session updated successfully");
                        break;
                        
                    default:
                        Log.d(TAG, "Unhandled message type: " + type);
                        break;
                }
                
            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse message: " + text, e);
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            // Handle binary messages if needed
            Log.d(TAG, "Received binary message: " + bytes.size() + " bytes");
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "=== WebSocket onClosing() ===");
            Log.d(TAG, "WebSocket closing: code=" + code + ", reason=" + reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "=== WebSocket onClosed() ===");
            Log.d(TAG, "WebSocket closed: code=" + code + ", reason=" + reason);
            if (callback != null) {
                Log.d(TAG, "Calling callback.onConnectionClosed()");
                callback.onConnectionClosed();
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.e(TAG, "=== WebSocket onFailure() ===", t);
            Log.e(TAG, "WebSocket failure: " + t.getMessage());
            if (response != null) {
                Log.e(TAG, "Failure response code: " + response.code());
                Log.e(TAG, "Failure response message: " + response.message());
            }
            if (callback != null) {
                Log.d(TAG, "Calling callback.onError()");
                callback.onError("Connection failed: " + t.getMessage());
            }
        }
    }
} 