package com.varizpardaz.listener.app.guide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MakerDetectorTest {
    private fun detect(manufacturer: String?, brand: String?) = MakerDetector.detect(manufacturer, brand)

    @Test
    fun `makers are recognised by manufacturer or brand, in any letter case`() {
        assertEquals(Maker.XIAOMI, detect("Xiaomi", "Xiaomi"))
        assertEquals(Maker.SAMSUNG, detect("samsung", "samsung"))
        assertEquals(Maker.HUAWEI, detect("HUAWEI", "HUAWEI"))
        assertEquals(Maker.OPPO, detect("OPPO", "OPPO"))
        assertEquals(Maker.VIVO, detect("vivo", "vivo"))
        assertEquals(Maker.NOKIA, detect("HMD Global", "Nokia"))
    }

    @Test
    fun `sub-brands follow their parent`() {
        assertEquals(Maker.XIAOMI, detect("Xiaomi", "Redmi"))
        assertEquals(Maker.XIAOMI, detect("Xiaomi", "POCO"))
        assertEquals(Maker.HUAWEI, detect("HONOR", "HONOR"))
        assertEquals(Maker.OPPO, detect("realme", "realme"))
        assertEquals(Maker.OPPO, detect("OnePlus", "OnePlus"))
        assertEquals(Maker.VIVO, detect("vivo", "iQOO"))
    }

    @Test
    fun `the brand alone is enough when the manufacturer says something else`() {
        assertEquals(Maker.XIAOMI, detect("unknown", "Redmi"))
        assertEquals(Maker.NOKIA, detect(null, "Nokia"))
    }

    @Test
    fun `phones with no maker layer, and missing values, fall back to the generic guide`() {
        assertEquals(Maker.OTHER, detect("Google", "google"))
        assertEquals(Maker.OTHER, detect("motorola", "motorola"))
        assertEquals(Maker.OTHER, detect("Sony", "Sony"))
        assertEquals(Maker.OTHER, detect(null, null))
        assertEquals(Maker.OTHER, detect("", ""))
    }

    @Test
    fun `every maker has a guide with steps, and the detected maker comes first`() {
        Maker.entries.forEach { maker ->
            val guide = MakerGuides.of(maker)
            assertTrue(guide.steps.isNotEmpty(), "$maker has no steps")
        }
        assertEquals(Maker.entries.size, MakerGuides.all.size)
        MakerGuides.ordered(Maker.SAMSUNG).also {
            assertEquals(Maker.SAMSUNG, it.first().maker)
            assertEquals(Maker.entries.size, it.map { g -> g.maker }.toSet().size)
        }
    }

    @Test
    fun `maker screens are fully named, so a launch never builds a half-empty component`() {
        val components = MakerGuides.all.flatMap { it.steps }.map { it.action }.filterIsInstance<StepAction.Maker>().flatMap { it.components }
        assertTrue(components.isNotEmpty())
        components.forEach {
            assertTrue(it.packageName.contains('.') && it.className.startsWith(it.packageName.substringBeforeLast('.')), "${it.packageName}/${it.className}")
        }
    }
}
