package org.hyperledger.besu.consensus.beacon.containers.misc;

import org.hyperledger.besu.consensus.beacon.types.Root;
import org.hyperledger.besu.consensus.beacon.types.Slot;
import org.hyperledger.besu.consensus.beacon.types.ValidatorIndex;

public record BeaconBlockHeader(
        Slot slot,
        ValidatorIndex proposerIndex,
        Root parentRoot,
        Root stateRoot,
        Root bodyRoot
) {
}
