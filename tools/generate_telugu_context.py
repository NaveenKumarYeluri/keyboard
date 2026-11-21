#!/usr/bin/env python3
"""Generate the bundled Telugu suggestion assets.

The model is derived from the bigram and trigram frequency lists published in
wasi-master/wmkeyboard-data, then augmented with common greetings that are rare
in that corpus but important in everyday typing (for example birthday wishes).
The dictionary and romanized alias files receive the same curated additions so
phonetic and native Telugu typing can both reach those words.

Everything is written into APK assets. The keyboard never contacts the network
or uploads typed text at runtime.
"""

from __future__ import annotations

import gzip
import io
import json
import unicodedata
import urllib.request
from collections import defaultdict
from pathlib import Path

BASE_URL = "https://raw.githubusercontent.com/wasi-master/wmkeyboard-data/master/data/te"
ROOT = Path(__file__).resolve().parents[1]
DICTIONARY = ROOT / "app/src/main/assets/ime/dict/te.json"
ALIASES = ROOT / "app/src/main/assets/ime/dict/te_aliases.json"
OUTPUT = ROOT / "app/src/main/assets/ime/nlp/te_context.json"

MAX_BIGRAM_CONTEXTS = 6000
MAX_TRIGRAM_CONTEXTS = 12000
TOP_NEXT_WORDS = 10
SCALE = 255

# Greetings and inflections are underrepresented in the source corpus. Give them
# a useful base frequency so they appear in suggestions, while context scoring
# decides when they are actually relevant.
CURATED_DICTIONARY = {
    "పుట్టినరోజు": 180,
    "పుట్టినరోజున": 160,
    "పుట్టినరోజుకు": 160,
    "పుట్టినరోజుకి": 155,
    "శుభాకాంక్షలు": 210,
    "శుభకాంశాలు": 205,
    "శుభకాంక్షలు": 195,
    "శుభకంశాలు": 190,
    "శుభాకాంక్ష": 185,
    "శుభకాంక్ష": 180,
    "శుభకాంశ": 175,
    "శుభం": 170,
    "శుభాలు": 165,
    "శుభాభినందనలు": 175,
    "శుభాశుభాలు": 160,
    "జన్మదినం": 175,
    "జన్మదిన": 165,
    "జన్మదినాలు": 155,
    "తెలియజేస్తారు": 145,
    "తెలియజేస్తున్నాను": 145,
    "కోరుకుంటున్నాను": 150,
    "కోరుకుంటున్నాము": 145,
    "కోరుకుంటాను": 140,
    "అభినందనలు": 170,
    "అభినందన": 160,
    "శుభోదయం": 180,
    "శుభసాయంత్రం": 170,
    "శుభరాత్రి": 165,
    "శుభయాత్ర": 160,
    "క్షమించండి": 170,
}

# Common romanizations, including the spellings people actually type rather
# than only a strict transliteration of each Telugu word.
CURATED_ALIASES = {
    "puttinaroju": "పుట్టినరోజు",
    "puttinarooju": "పుట్టినరోజు",
    "puttinarodju": "పుట్టినరోజు",
    "putttinaroju": "పుట్టినరోజు",
    "puttinaroj": "పుట్టినరోజు",
    "puttinaroja": "పుట్టినరోజు",
    "shubhakankshalu": "శుభాకాంక్షలు",
    "subhakankshalu": "శుభాకాంక్షలు",
    "shubhaakankshalu": "శుభాకాంక్షలు",
    "shubhaakaanshalu": "శుభాకాంక్షలు",
    "shubhakamshalu": "శుభకాంశాలు",
    "subhakamshalu": "శుభకాంశాలు",
    "shubhakanshalu": "శుభకాంశాలు",
    "subhakanshalu": "శుభకాంశాలు",
    "shubhakamshaalu": "శుభకాంశాలు",
    "shubhakamshal": "శుభకాంశాలు",
    "shubhakanksha": "శుభాకాంక్ష",
    "shubhakamsha": "శుభకాంశ",
    "subhakanksha": "శుభాకాంక్ష",
    "subhakamsha": "శుభకాంశ",
    "shubham": "శుభం",
    "subham": "శుభం",
    "shubhamu": "శుభం",
    "subhamu": "శుభం",
    "shubhalu": "శుభాలు",
    "subhalu": "శుభాలు",
    "shubhabhinandanlu": "శుభాభినందనలు",
    "shubhabhinandanalu": "శుభాభినందనలు",
    "subhabhinandanlu": "శుభాభినందనలు",
    "abhinandanlu": "అభినందనలు",
    "abhinandanalu": "అభినందనలు",
    "abhinandan": "అభినందన",
    "janmadinam": "జన్మదినం",
    "janmadhinam": "జన్మదినం",
    "janmadinamu": "జన్మదినం",
    "janmadin": "జన్మదిన",
    "janmadinamlu": "జన్మదినాలు",
    "puttinarojuna": "పుట్టినరోజున",
    "puttinarojuku": "పుట్టినరోజుకు",
    "puttinarojuki": "పుట్టినరోజుకి",
    "shubhodhayam": "శుభోదయం",
    "subhodhayam": "శుభోదయం",
    "shubhasayamtram": "శుభసాయంత్రం",
    "subhasayamtram": "శుభసాయంత్రం",
    "shubharatri": "శుభరాత్రి",
    "subharatri": "శుభరాత్రి",
    "shubhayatra": "శుభయాత్ర",
    "subhayatra": "శుభయాత్ర",
    "kshaminchandi": "క్షమించండి",
    "kshamichandi": "క్షమించండి",
    "koreukuntunnanu": "కోరుకుంటున్నాను",
    "koreukuntunnam": "కోరుకుంటున్నాము",
    "teliyajestaru": "తెలియజేస్తారు",
    "teliyajestunnanu": "తెలియజేస్తున్నాను",
}

