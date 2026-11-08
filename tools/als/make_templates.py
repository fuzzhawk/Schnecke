"""Build the Ableton Live 10 templates used by the app's .als exporter.

The templates are cut from the structure Live 10.0.6 itself writes. The source is the
anonymised Live 10.0.6 version fixture in abletoolz (https://github.com/elixirbeats/abletoolz,
MPL-2.0): test/version_fixtures/skeletons/10.0.6.als. That fixture keeps Live's element
structure but scrambles path-like values and drops plugin data, so this tool:

* keeps one MIDI track, one audio track, the master and the pre-hear (cue) track,
* removes every device, clip, automation envelope, send and preset reference,
* restores the routing targets the fixture's anonymiser scrambled,
* replaces every set-wide id (AutomationTarget, *ModulationTarget, ControllerTargets.N) with
  {{PID}}, which the exporter renumbers, and leaves other placeholders for names and content.

Usage: python3 make_templates.py PATH/TO/10.0.6.als OUTPUT_DIR
"""

from __future__ import annotations

import gzip
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

POINTEE_TAG = re.compile(r"^(AutomationTarget|\w*ModulationTarget|ControllerTargets\.\d+|Pointee)$")

ROUTING = {
    "AudioInputRouting": ("AudioIn/External/S0", "Ext. In", "1/2"),
    "MidiInputRouting": ("MidiIn/External.All/-1", "Ext: All Ins", ""),
    "AudioOutputRouting": ("AudioOut/Master", "Master", ""),
    "MidiOutputRouting": ("MidiOut/None", "None", ""),
}


def set_value(el: ET.Element, path: str, value: str) -> None:
    node = el.find(path)
    if node is None:
        raise SystemExit(f"missing {path} under {el.tag}")
    node.set("Value", value)


def clear(el: ET.Element, path: str, placeholder: str | None = None) -> None:
    node = el.find(path)
    if node is None:
        raise SystemExit(f"missing {path} under {el.tag}")
    for child in list(node):
        node.remove(child)
    node.text = placeholder


def placeholder_ids(el: ET.Element) -> None:
    for node in el.iter():
        if "Id" in node.attrib and POINTEE_TAG.match(node.tag):
            node.set("Id", "{{PID}}")


def routings(chain: ET.Element, output: tuple[str, str, str] | None = None) -> None:
    for tag, (target, upper, lower) in ROUTING.items():
        if tag == "AudioOutputRouting" and output is not None:
            target, upper, lower = output
        node = chain.find(tag)
        if node is None:
            continue
        set_value(node, "Target", target)
        set_value(node, "UpperDisplayString", upper)
        set_value(node, "LowerDisplayString", lower)


def common_track(track: ET.Element, name: str) -> None:
    set_value(track, "Name/EffectiveName", name)
    set_value(track, "Name/UserName", name if name.startswith("{{") else "")
    set_value(track, "Name/Annotation", "")
    set_value(track, "Name/MemorizedFirstClipName", "")
    clear(track, "AutomationEnvelopes/Envelopes")
    set_value(track, "TrackGroupId", "-1")
    chain = track.find("DeviceChain")
    lanes = chain.find("AutomationLanes/AutomationLanes")
    for extra in list(lanes)[1:]:
        lanes.remove(extra)
    for lane in lanes:
        for sub in ("SelectedDevice", "SelectedEnvelope"):
            if lane.find(sub) is not None:
                set_value(lane, sub, "0")
    set_value(chain, "ClipEnvelopeChooserViewState/SelectedDevice", "0")
    set_value(chain, "ClipEnvelopeChooserViewState/SelectedEnvelope", "0")
    mixer = chain.find("Mixer")
    clear(mixer, "Sends")
    set_value(mixer, "Volume/Manual", "{{VOLUME}}")
    set_value(mixer, "Pan/Manual", "{{PAN}}")
    set_value(mixer, "Speaker/Manual", "true")
    set_value(mixer, "On/Manual", "true")
    clear(chain, "DeviceChain/Devices")
    # Presets and source contexts point at files; an empty track has none.
    for node in track.iter():
        if node.tag in ("LastPresetRef", "SourceContext"):
            value = node.find("Value")
            if value is not None:
                for child in list(value):
                    value.remove(child)
    placeholder_ids(track)


def player_track(track: ET.Element, clip_container: str) -> None:
    track.set("Id", "{{TRACK_ID}}")
    common_track(track, "{{NAME}}")
    set_value(track, "ColorIndex", "{{COLOR}}")
    set_value(track, "TrackUnfolded", "true")
    set_value(track, "SavedPlayingSlot", "-1")
    set_value(track, "SavedPlayingOffset", "0")
    set_value(track, "Freeze", "false")
    chain = track.find("DeviceChain")
    routings(chain)
    main = chain.find("MainSequencer")
    clear(main, "ClipSlotList", "{{CLIP_SLOTS}}")
    clear(main, f"{clip_container}/ArrangerAutomation/Events", "{{ARRANGER_CLIPS}}")
    set_value(main, "MonitoringEnum", "1")
    set_value(main, "Recorder/IsArmed", "false")
    freeze = chain.find("FreezeSequencer")
    clear(freeze, "ClipSlotList", "{{FREEZE_CLIP_SLOTS}}")
    clear(freeze, "Sample/ArrangerAutomation/Events")
    set_value(freeze, "Recorder/IsArmed", "false")


