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

package dev.patrickgold.florisboard.ime.keyboard3.extension

import android.net.Uri
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.io.FlorisRef
import dev.patrickgold.florisboard.ime.io.Storage
import dev.patrickgold.florisboard.ime.io.readText
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.k3lp.lib.meta.source.SourceFileRef
import org.k3lp.lib.meta.source.TextSourceFile
import org.k3lp.lib.text.K3Descriptor
import org.k3lp.lib.text.WillRequireMigrationToRichErrors
import org.k3lp.model.K3CompileResult
import org.k3lp.model.K3ImpliedImports
import org.k3lp.model.K3ImportResolver
import org.k3lp.model.compileToModel

private val SCOPE_FOUNDATION = K3Descriptor("fl", "ext", "org.florisboard.k3.foundation")

/**
 * Keyboard layout IDs shipped by the foundation extension, mapped to their asset path.
 */
private val FOUNDATION_KEYBOARD_LAYOUTS = mapOf(
    "qwerty" to "keyboard/qwerty.xml",
    "qwertz" to "keyboard/qwertz.xml",
)

private fun resolveLayoutId(subtype: Subtype): String {
    return when (subtype.charactersLayout) {
        in FOUNDATION_KEYBOARD_LAYOUTS -> subtype.charactersLayout
        else -> if (subtype.primaryLocale.language == "de") "qwertz" else "qwerty"
    }
}

private val LONG_PRESS_ATTRIBUTES_REGEX =
    """\s+longPress(?:KeyIds|DefaultKeyId)="[^"]*"""".toRegex()

/**
 * Currency characters used by the `currency-1` .. `currency-6` keys of the foundation layouts.
 * Slot 1 is the primary currency of the set and is shown on the first symbols layer, the other
 * slots appear on the second symbols layer and in the long-press popup of the primary key.
 */
private val CURRENCY_SETS = mapOf(
    "usd" to listOf("\u0024", "\u20AC", "\u00A3", "\u00A5", "\u20B9", "\u00A2"),
    "eur" to listOf("\u20AC", "\u0024", "\u00A3", "\u00A5", "\u20B9", "\u00A2"),
    "gbp" to listOf("\u00A3", "\u0024", "\u20AC", "\u00A5", "\u20B9", "\u00A2"),
    "inr" to listOf("\u20B9", "\u0024", "\u20AC", "\u00A3", "\u00A5", "\u00A2"),
    "jpy" to listOf("\u00A5", "\u0024", "\u20AC", "\u00A3", "\u20B9", "\u00A2"),
)

private val EURO_COUNTRIES = setOf(
    "AT", "BE", "CY", "DE", "EE", "ES", "FI", "FR", "GR", "HR", "IE", "IT", "LT", "LU", "LV",
    "MT", "NL", "PT", "SI", "SK",
)

/**
 * Resolves the currency set to use. An explicit subtype selection always wins, otherwise a
 * reasonable default is derived from the subtype's primary locale.
 */
private fun resolveCurrencySetId(subtype: Subtype): String {
    val explicit = subtype.currencySet
    if (CURRENCY_SETS.containsKey(explicit)) {
        return explicit
    }
    val locale = subtype.primaryLocale
    return when {
        locale.language == "ja" || locale.country == "JP" -> "jpy"
        locale.country == "IN" -> "inr"
        locale.country == "GB" -> "gbp"
        locale.country == "US" -> "usd"
        locale.country in EURO_COUNTRIES -> "eur"
        else -> "eur"
    }
}

private fun applyCurrencySet(xml: String, subtype: Subtype): String {
    val slots = CURRENCY_SETS[resolveCurrencySetId(subtype)] ?: return xml
    var result = xml
    for ((index, currency) in slots.withIndex()) {
        val slotId = Regex.escape("currency-${index + 1}")
        val slotRegex = Regex("""(<key\b[^>]*\bid="$slotId"[^>]*\boutput=")[^"]*(""")
        result = result.replace(slotRegex) { match ->
            match.groupValues[1] + currency + match.groupValues[2]
        }
    }
    return result
}

private val TOUCH_LAYERS_REGEX = """<layers\b[^>]*formId="touch"[^>]*>""".toRegex()

private fun touchLayerRegex(layerId: String): Regex {
    return """<layer\b[^>]*\bid="${Regex.escape(layerId)}"[^>]*>.*?</layer>""".toRegex(RegexOption.DOT_MATCHES_ALL)
}

/**
 * Extracts the XML block of a touch layer from a full keyboard layout file, or null if the layer
 * does not exist in that file.
 */
