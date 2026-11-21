# Keyboard

A personal, privacy-first keyboard for Android — built on [FlorisBoard](https://github.com/florisboard/florisboard).

This is a **personal-use fork** of FlorisBoard (v0.6.0-alpha02 snapshot). It is not published to
any app store and does not accept contributions. It exists purely for private use and
experimentation.

## Credits

All credit for the original work belongs to the FlorisBoard project:

- **[Patrick Goldinger (patrickgold)](https://github.com/patrickgold)** — creator and lead
  developer of FlorisBoard
- **[FlorisBoard Contributors](https://github.com/florisboard/florisboard/graphs/contributors)**
  — everyone who contributed code, translations, and designs
- **[k3lp](https://github.com/k3lp)** (the Keyboard3 layout/runtime library this fork builds
  on) — by patrickgold and lm41

This fork only changes, removes, or extends things for personal use; the vast majority of the
codebase is upstream FlorisBoard, licensed under the
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt). Please consider
supporting or contributing to the original project.

## What changed compared to upstream

### Completed roadmap features (implemented in this fork)
- **Word suggestions and spell checking** — a real, offline suggestion engine built on the
  bundled English and Telugu frequency dictionaries and the user dictionaries. Telugu also has
  native and phonetic layouts, romanized aliases, and a bundled contextual n-gram model.
  Suggestions are tap-to-pick only (no auto-commit), so there are no false-positive
  autocorrections.
- **Physical keyboard support** — hardware key events are handled by the IME (letters,
  backspace, space, enter), flowing through the same pipeline as on-screen input so
  suggestions stay live, with auto-repeat support.
- **User dictionary reset** — "clear dictionary" actions now actually clear the Floris and
  system user dictionaries.

### Already present in upstream (no work needed)
Floating keyboard mode, glide typing, emoji panel with search, landscape/extracted input,
offline extension import/export, and the Keyboard3 layout engine.

### Removed for privacy and simplicity
- The addons store "check for updates" feature and all store constants (no URLs are ever
  constructed or opened from within the app; extension updates are not checked at all).
- Crowdin integration, issue/PR templates, funding links, contributing/CoC/AI-policy docs.
- The benchmark module, the reproducible-build Docker environment, and the empty Rust/NDK
  placeholder library (`lib:native`) — building now requires no NDK, CMake, or Rust.
- Beta/debug build flavors, store badges, and store release channels.

### Rebranding
- App name: **Keyboard**; application ID: `com.personal.keyboard`.
- Minimal adaptive icon (letter "K").
- The Kotlin source packages remain `dev.patrickgold.florisboard.*` for technical reasons
  (renaming would touch ~284 files for no benefit). The `org.florisboard.*` extension asset
  namespace is likewise unchanged.

## Privacy

- The app **does not request the `INTERNET` permission** and makes no network connections.
  All suggestion, spell-check, glide-typing, and emoji processing happens locally on-device.
  Contextual AI suggestions use a small bundled n-gram model; no cloud LLM is used and typed
  text is never uploaded.
- Emoji rendering uses AndroidX EmojiCompat with the **system emoji font as the first
  choice**; on devices lacking an emoji font, Google Play Services may be used as a local
  fallback to obtain one (a device-local font lookup, not data transfer).
- Nothing is uploaded anywhere. There is no telemetry, no crash reporting service, and no
  update checker.

## Building

Builds run on **GitHub Actions**; nothing needs to be installed locally.

1. Push this repository to GitHub.
2. The `Build APK` workflow (`.github/workflows/android.yml`) runs on every push to `main`
   and on `workflow_dispatch`, producing a signed release APK artifact
   (`keyboard-release-apk`). No GitHub releases are published — the APK is downloaded
   directly from the workflow's artifacts page.

### Signing

The workflow restores a fixed Actions cache entry named `personal-signing-keystore-v1` and
signs every release artifact with that key (alias `personal`). Repository signing secrets are
intentionally not used: switching between the cached key and a different secret would change
the APK signature and Android would refuse to update the installed app.

If that cache entry is missing, the build fails instead of generating a replacement key. A
replacement key would require uninstalling the app once and losing its local settings. The
workflow log prints the signing certificate's SHA-256 fingerprint so the key can be verified
between builds.

## Installing

1. Download the APK artifact (or release) from GitHub.
2. Enable *Keyboard* under **System settings → Languages & input → On-screen keyboard**.
3. Switch to it via the input-method picker.

## Known limitations

- Suggestions are English-only (driven by the bundled English frequency dictionary plus
  whatever you add to the user dictionary); other languages fall back to the user
  dictionary alone.
- The language pack extension type and the Chinese shape-based input provider are dead code
  in upstream and remain disabled here.
- Emoji search works for the six languages shipped with upstream emoji keyword assets.

## License

Apache License 2.0 — inherited from FlorisBoard. See [LICENSE](LICENSE).