def master_track(track: ET.Element) -> None:
    common_track(track, "Master")
    chain = track.find("DeviceChain")
    routings(chain, output=("AudioOut/External/S0", "Ext. Out", "1/2"))
    mixer = chain.find("Mixer")
    set_value(mixer, "Tempo/Manual", "{{TEMPO}}")
    set_value(mixer, "TimeSignature/Manual", "{{TIME_SIGNATURE}}")
    set_value(mixer, "Volume/Manual", "1")
    set_value(mixer, "Pan/Manual", "0")
    freeze = chain.find("FreezeSequencer/AudioSequencer")
    if freeze is not None:
        # Live writes no clip slots for the master's freeze sequencer.
        clear(freeze, "ClipSlotList")
        clear(freeze, "Sample/ArrangerAutomation/Events")


def prehear_track(track: ET.Element) -> None:
    chain = track.find("DeviceChain")
    out = chain.find("AudioOutputRouting/Target").get("Value")
    common_track(track, "Master")
    set_value(track, "Name/EffectiveName", "Master")
    routings(chain, output=("AudioOut/External/S0", "Ext. Out", "1/2") if out.startswith("p") else None)
    mixer = chain.find("Mixer")
    set_value(mixer, "Volume/Manual", "1")
    set_value(mixer, "Pan/Manual", "0")
    freeze = chain.find("FreezeSequencer/AudioSequencer")
    if freeze is not None:
        clear(freeze, "ClipSlotList")
        clear(freeze, "Sample/ArrangerAutomation/Events")


def liveset(ls: ET.Element) -> None:
    set_value(ls, "NextPointeeId", "{{NEXT_POINTEE_ID}}")
    clear(ls, "Tracks", "{{TRACKS}}")
    clear(ls, "SendsPre")
    clear(ls, "SceneNames", "{{SCENES}}")
    set_value(ls, "Transport/LoopOn", "false")
    set_value(ls, "Transport/LoopStart", "0")
    set_value(ls, "Transport/LoopLength", "{{LOOP_LENGTH}}")
    set_value(ls, "Transport/CurrentTime", "0")
    pos = ls.find("SongMasterValues/SessionScrollerPos")
    pos.set("X", "0")
    pos.set("Y", "0")
    set_value(ls, "TimeSelection/AnchorTime", "0")
    set_value(ls, "TimeSelection/OtherTime", "0")
    scroller = ls.find("SequencerNavigator/ScrollerPos")
    scroller.set("X", "0")
    scroller.set("Y", "0")
    set_value(ls, "HighlightedTrackIndex", "0")


def serialize(el: ET.Element, level: int) -> str:
    ET.indent(el, space="\t", level=level)
    text = ET.tostring(el, encoding="unicode")
    return "\t" * level + text.rstrip() + "\n"


def main() -> None:
    src, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    root = ET.fromstring(gzip.decompress(src.read_bytes()))
    if root.get("MinorVersion") != "10.0_370":
        raise SystemExit(f"expected a Live 10.0.6 set, got {root.get('Creator')}")
    ls = root.find("LiveSet")
    tracks = ls.find("Tracks")
    midi = next(t for t in tracks if t.tag == "MidiTrack")
    audio = next(t for t in tracks if t.tag == "AudioTrack")
    player_track(midi, "ClipTimeable")
    player_track(audio, "Sample")
    master_track(ls.find("MasterTrack"))
    prehear_track(ls.find("PreHearTrack"))
    liveset(ls)

    (out_dir / "live10_midi_track.xml").write_text(serialize(midi, 3), encoding="utf-8")
    (out_dir / "live10_audio_track.xml").write_text(serialize(audio, 3), encoding="utf-8")

    attrs = " ".join(f'{k}="{v}"' for k, v in root.attrib.items())
    body = serialize(ls, 1)
    doc = f'<?xml version="1.0" encoding="UTF-8"?>\n<Ableton {attrs}>\n{body}</Ableton>\n'
    (out_dir / "live10_set.xml").write_text(doc, encoding="utf-8")

    leftovers = re.findall(r'Value="p\d{5}[^"]*"', doc + (out_dir / "live10_midi_track.xml").read_text() + (out_dir / "live10_audio_track.xml").read_text())
    if leftovers:
        raise SystemExit(f"scrambled values left: {sorted(set(leftovers))[:10]}")
    print("templates written to", out_dir)


if __name__ == "__main__":
    main()
