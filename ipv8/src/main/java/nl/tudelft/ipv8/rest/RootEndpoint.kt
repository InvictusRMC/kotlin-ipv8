package nl.tudelft.ipv8.rest

import io.ktor.application.*
import io.ktor.client.features.json.serializer.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import nl.tudelft.ipv8.IPv8


class RootEndpoint(private val iPv8: IPv8) {
    var endpoints: Map<Class<out BaseEndpoint>, BaseEndpoint> = mutableMapOf(
        AttestationEndPoint::class.java to AttestationEndPoint(),
        IdentityEndpoint::class.java to IdentityEndpoint()
    )

    var server: ApplicationEngine = embeddedServer(Netty) {
        routing {
            endpoints.values
        }
        install(ContentNegotiation) {
            json()
        }
    }

    val app
        get() = server.application

    inline fun <reified T : BaseEndpoint> getOverlay(): T? {
        return endpoints[T::class.java] as? T
    }

    fun start() {
        this.endpoints.values.forEach {
            it.initialize(iPv8)
            server.addShutdownHook { it.onShutdown() }
        }
        this.server.start()
    }

}

suspend fun ApplicationCall.defaultRespond(message: String) {
//    this.response.headers.append()
    this.respondText(message,status= HttpStatusCode.OK, contentType = ContentType.Application.Json)
}
