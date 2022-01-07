package dev.w1zzrd.plugins

import dev.w1zzrd.CryptoManager
import dev.w1zzrd.db.*
import dev.w1zzrd.saltLength
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.json.*

suspend fun PipelineContext<Unit, ApplicationCall>.authenticated(dbManager: DBManager, body: suspend PipelineContext<Unit, ApplicationCall>.(session: AuthSession) -> Unit) {
    val session = ensureAuthSession(dbManager)
    if (session == null) {
        call.respondText("This endpoints requires authentication", status = HttpStatusCode.Unauthorized)
    } else {
        body(session)
    }
}

// Debug function for only allowing a specific, authenticated user
suspend fun PipelineContext<Unit, ApplicationCall>.authenticatedAs(dbManager: DBManager, email: String, body: suspend PipelineContext<Unit, ApplicationCall>.(session: AuthSession) -> Unit) {
    val session = ensureAuthSession(dbManager)
    if (session == null || dbManager.getUser(session.uid)?.username?.equals(email) != true) {
        call.respondText("This endpoint requires authentication", status = HttpStatusCode.Unauthorized)
    } else {
        body(session)
    }
}

fun PipelineContext<Unit, ApplicationCall>.ensureAuthSession(dbManager: DBManager): AuthSession? {
    val session = call.sessions.get<AuthSession>()
    if (session != null && (dbManager.getUser(session.uid) == null || session.isExpired)) {
        call.sessions.clear<AuthSession>()
        return null
    }
    session?.refresh()
    return session
}

suspend fun <T> PipelineContext<Unit, ApplicationCall>.getParam(name: String, parse: String.() -> T?): T? {
    val result = call.parameters[name]?.parse()

    if (result == null) {
        call.respondText("Parameter \"$name\" is either missing or malformed", status = HttpStatusCode.BadRequest)
        return null
    }

    return result
}

suspend fun PipelineContext<Unit, ApplicationCall>.getParam(name: String): String? {
    val result = call.parameters[name]

    if (result == null) {
        call.respondText("Missing required parameter: \"$name\"", status = HttpStatusCode.BadRequest)
        return null
    }

    return result
}

suspend fun ApplicationCall.respondDBResult(dbResult: DBResult) {
    respondText(
        when(dbResult) {
            DBResult.EXCEEDS_LIMIT -> "You have reached your limit for allowed entries"
            DBResult.ALREADY_EXISTS -> "Entry already exists?" // Possible race-condition?
            DBResult.NO_USER -> "Account doesn't exist?" // De-sync between auth session and accounts in DB
            DBResult.SUCCESS -> "Entry created"
        },
        status = when(dbResult) {
            DBResult.EXCEEDS_LIMIT -> HttpStatusCode.BadRequest
            DBResult.ALREADY_EXISTS, DBResult.NO_USER -> HttpStatusCode.InternalServerError
            DBResult.SUCCESS -> HttpStatusCode.OK
        }
    )
}

fun Application.configureRouting(dbManager: DBManager, cryptoManager: CryptoManager) {
    routing {
        configureAuthRoutes(dbManager, cryptoManager)
        configureAccountRoutes(dbManager, cryptoManager)
        configureEntriesRoutes(dbManager, cryptoManager)
        configureSessionRoutes(dbManager, cryptoManager)
    }
}


private fun Routing.configureAuthRoutes(dbManager: DBManager, cryptoManager: CryptoManager) {
    // Register a new account
    post("/auth/create") {
        val email = getParam("username") ?: return@post
        val pass = getParam("password") ?: return@post

        if (dbManager.createAccount(email, pass, cryptoManager)) {
            call.sessions.set(AuthSession(dbManager.getUser(email)!!.uid))
            call.respondText("Account created")
        } else {
            call.respondText("Account already exists", status = HttpStatusCode.Unauthorized)
        }
    }

    // Generate dummy account for debug
    get("/auth/dummy") {
        val check = dbManager.createAccount("dummy@email.com", "password", cryptoManager)
        call.sessions.set(AuthSession(dbManager.getUser("dummy@email.com")!!.uid))
        call.respondText("Account ${if (check) "created" else "already exists"}")
    }
}

private fun Routing.configureAccountRoutes(dbManager: DBManager, cryptoManager: CryptoManager) {
    // Delete account
    get("/account/delete") {
        authenticated(dbManager) { session ->
            dbManager.deleteAccount(session.uid)
            call.respondRedirect("/session/check")
        }
    }
}

