# Third-party notices

## Ableton Live 10 set templates

`app/src/main/resources/als/*.xml` are derived from the Live 10.0.6 version fixture
(`test/version_fixtures/skeletons/10.0.6.als`) in
[abletoolz](https://github.com/elixirbeats/abletoolz), which is licensed under the
[Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/). Those template files are made
available under the same license. `tools/als/make_templates.py` documents exactly how they were
derived (devices, clips, names and sends removed; ids replaced with placeholders).

During development the generated sets are checked with `tools/als/validate_live10.py` against
abletoolz's packed Live schema data (`abletoolz/schema/data/live_schemas.json.gz`, MPL-2.0). That
data is not shipped in the app.

Ableton and Ableton Live are trademarks of Ableton AG. This project is not affiliated with Ableton.

## Drum sounds

Every built-in drum sound is synthesized in code (`DrumSynth.kt`); no sample recordings are bundled.
