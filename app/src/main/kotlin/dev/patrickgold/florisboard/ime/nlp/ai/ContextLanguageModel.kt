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

import dev.patrickgold.florisboard.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import java.util.concurrent.ConcurrentHashMap

/**
 * Small offline n-gram model used to reorder suggestions.
 *
 * The asset maps a context key to next-word scores scaled to 0-255. Trigram keys use
 * `word1|word2`; bigram keys use a single word. All data is bundled in the APK and no network
 * connection or user-data collection is involved.
 */
class ContextLanguageModel private constructor(
    private val nextWordScores: Map<String, Map<String, Int>>,
) {
    /**
     * Returns normalized next-word probabilities for the strongest matching context. A trigram
     * match is preferred over a bigram match.
     */
    fun nextWordProbabilities(previousWords: List<String>): Map<String, Double> {
        if (previousWords.isEmpty()) return emptyMap()
        val last = previousWords.last().lowercase()
        val keys = if (previousWords.size >= 2) {
            listOf("${previousWords[previousWords.size - 2].lowercase()}|$last", last)
        } else {
            listOf(last)
        }
        for (key in keys) {
            val scores = nextWordScores[key] ?: continue
            if (scores.isEmpty()) continue
            return scores.mapValues { (_, score) -> score.coerceIn(0, 255) / 255.0 }
        }
        return emptyMap()
    }

    companion object {
        private const val ASSET_PATH = "ime/nlp"
        private val EMPTY = ContextLanguageModel(emptyMap())
        private val models = ConcurrentHashMap<String, ContextLanguageModel>()
        private val serializer = MapSerializer(
            String.serializer(),
            MapSerializer(String.serializer(), Int.serializer()),
        )

        suspend fun get(language: String): ContextLanguageModel? = withContext(Dispatchers.IO) {
            models.computeIfAbsent(language) { lang ->
                runCatching {
                    val rawData = appContext.assets.readText("$ASSET_PATH/${lang}_context.json")
                    ContextLanguageModel(Json.decodeFromString(serializer, rawData))
                }.getOrDefault(EMPTY)
            }.takeIf { it !== EMPTY }
        }

        fun clear() {
            models.clear()
        }
    }
}
