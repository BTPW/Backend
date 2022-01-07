package dev.w1zzrd.plugins

import dev.w1zzrd.CryptoManager
import dev.w1zzrd.db.DBManager
import io.ktor.http.*
import io.ktor.server.sessions.*
import io.ktor.server.auth.*
import io.ktor.util.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import java.time.Instant

const val SESSION_TIMEOUT_MS = 1000L * 60L * 10L // 10 minutes

data class AuthSession(val uid: Int, var expiration: Instant = Instant.now().plusSeconds(SESSION_TIMEOUT_MS)) {
    val isExpired: Boolean
        get() = Instant.now().isAfter(expiration)

    fun refresh() {
        expiration = Instant.now().plusSeconds(SESSION_TIMEOUT_MS)
    }
}

fun Application.configureSecurity(dbManager: DBManager, cryptoManager: CryptoManager) {
    data class UIDPrincipal(val uid: Int) : Principal

    fun doAuth(name: String, password: String): Principal? {
        val user = dbManager.checkAuth(name, password, cryptoManager)
        return if (user != null) {
            UIDPrincipal(user.id.value)
        } else {
            null
        }
    }

    suspend fun PipelineContext<Unit, ApplicationCall>.processAuth() {
        val principal = call.principal<UIDPrincipal>()

        if (principal == null) {
            call.respondText("Bad credentials", status = HttpStatusCode.Unauthorized)
        } else {
            call.sessions.set(AuthSession(principal.uid))
            call.respondText("Authenticated!")
        }
    }

    install(Sessions) {
        cookie<AuthSession>("AUTH_SESSION")
    }

    authentication {
        basic(name = "basic") {
            realm = "BTPW"
            validate { credentials ->
                doAuth(credentials.name, credentials.password)
            }
        }

        form(name = "form") {
            userParamName = "user"
            passwordParamName = "password"
            validate { credentials ->
                doAuth(credentials.name, credentials.password)
            }
        }
    }

    routing {
        authenticate("basic") {
            get("/auth/login/basic") {
                processAuth()
            }
            post("/auth/login/basic") {
                processAuth()
            }
        }
        authenticate("form") {
            get("/auth/login/form") {
                processAuth()
            }
            post("/auth/login/form") {
                processAuth()
            }
        }
    }
}
