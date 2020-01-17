package nl.tudelft.ipv8.peerdiscovery.strategy

import nl.tudelft.ipv8.peerdiscovery.DiscoveryCommunity

/**
 * On every step, it sends a similarity request to a random peer.
 */
class PeriodicSimilarity(
    private val overlay: DiscoveryCommunity
) : DiscoveryStrategy {
    override fun takeStep() {
        val peer = overlay.network.getRandomPeer()
        if (peer != null) {
            overlay.sendSimilarityRequest(peer.address)
        }
    }
}