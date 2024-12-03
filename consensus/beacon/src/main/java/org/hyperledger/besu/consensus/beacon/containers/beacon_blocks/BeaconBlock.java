package org.hyperledger.besu.consensus.beacon.containers.beacon_blocks;

import org.hyperledger.besu.consensus.beacon.types.Root;
import org.hyperledger.besu.consensus.beacon.types.Slot;
import org.hyperledger.besu.consensus.beacon.types.ValidatorIndex;

public record BeaconBlock(
        Slot slot,
        ValidatorIndex proposerIndex,
        Root parentRoot,
        Root stateRoot,
        BeaconBlockBody body
) {
}
