package com.varizpardaz.listener.app

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Project rules that are cheap to check mechanically. The unit-test working directory is `apps/android/app`. */
class ProjectRulesTest {
    private val moduleDir = File(".").absoluteFile.normalize()

    /** UI text belongs in values/strings.xml. Any Arabic-script character in Kotlin code is hard-coded text. */
    @Test
    fun `no hard-coded persian text in kotlin sources`() {
        val sources = File(moduleDir, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "no Kotlin sources found under $moduleDir")
        val offenders = sources.filter { file ->
            file.readText(Charsets.UTF_8).any { ch ->
                ch.code in 0x0600..0x06FF || ch.code in 0x0750..0x077F || ch.code in 0xFB50..0xFDFF || ch.code in 0xFE70..0xFEFF
            }
        }
        assertTrue(offenders.isEmpty(), "Persian text in code, move it to strings.xml: ${offenders.map { it.name }}")
    }

    /** The screens show counts, times and settings only. They must never be able to reach a queued message body. */
    @Test
    fun `screens never touch queued message items or their bodies`() {
        val screens = File(moduleDir, "src/main/kotlin/com/varizpardaz/listener/app/ui").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(screens.isNotEmpty(), "no screen sources found under $moduleDir")
        val forbidden = listOf("QueuedItem", "MessageQueue", "sealedBody", "SmsIntake", "InboxSms", "SmsPart", "queue.peek", "queue.next")
        val offenders = screens.flatMap { file ->
            val text = file.readText(Charsets.UTF_8)
            forbidden.filter { it in text }.map { "${file.name}: $it" }
        }
        assertTrue(offenders.isEmpty(), "screens must not read message content: $offenders")
    }

    /** The signing key and its passwords come from CI secrets. Nothing of them may be written into the repository. */
    @Test
    fun `no signing secret is written into the build files`() {
        val build = File(moduleDir, "build.gradle.kts").readText(Charsets.UTF_8)
        assertTrue(Regex("""(?i)(storePassword|keyPassword)\s*=\s*"""").find(build) == null, "a password is written into build.gradle.kts")
        assertTrue("PAYGATE_KEYSTORE_PASSWORD" in build && "System.getenv" in build, "release signing must read the environment")
        val ignored = File(moduleDir, "../.gitignore").readText(Charsets.UTF_8)
        assertTrue("*.jks" in ignored && "*.keystore" in ignored, "keystore files must be git-ignored")
    }

    @Test
    fun `strings xml is the persian default and contains the brand placeholder`() {
        val strings = File(moduleDir, "src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        assertTrue("name=\"brand_name\"" in strings)
        // The product name is applied through this one string.
        assertTrue("<string name=\"brand_name\">واریز پرداز</string>" in strings, "the product name is «واریز پرداز»")
        assertTrue(strings.any { it.code in 0x0600..0x06FF }, "default strings must be Persian")
        assertTrue(!File(moduleDir, "src/main/res/values-fa").exists(), "Persian is the default resource set; no values-fa")
    }

    @Test
    fun `the pairing scheme comes from brand properties`() {
        val brand = Properties().apply { File(moduleDir, "../brand.properties").inputStream().use(::load) }
        assertEquals(brand.getProperty("brand.pairScheme"), BuildConfig.PAIR_SCHEME)
        assertEquals(brand.getProperty("brand.pairHost"), BuildConfig.PAIR_HOST)
        assertTrue(BuildConfig.APPLICATION_ID.startsWith(brand.getProperty("brand.applicationId")))
    }
}
