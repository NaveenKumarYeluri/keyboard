/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.settings.localization

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.FlorisScreen
import dev.patrickgold.florisboard.ime.core.DisplayLanguageNamesIn
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import org.florisboard.lib.compose.FlorisDropdownLikeButton
import org.florisboard.lib.compose.stringRes

/**
 * Sentinel locale used as the "nothing selected yet" state of the primary language field.
 */
private val SelectLocaleSentinel = FlorisLocale.from("00", "00")

@Composable
fun SubtypeEditorScreen(id: Long?) = FlorisScreen {
    title = stringRes(if (id == null) {
        R.string.settings__localization__subtype_add_title
    } else {
        R.string.settings__localization__subtype_edit_title
    })
    iconSpaceReserved = false

    val prefs by FlorisPreferenceStore
    val navController = LocalNavController.current
    val context = LocalContext.current
    val subtypeManager by context.subtypeManager()
    val displayLanguageNamesIn by prefs.localization.displayLanguageNamesIn.collectAsState()

    val initialSubtype = remember { id?.let { subtypeManager.getSubtypeById(it) } }
    var primaryLocale by remember { mutableStateOf(initialSubtype?.primaryLocale ?: SelectLocaleSentinel) }
    var showSelectAsError by remember { mutableStateOf(false) }
    var errorDialogStrId by remember { mutableStateOf<Int?>(null) }

    // The primary language is picked on the SelectLocale screen, which returns its result through
    // the saved state handle of this back stack entry.
    val selectLocaleScreenResult = navController.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow<String?>(SelectLocaleScreenResultLanguageTag, null)
        ?.collectAsState()
    LaunchedEffect(selectLocaleScreenResult?.value) {
        val languageTag = selectLocaleScreenResult?.value ?: return@LaunchedEffect
        primaryLocale = FlorisLocale.fromTag(languageTag)
    }

    actions {
        if (id != null) {
            IconButton(onClick = {
                val subtype = subtypeManager.getSubtypeById(id)
                if (subtype != null) {
                    subtypeManager.removeSubtype(subtype)
                    navController.popBackStack()
                }
            }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                )
            }
        }
    }

    bottomBar {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = 8.dp),
        ) {
            TextButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringRes(R.string.action__cancel))
            }
            Button(
                onClick = {
                    if (primaryLocale == SelectLocaleSentinel) {
                        showSelectAsError = true
                        errorDialogStrId = R.string.settings__localization__subtype_error_fields_no_value
                        return@Button
                    }
                    val subtype = Subtype(
                        id = initialSubtype?.id ?: -1,
                        primaryLocale = primaryLocale,
                        secondaryLocales = emptyList(),
                    )
                    if (id == null) {
                        if (!subtypeManager.addSubtype(subtype)) {
                            errorDialogStrId = R.string.settings__localization__subtype_error_already_exists
                            return@Button
                        }
                    } else {
                        subtypeManager.modifySubtypeWithSameId(subtype)
                    }
                    navController.popBackStack()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringRes(R.string.action__save))
            }
        }
    }

    content {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = 16.dp),
        ) {
            Text(
                text = stringRes(R.string.settings__localization__subtype_locale),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            FlorisDropdownLikeButton(
                item = if (primaryLocale == SelectLocaleSentinel) {
                    stringRes(R.string.settings__localization__subtype_select_placeholder)
                } else {
                    when (displayLanguageNamesIn) {
                        DisplayLanguageNamesIn.SYSTEM_LOCALE -> primaryLocale.displayName()
                        DisplayLanguageNamesIn.NATIVE_LOCALE -> primaryLocale.displayName(primaryLocale)
                    }
                },
                isError = showSelectAsError && primaryLocale == SelectLocaleSentinel,
                onClick = {
                    navController.navigate(Routes.Settings.SelectLocale)
                },
            )
        }
    }

    errorDialogStrId?.let { errorStrId ->
        JetPrefAlertDialog(
            title = stringRes(R.string.error__title),
            confirmLabel = stringRes(R.string.action__ok),
            onConfirm = { errorDialogStrId = null },
            dismissLabel = stringRes(R.string.action__cancel),
            onDismiss = { errorDialogStrId = null },
        ) {
            Text(text = stringRes(errorStrId))
        }
    }
}
