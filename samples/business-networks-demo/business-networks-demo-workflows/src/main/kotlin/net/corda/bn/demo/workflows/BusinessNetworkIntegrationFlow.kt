package net.corda.bn.demo.workflows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.demo.contracts.LoanPermissions
import net.corda.bn.flows.DatabaseService
import net.corda.bn.states.MembershipState
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.Party

data class LoanMemberships(val lenderMembership: StateAndRef<MembershipState>, val borrowerMembership: StateAndRef<MembershipState>)

/**
 * This abstract flow is extended by any flow which will use business network membership verification methods.
 */
abstract class BusinessNetworkIntegrationFlow<T> : FlowLogic<T>() {

    /**
     * Verifies that [lender] and [borrower] are members of Business Network with [networkId] ID.
     *
     * @param networkId ID of the Business Network in which verification is performed.
     * @param lender Party issuing the loan.
     * @param borrower Party paying of the loan.
     */
    @Suspendable
    protected fun businessNetworkPartialVerification(networkId: String, lender: Party, borrower: Party): LoanMemberships {
        val bnService = serviceHub.cordaService(DatabaseService::class.java)
        val lenderMembership = bnService.getMembership(networkId, lender)
                ?: throw FlowException("Lender is not longer part of Business Network with $networkId ID")
        val borrowerMembership = bnService.getMembership(networkId, borrower)
                ?: throw FlowException("Borrower is not longer part of Business Network with $networkId ID")

        return LoanMemberships(lenderMembership, borrowerMembership)
    }

    /**
     * Verifies that [lender] and [borrower] are members of Business Network with [networkId] ID, their memberships are active and
     * that lender is authorised to issue the loan.
     *
     * @param networkId ID of the Business Network in which verification is performed.
     * @param lender Party issuing the loan.
     * @param borrower Party paying of the loan.
     */
    @Suspendable
    protected fun businessNetworkFullVerification(networkId: String, lender: Party, borrower: Party) {
        val bnService = serviceHub.cordaService(DatabaseService::class.java)

        bnService.getMembership(networkId, lender)?.state?.data?.apply {
            if (!isActive()) {
                throw FlowException("$lender is not active member of Business Network with $networkId ID")
            }
            if (roles.find { LoanPermissions.CAN_ISSUE_LOAN in it.permissions } == null) {
                throw FlowException("$lender is not authorised to issue loan in Business Network with $networkId ID")
            }
        } ?: throw FlowException("$lender is not member of Business Network with $networkId ID")

        bnService.getMembership(networkId, borrower)?.state?.data?.apply {
            if (!isActive()) {
                throw FlowException("$borrower is not active member of Business Network with $networkId ID")
            }
        } ?: throw FlowException("$borrower is not member of Business Network with $networkId ID")
    }
}