package com.eazychat.app.crypto

import com.eazychat.app.protocol.BitchatPacket
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Result of checking a packet signature.
 *
 * UNSIGNED keeps the backward-compatible path for legacy packets that predate signed broadcasts.
 */
enum class PacketSignatureStatus {
    UNSIGNED,
    VALID,
    INVALID
}

/**
 * Pure Ed25519 packet signature verification.
 *
 * Kept free of Android dependencies so it can be covered by offline unit tests.
 * The signed bytes are the packet's canonical form ([BitchatPacket.toBinaryDataForSigning]),
 * which excludes the signature itself and pins TTL so relay hops do not invalidate it.
 */
object PacketSignatureVerifier {

    fun verifyEd25519(signature: ByteArray, data: ByteArray, publicKeyBytes: ByteArray): Boolean {
        if (publicKeyBytes.size != 32) return false
        return try {
            val publicKey = Ed25519PublicKeyParameters(publicKeyBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Verify a packet that carries a signature.
     *
     * A packet without a signature is reported as UNSIGNED (compatibility pass).
     * A signed packet with an unknown/invalid key is INVALID.
     */
    fun verifyPacket(packet: BitchatPacket, signingPublicKey: ByteArray?): PacketSignatureStatus {
        val signature = packet.signature ?: return PacketSignatureStatus.UNSIGNED
        val key = signingPublicKey ?: return PacketSignatureStatus.INVALID
        val canonicalBytes = packet.toBinaryDataForSigning() ?: return PacketSignatureStatus.INVALID
        return if (verifyEd25519(signature, canonicalBytes, key)) {
            PacketSignatureStatus.VALID
        } else {
            PacketSignatureStatus.INVALID
        }
    }
}
