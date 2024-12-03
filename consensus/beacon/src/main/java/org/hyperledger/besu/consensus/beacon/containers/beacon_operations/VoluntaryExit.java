package org.hyperledger.besu.consensus.beacon.containers.beacon_operations;

import org.hyperledger.besu.consensus.beacon.types.Epoch;
import org.hyperledger.besu.consensus.beacon.types.ValidatorIndex;

public record VoluntaryExit (
        Epoch epoch,
        ValidatorIndex validatorIndex
) {
}
