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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import dev.patrickgold.jetpref.material.ui.JetPrefDropdown
import dev.patrickgold.jetpref.material.ui.JetPrefDropdownMenuDefaults
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

/**
 * Tracks whether the currently open locale picker should update the primary locale or append a
 * secondary locale to the subtype.
 */
private enum class LocaleSelectionTarget {
    PRIMARY,
    SECONDARY,
}

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
    var secondaryLocales by remember { mutableStateOf(initialSubtype?.secondaryLocales ?: emptyList()) }
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
    var localeSelectionTarget by remember { mutableStateOf(LocaleSelectionTarget.PRIMARY) }
    var showSelectAsError by remember { mutableStateOf(false) }
    var errorDialogStrId by remember { mutableStateOf<Int?>(null) }

    // The primary/secondary language is picked on the SelectLocale screen, which returns its
    // result through the saved state handle of this back stack entry.
    val selectLocaleScreenResult = navController.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow<String?>(SelectLocaleScreenResultLanguageTag, null)
        ?.collectAsState()
    LaunchedEffect(selectLocaleScreenResult?.value) {
        val languageTag = selectLocaleScreenResult?.value ?: return@LaunchedEffect
        val locale = FlorisLocale.fromTag(languageTag)
        when (localeSelectionTarget) {
            LocaleSelectionTarget.PRIMARY -> {
                primaryLocale = locale
                secondaryLocales = secondaryLocales.filterNot { it == locale }
                showSelectAsError = false
            }
            LocaleSelectionTarget.SECONDARY -> {
                if (locale != primaryLocale && locale !in secondaryLocales) {
                    secondaryLocales = secondaryLocales + locale
                }
            }
        }
        navController.currentBackStackEntry?.savedStateHandle?.set(
            SelectLocaleScreenResultLanguageTag,
            null,
        )
        localeSelectionTarget = LocaleSelectionTarget.PRIMARY
    }

    fun localeName(locale: FlorisLocale): String = when (displayLanguageNamesIn) {
        DisplayLanguageNamesIn.SYSTEM_LOCALE -> locale.displayName()
        DisplayLanguageNamesIn.NATIVE_LOCALE -> locale.displayName(locale)
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
                        secondaryLocales = secondaryLocales,
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
        "none" to stringRes(R.string.settings__localization__subtype_popup_mapping_none),
    )
    val suggestionProviderOptions = listOf(
        LatinLanguageProvider.ProviderId to "Latin",
        "" to stringRes(R.string.settings__localization__subtype_nlp_none),
    )
    val composerOptions = listOf(
        "" to stringRes(R.string.settings__localization__subtype_composer_default),
        "hangul-unicode" to "Hangul Unicode",
        "kana-unicode" to "Kana Unicode",
    )
    val currencySetOptions = listOf(
        "" to stringRes(R.string.settings__localization__subtype_currency_set_auto),
        "usd" to "US Dollar ($)",
        "eur" to "Euro (\u20AC)",
        "gbp" to "British Pound (\u00A3)",
        "inr" to "Indian Rupee (\u20B9)",
        "jpy" to "Japanese Yen (\u00A5)",
    )

    content {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            SubtypeSection(title = stringRes(R.string.settings__localization__subtype_group_language)) {
                Column {
                    Text(
                        text = stringRes(R.string.settings__localization__subtype_locale),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlorisDropdownLikeButton(
                        modifier = Modifier.fillMaxWidth(),
                        item = if (primaryLocale == SelectLocaleSentinel) {
                            stringRes(R.string.settings__localization__subtype_select_placeholder)
                        } else {
                            localeName(primaryLocale)
                        },
                        isError = showSelectAsError && primaryLocale == SelectLocaleSentinel,
                        appearance = JetPrefDropdownMenuDefaults.outlined(shape = ShapeDefaults.Small),
                        onClick = {
                            localeSelectionTarget = LocaleSelectionTarget.PRIMARY
                            navController.navigate(Routes.Settings.SelectLocale)
                        },
                    )
                }
                SubtypeDropdown(
                    title = stringRes(R.string.settings__localization__subtype_characters_layout),
                    value = charactersLayout,
                    options = charactersLayoutOptions,
                    onValueChange = { charactersLayout = it },
                )
                Column {
                    Text(
                        text = stringRes(R.string.settings__localization__subtype_secondary_locales),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (secondaryLocales.isEmpty()) {
                        Text(
                            modifier = Modifier.padding(vertical = 4.dp),
                            text = stringRes(R.string.settings__localization__subtype_secondary_locales_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        for (locale in secondaryLocales) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = localeName(locale),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                IconButton(onClick = {
                                    secondaryLocales = secondaryLocales - locale
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringRes(
                                            R.string.settings__localization__subtype_secondary_locales_remove,
                                        ),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    TextButton(onClick = {
                        localeSelectionTarget = LocaleSelectionTarget.SECONDARY
                        navController.navigate(Routes.Settings.SelectLocale)
                    }) {
                        Icon(
                            modifier = Modifier.padding(end = 4.dp),
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                        )
                        Text(text = stringRes(R.string.settings__localization__subtype_secondary_locales_add))
                    }
                }
            }

            SubtypeSection(title = stringRes(R.string.settings__localization__subtype_group_typing)) {
                SubtypeDropdown(
                    title = stringRes(R.string.settings__localization__subtype_suggestion_provider),
                    value = suggestionProvider,
                    options = suggestionProviderOptions,
                    onValueChange = { suggestionProvider = it },
                )
                SubtypeDropdown(
                    title = stringRes(R.string.settings__localization__subtype_popup_mapping),
                    value = popupMapping,
                    options = popupMappingOptions,
                    onValueChange = { popupMapping = it },
                )
                SubtypeDropdown(
                    title = stringRes(R.string.settings__localization__subtype_composer),
                    value = composer,
                    options = composerOptions,
                    onValueChange = { composer = it },
                )
                SubtypeDropdown(
                    title = stringRes(R.string.settings__localization__subtype_currency_set),
                    value = currencySet,
                    options = currencySetOptions,
                    onValueChange = { currencySet = it },
                )
            }

            SubtypeSection(title = stringRes(R.string.settings__localization__subtype_group_layouts)) {
                SubtypeDropdown(
                    title = stringRes(R.string.settings__localization__subtype_symbols_layout),
                    value = symbolsLayout,
                    options = layerLayoutOptions,
                    onValueChange = { symbolsLayout = it },
                )
                SubtypeDropdown(
                    title = stringRes(R.string.settings__localization__subtype_symbols2_layout),
                    value = symbols2Layout,
                    options = layerLayoutOptions,
                    onValueChange = { symbols2Layout = it },
                )
                SubtypeDropdown(
                    title = stringRes(R.string.settings__localization__subtype_numeric_layout),
                    value = numericLayout,
                    options = layerLayoutOptions,
                    onValueChange = { numericLayout = it },
                )
                SubtypeDropdown(
                    title = stringRes(R.string.settings__localization__subtype_numeric_advanced_layout),
                    value = numericAdvancedLayout,
                    options = layerLayoutOptions,
                    onValueChange = { numericAdvancedLayout = it },
                )
                SubtypeDropdown(
                    title = stringRes(R.string.settings__localization__subtype_numeric_row_layout),
                    value = numericRowLayout,
                    options = layerLayoutOptions,
                    onValueChange = { numericRowLayout = it },
                )
                SubtypeDropdown(
                    title = stringRes(R.string.settings__localization__subtype_phone_layout),
                    value = phoneLayout,
                    options = layerLayoutOptions,
                    onValueChange = { phoneLayout = it },
                )
                SubtypeDropdown(
                    title = stringRes(R.string.settings__localization__subtype_phone2_layout),
                    value = phone2Layout,
                    options = layerLayoutOptions,
                    onValueChange = { phone2Layout = it },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
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
private fun SubtypeSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
    ) {
        Text(
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(all = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SubtypeDropdown(
    title: String,
    value: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit,
) {
    val labels = remember(options) { options.map { it.second } }
    val selectedIndex = remember(value, options) {
        options.indexOfFirst { it.first == value }.coerceAtLeast(0)
    }
    JetPrefDropdown(
        modifier = Modifier.fillMaxWidth(),
        labelText = title,
        options = labels,
        selectedOptionIndex = selectedIndex,
        onSelectOption = { index ->
            options.getOrNull(index)?.let { onValueChange(it.first) }
        },
        appearance = JetPrefDropdownMenuDefaults.outlined(shape = ShapeDefaults.Small),
    )
}
