#!/usr/bin/env python3
"""Generate the offline Telugu context model asset.

The model is derived from the bigram and trigram frequency lists published in
wasi-master/wmkeyboard-data. It is intentionally small: only the strongest
contexts are kept, each with at most eight next-word choices, scaled to 0-255.
No network access is required by the keyboard at runtime.
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
OUTPUT = ROOT / "app/src/main/assets/ime/nlp/te_context.json"

MAX_BIGRAM_CONTEXTS = 6000
MAX_TRIGRAM_CONTEXTS = 12000
TOP_NEXT_WORDS = 8
SCALE = 255


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


def main() -> None:
    dictionary = json.loads(DICTIONARY.read_text(encoding="utf-8"))
    allowed = set(dictionary)

    bigrams = load_ngrams("te_bigrams.txt.gz", 2, allowed)
    trigrams = load_ngrams("te_trigrams.txt.gz", 3, allowed)

    model = compact(bigrams, MAX_BIGRAM_CONTEXTS)
    model.update(compact(trigrams, MAX_TRIGRAM_CONTEXTS))

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    raw = json.dumps(model, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    OUTPUT.write_bytes(raw)

    print(f"bigram contexts: {len(bigrams)} -> {MAX_BIGRAM_CONTEXTS}")
    print(f"trigram contexts: {len(trigrams)} -> {MAX_TRIGRAM_CONTEXTS}")
    print(f"wrote {OUTPUT.relative_to(ROOT)}: {len(model)} contexts, {len(raw)} bytes")


if __name__ == "__main__":
    main()
