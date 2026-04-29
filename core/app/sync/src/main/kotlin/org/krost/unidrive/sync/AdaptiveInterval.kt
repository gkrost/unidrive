package org.krost.unidrive.sync

fun computePollInterval(
    idleCycles: Int,
    min: Int,
    normal: Int,
    max: Int,
): Int =
    when {
        idleCycles < 3 -> min
        idleCycles < 8 -> normal
        else -> max
    }

fun pollStateName(idleCycles: Int): String =
    when {
        idleCycles < 3 -> "ACTIVE"
        idleCycles < 8 -> "NORMAL"
        else -> "IDLE"
    }