# Hand-curated contexts make the birthday phrase work regardless of whether the
# source corpus happened to keep those low-frequency bigrams after filtering.
CURATED_CONTEXTS = {
    "పుట్టినరోజు": {
        "శుభాకాంక్షలు": 255,
        "శుభకాంశాలు": 245,
        "సందర్భంగా": 235,
        "శుభాకాంక్ష": 220,
        "శుభకాంక్ష": 215,
        "శుభం": 205,
        "శుభాలు": 195,
        "శుభాభినందనలు": 190,
        "అభినందనలు": 185,
        "శుభకాంశ": 180,
    },
    "పుట్టిన|రోజు": {
        "సందర్భంగా": 255,
        "శుభాకాంక్షలు": 245,
        "శుభకాంశాలు": 235,
        "శుభం": 210,
        "శుభాలు": 200,
        "శుభాకాంక్ష": 190,
        "శుభకాంక్ష": 185,
        "అభినందనలు": 180,
    },
    "శుభాకాంక్షలు": {
        "తెలిపారు": 255,
        "తెలియజేస్తారు": 245,
        "కోరుకుంటున్నాను": 230,
        "కోరుకుంటున్నాము": 220,
        "ఉండాలని": 200,
        "శుభం": 185,
        "కలుగుతుంది": 175,
    },
    "శుభకాంశాలు": {
        "తెలిపారు": 255,
        "తెలియజేస్తారు": 245,
        "కోరుకుంటున్నాను": 230,
        "కోరుకుంటున్నాము": 220,
        "ఉండాలని": 200,
        "శుభం": 185,
        "కలుగుతుంది": 175,
    },
    "శుభం": {
        "కలుగుతుంది": 255,
        "కలుగుతాయి": 230,
        "తెలియని": 205,
        "శుభాలు": 180,
    },
    "జన్మదినం": {
        "సందర్భంగా": 255,
        "శుభాకాంక్షలు": 245,
        "శుభకాంశాలు": 235,
        "అభినందనలు": 220,
        "శుభం": 190,
    },
}


def read_gzip(name: str):
    with urllib.request.urlopen(f"{BASE_URL}/{name}", timeout=60) as response:
        compressed = gzip.GzipFile(fileobj=io.BytesIO(response.read()), mode="rb")
        return io.TextIOWrapper(compressed, encoding="utf-8")


def load_ngrams(name: str, n: int, allowed: set[str]) -> dict[str, dict[str, int]]:
    counts: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    with read_gzip(name) as source:
        for line in source:
            parts = line.rstrip("\n").split(" ")
            if len(parts) != n + 1:
                continue
            words = [unicodedata.normalize("NFC", word) for word in parts[:n]]
            try:
                count = int(parts[n])
            except ValueError:
                continue
            next_word = words[-1]
            if next_word not in allowed:
                continue
            context = "|".join(words[:-1])
            counts[context][next_word] += count
    return counts


def context_weight(next_words: dict[str, int]) -> int:
    return sum(next_words.values())


def compact(counts: dict[str, dict[str, int]], limit: int) -> dict[str, dict[str, int]]:
    result: dict[str, dict[str, int]] = {}
    for context in sorted(counts, key=lambda key: -context_weight(counts[key]))[:limit]:
        next_words = counts[context]
        top = sorted(next_words.items(), key=lambda item: (-item[1], item[0]))[:TOP_NEXT_WORDS]
        maximum = max(count for _, count in top)
        result[context] = {
            word: max(1, round(count * SCALE / maximum))
            for word, count in top
        }
    return result


def write_json(path: Path, value: object) -> None:
    raw = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    path.write_bytes(raw)


def main() -> None:
    dictionary = json.loads(DICTIONARY.read_text(encoding="utf-8-sig"))
    for word, frequency in CURATED_DICTIONARY.items():
        dictionary[word] = max(dictionary.get(word, 0), frequency)

    aliases = json.loads(ALIASES.read_text(encoding="utf-8-sig"))
    for alias, word in CURATED_ALIASES.items():
        aliases.setdefault(alias, word)

    allowed = set(dictionary)
    bigrams = load_ngrams("te_bigrams.txt.gz", 2, allowed)
    trigrams = load_ngrams("te_trigrams.txt.gz", 3, allowed)

    model = compact(bigrams, MAX_BIGRAM_CONTEXTS)
    model.update(compact(trigrams, MAX_TRIGRAM_CONTEXTS))
    for context, next_words in CURATED_CONTEXTS.items():
        merged = dict(model.get(context, {}))
        for word, score in next_words.items():
            merged[word] = max(merged.get(word, 0), score)
        model[context] = dict(
            sorted(merged.items(), key=lambda item: (-item[1], item[0]))[:TOP_NEXT_WORDS]
        )

    write_json(DICTIONARY, dictionary)
    write_json(ALIASES, aliases)
    write_json(OUTPUT, model)

    print(f"dictionary: {len(dictionary)} words")
    print(f"aliases: {len(aliases)} entries")
    print(f"bigram contexts: {len(bigrams)} -> {MAX_BIGRAM_CONTEXTS}")
    print(f"trigram contexts: {len(trigrams)} -> {MAX_TRIGRAM_CONTEXTS}")
    print(f"wrote {OUTPUT.relative_to(ROOT)}: {len(model)} contexts")


if __name__ == "__main__":
    main()
