package dev.momolite

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source-level guards so a later edit cannot quietly weaken the security setup. Runs with the unit tests. */
class SecurityRegressionTest {
    private val main = File("src/main")
    private fun text(rel: String) = File(main, rel).readText()
    private fun kotlinFiles() = File(main, "java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun manifestKeepsBackupOffAndTrafficHttpsOnly() {
        val m = text("AndroidManifest.xml")
        assertTrue(m.contains("android:allowBackup=\"false\""))
        assertTrue(m.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(m.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
    }

    @Test fun networkSecurityConfigForbidsCleartextAndUserCas() {
        val n = text("res/xml/network_security_config.xml")
        assertTrue(n.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse(n.contains("src=\"user\""))
    }

    @Test fun permissionsAreExactlyTheMinimumSet() {
        val perms = Regex("uses-permission android:name=\"([^\"]+)\"").findAll(text("AndroidManifest.xml")).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("android.permission.INTERNET", "android.permission.POST_NOTIFICATIONS", "android.permission.RECEIVE_BOOT_COMPLETED"), perms)
    }

    @Test fun onlyTheLauncherActivityIsExported() {
        val m = text("AndroidManifest.xml")
        assertEquals(1, Regex("android:exported=\"true\"").findAll(m).count())
    }

    @Test fun noLoggingOrPrintingOfSecretsInSource() {
        val bad = Regex("(Log\\.[a-z]\\(|println\\(|printStackTrace).*(apiKey|token|Authorization|key)", RegexOption.IGNORE_CASE)
        kotlinFiles().forEach { f -> f.readLines().forEachIndexed { i, l -> assertFalse("${f.name}:${i + 1}", bad.containsMatchIn(l)) } }
    }

    @Test fun noHardcodedSecretsInSource() {
        val patterns = listOf(Regex("sk-[A-Za-z0-9]{20,}"), Regex("AIza[0-9A-Za-z_\\-]{35}"), Regex("ghp_[A-Za-z0-9]{30,}"),
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"))
        main.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "gradle", "pro") }.forEach { f ->
            val t = f.readText()
            patterns.forEach { assertFalse("${f.name} matches ${it.pattern}", it.containsMatchIn(t)) }
        }
    }

    @Test fun providerCodeNeverFollowsRedirects() = assertTrue(text("java/dev/momolite/Providers.kt").contains("instanceFollowRedirects = false"))

    @Test fun keyStaysInEncryptedStorage() {
        val s = text("java/dev/momolite/ApiSettings.kt")
        assertTrue(s.contains("EncryptedSharedPreferences"))
        assertFalse(s.contains("getSharedPreferences("))
    }
}
