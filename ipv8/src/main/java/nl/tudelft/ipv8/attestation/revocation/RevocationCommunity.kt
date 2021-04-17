package nl.tudelft.ipv8.attestation.revocation

import kotlinx.coroutines.*
import mu.KotlinLogging
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.Overlay
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.attestation.AuthorityManager
import nl.tudelft.ipv8.attestation.revocation.caches.PENDING_REVOCATION_UPDATE_CACHE_PREFIX
import nl.tudelft.ipv8.attestation.revocation.caches.PendingRevocationUpdateCache
import nl.tudelft.ipv8.attestation.revocation.payloads.RevocationUpdateChunkPayload
import nl.tudelft.ipv8.attestation.revocation.payloads.RevocationUpdatePreviewPayload
import nl.tudelft.ipv8.attestation.revocation.payloads.RevocationUpdateRequestPayload
import nl.tudelft.ipv8.attestation.wallet.RequestCache
import nl.tudelft.ipv8.attestation.wallet.caches.PeerCache
import nl.tudelft.ipv8.keyvault.PrivateKey
import nl.tudelft.ipv8.messaging.*
import nl.tudelft.ipv8.peerdiscovery.Network
import nl.tudelft.ipv8.util.*

const val DELAY = 30000L
const val DEFAULT_GOSSIP_AMOUNT = 5
private const val CHUNK_SIZE = 800

private val logger = KotlinLogging.logger {}

const val REVOCATION_PRESENTATION_PAYLOAD = 1
const val REVOCATION_UPDATE_REQUEST_PAYLOAD = 2
const val REVOCATION_UPDATE_CHUNK_PAYLOAD = 3

class RevocationCommunity(val authorityManager: AuthorityManager) : Community() {
    override val serviceId = "fdbb9c5c18bf480a4baba08d352727e66ee89173"
    override var network = Network()

    private lateinit var gossipRoutine: Job

    private val lock = Object()
    private val requestCache = RequestCache()


//    private lateinit var onRevocationUpdatePreviewCallback: (List<Pair<String, Long>>) -> Unit
//    private lateinit var onRevocationUpdateRequestCallback: (Map<String, List<Long>>) -> Unit

    init {
        messageHandlers[REVOCATION_PRESENTATION_PAYLOAD] = ::onRevocationUpdatePreviewPayloadWrapper
        messageHandlers[REVOCATION_UPDATE_REQUEST_PAYLOAD] = ::onRevocationUpdateRequestPayloadWrapper
        messageHandlers[REVOCATION_UPDATE_CHUNK_PAYLOAD] = ::onRevocationUpdateChunkPayloadWrapper
    }

    suspend fun start() {
        this.gossipRoutine = GlobalScope.launch {
            while (isActive) {
                gossipRevocations(getRandomPeers(DEFAULT_GOSSIP_AMOUNT))
                delay(DELAY)
            }
        }
    }

    fun revokeAttestation(attestationHashes: List<ByteArray>) {
        logger.info("Revoking ${attestationHashes.size} hash(es)!")

        val myPublicKeyHash = this.myPeer.publicKey.keyToHash()
        var myAuthority = this.authorityManager.getAuthority(myPublicKeyHash)
        if (myAuthority == null) {
            this.authorityManager.addTrustedAuthority(this.myPeer.publicKey)
            myAuthority = this.authorityManager.getAuthority(myPublicKeyHash)!!
        }
        val version = myAuthority.version + 1
        var signableData = serializeULong(version.toULong())
        attestationHashes.forEach { signableData += it }
        val signature = (this.myPeer.key as PrivateKey).sign(sha1(signableData))

        logger.info("Revoking signatures with version $version")
        this.authorityManager.insertRevocations(myPublicKeyHash, version, signature, attestationHashes)
//        this.gossipMyRevocations()
    }

    private fun onRevocationUpdatePreviewPayloadWrapper(packet: Packet) {
        val (peer, payload) = packet.getAuthPayload(RevocationUpdatePreviewPayload.Deserializer)
        logger.info("Received RevocationPresentationPayload from ${peer.mid}.")
        this.onRevocationUpdatePreviewPayload(peer, payload)
//        this.onRevocationUpdatePreviewCallback(payload.revocationRefs)
    }

    private fun onRevocationUpdateRequestPayloadWrapper(packet: Packet) {
        val (peer, payload) = packet.getAuthPayload(RevocationUpdateRequestPayload.Deserializer)
        logger.info("Received RevocationUpdateRequestPayload from ${peer.mid}.")
        this.onRevocationUpdateRequestPayload(peer, payload)
    }

