/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.core

import dev.patrickgold.florisboard.ime.nlp.ai.AiLanguageProvider
import dev.patrickgold.florisboard.lib.FlorisLocale
import kotlinx.serialization.Serializable

/**
 * Data class which represents an user-specified set of language and layout. String representations
 * of this object are stored as an Json array in the preference datastore.
 *
 * @property id The ID of this subtype.
 * @property primaryLocale The primary locale of this subtype.
 * @property secondaryLocales The secondary locales of this subtype. May be an empty list.
 * @property charactersLayout The characters layout ID used by the keyboard3 renderer, e.g.
 *   "qwerty" or "qwertz". Empty string means auto-detect based on the primary locale.
 * @property popupMapping The popup (long-press) mapping ID. Empty string means the default
 *   mapping shipped with the keyboard layout.
 * @property symbolsLayout The symbols primary layout ID. Empty string means inherit from
 *   [charactersLayout].
 * @property symbols2Layout The symbols secondary layout ID. Empty string means inherit from
 *   [charactersLayout].
 * @property numericLayout The numeric layout ID. Empty string means inherit from [charactersLayout].
 * @property numericAdvancedLayout The advanced numeric layout ID. Empty string means inherit from
 *   [charactersLayout].
 * @property numericRowLayout The number row layout ID. Empty string means inherit from
 *   [charactersLayout].
 * @property phoneLayout The phone primary layout ID. Empty string means inherit from
 *   [charactersLayout].
 * @property phone2Layout The phone secondary layout ID. Empty string means inherit from
 *   [charactersLayout].
 * @property composer The composer ID. Empty string means the default appender composer.
 * @property currencySet The currency set ID. Empty string means automatic based on the locale.
 * @property nlpProviders The NLP provider map to instantiate the correct provider for each category.
 */
@Serializable
data class Subtype(
    val id: Long,
    val primaryLocale: FlorisLocale,
    val secondaryLocales: List<FlorisLocale>,
    val charactersLayout: String = "",
    val popupMapping: String = "",
    val symbolsLayout: String = "",
    val symbols2Layout: String = "",
    val numericLayout: String = "",
    val numericAdvancedLayout: String = "",
    val numericRowLayout: String = "",
    val phoneLayout: String = "",
    val phone2Layout: String = "",
    val composer: String = "",
    val currencySet: String = "",
    val nlpProviders: SubtypeNlpProviderMap = SubtypeNlpProviderMap(),
) {
    companion object {
        /**
         * Subtype to use when prefs do not contain any valid subtypes.
         */
        val DEFAULT = Subtype(
            id = -1,
            primaryLocale = FlorisLocale.from("en", "US"),
            secondaryLocales = emptyList(),
            charactersLayout = "",
            popupMapping = "",
            symbolsLayout = "",
            symbols2Layout = "",
            numericLayout = "",
            numericAdvancedLayout = "",
            numericRowLayout = "",
            phoneLayout = "",
            phone2Layout = "",
            composer = "",
            currencySet = "",
            nlpProviders = SubtypeNlpProviderMap(),
        )
    }

    /**
     * Returns an accumulated list of all locales of this subtype.
     */
    fun locales(): List<FlorisLocale> {
        val locales = mutableListOf(primaryLocale)
        locales.addAll(secondaryLocales)
        return locales
    }

    /**
     * Converts this object into its short string representation, used for debugging. Format:
     *  <id>/<language_tag>/<currency_set_name>
     */
    fun toShortString(): String {
        val languageTag = primaryLocale.languageTag()
        return "$id/$languageTag"
    }

    fun equalsExcludingId(other: Subtype): Boolean {
        if (other.primaryLocale != primaryLocale) return false
        if (other.secondaryLocales != secondaryLocales) return false
        if (other.charactersLayout != charactersLayout) return false
        if (other.popupMapping != popupMapping) return false
        if (other.symbolsLayout != symbolsLayout) return false
        if (other.symbols2Layout != symbols2Layout) return false
        if (other.numericLayout != numericLayout) return false
        if (other.numericAdvancedLayout != numericAdvancedLayout) return false
        if (other.numericRowLayout != numericRowLayout) return false
        if (other.phoneLayout != phoneLayout) return false
        if (other.phone2Layout != phone2Layout) return false
        if (other.composer != composer) return false
        if (other.currencySet != currencySet) return false
        if (other.nlpProviders != nlpProviders) return false

        return true
    }
}

@Serializable
data class SubtypeNlpProviderMap(
    val spelling: String = AiLanguageProvider.ProviderId,
    val suggestion: String = AiLanguageProvider.ProviderId,
) {
    inline fun forEach(action: (String, String) -> Unit) {
        action("spelling", spelling)
        action("suggestion", suggestion)
    }
}
