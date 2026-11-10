# Changelog

All notable changes to the AmazCGM phone app (called Libre2 Key up to 1.9) are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions match `versionName` in `app/build.gradle.kts` and the `v<version>` git tags.

## [Unreleased]

## [1.10] - 2026-11-10

### Added
- Sensor type picker. Libre 2 / 2+ (EU) is the default and the only type AmazCGM uses today.
- Libre 2 / 2+ (US) and Libre 3 / 3+ can be selected for NFC scans.
- Sibionics SG1 / SG3 and Dexcom ONE+ / G7 can be set up by typing their code, without NFC.
- `schema` and `sensorType` fields in the JSON. See `docs/sensor-types.md` for the type codes.
- Release APKs on GitHub, signed with a dedicated release key.

### Changed
- Renamed from Libre2 Key to AmazCGM. The application ID is now `com.vstoianov.amazcgm`.
- A scan is refused, before anything is written to the sensor, when the sensor does not match the selected type.

### Upgrade note
- AmazCGM installs as a new app next to Libre2 Key. Uninstall Libre2 Key and scan the sensor again with AmazCGM. Stored unlock codes are not carried over.

## [1.9] - 2026-11-06

### Added
- Direct Bluetooth transfer to AmazCGM: the watch pulls the scan with **Sync from phone**, without Zepp.

## [1.4] - 2026-11-05

### Added
- First public version: NFC scan of FreeStyle Libre 2 / 2+ (EU), BLE streaming enable, unlock data and factory calibration exported as JSON for AmazCGM.

[Unreleased]: https://github.com/vstoianov/amazcgm-android/compare/v1.10...HEAD
[1.10]: https://github.com/vstoianov/amazcgm-android/releases/tag/v1.10
[1.9]: https://github.com/vstoianov/amazcgm-android/commit/79baf7f
[1.4]: https://github.com/vstoianov/amazcgm-android/commit/c3bb3d7
