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

package dev.patrickgold.florisboard.ime.nlp.ai

import android.content.Context
import android.icu.text.BreakIterator
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.latin.LatinLanguageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.k3lp.runtime.K3Content
import java.util.Locale

/**
 * Offline contextual suggestion provider.
 *
 * Candidate generation is delegated to [LatinLanguageProvider]. This provider only reorders those
 * candidates using a small bundled n-gram model, so it cannot invent arbitrary text. No typed text
 * leaves the device, no network permission is used, and no user input is stored for training.
 */
class AiLanguageProvider(
    context: Context,
    private val latin: LatinLanguageProvider,
) : SpellingProvider, SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.ai"

        private const val AI_WEIGHT = 0.65
        private const val UNKNOWN_CONTEXT_SCORE = 0.05
        private const val MAX_CONTEXT_WORDS = 2
    }

    private val prefs by FlorisPreferenceStore
    private val appContext = context.applicationContext

    override val providerId = ProviderId

    override suspend fun create() {
        latin.create()
    }

    override suspend fun preload(subtype: Subtype) {
        latin.preload(subtype)
        ContextLanguageModel.get(appContext, subtype.primaryLocale.language)
    }

    override suspend fun destroy() {
        latin.destroy()
        ContextLanguageModel.clear()
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
        return latin.spell(
            subtype = subtype,
            word = word,
            precedingWords = precedingWords,
            followingWords = followingWords,
            maxSuggestionCount = maxSuggestionCount,
            allowPossiblyOffensive = allowPossiblyOffensive,
            isPrivateSession = isPrivateSession,
        )
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: K3Content,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> = withContext(Dispatchers.IO) {
        val baseCandidates = latin.suggest(
            subtype = subtype,
            content = content,
            maxCandidateCount = maxCandidateCount,
            allowPossiblyOffensive = allowPossiblyOffensive,
            isPrivateSession = isPrivateSession,
        )
        if (baseCandidates.isEmpty() || !prefs.suggestion.aiEnabled.get()) {
            baseCandidates.withSource(this@AiLanguageProvider)
        } else {
            val previousWords = extractPreviousWords(content, subtype, MAX_CONTEXT_WORDS)
            val model = ContextLanguageModel.get(appContext, subtype.primaryLocale.language)
            val probabilities = if (previousWords.isEmpty() || model == null) {
                emptyMap()
            } else {
                model.nextWordProbabilities(previousWords)
            }
            if (probabilities.isEmpty()) {
                baseCandidates.withSource(this@AiLanguageProvider)
            } else {
                baseCandidates
                    .map { candidate ->
                        val word = candidate.text.toString().lowercase()
                        val contextScore = probabilities[word] ?: UNKNOWN_CONTEXT_SCORE
                        candidate.withScore(
                            confidence = (candidate.confidence * (1.0 - AI_WEIGHT) + contextScore * AI_WEIGHT)
                                .coerceIn(0.0, 1.0),
                            sourceProvider = this@AiLanguageProvider,
                        )
                    }
                    .sortedByDescending { it.confidence }
                    .take(maxCandidateCount)
            }
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // No online learning: accepted suggestions are not stored or used for training.
        latin.notifySuggestionAccepted(subtype, candidate)
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        // No online learning: reverted suggestions are not stored or used for training.
        latin.notifySuggestionReverted(subtype, candidate)
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return latin.removeSuggestion(subtype, candidate)
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return latin.getListOfWords(subtype)
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return latin.getFrequencyForWord(subtype, word)
    }

    /**
     * Gets the words before the current composing/raw word. For Telugu phonetic mode the K3
     * controller rewrites the passed content so the current raw Latin word ends `textBefore`;
     * this function strips only that trailing word and keeps the real Telugu context.
     */
    private fun extractPreviousWords(content: K3Content, subtype: Subtype, limit: Int): List<String> {
        val textBefore = content.surroundingText.textBefore.toString()
        val composing = content.compositionText?.toString().orEmpty()
        val contextText = when {
            composing.isNotEmpty() && textBefore.endsWith(composing) -> textBefore.removeSuffix(composing)
            else -> textBefore.removeSuffix(textBefore.takeLastWhile { it.isLetterOrDigit() || it == '\'' })
        }
        if (contextText.isBlank()) return emptyList()

        val locale = Locale(subtype.primaryLocale.language)
        val breakIterator = BreakIterator.getWordInstance(locale)
        breakIterator.setText(contextText)
        val words = mutableListOf<String>()
        var start = breakIterator.first()
        var end = breakIterator.next()
        while (end != BreakIterator.DONE) {
            val word = contextText.substring(start, end).trim { !it.isLetterOrDigit() }
            if (word.isNotEmpty()) {
                words.add(word.lowercase(locale))
            }
            start = end
            end = breakIterator.next()
        }
        return words.takeLast(limit)
    }

    private fun SuggestionCandidate.withScore(
        confidence: Double,
        sourceProvider: SuggestionProvider,
    ): SuggestionCandidate {
        return WordSuggestionCandidate(
            text = text,
            secondaryText = secondaryText,
            confidence = confidence,
            isEligibleForAutoCommit = isEligibleForAutoCommit,
            isEligibleForUserRemoval = isEligibleForUserRemoval,
            sourceProvider = sourceProvider,
        )
    }

    private fun List<SuggestionCandidate>.withSource(
        sourceProvider: SuggestionProvider,
    ): List<SuggestionCandidate> {
        return map { candidate ->
            WordSuggestionCandidate(
                text = candidate.text,
                secondaryText = candidate.secondaryText,
                confidence = candidate.confidence,
                isEligibleForAutoCommit = candidate.isEligibleForAutoCommit,
                isEligibleForUserRemoval = candidate.isEligibleForUserRemoval,
                sourceProvider = sourceProvider,
            )
        }
    }
}
