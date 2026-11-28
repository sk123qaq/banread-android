package app.banread.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NetworkSecurityConfigTest {

    @Test
    fun `network_security_config permits base cleartext for LAN and Tailscale while manifest binds it`() {
        val manifestFile = resolveAppFile("src/main/AndroidManifest.xml")
        val configFile = resolveAppFile("src/main/res/xml/network_security_config.xml")

        assertTrue("AndroidManifest.xml should exist at ${manifestFile.absolutePath}", manifestFile.isFile)
        assertTrue("network_security_config.xml should exist at ${configFile.absolutePath}", configFile.isFile)

        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }

        val manifestDoc = dbf.newDocumentBuilder().parse(manifestFile)
        val appEl = manifestDoc.getElementsByTagName("application").item(0) as Element
        assertEquals(
            "@xml/network_security_config",
            appEl.getAttributeNS("http://schemas.android.com/apk/res/android", "networkSecurityConfig"),
        )

        val cfgDoc = dbf.newDocumentBuilder().parse(configFile)
        val baseConfigs = cfgDoc.getElementsByTagName("base-config")
        assertEquals("Expected exactly one <base-config> element", 1, baseConfigs.length)
        val baseConfig = baseConfigs.item(0) as Element
        assertEquals(
            "Platform base-config must permit cleartext HTTP so RFC1918/Tailscale IPs reach Kotlin SyncEndpointUrlPolicy",
            "true",
            baseConfig.getAttribute("cleartextTrafficPermitted"),
        )
    }

    private fun resolveAppFile(relativeToApp: String): File {
        val direct = File(relativeToApp)
        if (direct.isFile) return direct
        return File("app", relativeToApp)
    }
}
