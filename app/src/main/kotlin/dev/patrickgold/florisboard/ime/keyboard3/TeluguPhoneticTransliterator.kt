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

package dev.patrickgold.florisboard.ime.keyboard3

/**
 * Converts a raw Latin phonetic buffer into Telugu.
 *
 * Unlike K3's simple transforms, which only replace the first matching suffix once per key press,
 * this works over the whole word. That means trailing consonants are converted as well instead of
 * being left as English letters between Telugu glyphs.
 */
object TeluguPhoneticTransliterator {
    private const val VIRAMA = "\u0C4D"
    private const val ANUSVARA = "\u0C02"

    private val CLUSTERS = listOf(
        "ksh" to "\u0C15\u0C4D\u0C37",
        "jny" to "\u0C1C\u0C4D\u0C1E",
        "sth" to "\u0C38\u0C4D\u0C24",
        "st" to "\u0C38\u0C4D\u0C24",
        "shr" to "\u0C36\u0C4D\u0C30",
        "thr" to "\u0C25\u0C4D\u0C30",
        "khr" to "\u0C16\u0C4D\u0C30",
        "ghr" to "\u0C18\u0C4D\u0C30",
        "ndh" to "\u0C28\u0C4D\u0C27",
        "phr" to "\u0C2B\u0C4D\u0C30",
        "bhr" to "\u0C2D\u0C4D\u0C30",
        "mbh" to "\u0C2E\u0C4D\u0C2D",
        "kr" to "\u0C15\u0C4D\u0C30",
        "gr" to "\u0C17\u0C4D\u0C30",
        "tr" to "\u0C24\u0C4D\u0C30",
        "dr" to "\u0C26\u0C4D\u0C30",
        "dhr" to "\u0C27\u0C4D\u0C30",
        "nr" to "\u0C28\u0C4D\u0C30",
        "pr" to "\u0C2A\u0C4D\u0C30",
        "br" to "\u0C2C\u0C4D\u0C30",
        "mr" to "\u0C2E\u0C4D\u0C30",
        "vr" to "\u0C35\u0C4D\u0C30",
        "sr" to "\u0C38\u0C4D\u0C30",
        "hr" to "\u0C39\u0C4D\u0C30",
        "nd" to "\u0C28\u0C4D\u0C26",
    ).sortedByDescending { it.first.length }

    private val CONSONANTS = listOf(
        "tth" to "\u0C20",
        "ddh" to "\u0C22",
        "chh" to "\u0C1B",
        "kh" to "\u0C16",
        "gh" to "\u0C18",
        "ng" to "\u0C19",
        "ch" to "\u0C1B",
        "jh" to "\u0C1D",
        "ny" to "\u0C1E",
        "tt" to "\u0C1F",
        "th" to "\u0C25",
        "dd" to "\u0C21",
        "dh" to "\u0C27",
        "nn" to "\u0C23",
        "ph" to "\u0C2B",
        "bh" to "\u0C2D",
        "ll" to "\u0C33",
        "sh" to "\u0C36",
        "shh" to "\u0C37",
        "k" to "\u0C15",
        "g" to "\u0C17",
        "c" to "\u0C1A",
        "j" to "\u0C1C",
        "t" to "\u0C24",
        "d" to "\u0C26",
        "n" to "\u0C28",
        "p" to "\u0C2A",
        "b" to "\u0C2C",
        "m" to "\u0C2E",
        "y" to "\u0C2F",
        "r" to "\u0C30",
        "l" to "\u0C32",
        "v" to "\u0C35",
        "s" to "\u0C38",
        "h" to "\u0C39",
        "f" to "\u0C2B",
        "w" to "\u0C35",
        "z" to "\u0C1C",
    ).sortedByDescending { it.first.length }

    private val VOWEL_SIGNS = listOf(
        "aa" to "\u0C3E",
        "ee" to "\u0C47",
        "oo" to "\u0C42",
        "ai" to "\u0C48",
        "au" to "\u0C4C",
        "ou" to "\u0C4C",
        "ri" to "\u0C43",
        "i" to "\u0C3F",
        "u" to "\u0C41",
        "e" to "\u0C46",
        "o" to "\u0C4A",
        "a" to "",
    ).sortedByDescending { it.first.length }

    private val STANDALONE_VOWELS = listOf(
        "aa" to "\u0C06",
        "ee" to "\u0C08",
        "oo" to "\u0C0A",
        "ai" to "\u0C10",
        "au" to "\u0C14",
        "ou" to "\u0C14",
        "ri" to "\u0C43",
        "a" to "\u0C05",
        "i" to "\u0C07",
        "u" to "\u0C09",
        "e" to "\u0C0E",
        "o" to "\u0C12",
    ).sortedByDescending { it.first.length }

    fun transliterate(input: String): String {
        val raw = input.lowercase()
        val out = StringBuilder()
        var index = 0
        while (index < raw.length) {
            val cluster = CLUSTERS.firstOrNull { raw.startsWith(it.first, index) }
            if (cluster != null) {
                out.append(cluster.second)
                index += cluster.first.length
                val nextIndex = appendVowelSign(raw, index, out)
                if (nextIndex == index) {
                    out.append(VIRAMA)
                } else {
                    index = nextIndex
                }
                continue
            }

            val consonant = CONSONANTS.firstOrNull { raw.startsWith(it.first, index) }
            if (consonant != null) {
                out.append(consonant.second)
                index += consonant.first.length
                val nextIndex = appendVowelSign(raw, index, out)
                if (nextIndex == index) {
                    // A trailing m is much more commonly the anusvara in Telugu words.
                    out.append(
                        if (consonant.first == "m" && (index == raw.length || !isAsciiLetter(raw[index]))) {
                            ANUSVARA
                        } else {
                            VIRAMA
                        },
                    )
                } else {
                    index = nextIndex
                }
                continue
            }

            val vowel = STANDALONE_VOWELS.firstOrNull { raw.startsWith(it.first, index) }
            if (vowel != null) {
                out.append(vowel.second)
                index += vowel.first.length
                continue
            }

            out.append(raw[index])
            index++
        }
        return out.toString()
    }

    private fun appendVowelSign(raw: String, index: Int, out: StringBuilder): Int {
        val vowel = VOWEL_SIGNS.firstOrNull { raw.startsWith(it.first, index) } ?: return index
        out.append(vowel.second)
        return index + vowel.first.length
    }

    private fun isAsciiLetter(char: Char): Boolean {
        return char in 'a'..'z'
    }
}
