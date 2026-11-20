/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.lowercase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.k3lp.runtime.K3Content
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Latin script language provider which provides word suggestions, autocorrect candidates and spell checking
 * based on an on-device frequency dictionary (see the JSON assets in the `ime/dict` folder) and the user dictionary
 * databases managed by [DictionaryManager].
 *
 * All processing is done fully offline on the device.
 */
class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"

        private const val LONG_WORD_THRESHOLD = 5
        private const val MAX_EDIT_DISTANCE_SHORT = 1
        private const val MAX_EDIT_DISTANCE_LONG = 2
        private const val CONFIDENCE_DECAY_PER_DISTANCE = 0.25

        private const val DICT_ASSET_PATH = "ime/dict"

        private val DEFAULT_DICTIONARY = Dictionary(emptyMap(), 255)
    }

    private val appContext by context.appContext()

    private val dictionaryManager: DictionaryManager? by lazy {
        runCatching { DictionaryManager.default() }.getOrNull()
    }

    private class Dictionary(val words: Map<String, Int>, val maxFrequency: Int)

    // Frequency dictionaries, indexed by language code. Loaded lazily per language on preload or first lookup.
    private val dictionaries = ConcurrentHashMap<String, Dictionary>()
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    // Romanized aliases for the Telugu phonetic layout: raw Latin word -> canonical Telugu word.
    private val aliasDictionaries = ConcurrentHashMap<String, Map<String, String>>()
    private val aliasDataSerializer = MapSerializer(String.serializer(), String.serializer())

    override val providerId = ProviderId

    override suspend fun create() {
        // Here we initialize our provider, set up all things which are not language dependent.
    }

    private fun loadDictionary(language: String): Dictionary {
        // Prefer a language specific dictionary asset, fall back to the bundled English test dictionary.
        val assetName = "$DICT_ASSET_PATH/$language.json"
        return runCatching {
            val rawData = appContext.assets.readText(assetName)
            val jsonData = Json.decodeFromString(wordDataSerializer, rawData)
            Dictionary(jsonData, jsonData.values.maxOrNull() ?: 255)
        }.getOrElse {
            if (language == "en") {
                runCatching {
                    val rawData = appContext.assets.readText("$DICT_ASSET_PATH/data.json")
                    val jsonData = Json.decodeFromString(wordDataSerializer, rawData)
                    Dictionary(jsonData, jsonData.values.maxOrNull() ?: 255)
                }.getOrDefault(DEFAULT_DICTIONARY)
            } else {
                DEFAULT_DICTIONARY
            }
        }
    }

    private fun getDictionary(language: String): Dictionary {
        return dictionaries.computeIfAbsent(language) { loadDictionary(it) }
    }

    private fun loadAliases(language: String): Map<String, String> {
        return runCatching {
            val rawData = appContext.assets.readText("$DICT_ASSET_PATH/${language}_aliases.json")
            Json.decodeFromString(aliasDataSerializer, rawData)
        }.getOrDefault(emptyMap())
    }

    private fun getAliases(language: String): Map<String, String> {
        return aliasDictionaries.computeIfAbsent(language) { loadAliases(it) }
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        val language = subtype.primaryLocale.language
        getDictionary(language)
        getAliases(language)
        Unit
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        val locale = subtype.primaryLocale
        val wordLowercase = word.lowercase(locale)

        // Words without any letters (numbers, symbols) are always considered valid.
        if (wordLowercase.isEmpty() || !wordLowercase.any { it.isLetter() }) {
            return SpellingResult.validWord()
        }

        val isKnownWord = isKnownWord(wordLowercase, locale)
        if (isKnownWord) {
            return SpellingResult.validWord()
        }

        val corrections = findCorrections(wordLowercase, locale, maxSuggestionCount)
        return if (corrections.isNotEmpty()) {
            // Use grammar error if the algorithm can detect this. On Android 11 and lower grammar errors are visually
            // marked as typos due to a lack of support
            SpellingResult.typo(corrections.toTypedArray(), isHighConfidenceResult = corrections.firstOrNull()?.let {
                computeConfidence(it, wordLowercase, locale) > 0.8
            } == true)
        } else {
            // The word is unknown and no close corrections exist: do not flag it as a typo to avoid underlining
            // proper nouns and other novel words.
            SpellingResult.validWord()
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: K3Content,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> = withContext(Dispatchers.IO) {
        val locale = subtype.primaryLocale
        // Some editors disable composing (browser search fields, launcher search, etc.), so the
        // K3 composition is empty even though the user is typing a word. Derive the current word
        // from the surrounding text in that case so suggestions keep working in those apps.
        val compositionFromK3 = content.compositionText?.toString().orEmpty()
        val rawCompositionText = compositionFromK3.ifEmpty {
            content.surroundingText.textBefore.toString().takeLastWhile { it.isLetterOrDigit() || it == '\'' }
        }
        val composingWord = rawCompositionText.lowercase(locale)

        if (composingWord.isEmpty() || !composingWord.any { it.isLetter() }) {
            return@withContext emptyList()
        }

        val dictionary = getDictionary(locale.language)
        if (dictionary.words.isEmpty()) {
            return@withContext emptyList()
        }

        val typedWordIsKnown = isKnownWord(composingWord, locale)

        // Ranked candidate text -> confidence map.
        val candidates = mutableMapOf<String, Double>()

        // 1. Close corrections, only relevant if the typed word is unknown so far.
        if (!typedWordIsKnown) {
            for (correction in findCorrections(composingWord, locale, maxCandidateCount)) {
                candidates.getOrPut(correction) {
                    computeConfidence(correction, composingWord, locale)
                }
            }
        }

        // 2. Prefix completions of the currently typed word.
        for ((word, frequency) in dictionary.words) {
            if (word.length > composingWord.length && word.startsWith(composingWord)) {
                candidates.getOrPut(word) {
                    computeBaseConfidence(frequency, dictionary)
                }
            }
        }

        // 3. Romanized aliases used by the Telugu phonetic layout. The editor shows transliterated
        // Telugu, but the suggestion query is the raw Latin word the user typed.
        val aliases = getAliases(locale.language)
        if (aliases.isNotEmpty() && composingWord.all { it in 'a'..'z' || it == '\'' }) {
            aliases[composingWord]?.let { word ->
                val frequency = dictionary.words[word] ?: 255
                candidates[word] = maxOf(
                    candidates.getOrDefault(word, 0.0),
                    maxOf(computeBaseConfidence(frequency, dictionary), 0.99),
                )
            }
            for ((alias, word) in aliases) {
                if (alias.length > composingWord.length && alias.startsWith(composingWord)) {
                    val frequency = dictionary.words[word] ?: 128
                    candidates.getOrPut(word) { computeBaseConfidence(frequency, dictionary) }
                }
            }
        }

        // 4. User dictionary matches (own learned words take precedence over frequency dictionary entries).
        for (userCandidate in queryUserDictionary(composingWord, locale)) {
            val userWord = userCandidate.text.toString().lowercase(locale)
            candidates[userWord] = maxOf(candidates.getOrDefault(userWord, 0.0), userCandidate.confidence)
        }

        candidates.entries
            .sortedByDescending { it.value }
            .take(maxCandidateCount)
            .map { (word, confidence) ->
                WordSuggestionCandidate(
                    text = adaptCase(word, rawCompositionText),
                    confidence = confidence,
                    // We deliberately never auto-commit: the user decides which candidate to pick. This avoids
                    // frustrating false-positive autocorrections with the small on-device dictionaries.
                    isEligibleForAutoCommit = false,
                    // We set ourselves as the source provider so we can get notify events for our candidate
                    sourceProvider = this@LatinLanguageProvider,
                )
            }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // We can use flogDebug, flogInfo, flogWarning and flogError for debug logging, which is a wrapper for Logcat
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        flogDebug { candidate.toString() }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return getDictionary(subtype.primaryLocale.language).words.keys.toList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        val dictionary = getDictionary(subtype.primaryLocale.language)
        val maxFreq = (dictionary.maxFrequency.takeIf { it > 0 } ?: 255).toDouble()
        return dictionary.words.getOrDefault(word.lowercase(subtype.primaryLocale), 0) / maxFreq
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }

    private fun isKnownWord(wordLowercase: String, locale: FlorisLocale): Boolean {
        val dictionary = getDictionary(locale.language)
        if (dictionary.words.containsKey(wordLowercase)) {
            return true
        }
        val userDictSpell = runCatching { dictionaryManager?.spell(wordLowercase, locale) }.getOrDefault(false)
        return userDictSpell == true
    }

    private fun queryUserDictionary(wordLowercase: String, locale: FlorisLocale): List<SuggestionCandidate> {
        return runCatching { dictionaryManager?.queryUserDictionary(wordLowercase, locale) }.getOrNull().orEmpty()
    }

    private fun findCorrections(word: String, locale: FlorisLocale, maxCount: Int): List<String> {
        if (word.length < 2) {
            return emptyList()
        }
        val dictionary = getDictionary(locale.language)
        if (dictionary.words.isEmpty()) {
            return emptyList()
        }
        val maxDistance = if (word.length <= LONG_WORD_THRESHOLD) MAX_EDIT_DISTANCE_SHORT else MAX_EDIT_DISTANCE_LONG

        val corrections = mutableListOf<Pair<String, Int>>()
        for ((candidate, _) in dictionary.words) {
            if (candidate == word) {
                continue
            }
            if (abs(candidate.length - word.length) > maxDistance) {
                continue
            }
            val distance = boundedLevenshteinDistance(word, candidate, maxDistance)
            if (distance != null) {
                corrections.add(candidate to distance)
            }
        }

        return corrections
            .sortedWith(compareBy({ it.second }, { -(dictionary.words[it.first] ?: 0) }, { it.first.length }))
            .take(maxCount)
            .map { it.first }
    }

    private fun computeConfidence(correction: String, word: String, locale: FlorisLocale): Double {
        val dictionary = getDictionary(locale.language)
        val distance = boundedLevenshteinDistance(word, correction, MAX_EDIT_DISTANCE_LONG) ?: MAX_EDIT_DISTANCE_LONG
        val base = computeBaseConfidence(dictionary.words.getOrDefault(correction, 0), dictionary)
        return (base * (1.0 - CONFIDENCE_DECAY_PER_DISTANCE * distance)).coerceIn(0.01, 1.0)
    }

    private fun computeBaseConfidence(frequency: Int, dictionary: Dictionary): Double {
        return if (dictionary.maxFrequency <= 0) {
            0.5
        } else {
            (frequency.toDouble() / dictionary.maxFrequency).coerceIn(0.01, 1.0)
        }
    }

    /**
     * Computes the Levenshtein distance between [a] and [b], aborting early and returning null if the distance
     * exceeds [maxDistance].
     */
    private fun boundedLevenshteinDistance(a: String, b: String, maxDistance: Int): Int? {
        if (abs(a.length - b.length) > maxDistance) {
            return null
        }
        val inf = maxDistance + 1

        var previous = IntArray(b.length + 1) { if (it <= maxDistance) it else inf }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current.fill(inf)
            current[0] = if (i <= maxDistance) i else inf
            val iStart = maxOf(1, i - maxDistance)
            val iEnd = minOf(b.length, i + maxDistance)
            var rowMin = current[0]
            for (j in iStart..iEnd) {
                val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
                val value = minOf(
                    current[j - 1] + 1, // insertion
                    previous[j - 1] + substitutionCost, // substitution
                    previous[j] + 1, // deletion
                ).coerceAtMost(inf)
                current[j] = value
                if (value < rowMin) {
                    rowMin = value
                }
            }
            // Early abort: the best possible distance in this row already exceeds the maximum.
            if (rowMin > maxDistance) {
                return null
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length].takeIf { it <= maxDistance }
    }

    /**
     * Adapts the case of [suggestion] to match the case style of the [typed] word, e.g. "helo" -> "hello",
     * "Helo" -> "Hello", "HELO" -> "HELLO".
     */
    private fun adaptCase(suggestion: String, typed: String): String {
        return when {
            typed.isEmpty() -> suggestion
            typed.all { !it.isLetter() || it.isUpperCase() } && typed.any { it.isLetter() } -> suggestion.uppercase()
            typed.first().isUpperCase() && typed.drop(1).none { it.isUpperCase() } ->
                suggestion.replaceFirstChar { it.uppercaseChar() }
            else -> suggestion
        }
    }
}
