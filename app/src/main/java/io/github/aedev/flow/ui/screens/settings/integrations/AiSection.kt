package io.github.aedev.flow.ui.screens.settings.integrations

import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingsListScope
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.screens.settings.index.IntegrationsIndex

/** The Gemini key and model the Flow AI assistant runs on. */
internal fun SettingsListScope.aiSection(
    viewModel: IntegrationsViewModel,
    enabled: Boolean,
    hasKey: Boolean,
    model: String,
    onEditKey: () -> Unit,
    onEditModel: () -> Unit,
) {
    group(key = "integrations.ai.group", header = R.string.flow_ai_settings_header) {
        switch(IntegrationsIndex.aiEnabled, viewModel.aiEnabled, viewModel::setAiEnabled)
        switch(IntegrationsIndex.aiChapters, viewModel.aiChapters, viewModel::setAiChapters, enabled = enabled)
        row(IntegrationsIndex.geminiKey.key) { shape ->
            FlowNavRow(
                title = stringResource(IntegrationsIndex.geminiKey.title),
                supportingText = stringResource(if (hasKey) R.string.flow_ai_key_set else R.string.flow_ai_key_not_set),
                enabled = enabled,
                onClick = onEditKey,
                shape = shape,
            )
        }
        row(IntegrationsIndex.geminiModel.key) { shape ->
            FlowNavRow(
                title = stringResource(IntegrationsIndex.geminiModel.title),
                supportingText = model,
                enabled = enabled,
                onClick = onEditModel,
                shape = shape,
            )
        }
    }
}
