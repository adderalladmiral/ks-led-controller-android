# KS LED Controller (Android)

A native Android port of [H4ch1Net/ks-led-controller](https://github.com/H4ch1Net/ks-led-controller),
a reverse-engineered BLE controller for KS smart LED lights (the discontinued
KeepSmile app's devices). This app replaces the original Python/`bleak`
command-line tool and interactive terminal menu with a phone-native UI, using
the same byte-for-byte BLE command protocol.

## What's ported

- **Scanning** — finds nearby KS-prefixed BLE devices (`KS01-` … `KS15~`) via
  the Android BLE scanner, same as `scan_devices()` / `find_device_by_prefix()`.
- **On/Off** — `5BF001B5` / `5B0F01B5`.
- **RGB color** — floor-lamp (`5A0001RRGGBB00BB00A5`) and ceiling-light
  (`7E070503RRGGBB00EF`) command formats, auto-selected by device prefix.
- **Brightness** — floor lamps only (`5A000200000000BB00A5`), matching the
  original tool's limitation.
- **Color presets** — the same 10 default presets, plus add/delete/reset,
  stored on-device instead of `~/.ks_led_presets.json`.
- **Custom RGB picker** — live sliders with preview, optional "save as preset".
- **Device nicknames** — stored per BLE address instead of `~/.ks_led_devices.json`.
- **Batch KS03 on/off** — mirrors `led_control.py --all-ks03`.
- **Write fallback chain** — write-without-response → write-with-response →
  alternate characteristic (AFD3⇄FFF3), same order as the Python `write_command()`.

## What's not translatable

- **Cron / shell scripting** — Android apps can't run arbitrary cron jobs.
  Scheduling would need a separate feature (e.g. WorkManager-based schedules)
  and wasn't in scope for a straight port.
- **Verbose CLI flag** — replaced with an always-visible in-app connection log.
- **Command-line automation flags** (`--address`, `--timeout`) — not applicable
  to a touch UI; scanning and device selection replace them.

## Build

Requires Android Studio (or the command line with Android SDK installed):

```
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Continuous builds

Every push to `main` triggers `.github/workflows/build-release.yml`, which
builds the debug APK and publishes it as a GitHub Release. The first release
is tagged `1.0`; every subsequent build increments the tag by one (`2`, `3`, …).

## Disclaimer

Not affiliated with KeepSmile, KS Smart Light, or any official manufacturer.
Commands were reverse-engineered from a decompiled Android APK by the
original project author. Provided as-is, for personal/educational use.

Original project: MIT License, © H4ch1Net.
