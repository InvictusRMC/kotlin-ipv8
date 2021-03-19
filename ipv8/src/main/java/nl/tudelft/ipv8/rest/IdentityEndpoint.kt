package nl.tudelft.ipv8.rest

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import nl.tudelft.ipv8.IPv8
import nl.tudelft.ipv8.attestation.CommunicationManager
import nl.tudelft.ipv8.attestation.identity.database.IdentityStore
import nl.tudelft.ipv8.attestation.wallet.AttestationStore
import org.json.JSONObject

class IdentityEndpoint : BaseEndpoint() {

    private lateinit var communicationManager: CommunicationManager
    private lateinit var attestationDatabase: AttestationStore
    private lateinit var identityDatabase: IdentityStore

    override fun onShutdown() {
        super.onShutdown()
        this.communicationManager.shutdown()
    }

    override fun initialize(session: IPv8) {
        super.initialize(session)
        if (!this::attestationDatabase.isInitialized || !this::identityDatabase.isInitialized) {
            throw RuntimeException("IdentityDatabase is not properly initialized.")
        }
        this.communicationManager = CommunicationManager(session, attestationDatabase, identityDatabase)
    }

    override fun Routing.routes() {
        get("") {
            listPseudonyms(call)
        }
    }

    private suspend fun listPseudonyms(call: ApplicationCall) {
        call.respond(HttpStatusCode.OK, JSONObject().put("schema", this.communicationManager.listPseudonyms()))
    }

    private suspend fun listSchemas(call: ApplicationCall) {
        val channel = call.parameters["pseudonym_name"]?.let { this.communicationManager.load(it, call.request.header("X-Rendezvous")) }
//        call.respond
    }


}
