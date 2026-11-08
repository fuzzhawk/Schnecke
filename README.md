# Schnecke 🐌

Turn the beats you tap on tables, boxes and railings into drum loops on your Android phone.

Tap a groove on any surface: **fingertip thumps become kicks**, **fingernail flicks become snares**.
Schnecke hears the difference relative to whatever you're tapping on, replaces the kick with a
designed kick sample, and keeps the flick of the surface itself as the snare (or swaps in a
one-shot). Then beatbox a layer of hi-hats and percussion on top, either kept as audio or turned
into triggered samples too. Export the loop as **MIDI**, as a **WAV of any length**, or as an
**Ableton Live 10 project**.

## Install

1. Get `Schnecke-debug.apk` (from a build, see below) onto the phone.
2. Open it and allow installing from that source when Android asks.
3. Requires Android 8.0 or newer. The app asks for the microphone the first time you record.

Every build is signed with the same key (`app/debug.keystore`), so a newer APK installs over an
older one and keeps your loops.

## Making a loop

The hands layer is made in three steps.

1. **Record**: press the big record button and tap your groove freely on any surface, two or more
   times through. There's no metronome; the waveform scrolls as you play, like a voice recorder.
   Optionally **Teach** first: record a few kicks, then a few snares, on the surface you'll use, and
   detection matches your own examples.
2. **Set loop**: the whole recording is shown with **IN** and **OUT** handles. The app suggests where
   your groove repeats; drag the handles (they snap to your hits, or nudge them by 5 ms), say how
   many bars the loop is and in what time signature, and the tempo follows. *Preview* loops the
   marked part, optionally with a click to check the tempo. The wand finds the loop again.
3. **Detect hits** opens the lab: the waveform shows each hit (orange = kick, amber = snare), the
   pattern grid shows the loop.
   - Adjust *Sensitivity* if quiet ghost notes are missed or noise is picked up.
   - Hits keep the timing you played: each one sits on a grid step plus a **beat offset**, so it can
     land a little early or late, off the grid. The pattern editor has three tools (and Undo):
     - **Draw**: tap a cell to add or remove a hit; hold a hit to move it to the next sound (e.g. a
       snare that was heard as a kick).
     - **Snap**: a quantization brush. Brush over hits to snap them onto the grid, fully (100%) or
       halfway (50%); *All* snaps every hit.
     - **Nudge**: tap a hit to see and set its offset (in ms, up to just under half a step either
       way) with a slider, ±1 ms buttons or by dragging it; *Snap* puts it back on the grid.
   - *Re-record* starts over at step 1; the menu has *Edit loop points* to go back to step 2.

Then **record beatbox** on the Beatbox card: it's recorded in time over the loop, and with *Play
hands loop while recording* on (the default) the hands loop plays through the count-in and the
take. "ts" = hi-hat, "tsss" = open hat, "k"/"pf"/clicks = percussion. Choose **Raw audio** to loop
your beatbox as recorded (with optional noise gate) or **Samples** to replace it with triggered sounds.

**Sounds tab**: pick each voice's sample. *From your recording* uses a hit cut from your own take
(press *Another* to try a different hit). There are synthesized kicks, snares, claps, hats and
percussion, or *Import a WAV file…*. Set volume, pan and the MIDI note per voice.

### Headphones

Wear headphones while recording beatbox over the hands loop: without them the phone's speaker leaks
the loop into the microphone (the app warns you). The beatbox click plays only with headphones by
default (Settings), and *Vibrate on the beat* gives a silent click you can feel.

### Timing

The hands loop's timing comes from the loop points you mark. Beatbox takes are aligned to the loop
with the audio hardware's own timestamps; if they still land early or late (Bluetooth headphones add
delay), run **Settings → Calibrate…** and tap along with eight clicks, or set the offset by hand.

## Exports

| Export | What you get |
| --- | --- |
| **MIDI (.mid)** | Type 1 file: tempo/time signature, one General MIDI drum track (channel 10) per layer. Optional repeats. |
| **Audio (.wav)** | 48 kHz 16-bit stereo mix, repeated for the length you choose (optionally to a whole loop, with a fade-out). |
| **Ableton Live 10 (.zip)** | A Live project folder: the `.als` set, a looping audio clip per sound (and per raw layer) in Session and Arrangement view, MIDI clips of the pattern, every one-shot under `Samples/Imported/One-shots` for building a Drum Rack, and the `.mid`. |

Use *Save to…* (Downloads, Drive…) or *Share* after exporting. For Live: unzip the file on your
computer and open the `.als` inside the folder. It's written in the Live 10.0.6 format, so it opens in
Live 10.0.6 and later, including 10.1, 11 and 12.

## How the detection works

- **Onsets**: spectral flux of log-compressed magnitudes (1024-point FFT, 2.7 ms hop) with an
  adaptive median threshold, then each hit is refined to the start of its attack on the waveform.
- **Features** per hit: spectral centroid, roll-off, low/high band energy, flatness, zero-crossing
  rate, attack and decay time.
- **Classification**: hits of a take are split into two clusters on standardized features, and the
  darker cluster becomes the kick (or the percussion, for beatbox) wherever you're tapping. Long,
  bright beatbox sounds become open hats. A taught profile switches to nearest-centroid matching.
- **Loop finding**: the suggested loop is the shortest delay after which your hits recur with the
  same sound (kick on kick, snare on snare), extended to whole bars at a natural tempo.
- **Timing**: each hit is stored where it lands; its grid step and offset follow from the current
  grid, so you can change the grid (and swing, in Settings) later without losing your timing.
  Offsets carry through to every export: MIDI, audio and the Live set.

## Building

Requirements: JDK 17+, Android SDK with platform 35 and build-tools 35.

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug          # app/build/outputs/apk/debug/Schnecke-debug.apk
./gradlew testDebugUnitTest      # detection, export and app smoke tests (screenshots in app/build/screens)
```

`tools/als/` has the scripts used to cut the Live 10 templates from a real Live 10.0.6 set and to check
generated sets against Live 10's schema; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Project layout

```
app/src/main/java/com/fuzzhawk/schnecke/
  core/        plain Kotlin, unit-tested on the JVM
    dsp/       onset detection, features, classification, loop finding, sample cutting, raw loops
    synth/     the synthesized drum kit
    audio/     loop renderer shared by playback and export
    export/    MIDI, WAV, Ableton Live 10 writers
    model/     project data model and grid math
  audio/       Android audio engine (AudioTrack/AudioRecord, timestamps, haptics)
  data/        project storage and settings
  ui/          Jetpack Compose screens
```
