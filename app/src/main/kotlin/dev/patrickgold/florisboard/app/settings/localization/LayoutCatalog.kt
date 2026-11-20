/*
 * Copyright (C) 2026 The FlorisBoard Contributors
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

/**
 * Layout choices exposed in the subtype editor. IDs and labels mirror the layout extension shipped
 * with recent FlorisBoard alphas. The K3 loader maps these IDs to generated touch layer overrides.
 */
object LayoutCatalog {
    val characters: List<Pair<String, String>> = listOf(
        "qwerty" to "QWERTY",
        "qwertz" to "QWERTZ",
        "telugu" to "Telugu (Native)",
        "telugu-phonetic" to "Telugu (Phonetic)",
    )

    val symbols: List<Pair<String, String>> = listOf(
        "western" to "Western",
        "western_additional_symbols" to "Western (Additional symbols)",
        "western_samsung" to "Western (Samsung)",
        "eastern" to "Eastern",
        "cjk" to "CJK",
        "ipa" to "International Phonetic Alphabet",
        "neo2" to "Neo2",
        "persian" to "Persian",
        "armenian" to "Armenian",
    )

    val symbols2: List<Pair<String, String>> = listOf(
        "western" to "Western",
        "western_samsung" to "Western (Samsung)",
        "eastern" to "Eastern",
        "cjk" to "CJK",
        "ipa" to "International Phonetic Alphabet",
        "persian" to "Persian",
    )

    val numeric: List<Pair<String, String>> = listOf(
        "western_arabic" to "Western Arabic",
        "western_arabic_pc" to "Western Arabic (PC)",
    )

    val numericAdvanced: List<Pair<String, String>> = listOf(
        "western_arabic" to "Western Arabic",
        "western_arabic_pc" to "Western Arabic (PC)",
        "bengali" to "Bengali",
    )

    val numericRow: List<Pair<String, String>> = listOf(
        "western_arabic" to "Western Arabic",
        "bengali" to "Bengali",
        "devanagari" to "Devanagari",
        "eastern_arabic" to "Eastern Arabic",
        "gujarati" to "Gujarati",
        "gurmukhi" to "Gurmukhi",
        "kannada" to "Kannada",
        "malayalam" to "Malayalam",
        "oriya" to "Odia",
        "persian" to "Persian",
        "tamil" to "Tamil",
        "telugu" to "Telugu",
        "thai" to "Thai",
        "cjk" to "CJK",
        "czech" to "Czech",
        "neo2" to "Neo2",
        "warang_citi" to "Warang Citi",
    )

    val phone: List<Pair<String, String>> = listOf(
        "telpad" to "Simple telpad input",
    )

    val phone2: List<Pair<String, String>> = listOf(
        "telpad" to "Simple telpad input (advanced controls)",
    )
}
