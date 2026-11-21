# Telugu dictionary attribution

The files `app/src/main/assets/ime/dict/te.json` and
`app/src/main/assets/ime/dict/te_aliases.json` are derived from the Telugu
word-frequency list `data/te/te_full.txt.gz` in
[wasi-master/wmkeyboard-data](https://github.com/wasi-master/wmkeyboard-data).

The frequency lists in that repository are sourced from various open-source
projects and are licensed under CC BY-SA 4.0, MIT, Public Domain, or CC BY-SA
3.0. See the repository's [licensing and attribution section](https://github.com/wasi-master/wmkeyboard-data#licensing--attribution)
and its `data/README.md` for the full per-language details.

The word list was filtered to Telugu-script entries, normalized to NFC, and
reduced to the highest-frequency words. Frequencies were log-scaled to the
0-255 range used by FlorisBoard's Latin language provider. The alias file maps
common Latin phonetic spellings to those canonical Telugu words.

`app/src/main/assets/ime/nlp/te_context.json` is derived from the same
repository's `data/te/te_bigrams.txt.gz` and `data/te/te_trigrams.txt.gz`. It
keeps only the strongest 6,000 bigram and 12,000 trigram contexts, with at most
eight next words each, scaled to 0-255. The generator is
`tools/generate_telugu_context.py`. This model is bundled in the APK and is
used entirely on-device.
