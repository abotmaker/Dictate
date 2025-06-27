package net.devemperor.dictate.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.devemperor.dictate.BuildConfig;
import net.devemperor.dictate.DictateUtils;
import net.devemperor.dictate.R;
import net.devemperor.dictate.rewording.PromptModel;
import net.devemperor.dictate.rewording.PromptsDatabaseHelper;
import net.devemperor.dictate.rewording.PromptsOverviewActivity;
import net.devemperor.dictate.usage.UsageActivity;
import net.devemperor.dictate.usage.UsageDatabaseHelper;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PreferencesFragment extends PreferenceFragmentCompat {

    SharedPreferences sp;
    UsageDatabaseHelper usageDatabaseHelper;
    PromptsDatabaseHelper promptsDb;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName("net.devemperor.dictate");
        setPreferencesFromResource(R.xml.fragment_preferences, null);
        sp = getPreferenceManager().getSharedPreferences();
        usageDatabaseHelper = new UsageDatabaseHelper(requireContext());

        Preference editPromptsPreference = findPreference("net.devemperor.dictate.edit_custom_rewording_prompts");
        if (editPromptsPreference != null) {
            editPromptsPreference.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), PromptsOverviewActivity.class));
                return true;
            });
        }

        // Setup auto-rewording prompt selection
        androidx.preference.ListPreference autoRewordingPromptPreference = findPreference("net.devemperor.dictate.auto_rewording_prompt_id");
        if (autoRewordingPromptPreference != null) {
            setupAutoRewordingPromptPreference(autoRewordingPromptPreference);
        }

        MultiSelectListPreference inputLanguagesPreference = findPreference("net.devemperor.dictate.input_languages");
        if (inputLanguagesPreference != null) {
            inputLanguagesPreference.setSummaryProvider((Preference.SummaryProvider<MultiSelectListPreference>) preference -> {
                String[] selectedLanguagesValues = preference.getValues().toArray(new String[0]);
                return Arrays.stream(selectedLanguagesValues).map(DictateUtils::translateLanguageToEmoji).collect(Collectors.joining(" "));
            });

            inputLanguagesPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                Set<String> selectedLanguages = (Set<String>) newValue;
                if (selectedLanguages.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.dictate_input_languages_empty, Toast.LENGTH_SHORT).show();
                    return false;
                }
                sp.edit().putInt("net.devemperor.dictate.input_language_pos", 0).apply();
                return true;
            });
        }

        EditTextPreference overlayCharactersPreference = findPreference("net.devemperor.dictate.overlay_characters");
        if (overlayCharactersPreference != null) {
            overlayCharactersPreference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) preference -> {
                String text = preference.getText();
                if (TextUtils.isEmpty(text)) {
                    return getString(R.string.dictate_default_overlay_characters);
                }
                return text.chars().mapToObj(c -> String.valueOf((char) c)).collect(Collectors.joining(" "));
            });

            overlayCharactersPreference.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                editText.setSingleLine(true);
                editText.setHint(R.string.dictate_default_overlay_characters);
                editText.setFilters(new InputFilter[] {new InputFilter.LengthFilter(8)});
                editText.setSelection(editText.getText().length());
            });

            overlayCharactersPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String text = (String) newValue;
                if (text.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.dictate_overlay_characters_empty, Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            });
        }

        SwitchPreference instantOutputPreference = findPreference("net.devemperor.dictate.instant_output");
        SeekBarPreference outputSpeedPreference = findPreference("net.devemperor.dictate.output_speed");
        if (instantOutputPreference != null && outputSpeedPreference != null) {
            instantOutputPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                outputSpeedPreference.setEnabled(!(Boolean) newValue);
                return true;
            });
            outputSpeedPreference.setEnabled(!instantOutputPreference.isChecked());
        }

        Preference usagePreference = findPreference("net.devemperor.dictate.usage");
        if (usagePreference != null) {
            usagePreference.setSummary(getString(R.string.dictate_usage_total_cost, usageDatabaseHelper.getTotalCost()));

            usagePreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), UsageActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        Preference apiSettingsPreference = findPreference("net.devemperor.dictate.api_settings");
        if (apiSettingsPreference != null) {
            apiSettingsPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), APISettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        Preference promptPreference = findPreference("net.devemperor.dictate.prompt");
        if (promptPreference != null) {
            promptPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), StylePromptActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        EditTextPreference proxyHostPreference = findPreference("net.devemperor.dictate.proxy_host");
        if (proxyHostPreference != null) {
            proxyHostPreference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) preference -> {
                String host = preference.getText();
                if (TextUtils.isEmpty(host)) return getString(R.string.dictate_settings_proxy_hint);
                return host;
            });

            proxyHostPreference.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
                editText.setSingleLine(true);
                editText.setHint(R.string.dictate_settings_proxy_hint);
            });

            proxyHostPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String host = (String) newValue;
                if (DictateUtils.isValidProxy(host)) return true;
                else {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.dictate_proxy_invalid_title)
                            .setMessage(R.string.dictate_proxy_invalid_message)
                            .setPositiveButton(R.string.dictate_okay, null)
                            .show();
                    return false;
                }
            });
        }

        Preference howToPreference = findPreference("net.devemperor.dictate.how_to");
        if (howToPreference != null) {
            howToPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), HowToActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        Preference cachePreference = findPreference("net.devemperor.dictate.cache");
        File[] cacheFiles = requireContext().getCacheDir().listFiles();
        if (cachePreference != null) {
            if (cacheFiles != null) {
                long cacheSize = Arrays.stream(cacheFiles).mapToLong(File::length).sum();
                cachePreference.setTitle(getString(R.string.dictate_settings_cache, cacheFiles.length, cacheSize / 1024f / 1024f));
            }

            cachePreference.setOnPreferenceClickListener(preference -> {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.dictate_cache_clear_title)
                        .setMessage(R.string.dictate_cache_clear_message)
                        .setPositiveButton(R.string.dictate_yes, (dialog, which) -> {
                            if (cacheFiles != null) {
                                for (File file : cacheFiles) {
                                    file.delete();
                                }
                            }
                            cachePreference.setTitle(getString(R.string.dictate_settings_cache, 0, 0f));
                            Toast.makeText(requireContext(), R.string.dictate_cache_cleared, Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.dictate_no, null)
                        .show();
                return true;
            });
        }

        Preference feedbackPreference = findPreference("net.devemperor.dictate.feedback");
        if (feedbackPreference != null) {
            feedbackPreference.setOnPreferenceClickListener(preference -> {
                Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
                emailIntent.setData(Uri.parse("mailto:contact@devemperor.net"));
                emailIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.dictate_feedback_subject));
                emailIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.dictate_feedback_body)
                        + "\n\nDictate User-ID: " + sp.getString("net.devemperor.dictate.user_id", "null"));
                startActivity(Intent.createChooser(emailIntent, getString(R.string.dictate_feedback_title)));
                return true;
            });
        }

        Preference githubPreference = findPreference("net.devemperor.dictate.github");
        if (githubPreference != null) {
            githubPreference.setOnPreferenceClickListener(preference -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/DevEmperor/Dictate"));
                startActivity(browserIntent);
                return true;
            });
        }

        Preference donatePreference = findPreference("net.devemperor.dictate.donate");
        if (donatePreference != null) {
            donatePreference.setOnPreferenceClickListener(preference -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/DevEmperor"));
                startActivity(browserIntent);
                return true;
            });
        }

        Preference aboutPreference = findPreference("net.devemperor.dictate.about");
        if (aboutPreference != null) {
            aboutPreference.setTitle(getString(R.string.dictate_about, BuildConfig.VERSION_NAME));
            aboutPreference.setOnPreferenceClickListener(preference -> {
                Toast.makeText(requireContext(), "User-ID: " + sp.getString("net.devemperor.dictate.user_id", "null"), Toast.LENGTH_LONG).show();
                return true;
            });
        }
    }

    private void setupAutoRewordingPromptPreference(ListPreference autoRewordingPromptPreference) {
        // Set up the preference lazily - only when clicked
        autoRewordingPromptPreference.setOnPreferenceClickListener(preference -> {
            try {
                if (promptsDb == null) {
                    promptsDb = new PromptsDatabaseHelper(requireContext());
                }
                
                List<PromptModel> allPrompts = promptsDb.getAll(false); // Get prompts that don't require text selection
                
                if (allPrompts == null || allPrompts.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.dictate_auto_rewording_no_prompts_available), Toast.LENGTH_LONG).show();
                    return false; // Don't show dialog
                }

                // Create arrays for prompt names and IDs
                String[] promptNames = new String[allPrompts.size()];
                String[] promptIds = new String[allPrompts.size()];
                
                for (int i = 0; i < allPrompts.size(); i++) {
                    PromptModel prompt = allPrompts.get(i);
                    if (prompt != null) {
                        promptNames[i] = prompt.getName() != null ? prompt.getName() : "Unnamed Prompt";
                        promptIds[i] = String.valueOf(prompt.getId());
                    } else {
                        promptNames[i] = "Invalid Prompt";
                        promptIds[i] = "0";
                    }
                }
                
                autoRewordingPromptPreference.setEntries(promptNames);
                autoRewordingPromptPreference.setEntryValues(promptIds);
                
                return true; // Show dialog
            } catch (Exception e) {
                Log.e("PreferencesFragment", "Error setting up auto-rewording prompts", e);
                Toast.makeText(requireContext(), "Error loading prompts", Toast.LENGTH_SHORT).show();
                return false; // Don't show dialog
            }
        });
        
        // Set summary provider to show selected prompt name
        autoRewordingPromptPreference.setSummaryProvider((Preference.SummaryProvider<ListPreference>) preference -> {
            try {
                String selectedValue = preference.getValue();
                if (selectedValue == null || selectedValue.isEmpty()) {
                    return getString(R.string.dictate_auto_rewording_no_prompt_selected);
                }
                
                // Try to get the prompt name from database
                if (promptsDb == null) {
                    promptsDb = new PromptsDatabaseHelper(requireContext());
                }
                
                PromptModel selectedPrompt = promptsDb.get(Integer.parseInt(selectedValue));
                if (selectedPrompt != null && selectedPrompt.getName() != null) {
                    return selectedPrompt.getName();
                }
                
                return getString(R.string.dictate_auto_rewording_no_prompt_selected);
            } catch (Exception e) {
                return getString(R.string.dictate_auto_rewording_no_prompt_selected);
            }
        });
        
        // Set initial summary
        autoRewordingPromptPreference.setSummary(getString(R.string.dictate_auto_rewording_no_prompt_selected));
    }
}
