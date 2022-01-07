package dev.w1zzrd.db

import dev.w1zzrd.CryptoManager
import dev.w1zzrd.DigestType
import dev.w1zzrd.saltLength
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.*


const val entryNameLength = 128
const val entryContentLength = 1024

private val base64encoder = Base64.getEncoder().withoutPadding()
private val base64decoder = Base64.getDecoder()

class DBManager(url: String, user: String, password: String, dbName: String = "btpw") {
    private val db by lazy {
        Database.connect("jdbc:mysql://$url/$dbName", driver = "com.mysql.cj.jdbc.Driver", user = user, password = password)
    }

    fun getUser(email: String) = transaction(db) {
        User.find { Users.username eq email }.firstOrNull()
    }

    fun getUser(uid: Int) = transaction(db) {
        User.findById(uid)
    }

    fun getEntryChanges(ownerID: Int) = transaction(db) {
        Entries.slice(Entries.uid, Entries.lastchange).select { Entries.owner_uid eq ownerID }.map { Pair(it[Entries.uid], it[Entries.lastchange]) }
    }

    fun getEntriesAfter(ownerID: Int, changeTime: Long) = transaction(db) {
        Entry.find { (Entries.owner_uid eq ownerID) and (Entries.lastchange greater Instant.ofEpochSecond(changeTime)) }.map(Entry::toJsonObject)
    }

    fun getEntry(ownerID: Int, entryID: Int) = transaction(db) {
        Entry.find { (Entries.owner_uid eq ownerID) and (Entries.uid eq entryID) }.firstOrNull()
    }

    fun getEntryCount(ownerID: Int) = transaction(db) {
        Entries.slice(Entries.owner_uid.count()).select { Entries.owner_uid eq ownerID }.first()[Entries.owner_uid.count()]
    }

    fun deleteEntry(ownerID: Int, entryID: Int) = transaction(db) {
        Entries.deleteWhere { (Entries.owner_uid eq ownerID) and (Entries.uid eq entryID) } == 1
    }

    fun createEntry(ownerID: Int, incomingEntry: DecodedIncomingEntry): DBResult {
        // Ensure user exists and is not exceeding their entry allowance
        if (getEntryCount(ownerID) + 1 >= (getUser(ownerID) ?: return DBResult.NO_USER).allowance) return DBResult.EXCEEDS_LIMIT

        return transaction(db) {
            if (Entries.insert {
                it[owner_uid] = ownerID
                it[salt] = ExposedBlob(incomingEntry.salt)
                it[name] = ExposedBlob(incomingEntry.name)
                it[content] = ExposedBlob(incomingEntry.content)
                // it[lastchange] = Instant.now() // Timestamp generated automatically by mysql trigger
            }.insertedCount == 1) DBResult.SUCCESS
            else DBResult.ALREADY_EXISTS
        }
    }

    fun updateEntry(ownerID: Int, incomingEntry: DecodedIncomingEntry) =
        if(incomingEntry.id != null) transaction(db) {
            Entries.update({ (Entries.owner_uid eq ownerID) and (Entries.uid eq incomingEntry.id) }, null) {
                it[salt] = ExposedBlob(incomingEntry.salt)
                it[name] = ExposedBlob(incomingEntry.name)
                it[content] = ExposedBlob(incomingEntry.content)
                // it[lastchange] = Instant.now() // Timestamp generated automatically by mysql trigger
            } == 1
        }
        else false

    fun createAccount(userEmail: String, password: String, cryptoManager: CryptoManager): Boolean {
        if (getUser(userEmail) != null) return false

        val salt = cryptoManager.generateSalt()
        val digest = cryptoManager.digest(password.toByteArray(Charsets.UTF_8), salt, DigestType.PASSWORD)

        return transaction(db) {
            Users.insert {
                it[username] = userEmail
                it[phash] = ExposedBlob(digest)
                it[psalt] = ExposedBlob(salt)
            }.insertedCount == 1
        }
    }

    fun deleteAccount(userID: Int) = transaction(db) {
        Users.deleteWhere { Users.uid eq userID } == 1
    }

    fun checkAuth(email: String, password: String, cryptoManager: CryptoManager): User? {
        val user = getUser(email) ?: return null
        return if (cryptoManager.digest(password.toByteArray(), user.psalt.bytes, DigestType.PASSWORD).contentEquals(user.phash.bytes)) user else null
    }
}

object Users: IdTable<Int>("Users") {
    val uid = integer("uid").autoIncrement().uniqueIndex()
    val username = varchar("username", 320).uniqueIndex()
    val phash = blob("phash")
    val psalt = blob("psalt")
    val allowance = integer("allowance").default(4096)

    override val id = uid.entityId()
    override val primaryKey = PrimaryKey(id)
}

class User(uid: EntityID<Int>): IntEntity(uid), JsonSerializable {
    companion object : IntEntityClass<User>(Users)
    var uid by Users.uid
    var username by Users.username
    var phash by Users.phash
    var psalt by Users.psalt
    var allowance by Users.allowance

    val entries by Entry referrersOn Entries.owner_uid

    fun getNewEntries(lastChange: Long) = transaction(db) {
        Entry.wrapRows(Entries.select { (Entries.owner_uid eq uid) and (Entries.lastchange greater lastChange) })
    }

    override fun toJsonObject() = buildJsonObject {
        put("username", username)
        put("allowance", allowance)
    }
}

object Entries: IdTable<Int>("Entries") {
    val uid = integer("id").autoIncrement().uniqueIndex()
    val owner_uid = reference("owner_uid", Users, onDelete = ReferenceOption.CASCADE)
    val salt = blob("salt")
    val name = blob("name")
    val content = blob("content")
    val lastchange = timestamp("lastchange")

    override val id = uid.entityId()
    override val primaryKey = PrimaryKey(id)
}

class Entry(id: EntityID<Int>): IntEntity(id), JsonSerializable {
    companion object : IntEntityClass<Entry>(Entries)
    var uid by Entries.uid
    var owner_uid by User referencedOn Entries.owner_uid
    var salt by Entries.salt
    var name by Entries.name
    var content by Entries.content
    var lastchange by Entries.lastchange

    override fun toJsonObject() = buildJsonObject {
        put("id", uid)
        put("salt", base64encoder.encodeToString(salt.bytes))
        put("name", base64encoder.encodeToString(name.bytes))
        put("content", base64encoder.encodeToString(content.bytes))
        put("lastchange", lastchange.epochSecond)
    }
}

@Serializable
data class IncomingEntry(val salt: String, val name: String, val content: String, val id: Int? = null) {
    fun decode() = DecodedIncomingEntry(
        ByteArray(saltLength).apply { base64decoder.decode(salt.toByteArray(), this) },
        ByteArray(128).apply { base64decoder.decode(name.toByteArray(), this) },
        ByteArray(1024).apply { base64decoder.decode(content.toByteArray(), this) },
        id
    )
}

data class DecodedIncomingEntry(val salt: ByteArray, val name: ByteArray, val content: ByteArray, val id: Int? = null)


interface JsonSerializable {
    fun toJsonObject(): JsonObject
}

enum class DBResult {
    SUCCESS, EXCEEDS_LIMIT, NO_USER, ALREADY_EXISTS
}