package jdelta.classfile

import java.security.MessageDigest

/**
 * canonical byte stream을 SHA-256으로 hash하고 앞 128bit를 hex로 돌려준다 (ARCHITECTURE.md D12).
 * 각 항목 뒤에 0 byte를 넣어 경계 모호성을 없앤다.
 */
internal class Hasher {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun add(value: String?): Hasher = apply {
        digest.update((value ?: "\u0001null").toByteArray(Charsets.UTF_8))
        digest.update(0)
    }

    fun add(value: Int): Hasher = add(value.toString())

    fun addAll(values: Collection<String?>): Hasher = apply {
        add(values.size)
        values.forEach { add(it) }
    }

    fun hex(): String = digest.digest().copyOf(16).toHex()

    companion object {
        fun of(vararg values: String?): String = Hasher().apply { values.forEach { add(it) } }.hex()

        fun bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(16).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
