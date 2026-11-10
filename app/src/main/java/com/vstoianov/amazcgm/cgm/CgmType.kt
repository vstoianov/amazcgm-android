package com.vstoianov.amazcgm.cgm

/** How the app obtains a sensor's data. */
enum class InputMethod {
    /** Read the sensor over NFC. */
    NFC,

    /** The user types a code printed on the sensor or its package. */
    CODE
}

enum class CgmVendor(val code: Int, val label: String) {
    ABBOTT(0x01, "Abbott"),
    SIBIONICS(0x02, "Sibionics"),
    DEXCOM(0x03, "Dexcom")
}

/**
 * Sensor types this app can hand to AmazCGM, sent as `sensorType` in the JSON.
 *
 * [code] is a 16-bit number: high byte = [CgmVendor.code], low byte = model.
 * `0x0000` is reserved for "unknown". Codes are a contract with the watch app:
 * never reuse or renumber one. Add new sensors with a new code and list them in
 * docs/sensor-types.md.
 */
enum class CgmType(
    val code: Int,
    val vendor: CgmVendor,
    val label: String,
    val input: InputMethod,
    /** Accepted format for [InputMethod.CODE] types, checked after trimming and upper-casing. */
    val codeFormat: Regex? = null,
    /** True when the current AmazCGM release can use this sensor. */
    val amazcgmSupported: Boolean = false
) {
    LIBRE2_EU(0x0101, CgmVendor.ABBOTT, "Libre 2 / 2+ (EU)", InputMethod.NFC, amazcgmSupported = true),
    LIBRE2_US(0x0102, CgmVendor.ABBOTT, "Libre 2 / 2+ (US)", InputMethod.NFC),
    LIBRE3(0x0103, CgmVendor.ABBOTT, "Libre 3 / 3+", InputMethod.NFC),
    SIBIONICS_SG1(0x0201, CgmVendor.SIBIONICS, "Sibionics SG1", InputMethod.CODE, Regex("^[A-Z0-9]{4,16}$")),
    SIBIONICS_SG3(0x0202, CgmVendor.SIBIONICS, "Sibionics SG3", InputMethod.CODE, Regex("^[A-Z0-9]{4,16}$")),
    DEXCOM_ONE_PLUS(0x0301, CgmVendor.DEXCOM, "Dexcom ONE+", InputMethod.CODE, Regex("^[0-9]{4}$")),
    DEXCOM_G7(0x0302, CgmVendor.DEXCOM, "Dexcom G7", InputMethod.CODE, Regex("^[0-9]{4}$"));

    /** Normalizes user input; returns null when it does not match [codeFormat]. */
    fun normalizeCode(raw: String?): String? {
        val value = raw?.trim()?.uppercase()?.replace(" ", "")?.takeIf { it.isNotEmpty() } ?: return null
        val format = codeFormat ?: return value
        return value.takeIf { format.matches(it) }
    }

    companion object {
        /** The only sensor AmazCGM supports today. Also assumed when a payload has no `sensorType`. */
        val DEFAULT = LIBRE2_EU

        fun of(code: Int): CgmType? = entries.firstOrNull { it.code == code }
    }
}
