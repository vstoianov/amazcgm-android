package com.vstoianov.amazcgm.cgm

import com.vstoianov.amazcgm.libre.Sensor
import com.vstoianov.amazcgm.libre.SensorFamily
import com.vstoianov.amazcgm.libre.SensorRegion
import com.vstoianov.amazcgm.libre.SensorType

/** The [CgmType] an NFC-scanned Libre sensor belongs to, or null for sensors this app does not list. */
fun Sensor.cgmType(): CgmType? = when {
    family == SensorFamily.LIBRE3 -> CgmType.LIBRE3
    type == SensorType.LIBRE_US14DAY -> CgmType.LIBRE2_US
    type == SensorType.LIBRE2 || type == SensorType.LIBRE2_GEN2 -> when (region) {
        SensorRegion.USA -> CgmType.LIBRE2_US
        else -> CgmType.LIBRE2_EU
    }
    else -> null
}
