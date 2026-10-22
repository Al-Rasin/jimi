package com.alrasin.jarvis.data

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.w3c.dom.Element

private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

private fun appSource(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .flatMap { sequenceOf(File(it, "src/main"), File(it, "app/src/main")) }
    .first { File(it, "AndroidManifest.xml").isFile }

private fun xmlRoot(path: String): Element = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
}.newDocumentBuilder().parse(File(appSource(), path)).documentElement

private fun Element.elements(tag: String): List<Element> = getElementsByTagName(tag).let { nodes ->
    (0 until nodes.length).map { nodes.item(it) as Element }
}

private fun backupSection(mode: String): Element = when (mode) {
    "full-backup-content" -> xmlRoot("res/xml/backup_rules.xml")
    else -> xmlRoot("res/xml/data_extraction_rules.xml").elements(mode).single()
}

@RunWith(Parameterized::class)
class BackupExclusionTest(private val mode: String, private val domain: String) {
    @Test fun storageDomainIsExcludedRecursivelyWithoutConditions() {
        val section = backupSection(mode)
        assertEquals(mode, section.tagName)
        val exclusion = section.elements("exclude").singleOrNull {
            it.getAttribute("domain") == domain && it.getAttribute("path") == "."
        }
        assertTrue("$mode must exclude all $domain data", exclusion != null)
        assertFalse(exclusion!!.hasAttribute("requireFlags"))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}: {1}")
        fun cases(): List<Array<String>> = listOf("full-backup-content", "cloud-backup", "device-transfer")
            .flatMap { mode ->
                listOf("root", "file", "database", "sharedpref", "external", "device_root",
                    "device_file", "device_database", "device_sharedpref")
                    .map { domain -> arrayOf(mode, domain) }
            }
    }
}

class BackupPrivacyTest {
    private fun application() = xmlRoot("AndroidManifest.xml").elements("application").single()

    @Test fun platformBackupIsDisabled() {
        assertEquals("false", application().getAttributeNS(ANDROID_NAMESPACE, "allowBackup"))
    }

    @Test fun legacyManifestRulesPointToCheckedPolicy() {
        assertEquals("@xml/backup_rules", application().getAttributeNS(ANDROID_NAMESPACE, "fullBackupContent"))
        assertEquals("full-backup-content", xmlRoot("res/xml/backup_rules.xml").tagName)
    }

    @Test fun modernManifestRulesPointToCheckedPolicy() {
        assertEquals("@xml/data_extraction_rules", application().getAttributeNS(ANDROID_NAMESPACE, "dataExtractionRules"))
        assertEquals("data-extraction-rules", xmlRoot("res/xml/data_extraction_rules.xml").tagName)
    }

    @Test fun noCustomBackupAgentBypassesPlatformExclusions() {
        assertFalse(application().hasAttributeNS(ANDROID_NAMESPACE, "backupAgent"))
    }

    @Test fun policiesNeverOptDataBackIntoBackup() {
        for (path in listOf("backup_rules", "data_extraction_rules")) {
            assertTrue(xmlRoot("res/xml/$path.xml").elements("include").isEmpty())
        }
    }

    @Test fun bothModernTransferModesHaveExplicitPolicies() {
        val rules = xmlRoot("res/xml/data_extraction_rules.xml")
        assertEquals(1, rules.elements("cloud-backup").size)
        assertEquals(1, rules.elements("device-transfer").size)
    }
}
