package org.hyperledger.besu.consensus.beacon.containers.misc;

import org.hyperledger.besu.consensus.beacon.types.Root;
import org.hyperledger.besu.consensus.beacon.types.Version;

public record ForkData(
        Version currentVersion,
        Root genesisValidatorsRoot
) {
}
