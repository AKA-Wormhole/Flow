package io.github.aedev.flow.data.ai

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.KeystoreSecretBox
import io.github.aedev.flow.data.local.safePreferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.aiDataStore by safePreferencesDataStore(name = "ai_assistant")

/**
 * The user's own Gemini API key and model. The key is sealed with the Android Keystore before it is
 * written, the same way the proxy password and scrobbling secrets are.
 */
@Singleton
class GeminiSettingsStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val apiKeyKey = stringPreferencesKey("gemini_api_key")
        private val modelKey = stringPreferencesKey("gemini_model")
        private val enabledKey = booleanPreferencesKey("ai_enabled")
        private val chaptersKey = booleanPreferencesKey("ai_chapters_enabled")

        /** Off hides every Flow AI button and sheet and stops the assistant answering. */
        val enabled: Flow<Boolean> =
            context.aiDataStore.data
                .map { it[enabledKey] ?: true }
                .distinctUntilChanged()

        suspend fun isEnabled(): Boolean = enabled.first()

        /**
         * Whether videos that come without chapters get AI-made ones on the seek bar. Each one sends that
         * video's captions to Gemini, so it has its own switch next to the assistant's.
         */
        val chaptersEnabled: Flow<Boolean> =
            context.aiDataStore.data
                .map { it[chaptersKey] ?: true }
                .distinctUntilChanged()

        suspend fun isChaptersEnabled(): Boolean = chaptersEnabled.first()

        suspend fun setChaptersEnabled(value: Boolean) {
            context.aiDataStore.edit { it[chaptersKey] = value }
        }

        suspend fun setEnabled(value: Boolean) {
            context.aiDataStore.edit { it[enabledKey] = value }
        }

        val apiKey: Flow<String> =
            context.aiDataStore.data
                .map { KeystoreSecretBox.open(it[apiKeyKey]) }
                .flowOn(Dispatchers.IO)
                .distinctUntilChanged()

        val model: Flow<String> =
            context.aiDataStore.data
                .map { it[modelKey]?.takeIf(String::isNotBlank) ?: DEFAULT_MODEL }
                .distinctUntilChanged()

        suspend fun currentApiKey(): String = apiKey.first()

        suspend fun currentModel(): String = model.first()

        suspend fun setApiKey(value: String) {
            val trimmed = value.trim()
            val sealed = if (trimmed.isEmpty()) "" else withContext(Dispatchers.IO) { KeystoreSecretBox.seal(trimmed) }
            context.aiDataStore.edit { preferences ->
                if (sealed.isEmpty()) preferences.remove(apiKeyKey) else preferences[apiKeyKey] = sealed
            }
        }

        suspend fun setModel(value: String) {
            val trimmed = value.trim()
            context.aiDataStore.edit { preferences ->
                if (trimmed.isEmpty()) preferences.remove(modelKey) else preferences[modelKey] = trimmed
            }
        }

        companion object {
            const val DEFAULT_MODEL = "gemini-2.5-flash"
        }
    }
