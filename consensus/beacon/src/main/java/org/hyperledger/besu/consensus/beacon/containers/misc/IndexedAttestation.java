package org.hyperledger.besu.consensus.beacon.containers.misc;

import org.hyperledger.besu.consensus.beacon.types.BLSSignature;
import org.hyperledger.besu.consensus.beacon.types.ValidatorIndex;

import java.util.List;

public record IndexedAttestation(
        List<ValidatorIndex> attestingIndices,
        AttestationData data,
        BLSSignature signature

) {
}
