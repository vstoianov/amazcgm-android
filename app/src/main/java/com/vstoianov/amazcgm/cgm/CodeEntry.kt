package com.vstoianov.amazcgm.cgm

import org.json.JSONObject

/** Payload for sensors set up by code instead of an NFC scan (Sibionics, Dexcom). */
data class CodeEntry(
    val type: CgmType,
    val code: String,
    val serial: String? = null
) {
    init {
        require(type.input == InputMethod.CODE) { "${type.name} is not a code sensor" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", PAYLOAD_SCHEMA)
        put("sensorType", type.code.toString())
        put("code", code)
        serial?.takeIf { it.isNotBlank() }?.let { put("serial", it) }
    }
}

/** Version of the JSON layout. "1" (no field) was the Libre 2 EU only format. */
const val PAYLOAD_SCHEMA = "2"
