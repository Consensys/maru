package org.hyperledger.besu.consensus.beacon.containers.beacon_blocks;

import org.hyperledger.besu.consensus.beacon.containers.beacon_operations.Attestation;
import org.hyperledger.besu.consensus.beacon.containers.beacon_operations.AttesterSlashing;
import org.hyperledger.besu.consensus.beacon.containers.beacon_operations.Deposit;
import org.hyperledger.besu.consensus.beacon.containers.beacon_operations.ProposerSlashing;
import org.hyperledger.besu.consensus.beacon.types.BLSSignature;
import org.hyperledger.besu.consensus.beacon.types.Bytes32;

import java.util.List;

public record BeaconBlockBody(
        BLSSignature randaoReveal,
        Eth1Data eth1Data,
        Bytes32 graffiti,
        List<ProposerSlashing> proposerSlashings,
        List<AttesterSlashing> attesterSlashings,
        List<Attestation> attestations,
        List<Deposit> deposis
) {
}
