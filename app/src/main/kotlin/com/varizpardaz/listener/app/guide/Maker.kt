package com.varizpardaz.listener.app.guide

import java.util.Locale

/** Phone makers whose Android adds its own background limits on top of the stock ones (see dontkillmyapp.com). */
enum class Maker { XIAOMI, SAMSUNG, HUAWEI, OPPO, VIVO, NOKIA, OTHER }

object MakerDetector {
    /** Sub-brands count with their parent: Redmi and POCO are Xiaomi, Honor is Huawei, realme and OnePlus are the OPPO family, iQOO is vivo. */
    fun detect(manufacturer: String?, brand: String?): Maker {
        val names = listOfNotNull(manufacturer, brand).map { it.trim().lowercase(Locale.ROOT) }
        fun any(vararg keys: String) = names.any { name -> keys.any { it in name } }
        return when {
            any("xiaomi", "redmi", "poco") -> Maker.XIAOMI
            any("samsung") -> Maker.SAMSUNG
            any("huawei", "honor") -> Maker.HUAWEI
            any("oppo", "realme", "oneplus") -> Maker.OPPO
            any("vivo", "iqoo") -> Maker.VIVO
            any("nokia", "hmd") -> Maker.NOKIA
            else -> Maker.OTHER
        }
    }
}
