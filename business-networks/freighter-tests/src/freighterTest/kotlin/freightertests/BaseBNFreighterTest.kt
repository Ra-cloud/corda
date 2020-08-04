package freightertests

import freighter.deployments.DeploymentContext
import freighter.deployments.NodeBuilder
import freighter.deployments.SingleNodeDeployed
import freighter.deployments.SingleNodeDeployment
import freighter.installers.corda.OPEN_SOURCE
import freighter.machine.DeploymentMachineProvider
import freighter.testing.RemoteMachineBasedTest
import net.corda.bn.flows.ActivateMembershipFlow
import net.corda.bn.flows.CreateBusinessNetworkFlow
import net.corda.bn.flows.CreateGroupFlow
import net.corda.bn.flows.ModifyGroupFlow
import net.corda.bn.flows.RequestMembershipFlow
import net.corda.bn.schemas.MembershipStateSchemaV1
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import org.apache.logging.log4j.spi.ExtendedLogger
import org.junit.jupiter.api.Assertions
import utility.getOrThrow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

abstract class BaseBNFreighterTest : RemoteMachineBasedTest() {

    private val bnoContracts = NodeBuilder.DeployedCordapp.fromClassPath("corda-business-network-contracts")
    private val bnoWorkflows = NodeBuilder.DeployedCordapp.fromClassPath("corda-business-network-workflows")
    private val bnoTesting = NodeBuilder.DeployedCordapp.fromClassPath("corda-business-network-testing-cordapp")
    private val freighterHelperCordapp = NodeBuilder.DeployedCordapp.fromClassPath("freighter-cordapp-flows")

    abstract fun getLogger(): ExtendedLogger
    abstract fun runScenario(numberOfParticipants: Int, deploymentContext: DeploymentContext): Map<String, Long>

    fun runBenchmark(numberOfParticipants: Int, cutOffTime: Long) {
        val deploymentContext = DeploymentContext(machineProvider, nms, artifactoryUsername, artifactoryPassword)
        val benchmark = runScenario(numberOfParticipants, deploymentContext)

        benchmark.map {
            getLogger().info("${it.key} BenchMark ${it.value}")
        }

        benchmark.map {
            Assertions.assertTrue(it.value <= cutOffTime, "${it.value} exceeded the benchmark of $cutOffTime")
        }
    }

    fun checkGroupSizeIsAsExpectedInMembersVaults(listOfNetworkMembers: List<SingleNodeDeployed>, groupLinearId: UniqueIdentifier, expectedGroupSize: Int) {
        val listToIterateOver = listOfNetworkMembers.toMutableList()
        while (listToIterateOver.isNotEmpty()) {
            val itr = listToIterateOver.iterator()
            while (itr.hasNext()) {
                val currentNode = itr.next()
                val actual = currentNode.rpc {
                    vaultQuery(GroupState::class.java).states.map { it.state.data }
                }
                checkGroupStatesFromVault(actual, groupLinearId, expectedGroupSize, currentNode, itr)
            }
        }
    }

    private fun checkGroupStatesFromVault(actual: List<GroupState>, groupLinearId: UniqueIdentifier, expectedGroupSize: Int, currentNode: SingleNodeDeployed, itr: MutableIterator<SingleNodeDeployed>) {
        for (groupState in actual) {
            if (groupState.linearId == groupLinearId && groupState.participants.size == expectedGroupSize) {
                getLogger().info("${currentNode.name} has correct group size")
                itr.remove()
            } else if (groupState.linearId == groupLinearId) {
                getLogger().info(currentNode.name + "still waiting on full group up")
                getLogger().info("Group members: ${groupState.participants.size} out of $expectedGroupSize")
            }
        }
        if (actual.isEmpty() || actual.first().linearId != groupLinearId) {
            getLogger().info("${currentNode.name} does not have the expected group state in vault")
        }
    }

    fun addMembersToAGroup(bnoNode: SingleNodeDeployed, newGroupId: UniqueIdentifier, newGroupName: String, groupMembers: List<UniqueIdentifier>) {
        bnoNode.rpc {
            startFlow(::ModifyGroupFlow,
                    newGroupId,
                    newGroupName,
                    groupMembers.toSet(),
                    null).returnValue.getOrThrow()
        }
    }

    fun activateNetworkMembership(nodeToMembershipIds: Map<SingleNodeDeployed, MembershipState>, bnoNode: SingleNodeDeployed) {
        nodeToMembershipIds.map {
            bnoNode.rpc {
                startFlow(::ActivateMembershipFlow,
                        it.value.linearId,
                        null).returnValue.getOrThrow()
            }
        }
    }

