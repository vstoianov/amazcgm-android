# Sensor types

The AmazCGM phone app tells the AmazCGM watch app which sensor the data belongs to with the `sensorType` field. It is a 16-bit number sent as a decimal string. The high byte is the vendor and the low byte is the model.

This table is the contract between the phone app and the watch app. A code is never reused or renumbered. New sensors get a new code at the end of their vendor's range.

| Code (hex) | `sensorType` | Vendor | Sensor | Input | AmazCGM |
|---|---|---|---|---|---|
| `0x0000` | `0` | | Reserved: unknown | | |
| `0x0101` | `257` | Abbott | FreeStyle Libre 2 / 2+ (EU) | NFC scan | Supported (default) |
| `0x0102` | `258` | Abbott | FreeStyle Libre 2 / 2+ (US) | NFC scan | Not yet |
| `0x0103` | `259` | Abbott | FreeStyle Libre 3 / 3+ | NFC scan | Not yet |
| `0x0201` | `513` | Sibionics | SG1 | Code | Not yet |
| `0x0202` | `514` | Sibionics | SG3 | Code | Not yet |
| `0x0301` | `769` | Dexcom | ONE+ | 4-digit pairing code | Not yet |
| `0x0302` | `770` | Dexcom | G7 | 4-digit pairing code | Not yet |

Vendor bytes: `0x01` Abbott, `0x02` Sibionics, `0x03` Dexcom.

The source of truth in the app is [`CgmType.kt`](../app/src/main/java/com/vstoianov/amazcgm/cgm/CgmType.kt). `CgmTypeTest` fails if a code changes.

## Payloads

Every payload has `"schema": "2"` and `sensorType`. All values are strings, because some Zepp JSON parsers drop or coerce numbers.

**Compatibility:** a payload without `sensorType` comes from the phone app 1.9 or older, when it was called Libre2 Key. Treat it as Libre 2 / 2+ EU (`257`).

### NFC sensors (Abbott)

```json
{
  "schema": "2",
  "sensorType": "257",
  "serial": "3MH001A2B3C4",
  "uid": "2FE7B10000A407E0",
  "patchInfo": "9D083001712B",
  "bleAddress": "AA:BB:CC:DD:EE:FF",
  "unlockCode": "42",
  "nextUnlockCount": "1",
  "streamingEnabled": "1",
  "calibration": { "i1": "1", "i2": "8", "i3": "0", "i4": "6000", "i5": "320", "i6": "5000" }
}
```

The unlock fields are only filled for Libre 2 / 2+ EU. For Libre 2 US and Libre 3 the payload carries the sensor identity (`serial`, `uid`, `patchInfo`).

The app refuses a scan when the sensor does not match the selected type, before anything is written to the sensor.

### Code sensors (Sibionics, Dexcom)

```json
{
  "schema": "2",
  "sensorType": "770",
  "code": "1234",
  "serial": "SN12345"
}
```

`serial` is optional. Accepted code formats:

| Sensor | `code` format |
|---|---|
| Dexcom ONE+, G7 | 4 digits |
| Sibionics SG1, SG3 | 4–16 letters or digits, upper case |

## Transport

The payload goes over the same paths for every sensor: Share JSON, Copy JSON, or direct Bluetooth. The Bluetooth advert format is unchanged (version `0x01`). `sensorType` is inside the JSON, so it is covered by the advert CRC.
