package com.watchcoach.app.ble

import java.util.UUID

/**
 * Known GloryFit / UTE-family GATT identifiers used by many low-cost Realtek
 * watches (the Crossbeats Everest 2.0 companion, CB SmartFit, is widely
 * reported as a GloryFit rebrand). Presence of these UUIDs is a strong hint,
 * not a guarantee, that Gadgetbridge-style protocol work may apply.
 *
 * 16-bit IDs expand to Bluetooth SIG base UUIDs:
 *   0000XXXX-0000-1000-8000-00805f9b34fb
 */
object GloryFitUuids {
    val SERVICE_55FF: UUID = uuid16(0x55FF)
    val CHAR_33F1: UUID = uuid16(0x33F1)
    val SERVICE_56FF: UUID = uuid16(0x56FF)
    val CHAR_34F1: UUID = uuid16(0x34F1)
    val CHAR_33F2: UUID = uuid16(0x33F2)
    val CHAR_34F2: UUID = uuid16(0x34F2)

    val ALL: Set<UUID> = setOf(
        SERVICE_55FF, CHAR_33F1, CHAR_33F2,
        SERVICE_56FF, CHAR_34F1, CHAR_34F2,
    )

    fun uuid16(id: Int): UUID =
        UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(id))

    fun isKnown(uuid: UUID): Boolean = ALL.contains(uuid)

    fun shortLabel(uuid: UUID): String {
        val s = uuid.toString().lowercase()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            "0x" + s.substring(4, 8).uppercase()
        } else {
            uuid.toString()
        }
    }
}
