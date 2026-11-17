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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import dev.patrickgold.florisboard.ime.core.SubtypeNlpProviderMap
import dev.patrickgold.florisboard.ime.nlp.latin.LatinLanguageProvider
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

/**
 * All keyboard layout IDs which are currently shipped by the foundation keyboard extension.
 * Empty string means "automatic"/"inherit" depending on the field.
 */
private val KeyboardLayoutIds = listOf("qwerty", "qwertz")

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
    var charactersLayout by remember { mutableStateOf(initialSubtype?.charactersLayout ?: "") }
    var popupMapping by remember { mutableStateOf(initialSubtype?.popupMapping ?: "") }
    var symbolsLayout by remember { mutableStateOf(initialSubtype?.symbolsLayout ?: "") }
    var symbols2Layout by remember { mutableStateOf(initialSubtype?.symbols2Layout ?: "") }
    var numericLayout by remember { mutableStateOf(initialSubtype?.numericLayout ?: "") }
    var numericAdvancedLayout by remember { mutableStateOf(initialSubtype?.numericAdvancedLayout ?: "") }
    var numericRowLayout by remember { mutableStateOf(initialSubtype?.numericRowLayout ?: "") }
    var phoneLayout by remember { mutableStateOf(initialSubtype?.phoneLayout ?: "") }
    var phone2Layout by remember { mutableStateOf(initialSubtype?.phone2Layout ?: "") }
    var composer by remember { mutableStateOf(initialSubtype?.composer ?: "") }
    var currencySet by remember { mutableStateOf(initialSubtype?.currencySet ?: "") }
    var suggestionProvider by remember {
        mutableStateOf(initialSubtype?.nlpProviders?.suggestion ?: LatinLanguageProvider.ProviderId)
    }
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
            if (id != null) {
                TextButton(
                    onClick = {
                        subtypeManager.switchToSubtypeById(id)
                        navController.popBackStack()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringRes(R.string.settings__localization__subtype_set_active))
                }
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
                        secondaryLocales = initialSubtype?.secondaryLocales ?: emptyList(),
                        charactersLayout = charactersLayout,
                        popupMapping = popupMapping,
                        symbolsLayout = symbolsLayout,
                        symbols2Layout = symbols2Layout,
                        numericLayout = numericLayout,
                        numericAdvancedLayout = numericAdvancedLayout,
                        numericRowLayout = numericRowLayout,
                        phoneLayout = phoneLayout,
                        phone2Layout = phone2Layout,
                        composer = composer,
                        currencySet = currencySet,
                        nlpProviders = SubtypeNlpProviderMap(
                            spelling = suggestionProvider,
                            suggestion = suggestionProvider,
                        ),
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

    val charactersLayoutOptions = listOf(
        "" to stringRes(R.string.settings__localization__subtype_layout_auto),
        "qwerty" to "QWERTY",
        "qwertz" to "QWERTZ",
    )
    val layerLayoutOptions = buildList {
        add("" to stringRes(R.string.settings__localization__subtype_layout_inherit))
        for (layoutId in KeyboardLayoutIds) {
            add(layoutId to layoutId.uppercase())
        }
    }
    val popupMappingOptions = listOf(
        "" to stringRes(R.string.settings__localization__subtype_popup_mapping_default),
    )
    val suggestionProviderOptions = listOf(
        LatinLanguageProvider.ProviderId to "Latin",
        "" to stringRes(R.string.settings__localization__subtype_nlp_none),
    )
    val composerOptions = listOf(
        "" to stringRes(R.string.settings__localization__subtype_composer_default),
    )
    val currencySetOptions = listOf(
        "" to stringRes(R.string.settings__localization__subtype_currency_set_auto),
    )

    content {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = 16.dp),
        ) {
            SubtypeProperty(stringRes(R.string.settings__localization__subtype_locale)) {
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
            SubtypeProperty(stringRes(R.string.settings__localization__subtype_popup_mapping)) {
                SubtypeDropdown(
                    value = popupMapping,
                    options = popupMappingOptions,
                    onValueChange = { popupMapping = it },
                )
            }
            SubtypeProperty(stringRes(R.string.settings__localization__subtype_characters_layout)) {
                SubtypeDropdown(
                    value = charactersLayout,
                    options = charactersLayoutOptions,
                    onValueChange = { charactersLayout = it },
                )
            }

            SubtypeGroupSpacer()

            SubtypeProperty(stringRes(R.string.settings__localization__subtype_suggestion_provider)) {
                SubtypeDropdown(
                    value = suggestionProvider,
                    options = suggestionProviderOptions,
                    onValueChange = { suggestionProvider = it },
                )
            }

            SubtypeGroupSpacer()

            SubtypeProperty(stringRes(R.string.settings__localization__subtype_symbols_layout)) {
                SubtypeDropdown(
                    value = symbolsLayout,
                    options = layerLayoutOptions,
                    onValueChange = { symbolsLayout = it },
                )
            }
            SubtypeProperty(stringRes(R.string.settings__localization__subtype_symbols2_layout)) {
                SubtypeDropdown(
                    value = symbols2Layout,
                    options = layerLayoutOptions,
                    onValueChange = { symbols2Layout = it },
                )
            }
            SubtypeProperty(stringRes(R.string.settings__localization__subtype_composer)) {
                SubtypeDropdown(
                    value = composer,
                    options = composerOptions,
                    onValueChange = { composer = it },
                )
            }
            SubtypeProperty(stringRes(R.string.settings__localization__subtype_currency_set)) {
                SubtypeDropdown(
                    value = currencySet,
                    options = currencySetOptions,
                    onValueChange = { currencySet = it },
                )
            }

            SubtypeGroupSpacer()

            SubtypeProperty(stringRes(R.string.settings__localization__subtype_numeric_layout)) {
                SubtypeDropdown(
                    value = numericLayout,
                    options = layerLayoutOptions,
                    onValueChange = { numericLayout = it },
                )
            }
            SubtypeProperty(stringRes(R.string.settings__localization__subtype_numeric_advanced_layout)) {
                SubtypeDropdown(
                    value = numericAdvancedLayout,
                    options = layerLayoutOptions,
                    onValueChange = { numericAdvancedLayout = it },
                )
            }
            SubtypeProperty(stringRes(R.string.settings__localization__subtype_numeric_row_layout)) {
                SubtypeDropdown(
                    value = numericRowLayout,
                    options = layerLayoutOptions,
                    onValueChange = { numericRowLayout = it },
                )
            }

            SubtypeGroupSpacer()

            SubtypeProperty(stringRes(R.string.settings__localization__subtype_phone_layout)) {
                SubtypeDropdown(
                    value = phoneLayout,
                    options = layerLayoutOptions,
                    onValueChange = { phoneLayout = it },
                )
            }
            SubtypeProperty(stringRes(R.string.settings__localization__subtype_phone2_layout)) {
                SubtypeDropdown(
                    value = phone2Layout,
                    options = layerLayoutOptions,
                    onValueChange = { phone2Layout = it },
                )
            }
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

@Composable
private fun SubtypeProperty(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun SubtypeDropdown(
    value: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == value }?.second ?: value
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for ((optionValue, optionLabel) in options) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = optionLabel,
                            fontWeight = if (optionValue == value) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onValueChange(optionValue)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SubtypeGroupSpacer() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp),
    )
}
