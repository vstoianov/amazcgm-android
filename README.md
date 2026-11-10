# AmazCGM for Android

The AmazCGM phone app sets up sensors for the AmazCGM watch app. It reads a FreeStyle Libre 2 Plus (EU) sensor over NFC once, turns on the sensor's Bluetooth stream, and gives you the unlock data to hand to the AmazCGM watch app.

You can also pick other sensors: Libre 2 / 2+ (US) and Libre 3 / 3+ are scanned over NFC; Sibionics SG1 / SG3 and Dexcom ONE+ / G7 are set up by typing their code. AmazCGM only uses Libre 2 / 2+ (EU) today, which is the default. The data for the other sensors is already sent, so a later AmazCGM version can use it. See [docs/sensor-types.md](docs/sensor-types.md).

## Use

1. Open the AmazCGM phone app and check **Sensor type** at the top. Leave it on **Libre 2 / 2+ (EU)** for the watch app today. For a Sibionics or Dexcom sensor, choose it, type its code and tap **Save code**, then skip to step 3.
2. Hold the sensor against the back of the phone. The app shows the sensor serial number and patch info.
3. Tap **Share JSON** and send it to the phone that has the Zepp app, for example your iPhone. **Copy JSON** puts it on the clipboard instead, when Zepp runs on this phone.
4. In Zepp open **Profile → your watch → App settings → AmazCGM** and tap **Paste JSON**.
5. On the watch open AmazCGM and tap **Sync from phone**.

### Direct Bluetooth

After a scan, the phone is also visible to the watch over Bluetooth for 10 minutes, so **Sync from phone** can pull the scan straight from this phone without Zepp. Tap **Make visible to watch** to open a new window. This needs the **Nearby devices** permission. While visible, the phone calls itself **AMZCGM** in Bluetooth; its normal name is restored afterwards. If it does not connect, the Share JSON steps above always work.

## Bluetooth protocol

| Item | Value |
|---|---|
| Service | `AC10` (`0000ac10-0000-1000-8000-00805f9b34fb`) |
| Control, write | `AC11`: the watch writes `GET`, then `ACK` |
| Data, notify | `AC12`: frames `[seq][total][≤18 bytes of JSON]` |
| Advert | name `AMZCGM`, service UUID `AC10`, service data `[0x01][CRC32 LE]`; scan response: manufacturer data, company `0xFFFF`: `AMZCGM`, version `0x01`, CRC32 LE |

The watch compares the advert CRC with its stored scan and skips the transfer when they match. Anyone nearby could read the scan while the phone is visible, so visibility is limited to 10 minutes and ends after the first transfer.

To check the phone side without a watch, run the bench script on a laptop with Bluetooth:

```sh
pip install bleak
python3 tools/ble_bench.py
```

## What the JSON contains

Only what AmazCGM uses:

| Field | Meaning |
|---|---|
| `schema` | Payload layout version, currently `2` |
| `sensorType` | Sensor type code, `257` for Libre 2 / 2+ (EU). See [docs/sensor-types.md](docs/sensor-types.md). Missing in payloads from 1.9 and older, which are always Libre 2 / 2+ (EU) |
| `serial` | Sensor serial, matches the Bluetooth name |
| `uid`, `patchInfo` | Sensor identity, used to derive unlock and decryption keys |
| `bleAddress` | Sensor Bluetooth address |
| `unlockCode`, `nextUnlockCount` | Bluetooth unlock key and counter |
| `streamingEnabled` | Present when this scan re-enabled streaming, so the watch resets its counter |
| `calibration` | Factory calibration from sensor memory, when its checksum is valid |
| `code` | Sibionics and Dexcom only: the sensor code you typed |

## Download