    private fun onRevocationUpdateChunkPayloadWrapper(packet: Packet) {
        val (peer, payload) = packet.getAuthPayload(RevocationUpdateChunkPayload.Deserializer)
        logger.info("Received RevocationUpdateChunkPayload ${payload.sequenceNumber} from ${peer.mid}.")
        this.onRevocationUpdateChunkPayload(peer, payload)
    }

    private fun onRevocationUpdatePreviewPayload(peer: Peer, payload: RevocationUpdatePreviewPayload) {
        val remoteRefs = payload.revocationRefs
        val localRefs = this.authorityManager.getLatestRevocationPreviews()
        val requestedRefs = hashMapOf<ByteArrayKey, Long>()

        for (key in remoteRefs.keys) {
            val localVersion = localRefs.get(key)
            val remoteVersion = remoteRefs[key]!!

            // If we're already waiting on a version, wait until we receive it or the cache times out.
            // We want to receive versions concurrent for now.
            val idPair = PendingRevocationUpdateCache.generateId(peer.publicKey.keyToHash(), key.bytes, (localVersion ?: 0L) + 1)
            if (!this.requestCache.has(idPair)) {
                if (localVersion == null) {
                    requestedRefs[key] = 0L
                    for (i in 1L..remoteVersion) {
                        this.requestCache.add(PendingRevocationUpdateCache(this.requestCache, peer.publicKey.keyToHash(), key.bytes, i))
                    }
                } else if (localVersion < remoteVersion) {
                    requestedRefs[key] = localVersion
                    for (i in (localVersion + 1L)..remoteVersion) {
                        this.requestCache.add(PendingRevocationUpdateCache(this.requestCache, peer.publicKey.keyToHash(), key.bytes, i))
                    }
                }
            } else {
                logger.info("Not requesting V$remoteVersion:${key.bytes.toHex()} as we have already requested it.")
            }
        }

        if (requestedRefs.isNotEmpty()) {
            val updateRequestPayload = RevocationUpdateRequestPayload(requestedRefs)
            val packet = serializePacket(REVOCATION_UPDATE_REQUEST_PAYLOAD, updateRequestPayload)
            logger.info("Requesting revocation update: ${requestedRefs}.")
//            logger.info("Remoterefs: $remoteRefs")
//            logger.info("Localrefs: $localRefs")
//                this.requestCache.add(PendingRevocationUpdateCache(this.requestCache, peer.mid))
            this.endpoint.send(peer, packet)
        }
    }


    private fun onRevocationUpdateRequestPayload(
        peer: Peer,
        payload: RevocationUpdateRequestPayload,
        bulkSending: Boolean = false,
    ) {
        // TODO make sure we want to send to this clients.

        if (bulkSending) {
            val revocations = hashMapOf<ByteArrayKey, List<RevocationBlob>>()
            payload.revocationRefs.forEach { (hash, version) ->
                revocations[hash] = this.authorityManager.getRevocations(hash.bytes, version)
            }

            val blob = serializeRevocationMap(revocations)
            val hash = sha1(blob)
            var sequenceNumber = 0
            for (i in blob.indices step CHUNK_SIZE) {
                val endIndex = if (i + CHUNK_SIZE > blob.size) blob.size else i + CHUNK_SIZE
                val chunkPayload = RevocationUpdateChunkPayload(sequenceNumber,
                    hash,
                    this.myPeer.publicKey.keyToHash(),
                    0L,
                    blob.copyOfRange(i, endIndex))
                val packet = serializePacket(REVOCATION_UPDATE_CHUNK_PAYLOAD, chunkPayload)
                logger.info("Sending update chunk $sequenceNumber")
                this.endpoint.send(peer, packet)
                sequenceNumber += 1
            }
        } else {
            val revocations = mutableListOf<RevocationBlob>()

            payload.revocationRefs.forEach { (hash, version) ->
                revocations.addAll(this.authorityManager.getRevocations(hash.bytes, version))
            }
            logger.info("CLIENT REQUESTED THE FOLLOWING VERSIONS: ")
            revocations.forEach { print("${it.version}, ") }

            // TODO: ths could be performed in coroutines, however, would most likely lead to package lost.
            for (rev in revocations) {
//                logger.info("Sending version ${rev.version}")
                val blob = serializeRevocationBlob(rev)
                val hash = sha1(blob)
                var sequenceNumber = 0
                for (i in blob.indices step CHUNK_SIZE) {
                    val endIndex = if (i + CHUNK_SIZE > blob.size) blob.size else i + CHUNK_SIZE
                    val chunkPayload =
                        RevocationUpdateChunkPayload(sequenceNumber, hash, rev.publicKeyHash, rev.version,
                            blob.copyOfRange(i, endIndex))
                    val packet = serializePacket(REVOCATION_UPDATE_CHUNK_PAYLOAD, chunkPayload)
                    logger.info("Sending update chunk $sequenceNumber")
                    this.endpoint.send(peer, packet)
                    sequenceNumber += 1
                }
            }
        }

    }

