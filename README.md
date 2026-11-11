# Ring0

Local-only health monitoring for the **Colmi R09 smart ring**: sleep, heart rate, HRV, stress, SpO2, skin temperature and
estimates derived from them. No cloud, no account, no subscription: the Android app talks to the ring directly over Bluetooth LE
and keeps everything on the phone (the app does not even request the INTERNET permission).

> Ring0 is an independent hobby project. It is not affiliated with or endorsed by Colmi, and it is **not a medical device**:
> every number is a consumer-grade estimate. If you feel unwell, talk to a doctor.

## What it does
- Reverse-engineered BLE protocol for the R09 (HR log, HRV, stress, SpO2, temperature, real-time RR and SpO2, find ring).
  Every command is documented with real hex examples in [`docs/protocol.md`](docs/protocol.md).
- Raw optical pulse signal (about 25 Hz, found in the ring's firmware: real-time types 13/14) instead of the ring's 30-second RR stream: the app
  detects the beats itself for HRV, breathing rate and a rhythm check over three minutes. See [`docs/protocol.md`](docs/protocol.md).
- Android app (Kotlin, Compose, Room, foreground service): live values, history charts, alerts with context, recovery score,
  illness early warning, incident log with triage, guided checks, English and Italian UI.
- Pure Kotlin domain module with unit tests: HRV (RMSSD/SDNN), respiration from RR (RSA), readiness, sleep metrics,
  nocturnal SpO2, training load (Banister TRIMP), calories (Mifflin-St Jeor, Keytel).
- Estimates are researched before they are implemented: see [`docs/research/`](docs/research/stime.md) for methods, evidence and limits.

The R09 has **no vibration motor**, so alarms and haptic notifications are not possible; "find ring" lights its green LED.

## Repository
- `android/` Android app and Kotlin modules (`protocol`, `domain`, `migrations`, `data`, `app`). Quality gate: `./check.sh` (ktlint, detekt, tests, build).
- `proto/` Python prototype used to explore the protocol (bleak). Quality gate: `./check.sh`.
- `docs/` protocol, plan, roadmap, research notes (written in Italian), design mockup.

## Build
See [`docs/android-build.md`](docs/android-build.md). In short: JDK 21, Android SDK, then `cd android && ./gw.sh :app:assembleDebug`.
(`gw.sh` runs Gradle without a daemon; the notes explain why it was needed on the author's machine.)

## Help wanted
- **Cycle estimate** (optional, experimental, in Settings when the profile is female): it looks for the sustained rise of nightly skin
  temperature after ovulation. It was only tested on synthetic data, because the author cannot try it. If you track your cycle and
  want to compare it with the app, feedback is very welcome.
- **Rhythm check** (three minutes at rest, from the raw pulse signal): tuned on public ECG data and tried on one person with a regular pulse; reports from people with known atrial
  fibrillation or frequent extra beats would show how it behaves with a real irregular pulse.

## Rules of the project
- One ring only: no firmware flashing, reboot, OTA or factory reset without explicit approval.
- Every discovered command goes into `docs/protocol.md` with a real hex example.
- No personal data in the repository: raw captures are ignored by git.

## License
GPL-3.0-or-later, see [`LICENSE`](LICENSE).

## Acknowledgements
Protocol knowledge was cross-checked against open projects: [colmi_r02_client](https://github.com/tahnok/colmi_r02_client) (MIT),
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) (AGPL-3.0) and
[ATC_RF03_Ring](https://github.com/atc1441/ATC_RF03_Ring) (GPL-3.0) and
[colmi-r09-smart-ring](https://github.com/patmorli/colmi-r09-smart-ring) (MIT). No code was copied from them.
The rhythm check was tuned on, and its tests use short excerpts of, the
[MIT-BIH Atrial Fibrillation Database](https://physionet.org/content/afdb/1.0.0/) (PhysioNet, ODC-By 1.0; Moody & Mark, 1983).