Signed APKs are on the [Releases page](https://github.com/vstoianov/amazcgm-android/releases/latest). Download `amazcgm-<version>.apk` and its `.sha256` file, then check and install:

```sh
sha256sum -c amazcgm-<version>.apk.sha256
adb install -r amazcgm-<version>.apk
```

You can also open the APK on the phone and allow installing from that source.

Release certificate SHA-256: _added with the first signed release_

**Upgrading from Libre2 Key 1.9 or older:** this app was called Libre2 Key until 1.9. It now installs as a new app (`com.vstoianov.amazcgm`) next to the old one. Uninstall Libre2 Key, then scan the sensor again with AmazCGM. The old app's stored unlock codes are not carried over.

Maintainers: see [RELEASING.md](RELEASING.md).

## Build and install

### 1. Prerequisites

- **JDK 17.** Check with `java -version`.
- **Android SDK** with platform `android-36` and the build tools. Install them with Android Studio's SDK Manager, or with the command-line tools:

  ```sh
  sdkmanager "platforms;android-36" "build-tools;36.1.0" "platform-tools"
  ```

- **adb**, from the SDK's `platform-tools`. Add it to your `PATH`:

  ```sh
  export ANDROID_HOME="$HOME/Android/Sdk"
  export PATH="$PATH:$ANDROID_HOME/platform-tools"
  ```

- **`local.properties`** in the project root, pointing at the SDK. Android Studio creates it. Otherwise create it yourself:

  ```sh
  echo "sdk.dir=$HOME/Android/Sdk" > local.properties
  ```

You do not need to install Gradle. The `./gradlew` wrapper downloads the right version the first time it runs.

### 2. Build

From the project root:

```sh
chmod +x gradlew                 # only needed once, if the script is not executable
./gradlew testDebugUnitTest      # run the unit tests
./gradlew assembleDebug          # build the debug APK
```

The APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

To build a release APK instead, run the command below. Without a release key (see [RELEASING.md](RELEASING.md)) it is signed with the debug key, so it installs the same way.

```sh
./gradlew assembleRelease
```

It is written to `app/build/outputs/apk/release/app-release.apk`.

If a build behaves oddly after changing branches, start clean:

```sh
./gradlew clean assembleDebug
```

### 3. Prepare the phone

1. Open **Settings → About phone** and tap **Build number** seven times to unlock developer options.
2. Open **Settings → System → Developer options** and turn on **USB debugging**.
3. Connect the phone with a USB cable. When the phone asks **Allow USB debugging?**, tap **Allow**.
4. Check that adb sees the phone:

   ```sh
   adb devices
   ```

   The phone should be listed as `device`. If it shows `unauthorized`, unlock the phone and accept the prompt. If it is not listed, try another cable or USB port.

#### Optional: install over Wi‑Fi instead of USB (Android 11+)

1. In **Developer options** turn on **Wireless debugging** and open it.
2. Tap **Pair device with pairing code**. Then pair using the IP, port and code the phone shows:

   ```sh
   adb pair 192.168.1.23:37000      # enter the 6-digit code when asked
   ```

3. Connect using the IP and port shown on the main **Wireless debugging** screen. That port is different from the pairing port.

   ```sh
   adb connect 192.168.1.23:41000
   adb devices
   ```

### 4. Install the APK

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` replaces an installed copy and keeps its data, including the stored unlock codes.

Gradle can also build and install in one step:

```sh
./gradlew installDebug
```

If several devices are connected, choose one by serial number from `adb devices`:

```sh
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

### 5. Start the app

Open **AmazCGM** from the launcher, or start it from the computer:

```sh
adb shell am start -n com.vstoianov.amazcgm/.MainActivity
```

On first start, allow **Nearby devices** and notifications. They are only needed for the direct Bluetooth transfer.

### Troubleshooting

- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`:** the installed copy was signed with a different key. Uninstall it first. This deletes the app's stored data.

  ```sh
  adb uninstall com.vstoianov.amazcgm
  adb install app/build/outputs/apk/debug/app-debug.apk
  ```

- **`SDK location not found`:** `local.properties` is missing or points to the wrong folder. See step 1.
- **View the app's log:**

  ```sh
  adb logcat --pid=$(adb shell pidof -s com.vstoianov.amazcgm)
  ```
## Disclaimer

The AmazCGM phone and watch apps are not medical devices. They are not made, approved or supported by Abbott, Sibionics, Dexcom or Zepp Health.

- Do not make treatment decisions, such as insulin doses, from readings shown by these apps. Check with a blood glucose meter and follow the sensor maker's official app and your healthcare team.
- **Enable BLE streaming** writes a new unlock code to the sensor. Other apps or readers that use the sensor may lose their connection.
- You use this software at your own risk. It comes with no warranty, as stated in sections 15 and 16 of the GPL.

## License

The AmazCGM phone app is licensed under the [GNU General Public License v3.0 or later](LICENSE). You may use, study, change and share it. If you distribute a changed version, it must also be released under the GPL with its source code.

Bundled third-party code keeps its own MIT license. See [NOTICE](NOTICE).

FreeStyle Libre is a trademark of Abbott. Amazfit and Zepp are trademarks of Zepp Health. They are used here only to describe compatibility.