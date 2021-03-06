package net.corda.bn.contracts

import net.corda.bn.states.BNIdentity
import net.corda.bn.states.BNORole
import net.corda.bn.states.MembershipIdentity
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.Contract
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.junit.Test

class DummyContract : Contract {

    companion object {
        const val CONTRACT_NAME = "net.corda.bn.contracts.DummyContract"
    }

    override fun verify(tx: LedgerTransaction) {}
}

class DummyCommand : TypeOnlyCommandData()

@CordaSerializable
private data class DummyIdentity(val name: String) : BNIdentity

class MembershipContractTest {

    private val ledgerServices = MockServices(listOf("net.corda.bn.contracts"))

    private val memberIdentity = TestIdentity(CordaX500Name.parse("O=Member,L=London,C=GB")).party
    private val bnoIdentity = TestIdentity(CordaX500Name.parse("O=BNO,L=London,C=GB")).party

    private val membershipState = MembershipState(
            identity = MembershipIdentity(memberIdentity),
            networkId = "network-id",
            status = MembershipStatus.PENDING,
            participants = listOf(memberIdentity, bnoIdentity),
            issuer = bnoIdentity
    )

    @Test(timeout = 300_000)
    fun `test common contract verification`() {
        ledgerServices.ledger {
            transaction {
                val input = membershipState
                output(MembershipContract.CONTRACT_NAME, input)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), DummyCommand())
                fails()
            }
            transaction {
                val input = membershipState
                input(DummyContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                command(memberIdentity.owningKey, MembershipContract.Commands.Revoke(listOf(memberIdentity.owningKey)))
                this `fails with` "Input state has to be validated by ${MembershipContract.CONTRACT_NAME}"
            }
            transaction {
                val output = membershipState
                input(MembershipContract.CONTRACT_NAME, output)
                output(DummyContract.CONTRACT_NAME, output)
                command(memberIdentity.owningKey, MembershipContract.Commands.Request(listOf(memberIdentity.owningKey)))
                this `fails with` "Output state has to be validated by ${MembershipContract.CONTRACT_NAME}"
            }
            transaction {
                val input = membershipState.run { copy(modified = modified.minusSeconds(100)) }
                input(MembershipContract.CONTRACT_NAME, input)
                command(memberIdentity.owningKey, MembershipContract.Commands.Revoke(listOf(memberIdentity.owningKey)))
                this `fails with` "Input state's modified timestamp should be greater or equal to issued timestamp"
            }
            transaction {
                val output = membershipState.run { copy(modified = modified.minusSeconds(100)) }
                output(MembershipContract.CONTRACT_NAME, output)
                command(memberIdentity.owningKey, MembershipContract.Commands.Request(listOf(memberIdentity.owningKey)))
                this `fails with` "Output state's modified timestamp should be greater or equal to issued timestamp"
            }
            transaction {
                val output = membershipState.run { copy(participants = listOf(memberIdentity)) }
                output(MembershipContract.CONTRACT_NAME, output)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey)))
                this `fails with` "Required signers should be subset of all output state's participants"
            }

            val input = membershipState
            transaction {
                val output = input.copy(identity = MembershipIdentity(bnoIdentity), status = MembershipStatus.ACTIVE)
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership activation transaction should have same Corda identity"
            }
            transaction {
                val output = input.copy(networkId = "other-network-id")
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state should have same network IDs"
            }
            transaction {
                val output = input.run { copy(issuer = memberIdentity) }
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Membership state issuer cannot be changed"
            }
            transaction {
                val output = input.run { copy(issued = issued.minusSeconds(100)) }
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state should have same issued timestamps"
            }
            transaction {
                val output = input.run { copy(modified = modified.plusSeconds(100)) }
                input(MembershipContract.CONTRACT_NAME, input.run { copy(modified = modified.plusSeconds(200)) })
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Output state's modified timestamp should be greater or equal than input's"
            }
            transaction {
                val output = input.copy(linearId = UniqueIdentifier())
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state should have same linear IDs"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Transaction must be signed by all signers specified inside command"
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test request membership command contract verification`() {
        ledgerServices.ledger {
            val output = membershipState
            transaction {
                input(MembershipContract.CONTRACT_NAME, output)
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Membership request transaction shouldn't contain any inputs"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output.copy(status = MembershipStatus.ACTIVE))
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Membership request transaction should contain output state in PENDING status"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output.copy(roles = setOf(BNORole())))
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Membership request transaction should issue membership with empty roles set"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey)))
                this `fails with` "Pending membership owner should be required signer of membership request transaction"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                verifies()
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output.copy(identity = output.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test activate membership command contract verification`() {
        ledgerServices.ledger {
            val input = membershipState
            transaction {
                input(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE))
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE))
                command(bnoIdentity.owningKey, MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input state of membership activation transaction shouldn't be already active"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                command(bnoIdentity.owningKey, MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Output state of membership activation transaction should be active"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE, roles = setOf(BNORole())))
                command(bnoIdentity.owningKey, MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership activation transaction should have same roles set"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE, identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                command(bnoIdentity.owningKey, MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership activation transaction should have same business identity"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE, participants = listOf(bnoIdentity)))
                command(bnoIdentity.owningKey, MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership activation transaction should have same participants"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE))
                command(memberIdentity.owningKey, MembershipContract.Commands.Activate(listOf(memberIdentity.owningKey)))
                this `fails with` "Input membership owner shouldn't be required signer of membership activation transaction"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE))
                command(bnoIdentity.owningKey, MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test onboard membership command contract verification`() {
        ledgerServices.ledger {
            val output = membershipState.copy(status = MembershipStatus.ACTIVE)
            transaction {
                input(MembershipContract.CONTRACT_NAME, output)
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Membership onboarding transaction shouldn't contain any inputs"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output.copy(status = MembershipStatus.PENDING))
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Membership onboarding transaction should contain output state in ACTIVE status"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output.copy(roles = setOf(BNORole())))
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Membership onboarding transaction should issue membership with empty roles set"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey)))
                this `fails with` "Onboarded membership owner should be required signer of membership onboarding transaction"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test suspend membership command contract verification`() {
        ledgerServices.ledger {
            val input = membershipState
            transaction {
                input(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                command(bnoIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input state of membership suspension transaction shouldn't be already suspended"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE))
                command(bnoIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(bnoIdentity.owningKey)))
                this `fails with` "Output state of membership suspension transaction should be suspended"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED, roles = setOf(BNORole())))
                command(bnoIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership suspension transaction should have same roles set"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED, identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                command(bnoIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership suspension transaction should have same business identity"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED, participants = listOf(bnoIdentity)))
                command(bnoIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership suspension transaction should have same participants"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                command(memberIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(memberIdentity.owningKey)))
                this `fails with` "Input membership owner shouldn't be required signer of membership suspension transaction"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                command(bnoIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test revoke membership command contract verification`() {
        ledgerServices.ledger {
            val input = membershipState
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Revoke(listOf(bnoIdentity.owningKey)))
                this `fails with` "Membership revocation transaction shouldn't contain any outputs"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                command(memberIdentity.owningKey, MembershipContract.Commands.Revoke(listOf(memberIdentity.owningKey)))
                this `fails with` "Input membership owner shouldn't be required signer of membership revocation transaction"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Revoke(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test modify role command contract verification`() {
        ledgerServices.ledger {
            val input = membershipState.copy(status = MembershipStatus.ACTIVE)
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey), bnoIdentity))
                this `fails with` "Input and output state of membership roles modification transaction should have same status"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.PENDING))
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.PENDING, roles = setOf(BNORole())))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey), bnoIdentity))
                this `fails with` "Membership roles modification transaction can only be performed on active or suspended state"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey), bnoIdentity))
                this `fails with` "Input and output state of membership roles modification transaction should have different set of roles"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(roles = setOf(BNORole()), identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey), bnoIdentity))
                this `fails with` "Input and output state of membership roles modification transaction should have same business identity"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(roles = setOf(BNORole()), participants = listOf(bnoIdentity)))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey), bnoIdentity))
                this `fails with` "Input and output state of membership roles modification transaction should have same participants"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(roles = setOf(BNORole())))
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey), memberIdentity))
                this `fails with` "Input membership owner should be required signer of membership roles modification transaction if it initiated it"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(roles = setOf(BNORole())))
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), bnoIdentity))
                this `fails with` "Input membership owner shouldn't be required signer of membership roles modification transaction if it didn't initiate it"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(roles = setOf(BNORole())))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey), bnoIdentity))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test modify business identity command contract verification`() {
        ledgerServices.ledger {
            val input = membershipState.copy(status = MembershipStatus.ACTIVE)
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey), bnoIdentity))
                this `fails with` "Input and output state of membership business identity modification transaction should have same status"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.PENDING))
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.PENDING, identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey), bnoIdentity))
                this `fails with` "Membership business identity modification transaction can only be performed on active or suspended state"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(roles = setOf(BNORole())))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey), bnoIdentity))
                this `fails with` "Input and output state of membership business identity modification transaction should have same roles"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey), bnoIdentity))
                this `fails with` "Input and output state of membership business identity modification transaction should have different business identity"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity")), participants = listOf(bnoIdentity)))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey), bnoIdentity))
                this `fails with` "Input and output state of membership business identity modification transaction should have same participants"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey), memberIdentity))
                this `fails with` "Input membership owner should be required signer of membership business identity modification transaction if it initiated it"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                command(memberIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(memberIdentity.owningKey), bnoIdentity))
                this `fails with` "Input membership owner shouldn't be required signer of membership business identity modification transaction if it didn't initiate it"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey), bnoIdentity))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test modify participants command contract verification`() {
        ledgerServices.ledger {
            val input = membershipState.copy(status = MembershipStatus.ACTIVE)
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyParticipants(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership participants modification transaction should have same status"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.PENDING))
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.PENDING))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyParticipants(listOf(bnoIdentity.owningKey)))
                this `fails with` "Membership participants modification transaction can only be performed on active or suspended state"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(roles = setOf(BNORole())))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyParticipants(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership participants modification transaction should have same roles"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyParticipants(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership participants modification transaction should have same business identity"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyParticipants(listOf(bnoIdentity.owningKey)))
                verifies()
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(participants = listOf(bnoIdentity)))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyParticipants(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }
}
