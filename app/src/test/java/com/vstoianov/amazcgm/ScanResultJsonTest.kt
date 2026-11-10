package com.vstoianov.amazcgm

import com.vstoianov.amazcgm.cgm.CgmType
import com.vstoianov.amazcgm.libre.FactoryCalibration
import com.vstoianov.amazcgm.libre.FramInfo
import com.vstoianov.amazcgm.libre.Sensor
import com.vstoianov.amazcgm.libre.SensorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ScanResultJsonTest {

    private fun bytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `json contains only the fields the watch reads`() {
        val result = ScanResult(
            type = CgmType.LIBRE2_EU,
            time = Date(),
            sensor = Sensor(bytes("2fe7b10000a407e0"), bytes("9d083001712b")),
            fram = null,
            framError = null,
            bleAddress = bytes("ffeeddccbbaa"),
            unlockCode = 42,
            nextUnlockCount = 1,
            streamingWasEnabled = true,
            notes = emptyList()
        )
        val json = result.toJson()
        val keys = json.keys().asSequence().toSet()
        assertEquals(
            setOf(
                "schema",
                "sensorType",
                "serial",
                "uid",
                "patchInfo",
                "bleAddress",
                "unlockCode",
                "nextUnlockCount",
                "streamingEnabled"
            ),
            keys
        )
        assertFalse(json.has("scannedAt"))
        assertFalse(json.has("sensorStatus"))
        assertFalse(json.has("bleService"))
        assertEquals("AA:BB:CC:DD:EE:FF", json.getString("bleAddress"))
        assertEquals("42", json.getString("unlockCode"))
        assertEquals("2", json.getString("schema"))
        assertEquals("257", json.getString("sensorType"))
        assertEquals("1", json.getString("streamingEnabled"))
        assertFalse(json.has("unlockPayloads"))
        assertTrue(json.getString("uid").equals("2FE7B10000A407E0", ignoreCase = true))
    }

    @Test
    fun `read-only scan does not claim a fresh streaming enable`() {
        val result = ScanResult(
            type = CgmType.LIBRE2_EU,
            time = Date(),
            sensor = Sensor(bytes("2fe7b10000a407e0"), bytes("9d083001712b")),
            fram = null,
            framError = null,
            bleAddress = bytes("ffeeddccbbaa"),
            unlockCode = 42,
            nextUnlockCount = 1,
            streamingWasEnabled = false,
            notes = emptyList()
        )
        assertFalse(result.toJson().has("streamingEnabled"))
    }

    @Test
    fun `json includes factory calibration when FRAM CRC is ok`() {
        val result = ScanResult(
            type = CgmType.LIBRE2_EU,
            time = Date(),
            sensor = Sensor(bytes("2fe7b10000a407e0"), bytes("9d083001712b")),
            fram = FramInfo(
                headerCrcOk = true,
                bodyCrcOk = true,
                footerCrcOk = true,
                state = SensorState.ACTIVE,
                ageMinutes = 100,
                maxLifeMinutes = 20160,
                initializations = 1,
                latestRawGlucose = 900,
                currentMgdl = 100,
                calibration = FactoryCalibration(1, 8, 0, 6000, 320, 5000)
            ),
            framError = null,
            bleAddress = bytes("ffeeddccbbaa"),
            unlockCode = 42,
            nextUnlockCount = 1,
            streamingWasEnabled = false,
            notes = emptyList()
        )
        val json = result.toJson()
        assertTrue(json.has("calibration"))
        val cal = json.getJSONObject("calibration")
        assertEquals("1", cal.getString("i1"))
        assertEquals("8", cal.getString("i2"))
        assertEquals("0", cal.getString("i3"))
        assertEquals("6000", cal.getString("i4"))
        assertEquals("320", cal.getString("i5"))
        assertEquals("5000", cal.getString("i6"))
    }
}
