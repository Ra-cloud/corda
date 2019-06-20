package net.corda.bridge.services

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import net.corda.bridge.createAndLoadConfigFromResource
import net.corda.bridge.createBridgeKeyStores
import net.corda.bridge.createNetworkParams
import net.corda.bridge.services.api.FirewallConfiguration
import net.corda.bridge.services.api.TLSSigningService
import net.corda.bridge.services.artemis.BridgeArtemisConnectionServiceImpl
import net.corda.bridge.services.config.BridgeConfigHelper.makeCryptoService
import net.corda.bridge.services.receiver.CryptoServiceSigningService
import net.corda.core.internal.div
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.node.services.config.EnterpriseConfiguration
import net.corda.node.services.config.MutualExclusionConfiguration
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.messaging.ArtemisMessagingServer
import net.corda.nodeapi.internal.provider.extractCertificates
import net.corda.testing.core.DUMMY_BANK_A_NAME
import net.corda.testing.core.MAX_MESSAGE_SIZE
import net.corda.testing.core.SerializationEnvironmentRule
import net.corda.testing.internal.rigorousMock
import net.corda.testing.internal.stubs.CertificateStoreStubs
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArtemisConnectionTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Rule
    @JvmField
    val serializationEnvironment = SerializationEnvironmentRule(true)

    private abstract class AbstractNodeConfiguration : NodeConfiguration

    @Test
    fun `Basic lifecycle test`() {
        val configResource = "/net/corda/bridge/singleprocess/firewall.conf"
        createNetworkParams(tempFolder.root.toPath())
        val bridgeConfig = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        bridgeConfig.createBridgeKeyStores(DUMMY_BANK_A_NAME)
        val auditService = TestAuditService()
        val artemisSigningService = createArtemisSigningService(bridgeConfig)
        val artemisService = BridgeArtemisConnectionServiceImpl(artemisSigningService, bridgeConfig, MAX_MESSAGE_SIZE, auditService)
        val stateFollower = artemisService.activeChange.toBlocking().iterator
        artemisService.start()
        assertEquals(false, stateFollower.next())
        assertEquals(false, artemisService.active)
        assertNull(artemisService.started)
        auditService.start()
        assertEquals(false, artemisService.active)
        assertNull(artemisService.started)
        var artemisServer = createArtemis()
        try {
            assertEquals(true, stateFollower.next())
            assertEquals(true, artemisService.active)
            assertNotNull(artemisService.started)
            auditService.stop()
            assertEquals(false, stateFollower.next())
            assertEquals(false, artemisService.active)
            assertNull(artemisService.started)
            auditService.start()
            assertEquals(true, stateFollower.next())
            assertEquals(true, artemisService.active)
            assertNotNull(artemisService.started)
        } finally {
            artemisServer.stop()
        }
        assertEquals(false, stateFollower.next())
        assertEquals(false, artemisService.active)
        assertNull(artemisService.started)
        artemisServer = createArtemis()
        try {
            assertEquals(true, stateFollower.next())
            assertEquals(true, artemisService.active)
            assertNotNull(artemisService.started)
        } finally {
            artemisServer.stop()
        }
        assertEquals(false, stateFollower.next())
        assertEquals(false, artemisService.active)
        assertNull(artemisService.started)
        artemisService.stop()
    }


    private fun createArtemis(): ArtemisMessagingServer {

        val baseDirectory = tempFolder.root.toPath()
        val certificatesDirectory = baseDirectory / "certificates"
        val signingCertificateStore = CertificateStoreStubs.Signing.withCertificatesDirectory(certificatesDirectory)
        val p2pSslOptions = CertificateStoreStubs.P2P.withCertificatesDirectory(certificatesDirectory)
        val artemisConfig = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(baseDirectory).whenever(it).baseDirectory
            doReturn(certificatesDirectory).whenever(it).certificatesDirectory
            doReturn(DUMMY_BANK_A_NAME).whenever(it).myLegalName
            doReturn(signingCertificateStore).whenever(it).signingCertificateStore
            doReturn(p2pSslOptions).whenever(it).p2pSslOptions
            doReturn(NetworkHostAndPort("localhost", 11005)).whenever(it).p2pAddress
            doReturn(null).whenever(it).jmxMonitoringHttpPort
            doReturn(EnterpriseConfiguration(MutualExclusionConfiguration(false, "", 20000, 40000), externalBridge = true)).whenever(it).enterpriseConfiguration
        }
        val artemisServer = ArtemisMessagingServer(artemisConfig, NetworkHostAndPort("0.0.0.0", 11005), MAX_MESSAGE_SIZE)
        artemisServer.start()
        return artemisServer
    }

    private fun createArtemisSigningService(conf: FirewallConfiguration): TLSSigningService {
        val artemisSSlConfiguration = conf.outboundConfig?.artemisSSLConfiguration ?: conf.publicSSLConfiguration
        val artemisCryptoService = makeCryptoService(conf.artemisCryptoServiceConfig, DUMMY_BANK_A_NAME, artemisSSlConfiguration.keyStore)
        return CryptoServiceSigningService(artemisCryptoService,
                artemisSSlConfiguration.keyStore.get().extractCertificates(),
                artemisSSlConfiguration.trustStore.get(), conf.sslHandshakeTimeout, TestAuditService())
    }
}