    private fun onRevocationUpdateChunkPayload(
        peer: Peer,
        payload: RevocationUpdateChunkPayload,
        bulkSending: Boolean = false,
    ) {
        if (bulkSending) {
            val (prefix, id) = PeerCache.idFromAddress(PENDING_REVOCATION_UPDATE_CACHE_PREFIX, peer.mid)
            if (this.requestCache.has(prefix, id)) {
                val cache = this.requestCache.get(prefix, id) as PendingRevocationUpdateCache
                var localBlob = byteArrayOf()
                cache.revocationMap[payload.sequenceNumber] = payload.data
                cache.revocationMap.keys.sorted().forEach { localBlob += cache.revocationMap[it]!! }
                if (sha1(localBlob).contentEquals(payload.hash)) {
                    this.requestCache.pop(prefix, id) as PendingRevocationUpdateCache
                    val revocationMap = this.deserializeRevocationMap(localBlob)
                    logger.info("Received update: ${revocationMap.keys.size}")
                    revocationMap.forEach { (hash, list) ->
                        for (blob in list) {
                            val authority = this.authorityManager.getAuthority(hash.bytes)

                            if (authority?.publicKey != null) {
                                var signable = serializeULong(blob.version.toULong())
                                blob.revocations.forEach { signable += it }
                                if (!authority.publicKey.verify(blob.signature, signable)) {
                                    logger.warn("Peer ${peer.mid} might have altered the revoked signatures, skipping!")
                                    continue
                                }
                            } else {
                                logger.info("Inserting revocations without verification as authority is not recognized.")
                            }

                            this.authorityManager.insertRevocations(
                                hash.bytes,
                                blob.version,
                                blob.signature,
                                blob.revocations)
                        }
                    }
                }
            } else {
                logger.warn { "Received update we did not request, dropping." }
            }
        } else {
            val idPair =
                PendingRevocationUpdateCache.generateId(peer.publicKey.keyToHash(), payload.publicKeyHash, payload.version)
            if (this.requestCache.has(idPair)) {
                val cache = this.requestCache.get(idPair) as PendingRevocationUpdateCache
                var localBlob = byteArrayOf()
                cache.revocationMap[payload.sequenceNumber] = payload.data
                cache.revocationMap.keys.sorted().forEach { localBlob += cache.revocationMap[it]!! }
                if (sha1(localBlob).contentEquals(payload.hash)) {
                    this.requestCache.pop(idPair) as PendingRevocationUpdateCache
                    val revocationBlob = this.deserializeRevocationBlob(localBlob)
                    logger.info("Received update: ${revocationBlob.revocations.size} revoked signatures")

                    val authority = this.authorityManager.getAuthority(revocationBlob.publicKeyHash)
                    if (authority?.publicKey != null) {
                        var signable = serializeULong(revocationBlob.version.toULong())
                        revocationBlob.revocations.forEach { signable += it }
                        if (!authority.publicKey.verify(revocationBlob.signature, signable)) {
                            logger.warn("Peer ${peer.mid} might have altered the revoked signatures, skipping!")
                        }
                    } else {
                        logger.info("Inserting revocations without verification as authority is not recognized.")
                    }

                    this.authorityManager.insertRevocations(
                        revocationBlob.publicKeyHash,
                        revocationBlob.version,
                        revocationBlob.signature,
                        revocationBlob.revocations)
                }
            } else {
                logger.warn { "Received update for version ${payload.version} we did not request, dropping." }
            }

        }
    }

    private fun serializeRevocationBlob(blob: RevocationBlob): ByteArray {
        var out =
            blob.publicKeyHash + blob.signature + serializeULong(blob.version.toULong()) + serializeUInt(blob.revocations.size.toUInt())
        for (hash in blob.revocations) {
            out += hash
        }
        return out
    }

