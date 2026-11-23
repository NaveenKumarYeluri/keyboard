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

        // Glides are noisier than typed input: a turn can be missed or an adjacent key crossed.
        // Allow a wider search, but decay confidence hard so an exact match always wins and
        // unrelated high-frequency words do not take over.
        private const val GLIDE_MAX_EDIT_DISTANCE_SHORT = 2
        private const val GLIDE_MAX_EDIT_DISTANCE_LONG = 3
        private const val GLIDE_CONFIDENCE_DECAY_PER_DISTANCE = 0.45

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
    // Reverse index used to check whether a context candidate matches the raw word being typed.
    private val aliasesByWord = ConcurrentHashMap<String, Map<String, List<String>>>()
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

    /**
     * Checks whether [word] is a plausible completion of the raw [input]. Direct prefix matches
     * cover native Telugu; alias matches cover the phonetic layout, where the user types Latin but
     * the suggestion candidate is Telugu. The AI provider uses this to avoid injecting contextually
     * likely words that have nothing to do with the current keystrokes.
     */
    fun isCandidateRelevantForInput(locale: FlorisLocale, word: String, input: String): Boolean {
        val typed = input.lowercase(locale)
        if (typed.isEmpty()) return false
        if (word.startsWith(typed)) return true
        if (!typed.all { it in 'a'..'z' || it == '\'' }) return false

        val byWord = aliasesByWord.computeIfAbsent(locale.language) { language ->
            getAliases(language).entries.groupBy({ it.value }) { it.key }
        }
        for (alias in byWord[word].orEmpty()) {
            if (alias.startsWith(typed) || typed.startsWith(alias)) return true
            val sharedPrefix = alias.zip(typed).takeWhile { (a, b) -> a == b }.size
            if (sharedPrefix >= 6) return true
        }
        return false
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
        val autoCommitCorrections = mutableSetOf<String>()

        // Keep the exact typed word available. Without this, typing a complete known word could
        // show only longer extensions and corrections, making the suggestions feel wrong.
        if (typedWordIsKnown) {
            val frequency = dictionary.words[composingWord] ?: 255
            candidates[composingWord] = maxOf(computeBaseConfidence(frequency, dictionary), 0.98)
        }

        // 1. Close corrections, only relevant if the typed word is unknown so far.
        if (!typedWordIsKnown) {
            for (correction in findCorrections(composingWord, locale, maxCandidateCount)) {
                val confidence = computeConfidence(correction, composingWord, locale)
                candidates.getOrPut(correction) { confidence }
                // Only one-edit substitutions are safe enough for silent replacement. Prefix
                // completions and fuzzy two-edit matches must remain manual picks.
                if (confidence >= 0.7 &&
                    boundedLevenshteinDistance(composingWord, correction, 1) == 1
                ) {
                    autoCommitCorrections.add(correction)
                }
            }
            // Handle common adjacent-letter swaps such as "teh" -> "the", which Levenshtein
            // distance alone scores as two edits and would otherwise miss.
            for (index in 0 until composingWord.length - 1) {
                val chars = composingWord.toCharArray()
                val swapped = chars[index]
                chars[index] = chars[index + 1]
                chars[index + 1] = swapped
                val correction = String(chars)
                if (correction == composingWord || !isKnownWord(correction, locale)) continue
                val confidence = maxOf(computeConfidence(correction, composingWord, locale), 0.8)
                candidates[correction] = maxOf(candidates.getOrDefault(correction, 0.0), confidence)
                autoCommitCorrections.add(correction)
            }
        }

        // 2. Prefix completions of the currently typed word. Strongly prefer completions close in
        // length so "swi" offers swim/swift/swipe before much longer high-frequency words such as
        // "switzerland" or "swimming".
        for ((word, frequency) in dictionary.words) {
            if (word.length > composingWord.length && word.startsWith(composingWord)) {
                candidates.getOrPut(word) {
                    computeBaseConfidence(frequency, dictionary) *
                        prefixProximity(word.length - composingWord.length)
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
                    candidates.getOrPut(word) {
                        computeBaseConfidence(frequency, dictionary) *
                            prefixProximity(alias.length - composingWord.length)
                    }
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
                    // Only high-confidence one-edit corrections may be applied silently, and only
                    // when the auto-correct preference is enabled at the commit site.
                    isEligibleForAutoCommit = word in autoCommitCorrections,
                    autocorrectInput = if (word in autoCommitCorrections) composingWord else null,
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

    override suspend fun glideCandidates(
        subtype: Subtype,
        sequence: String,
        maxCandidateCount: Int,
    ): List<SuggestionCandidate> = withContext(Dispatchers.IO) {
        if (maxCandidateCount <= 0) return@withContext emptyList()
        val locale = subtype.primaryLocale
        // A glide visits each key once even when the intended word contains double letters.
        val typed = collapseRepeats(sequence.lowercase(locale))
        if (typed.length < 2) return@withContext emptyList()

        val dictionary = getDictionary(locale.language)
        if (dictionary.words.isEmpty()) return@withContext emptyList()
        val maxDistance = if (typed.length <= LONG_WORD_THRESHOLD) {
            GLIDE_MAX_EDIT_DISTANCE_SHORT
        } else {
            GLIDE_MAX_EDIT_DISTANCE_LONG
        }
        val candidates = mutableMapOf<String, Double>()

        // An exact sequence match must win, even if a longer high-frequency word sits one edit away.
        dictionary.words[typed]?.let { frequency ->
            candidates[typed] = maxOf(computeBaseConfidence(frequency, dictionary), 0.95)
        }
        for ((word, frequency) in dictionary.words) {
            if (word == typed) continue
            if (word.length < typed.length - maxDistance || word.length > typed.length + maxDistance) continue
            val prefix = sharedPrefixLength(word, typed)
            if (word.firstOrNull() != typed.firstOrNull() && prefix < 2) continue
            val distance = boundedLevenshteinDistance(typed, word, maxDistance) ?: continue
            val confidence = computeBaseConfidence(frequency, dictionary) *
                (1.0 - GLIDE_CONFIDENCE_DECAY_PER_DISTANCE * distance) *
                (1.0 + 0.05 * prefix)
            candidates[word] = maxOf(candidates.getOrDefault(word, 0.0), confidence.coerceIn(0.01, 1.0))
        }

        // Telugu phonetic glides produce raw Latin sequences; aliases map them to canonical Telugu words.
        val aliases = getAliases(locale.language)
        if (typed.all { it in 'a'..'z' || it == '\'' }) {
            aliases[typed]?.let { word ->
                val frequency = dictionary.words[word] ?: 255
                candidates[word] = maxOf(
                    candidates.getOrDefault(word, 0.0),
                    maxOf(computeBaseConfidence(frequency, dictionary), 0.99),
                )
            }
            for ((alias, word) in aliases) {
                if (alias.length < typed.length - maxDistance || alias.length > typed.length + maxDistance) continue
                val distance = boundedLevenshteinDistance(typed, alias, maxDistance) ?: continue
                val prefix = sharedPrefixLength(alias, typed)
                val frequency = dictionary.words[word] ?: 128
                val confidence = computeBaseConfidence(frequency, dictionary) *
                    (1.0 - GLIDE_CONFIDENCE_DECAY_PER_DISTANCE * distance) *
                    (1.0 + 0.05 * prefix)
                candidates[word] = maxOf(candidates.getOrDefault(word, 0.0), confidence.coerceIn(0.01, 1.0))
            }
        }

        for (userCandidate in queryUserDictionary(typed, locale)) {
            val userWord = userCandidate.text.toString()
            if (userWord.isBlank()) continue
            candidates[userWord] = maxOf(candidates.getOrDefault(userWord, 0.0), userCandidate.confidence)
        }

        candidates.toList()
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first.length })
            .take(maxCandidateCount)
            .map { (word, confidence) ->
                WordSuggestionCandidate(
                    text = word,
                    confidence = confidence,
                    isEligibleForAutoCommit = false,
                    sourceProvider = this@LatinLanguageProvider,
                )
            }
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }

    private fun sharedPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var index = 0
        while (index < limit && a[index] == b[index]) {
            index++
        }
        return index
    }

    private fun collapseRepeats(input: String): String {
        if (input.length < 2) return input
        val builder = StringBuilder(input.length)
        var previous = '\u0000'
        var hasPrevious = false
        for (char in input) {
            if (!hasPrevious || char != previous) {
                builder.append(char)
                previous = char
                hasPrevious = true
            }
        }
        return builder.toString()
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

    /** Penalizes long prefix completions quadratically, without affecting exact matches. */
    private fun prefixProximity(lengthDelta: Int): Double {
        if (lengthDelta <= 0) return 1.0
        return 1.0 / (1.0 + 0.12 * lengthDelta * lengthDelta)
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
