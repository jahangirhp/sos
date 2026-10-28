package com.sos.nearby

internal object PersianAnnouncement {
    fun text(name: String, unit: String): String =
        "درخواست کمک اضطراری! ${clean(name)}، واحد ${digits(clean(unit))}، به کمک نیاز دارد."

    private fun clean(value: String) = value.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").trim()
    private fun digits(value: String) = value.map {
        when (it) {
            in '0'..'9' -> '۰' + (it - '0')
            in '٠'..'٩' -> '۰' + (it - '٠')
            else -> it
        }
    }.joinToString("")
}
