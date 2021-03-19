package nl.tudelft.ipv8.rest

import io.ktor.routing.*
import nl.tudelft.ipv8.IPv8

abstract class BaseEndpoint {

    lateinit var session: IPv8


    abstract fun Routing.routes()

    open fun onShutdown() {}

    open fun initialize(session: IPv8) {
        this.session = session
    }
}
