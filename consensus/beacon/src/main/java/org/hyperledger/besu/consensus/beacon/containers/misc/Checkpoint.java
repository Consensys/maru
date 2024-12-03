package org.hyperledger.besu.consensus.beacon.containers.misc;

import org.hyperledger.besu.consensus.beacon.types.Epoch;
import org.hyperledger.besu.consensus.beacon.types.Root;

public record Checkpoint(
        Epoch epoch,
        Root root
) {
}
