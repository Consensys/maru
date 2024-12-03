package org.hyperledger.besu.consensus.beacon.containers.misc;

import org.hyperledger.besu.consensus.beacon.types.CommitteeIndex;
import org.hyperledger.besu.consensus.beacon.types.Root;
import org.hyperledger.besu.consensus.beacon.types.Slot;

public record AttestationData(
        Slot slot,
        CommitteeIndex committeeIndex,
        Root beaconBlockRoot,
        Checkpoint source,
        Checkpoint target
) {
}
