package freightertests

import freighter.deployments.DeploymentContext
import freighter.deployments.SingleNodeDeployed
import freighter.machine.AzureMachineProvider
import freighter.machine.DeploymentMachineProvider
import freighter.machine.DockerMachineProvider
import freighter.testing.AzureTest
import freighter.testing.DockerTest
import net.corda.bn.flows.SuspendMembershipFlow
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.messaging.startFlow
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.spi.ExtendedLogger
import org.junit.jupiter.api.Test
import utility.getOrThrow
import kotlin.system.measureTimeMillis

@AzureTest
class ImpactOfSingleMemberOnExistingNetworkTest : AbstractImpactOfSingleMemberOnExistingNetworkTest() {

    private companion object {
        const val numberOfParticipants = 20
        const val cutOffTime: Long = 300000
    }

    override fun getLogger(): ExtendedLogger {
        return LogManager.getContext().getLogger(ImpactOfSingleMemberOnExistingNetworkTest::class.java.name)
    }

    override val machineProvider: DeploymentMachineProvider = AzureMachineProvider()

    @Test
    fun testOfAddingASingleNodeTOAnExistingNetwork() {
        runBenchmark(numberOfParticipants, cutOffTime)
    }
}

@DockerTest
class DockerImpactOfSingleMemberOnExistingNetworkTest : AbstractImpactOfSingleMemberOnExistingNetworkTest() {

    private companion object {
        const val numberOfParticipants = 5
        const val cutOffTime: Long = 300000
    }

    override fun getLogger(): ExtendedLogger {
        return LogManager.getContext().getLogger(DockerNetworkMembershipActivationTest::class.java.name)
    }

    override val machineProvider: DeploymentMachineProvider = DockerMachineProvider()

    @Test
    fun testOfAddingASingleNodeTOAnExisting() {
        runBenchmark(numberOfParticipants, cutOffTime)
    }
}

abstract class AbstractImpactOfSingleMemberOnExistingNetworkTest : BaseBNFreighterTest() {

    override fun runScenario(numberOfParticipants: Int, deploymentContext: DeploymentContext): Map<String, Long> {
        val nodeGenerator = createDeploymentGenerator(deploymentContext)
        val bnoNode = nodeGenerator().getOrThrow()

        val networkID = UniqueIdentifier()
        val defaultGroupID = UniqueIdentifier()
        val defaultGroupName = "InitialGroup"

        getLogger().info("Setting up Business Network")

        val bnoMembershipState: MembershipState = createBusinessNetwork(bnoNode, networkID, defaultGroupID, defaultGroupName)
        val listOfGroupMembers = buildGroupMembershipNodes(numberOfParticipants, nodeGenerator)
        val nodeToMembershipIds: Map<SingleNodeDeployed, MembershipState> = requestNetworkMembership(listOfGroupMembers, bnoNode, bnoMembershipState)
        activateNetworkMembership(nodeToMembershipIds, bnoNode)
        val groupMembers = setupDefaultGroup(nodeToMembershipIds, bnoMembershipState, bnoNode, defaultGroupID, defaultGroupName) as MutableList

        getLogger().info("Adding New Single Node")
        val newNode = nodeGenerator().getOrThrow()
        val newNodeToMembershipState = requestNetworkMembership(listOf(newNode), bnoNode, bnoMembershipState)
        val newNodeMemberState = newNodeToMembershipState[newNode] ?: error("Node Not Found")

        val membershipActivation = measureTimeMillis { activateNetworkMembership(newNodeToMembershipState, bnoNode) }
        val groupAdditionTime = measureTimeMillis {
            groupMembers.add(newNodeMemberState.linearId)
            addMembersToAGroup(bnoNode, defaultGroupID, defaultGroupName, groupMembers)
        }

        val suspensionTime = measureTimeMillis {
            bnoNode.rpc {
                startFlow(::SuspendMembershipFlow, newNodeMemberState.linearId, null)
            }
        }

        val membershipSuspendInVaultTime = measureTimeMillis { waitForStatusUpdate(listOf(newNode), getMembershipStatusQueryCriteria(listOf(MembershipStatus.SUSPENDED))) }

        return mapOf("Membership Activation Time" to membershipActivation,
                "Group Addition Time" to groupAdditionTime,
                "Time taken to Run Suspend Membership Flow" to suspensionTime,
                "Time take to Register Suspension In Vault" to membershipSuspendInVaultTime
        )
    }
}