private fun extractTouchLayer(xml: String, layerId: String): String? {
    val touchStart = TOUCH_LAYERS_REGEX.find(xml)?.range?.first ?: return null
    return touchLayerRegex(layerId).find(xml, touchStart)?.value
}

/**
 * Replaces the XML block of a touch layer in the given keyboard layout with [newLayer]. Returns
 * null if the layer does not exist in the base layout.
 */
private fun replaceTouchLayer(xml: String, layerId: String, newLayer: String): String? {
    val touchStart = TOUCH_LAYERS_REGEX.find(xml)?.range?.first ?: return null
    val match = touchLayerRegex(layerId).find(xml, touchStart) ?: return null
    return xml.substring(0, match.range.first) + newLayer + xml.substring(match.range.last + 1)
}

// TODO this is just a placeholder testing code, must be replaced by proper logic & extension-aware logic
@OptIn(WillRequireMigrationToRichErrors::class)
suspend fun loadFoundationKeyboard(imeController: ImeController, storage: Storage, subtype: Subtype) {
    // The foundation extension currently only ships a small set of layouts. Use the per-subtype
    // characters layout selection if set, otherwise pick one matching the subtype's language.
    val layoutId = resolveLayoutId(subtype)
    val layoutPath = FOUNDATION_KEYBOARD_LAYOUTS.getValue(layoutId)
    suspend fun loadAssetText(path: String): String = withContext(Dispatchers.IO) {
        val ref = FlorisRef.assets("extensions/org.florisboard.k3.foundation/$path")
        storage.readText(ref)
    }

    // The keyboard3 renderer uses a single layout file which contains the characters, symbols and
    // numeric layers. A per-subtype layer override is applied by swapping the matching touch layer
    // of the base layout with the one from the selected layout file.
    var keyboardXml = loadAssetText(layoutPath)
    val layerOverrides = listOf(
        subtype.symbolsLayout to "symbol-1",
        subtype.symbols2Layout to "symbol-2",
        subtype.numericLayout to "numeric",
    )
    for ((overrideLayoutId, layerId) in layerOverrides) {
        if (overrideLayoutId.isBlank() || overrideLayoutId == layoutId) {
            continue
        }
        val overridePath = FOUNDATION_KEYBOARD_LAYOUTS[overrideLayoutId] ?: continue
        val overrideXml = runCatching { loadAssetText(overridePath) }.getOrNull() ?: continue
        val overrideLayer = extractTouchLayer(overrideXml, layerId)
        val replacedXml = overrideLayer?.let { replaceTouchLayer(keyboardXml, layerId, it) }
        if (replacedXml != null) {
            keyboardXml = replacedXml
        } else {
            flogWarning { "Failed to override touch layer '$layerId' with layout '$overrideLayoutId'" }
        }
    }

    // Currency keys on the symbols layers follow the subtype's currency set (explicit choice or
    // a locale-derived default), like FlorisBoard does with its currency slots.
    keyboardXml = applyCurrencySet(keyboardXml, subtype)

    // The "none" popup mapping disables all long-press popups and hints by stripping the
    // corresponding attributes from every key definition.
    if (subtype.popupMapping == "none") {
        keyboardXml = keyboardXml.replace(LONG_PRESS_ATTRIBUTES_REGEX, "")
    }

    suspend fun loadAssetFile(path: String): TextSourceFile {
        val xml = loadAssetText(path)
        val sourceFile = TextSourceFile(object : SourceFileRef {
            override fun toString(): String {
                return "toString()"
            }
        }, xml)
        return sourceFile
    }
    val importResolver = K3ImportResolver { path, _ ->
        val relPath = "import/" + runCatching { Uri.parse(path).path?.removePrefix("/") ?: "" }.getOrDefault(path)
        loadAssetFile(relPath)
    }
    val impliedImports = K3ImpliedImports(
        displays = listOf("flex://org.florisboard.k3.foundation/displays-implied.xml"),
        keys = listOf("flex://org.florisboard.k3.foundation/keys-implied.xml"),
    )
    val file = TextSourceFile(object : SourceFileRef {
        override fun toString(): String {
            return "toString()"
        }
    }, keyboardXml)
    val result = file.compileToModel(importResolver, impliedImports)
    for (report in result.reports) {
        flogError { report.cause?.stackTraceToString() ?: "" }
    }
    if (result is K3CompileResult.Success) {
        imeController.updateState {
            switchModel(result.model)
        }
    }
}
