package dev.w1zzrd

import dev.w1zzrd.db.DBManager
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import dev.w1zzrd.plugins.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size != 3) {
        exitProcess(-1)
    }
    val dbManager = DBManager(args[0], args[1], args[2])
    val cryptoManager = CryptoManager()

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        configureRouting(dbManager, cryptoManager)
        configureSecurity(dbManager, cryptoManager)
        configureHTTP()
        configureSockets()
    }.start(wait = true)
}