private fun Routing.configureEntriesRoutes(dbManager: DBManager, cryptoManager: CryptoManager) {
    // Get a list of all entry IDs associated with current session along with their respective change/creation times
    get("/entries/changes") {
        authenticated(dbManager) { session ->
            /*
             * [
             *     { "id": 123, "lastchange": 12345 },
             *     { "id": 456, "lastchange": 67890 }
             * ]
             */
            val array = dbManager.getEntryChanges(session.uid)
            call.respondText(
                buildJsonArray {
                    array.forEach { add(buildJsonObject {
                        put("id", it.first)
                        put("lastchange", it.second.epochSecond)
                    }) }
                }.toString(),
                contentType = ContentType.Application.Json
            )
        }
    }

    // Count how many entries account has
    get("/entries/count") {
        authenticated(dbManager) { session ->
            call.respondText("${dbManager.getEntryCount(session.uid)}")
        }
    }

    // Get all entries updated/created after a given UNIX timestamp
    get("/entries/after/{timestamp}") {
        authenticated(dbManager) { session ->
            val timestamp = call.parameters["timestamp"]?.toLong()
            if (timestamp == null || timestamp < 0L) {
                call.respondText("Timestamp is not valid", status = HttpStatusCode.BadRequest)
            } else {
                call.respondText(
                    buildJsonArray {
                        dbManager.getEntriesAfter(session.uid, timestamp).forEach(this::add)
                    }.toString(),
                    contentType = ContentType.Application.Json
                )
            }
        }
    }

    // Get enntry with given id
    get("/entries/{id}") {
        authenticated(dbManager) { session ->
            val entryID = getParam("id", String::toIntOrNull) ?: return@authenticated
            val entry = dbManager.getEntry(session.uid, entryID)

            if (entry != null) {
                call.respondText(entry.toJsonObject().toString(), contentType = ContentType.Application.Json)
            } else {
                call.respondText("Cannot find entry", status = HttpStatusCode.BadRequest)
            }
        }
    }

    // Update content of entry with given id
    post("/entries/{id}") {
        authenticated(dbManager) { session ->
            try {
                val updated = dbManager.updateEntry(session.uid, call.receive<IncomingEntry>().decode())
                if (updated) {
                    call.respondText("Entry updated")
                } else {
                    call.respondText("Entry could not be updated", status = HttpStatusCode.BadRequest)
                }
            } catch (e: Exception) {
                call.respondText("Invalid content", status = HttpStatusCode.BadRequest)
            }
        }
    }

    // Delete entry with given id
    post("/entries/{id}/delete") {
        authenticated(dbManager) { session ->
            val entryID = getParam("id", String::toIntOrNull) ?: return@authenticated

            if (dbManager.deleteEntry(session.uid, entryID)) {
                call.respondText("Entry deleted")
            } else {
                call.respondText("Cannot find entry", status = HttpStatusCode.BadRequest)
            }
        }
    }

    // Create entry
    post("/entries/create") {
        authenticated(dbManager) { session ->
            try {
                call.respondDBResult(dbManager.createEntry(session.uid, call.receive<IncomingEntry>().decode()))
            } catch (e: Exception) {
                call.respondText("Invalid content", status = HttpStatusCode.BadRequest)
            }
        }
    }

    // Generate dummy entry with given text as name
    get("/entries/dummy/{text}") {
        authenticatedAs(dbManager, "dummy@email.com") { session ->
            val result = dbManager.createEntry(
                session.uid,
                DecodedIncomingEntry(
                    ByteArray(saltLength),
                    ByteArray(entryNameLength).apply { call.parameters["text"]!!.toByteArray().copyInto(this) },
                    ByteArray(entryContentLength)
                )
            )
            call.respondDBResult(result)
        }
    }

    // Set name of dummy entry with given id
    get("/entries/dummy/{id}/{text}") {
        authenticatedAs(dbManager, "dummy@email.com") { session ->
            val entryID = getParam("id", String::toIntOrNull) ?: return@authenticatedAs
            val created = dbManager.updateEntry(
                session.uid,
                DecodedIncomingEntry(
                    ByteArray(saltLength),
                    ByteArray(entryNameLength).apply { call.parameters["text"]!!.toByteArray().copyInto(this) },
                    ByteArray(entryContentLength),
                    entryID
                )
            )
            if (created) {
                call.respondText("Entry created")
            } else {
                call.respondText("Entry already exists", status = HttpStatusCode.BadRequest)
            }
        }
    }
}

private fun Routing.configureSessionRoutes(dbManager: DBManager, cryptoManager: CryptoManager) {
    // Boolean value stating if there is an active session
    get("/session/validate") {
        val session = ensureAuthSession(dbManager)
        call.respondText(if (session == null) "false" else "true")
    }

    // Echo username of active session (or "No session" for no/unauthenticated sessions)
    get("/session/check") {
        val session = ensureAuthSession(dbManager)
        call.respondText(if (session != null) dbManager.getUser(session.uid)!!.username else "No session")
    }

    // Expire an active session
    get("/session/expire") {
        authenticated(dbManager) {
            call.sessions.clear<AuthSession>()
            call.respondRedirect("/session/check")
        }
    }
}