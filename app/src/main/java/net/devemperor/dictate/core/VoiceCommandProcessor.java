package net.devemperor.dictate.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import net.devemperor.dictate.DictateUtils;
import net.devemperor.dictate.R;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceCommandProcessor {
    private static final String TAG = "VoiceCommandProcessor";
    
    private Context context;
    private SharedPreferences sp;
    private ExecutorService executorService;
    
    // Intent classification prompt
    private static final String INTENT_CLASSIFIER_PROMPT = 
        "Classify this voice input as either DICTATION or COMMAND.\n\n" +
        "DICTATION: User wants to insert text (sentences, words, content)\n" +
        "COMMAND: User wants to edit existing text (delete, replace, rewrite, format)\n\n" +
        "Examples:\n" +
        "\"Hello how are you\" → DICTATION\n" +
        "\"Delete the last word\" → COMMAND\n" +
        "\"Replace morning with evening\" → COMMAND\n" +
        "\"Make this more formal\" → COMMAND\n" +
        "\"I went to the store today\" → DICTATION\n" +
        "\"Fix the grammar in that sentence\" → COMMAND\n\n" +
        "Respond with ONLY 'DICTATION' or 'COMMAND':";
    
    // Command processing prompt
    private static final String COMMAND_PROCESSOR_PROMPT = 
        "You are a text editing assistant. The user has given a voice command to edit text.\n" +
        "Analyze the command and current text, then return the edited text.\n\n" +
        "Rules:\n" +
        "- For DELETE commands: Remove the specified content\n" +
        "- For REPLACE commands: Replace old content with new content\n" +
        "- For REWRITE commands: Rewrite according to instructions\n" +
        "- For FORMAT commands: Apply formatting changes\n" +
        "- Return ONLY the final edited text, no explanations\n" +
        "- If the command cannot be executed, return the original text unchanged\n\n";
    
    public interface VoiceCommandCallback {
        void onCommandProcessed(String result);
        void onDictationDetected(String text);
        void onError(String error);
    }
    
    public VoiceCommandProcessor(Context context) {
        this.context = context;
        this.sp = context.getSharedPreferences("net.devemperor.dictate", Context.MODE_PRIVATE);
        this.executorService = Executors.newSingleThreadExecutor();
    }
    
    /**
     * Main entry point: Intelligently process voice input
     */
    public void processVoiceInput(String voiceInput, String currentText, VoiceCommandCallback callback) {
        Log.d(TAG, "Processing voice input: '" + voiceInput + "'");
        
        executorService.execute(() -> {
            try {
                // Step 1: Classify intent using LLM
                String intent = classifyIntent(voiceInput);
                Log.d(TAG, "Classified intent: " + intent);
                
                if ("DICTATION".equals(intent)) {
                    // Regular dictation - just insert the text
                    callback.onDictationDetected(voiceInput);
                } else if ("COMMAND".equals(intent)) {
                    // Voice command - process with current text
                    String editedText = processCommand(voiceInput, currentText);
                    callback.onCommandProcessed(editedText);
                } else {
                    // Fallback to dictation if unclear
                    Log.w(TAG, "Unclear intent, defaulting to dictation");
                    callback.onDictationDetected(voiceInput);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error processing voice input", e);
                callback.onError("Failed to process voice command: " + e.getMessage());
            }
        });
    }
    
    /**
     * Step 1: Use LLM to classify if input is dictation or command
     */
    private String classifyIntent(String voiceInput) throws Exception {
        Log.d(TAG, "Classifying intent for: '" + voiceInput + "'");
        
        String prompt = INTENT_CLASSIFIER_PROMPT + "\n\nInput: \"" + voiceInput + "\"";
        String response = callLLM(prompt, 10); // Short response expected
        
        String intent = response.trim().toUpperCase();
        if (!intent.equals("DICTATION") && !intent.equals("COMMAND")) {
            Log.w(TAG, "Unexpected classification response: " + response + ", defaulting to DICTATION");
            return "DICTATION";
        }
        
        return intent;
    }
    
    /**
     * Step 2: Process command using LLM with current text context
     */
    private String processCommand(String command, String currentText) throws Exception {
        Log.d(TAG, "Processing command: '" + command + "' on text: '" + currentText + "'");
        
        String prompt = COMMAND_PROCESSOR_PROMPT + 
                       "Voice Command: \"" + command + "\"\n" +
                       "Current Text: \"" + (currentText != null ? currentText : "") + "\"\n\n" +
                       "Edited Text:";
        
        String editedText = callLLM(prompt, 500); // Allow longer response for edited text
        return editedText.trim();
    }
    
    /**
     * Call LLM (Groq or OpenAI) for processing
     */
    private String callLLM(String prompt, int maxTokens) throws Exception {
        // Get rewording provider settings (reuse existing infrastructure)
        int rewordingProvider = sp.getInt("net.devemperor.dictate.rewording_provider", 0);
        String apiHost = context.getResources().getStringArray(R.array.dictate_api_providers_values)[rewordingProvider];
        if (apiHost.equals("custom_server")) {
            apiHost = sp.getString("net.devemperor.dictate.rewording_custom_host", 
                                 context.getString(R.string.dictate_custom_server_host_hint));
        }
        
        String apiKey = sp.getString("net.devemperor.dictate.rewording_api_key", 
                                   sp.getString("net.devemperor.dictate.api_key", "NO_API_KEY"))
                         .replaceAll("[^ -~]", "");
        
        String model = "";
        switch (rewordingProvider) {
            case 0: // OpenAI
                model = sp.getString("net.devemperor.dictate.rewording_openai_model", "gpt-4o-mini");
                break;
            case 1: // Groq
                model = sp.getString("net.devemperor.dictate.rewording_groq_model", "llama-3.3-70b-versatile");
                break;
            case 2: // Custom
                model = sp.getString("net.devemperor.dictate.rewording_custom_model", "gpt-4o-mini");
                break;
        }
        
        Log.d(TAG, "Using provider: " + rewordingProvider + ", model: " + model);
        
        // Build OpenAI client (works with Groq too)
        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(apiHost)
                .timeout(Duration.ofSeconds(30));
        
        // Apply proxy if enabled
        if (sp.getBoolean("net.devemperor.dictate.proxy_enabled", false)) {
            String proxyHost = sp.getString("net.devemperor.dictate.proxy_host", "");
            if (DictateUtils.isValidProxy(proxyHost)) {
                DictateUtils.applyProxy(clientBuilder, sp);
            }
        }
        
        // Create chat completion request (matching existing code pattern)
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .addUserMessage(prompt)
                .model(model)
                .build();
        
        ChatCompletion completion = clientBuilder.build().chat().completions().create(params);
        
        String response = completion.choices().get(0).message().content().orElse("");
        Log.d(TAG, "LLM response: '" + response + "'");
        
        return response;
    }
    
    public void cleanup() {
        if (executorService != null) {
            executorService.shutdown();
        }
    }
} 