    private fun deserializeRevocationBlob(serialized: ByteArray): RevocationBlob {
        var offset = 0

        val keyHash = serialized.copyOfRange(offset, offset + SERIALIZED_SHA1_HASH_SIZE)
        offset += SERIALIZED_SHA1_HASH_SIZE

        val signature = serialized.copyOfRange(offset, offset + SIGNATURE_SIZE)
        offset += SIGNATURE_SIZE

        val version = deserializeULong(serialized, offset).toLong()
        offset += SERIALIZED_ULONG_SIZE

        val revocationAmount = deserializeUInt(serialized, offset).toInt()
        offset += SERIALIZED_UINT_SIZE

        val revocations = mutableListOf<ByteArray>()
        for (i in 0 until revocationAmount) {
            val revocation = serialized.copyOfRange(offset, offset + SERIALIZED_SHA1_HASH_SIZE)
            offset += SERIALIZED_SHA1_HASH_SIZE
            revocations.add(revocation)
        }

        return RevocationBlob(keyHash, version, signature, revocations)
    }

    private fun serializeRevocationMap(map: Map<ByteArrayKey, List<RevocationBlob>>): ByteArray {
        val size = map.size
        var out = serializeUInt(size.toUInt())
        map.forEach { (keyHashKey, list) ->
            out += keyHashKey.bytes
            val versionAmount = list.size.toUInt()
            out += serializeUInt(versionAmount)
            for (blob in list) {
                out += serializeULong(blob.version.toULong())
                out += serializeVarLen(blob.signature)
                val revocationAmount = blob.revocations.size.toUInt()
                out += serializeUInt(revocationAmount)
                for (revocation in blob.revocations) {
                    out += revocation
                }
            }
        }
        return out
    }

    private fun deserializeRevocationMap(serialized: ByteArray): Map<ByteArrayKey, List<RevocationBlob>> {
        var localOffset = 0
        val out = hashMapOf<ByteArrayKey, List<RevocationBlob>>()
        val size = deserializeUInt(serialized, localOffset).toInt()
        localOffset += SERIALIZED_UINT_SIZE
        for (i in 0 until size) {
            val keyHash = serialized.copyOfRange(localOffset, localOffset + SERIALIZED_SHA1_HASH_SIZE)
            localOffset += SERIALIZED_SHA1_HASH_SIZE
            val versionAmount = deserializeUInt(serialized, localOffset).toInt()
            localOffset += SERIALIZED_UINT_SIZE

            val revocationBlobs = mutableListOf<RevocationBlob>()
            for (j in 0 until versionAmount) {
                val version = deserializeULong(serialized, localOffset).toLong()
                localOffset += SERIALIZED_ULONG_SIZE
                val (signature, signatureOffset) = deserializeVarLen(serialized, localOffset)
                localOffset += signatureOffset
                val revocationAmount = deserializeUInt(serialized, localOffset).toInt()
                localOffset += SERIALIZED_UINT_SIZE

                val revocations = mutableListOf<ByteArray>()
                for (k in 0 until revocationAmount) {
                    val revocation = serialized.copyOfRange(localOffset, localOffset + SERIALIZED_SHA1_HASH_SIZE)
                    revocations.add(revocation)
                    localOffset += SERIALIZED_SHA1_HASH_SIZE
                }
                revocationBlobs.add(RevocationBlob(keyHash, version, signature, revocations))
            }
            out[keyHash.toKey()] = revocationBlobs
        }
        return out
    }


//    fun setOnRevocationPresentationCallback(f: (List<Long>, List<String>) -> Unit) {
//        this.onRevocationUpdatePreviewCallback = f
//    }

    fun shutdown() {
        this.gossipRoutine.cancel()
    }

    private fun gossipMyRevocations() {
        val peers = this.getPeers()
        this.gossipRevocations(peers)
    }

    private fun getRandomPeers(amount: Int): List<Peer> {
        return this.getPeers().random(amount).toList()
    }

    private fun gossipRevocations(peers: List<Peer>) {
        val revocations = this.authorityManager.getLatestRevocationPreviews()
        val payload = RevocationUpdatePreviewPayload(revocations)
        val packet = serializePacket(REVOCATION_PRESENTATION_PAYLOAD, payload)
        runBlocking {
            peers.forEach {
                launch {
                    endpoint.send(it, packet)
                }
            }
        }
    }

    class Factory(
        private val authorityManager: AuthorityManager,
    ) : Overlay.Factory<RevocationCommunity>(RevocationCommunity::class.java) {
        override fun create(): RevocationCommunity {
            return RevocationCommunity(authorityManager)
        }
    }


}