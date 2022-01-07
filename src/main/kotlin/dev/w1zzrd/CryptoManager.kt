package dev.w1zzrd

import de.mkammerer.argon2.Argon2Factory
import de.mkammerer.argon2.Argon2Version

const val saltLength = 32
private const val pwLength = 192
private val dummyPepper = byteArrayOf(0) // Serverside encryption should be system-agnostic, so pepper shouldn't be used

class CryptoManager {
    val argon2 by lazy { Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id, saltLength, pwLength) }

    fun digest(data: ByteArray, salt: ByteArray, dataType: DigestType) =
        argon2.rawHashAdvanced(10, 65536, 4, data, salt, dummyPepper, dataType.associatedData, pwLength, Argon2Version.V13)

    fun generateSalt() =
        argon2.generateSalt(saltLength)
}


enum class DigestType {
    PASSWORD, DATA;

    val associatedData: ByteArray
        get() = name.toByteArray(Charsets.UTF_8)
}