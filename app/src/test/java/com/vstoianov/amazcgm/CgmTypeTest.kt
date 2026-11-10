package com.vstoianov.amazcgm

import com.vstoianov.amazcgm.cgm.CgmType
import com.vstoianov.amazcgm.cgm.InputMethod
import com.vstoianov.amazcgm.cgm.cgmType
import com.vstoianov.amazcgm.libre.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CgmTypeTest {

    private fun bytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Codes are shared with AmazCGM. Changing one here means the watch misreads payloads. */
    @Test
    fun `codes are frozen`() {
        assertEquals(
            mapOf(
                "LIBRE2_EU" to 0x0101,
                "LIBRE2_US" to 0x0102,
                "LIBRE3" to 0x0103,
                "SIBIONICS_SG1" to 0x0201,
                "SIBIONICS_SG3" to 0x0202,
                "DEXCOM_ONE_PLUS" to 0x0301,
                "DEXCOM_G7" to 0x0302
            ),
            CgmType.entries.associate { it.name to it.code }
        )
    }

    @Test
    fun `codes are unique and carry the vendor in the high byte`() {
        assertEquals(CgmType.entries.size, CgmType.entries.map { it.code }.toSet().size)
        for (type in CgmType.entries) {
            assertEquals(type.name, type.vendor.code, type.code shr 8)
            assert(type.code in 0x0001..0xFFFF)
        }
    }

    @Test
    fun `default is Libre 2 EU`() {
        assertEquals(CgmType.LIBRE2_EU, CgmType.DEFAULT)
        assertEquals(InputMethod.NFC, CgmType.DEFAULT.input)
        assertEquals(listOf(CgmType.LIBRE2_EU), CgmType.entries.filter { it.amazcgmSupported })
    }

    @Test
    fun `lookup by code`() {
        assertEquals(CgmType.DEXCOM_G7, CgmType.of(0x0302))
        assertNull(CgmType.of(0))
        assertNull(CgmType.of(0x0999))
    }

    @Test
    fun `code formats`() {
        assertEquals("1234", CgmType.DEXCOM_G7.normalizeCode(" 1234 "))
        assertNull(CgmType.DEXCOM_G7.normalizeCode("12a4"))
        assertNull(CgmType.DEXCOM_ONE_PLUS.normalizeCode("12345"))
        assertEquals("AB12CD", CgmType.SIBIONICS_SG1.normalizeCode("ab12 cd"))
        assertNull(CgmType.SIBIONICS_SG3.normalizeCode(""))
    }

    @Test
    fun `european Libre 2 scan maps to Libre 2 EU`() {
        val sensor = Sensor(bytes("2fe7b10000a407e0"), bytes("9d083001712b"))
        assertEquals(CgmType.LIBRE2_EU, sensor.cgmType())
    }
}
