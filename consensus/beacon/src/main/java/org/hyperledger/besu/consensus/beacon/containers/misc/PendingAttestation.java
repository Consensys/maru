package org.hyperledger.besu.consensus.beacon.containers.misc;

import org.hyperledger.besu.consensus.beacon.types.Slot;
import org.hyperledger.besu.consensus.beacon.types.ValidatorIndex;

import java.util.List;

public record PendingAttestation(
    List<Byte> aggregationBits,
    AttestationData data,
    Slot inclusionDelay,
    ValidatorIndex proposerIndex
) {
}
