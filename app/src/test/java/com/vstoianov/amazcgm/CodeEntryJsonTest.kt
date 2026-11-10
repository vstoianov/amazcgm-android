package com.vstoianov.amazcgm

import com.vstoianov.amazcgm.ble.ScanFrames
import com.vstoianov.amazcgm.cgm.CgmType
import com.vstoianov.amazcgm.cgm.CodeEntry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CodeEntryJsonTest {

    @Test
    fun `dexcom G7 entry json`() {
        val json = CodeEntry(CgmType.DEXCOM_G7, "1234").toJson()
        assertEquals(setOf("schema", "sensorType", "code"), json.keys().asSequence().toSet())
        assertEquals("2", json.getString("schema"))
        assertEquals("770", json.getString("sensorType"))
        assertEquals("1234", json.getString("code"))
        assertFalse(json.has("serial"))
    }

    @Test
    fun `serial is included when given`() {
        val json = CodeEntry(CgmType.SIBIONICS_SG1, "AB12CD", "LT12345").toJson()
        assertEquals("513", json.getString("sensorType"))
        assertEquals("LT12345", json.getString("serial"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `NFC types are rejected`() {
        CodeEntry(CgmType.LIBRE2_EU, "1234")
    }

    @Test
    fun `code entry survives BLE framing`() {
        val bytes = CodeEntry(CgmType.DEXCOM_ONE_PLUS, "0042", "SN1").toJson().toString().toByteArray()
        val back = ScanFrames.reassemble(ScanFrames.frames(bytes).reversed())
        assertArrayEquals(bytes, back)
        assertEquals("769", JSONObject(String(back!!)).getString("sensorType"))
    }
}