    fun requestNetworkMembership(listOfGroupMembers: List<SingleNodeDeployed>, bnoNode: SingleNodeDeployed, membershipState: MembershipState): Map<SingleNodeDeployed, MembershipState> {
        return listOfGroupMembers.associateBy(
                { it },
                {
                    it.rpc {
                        startFlow(::RequestMembershipFlow,
                                bnoNode.identity(),
                                membershipState.networkId,
                                null,
                                null).returnValue.getOrThrow()
                    }.tx.outputStates.single() as MembershipState
                })
    }

    private fun createGroup(bnoNode: SingleNodeDeployed, membershipState: MembershipState, newGroupId: UniqueIdentifier, newGroupName: String, groupMembers: Set<UniqueIdentifier> = setOf()): GroupState {
        return bnoNode.rpc {
            startFlow(::CreateGroupFlow,
                    membershipState.networkId,
                    newGroupId,
                    newGroupName,
                    groupMembers,
                    null
            ).returnValue.getOrThrow()
        }.tx.outputStates.single() as GroupState
    }

    fun createSubGroup(listOfMemberStates: List<MembershipState>, groupIndex: AtomicInteger, bnoMembershipState: MembershipState, bnoNode: SingleNodeDeployed): GroupState {
        val subGroupMembers = listOfMemberStates.map { it.linearId } as MutableList
        val subsetGroupName = "subGroup-${groupIndex.incrementAndGet()}"
        val subGroupId = UniqueIdentifier()
        subGroupMembers.add(0, bnoMembershipState.linearId)

        return bnoNode.rpc {
            startFlow(::CreateGroupFlow,
                    bnoMembershipState.networkId,
                    subGroupId,
                    subsetGroupName,
                    subGroupMembers.toSet(),
                    null
            ).returnValue.getOrThrow()
        }.tx.outputStates.single() as GroupState
    }

    fun getMembershipStatusQueryCriteria(listOfMemberShipStatus: List<MembershipStatus>): QueryCriteria {
        return QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(QueryCriteria.VaultCustomQueryCriteria(builder { MembershipStateSchemaV1.PersistentMembershipState::status.`in`(listOfMemberShipStatus) }))
    }

    fun waitForStatusUpdate(listOfGroupMembers: List<SingleNodeDeployed>, holderCriteria: QueryCriteria) {
        val listToIterateOver = listOfGroupMembers.toMutableList()
        while (listToIterateOver.isNotEmpty()) {
            val itr = listToIterateOver.iterator()
            while (itr.hasNext()) {
                val currentNode = itr.next()
                val actual = currentNode.rpc {
                    vaultQueryByCriteria(holderCriteria, MembershipState::class.java).states.map { it.state.data }
                }
                if (actual.isNotEmpty()) {
                    itr.remove()
                }
            }
        }
    }

    fun createBusinessNetwork(bnoNode: SingleNodeDeployed, networkId: UniqueIdentifier, groupId: UniqueIdentifier, groupName: String): MembershipState {
        val output: SignedTransaction = bnoNode.rpc {
            startFlow(::CreateBusinessNetworkFlow,
                    networkId,
                    null,
                    groupId,
                    groupName,
                    null).returnValue.getOrThrow()
        }

        return (output.tx.outputStates.single() as MembershipState)
    }

    fun buildGroupMembershipNodes(numberOfParticipants: Int, nodeGenerator: () -> CompletableFuture<SingleNodeDeployed>): List<SingleNodeDeployed> {
        return (0 until numberOfParticipants).map {
            nodeGenerator()
        }.map { it.getOrThrow() }
    }

    fun setupDefaultGroup(nodeToMembershipIds: Map<SingleNodeDeployed, MembershipState>, bnoMembershipState: MembershipState, bnoNode: SingleNodeDeployed, defaultGroupID: UniqueIdentifier, defaultGroupName: String): List<UniqueIdentifier> {
        val groupMembers = nodeToMembershipIds.values.map { it.linearId } as MutableList
        groupMembers.add(0, bnoMembershipState.linearId)
        addMembersToAGroup(bnoNode, defaultGroupID, defaultGroupName, groupMembers)
        return groupMembers
    }

    fun createDeploymentGenerator(
            deploymentContext: DeploymentContext,
            indexGenerator: AtomicInteger = AtomicInteger(0)
    ): () -> CompletableFuture<SingleNodeDeployed> =
            run {
                ({ indexGen: AtomicInteger, deploymentContext: DeploymentContext, db: DeploymentMachineProvider.DatabaseType ->
                    {
                        SingleNodeDeployment(
                                NodeBuilder().withX500("O=Party${indexGen.getAndIncrement()}, C=IE, L=DUBLIN CN=Corda")
                                        .withCordapp(bnoContracts)
                                        .withCordapp(bnoWorkflows)
                                        .withCordapp(freighterHelperCordapp)
                                        .withCordapp(bnoTesting)
                        ).withVersion("4.5")
                                .withDistribution(OPEN_SOURCE)
                                .deploy(deploymentContext)
                    }
                })
            }(indexGenerator, deploymentContext, DeploymentMachineProvider.DatabaseType.PG_11_5)
}