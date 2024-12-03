package org.hyperledger.besu.consensus.beacon.containers.beacon_state;

import org.hyperledger.besu.consensus.beacon.containers.misc.BeaconBlockHeader;
import org.hyperledger.besu.consensus.beacon.containers.misc.Checkpoint;
import org.hyperledger.besu.consensus.beacon.containers.misc.Fork;
import org.hyperledger.besu.consensus.beacon.containers.misc.PendingAttestation;
import org.hyperledger.besu.consensus.beacon.containers.misc.Validator;
import org.hyperledger.besu.consensus.beacon.types.Bytes32;
import org.hyperledger.besu.consensus.beacon.types.Gwei;
import org.hyperledger.besu.consensus.beacon.types.Root;
import org.hyperledger.besu.consensus.beacon.types.Slot;
import org.hyperledger.besu.consensus.beacon.types.UInt64;

import java.util.List;

public record BeaconState(
        UInt64 genesisTime,
        Root genesisValidatorsRoot,
        Slot slot,
        Fork fork,
        BeaconBlockHeader latestBlockHeader,
        List<Root> blockRoots,
        List<Root> stateRoots,
        Eth1Data eth1Data,
        List<Eth1Data> eth1DataVotes,
        UInt64 eth1DepositIndex,
        List<Validator> validators,
        List<Gwei> balances,
        List<Bytes32> randaoMixes,
        List<Gwei> slashings,
        List<PendingAttestation> previousEpochAttestations,
        List<PendingAttestation> currentEpochAttestations,
        List<Byte> justificationBits,
        Checkpoint previousJustifiedCheckpoint,
        Checkpoint currentJustifiedCheckpoint,
        Checkpoint finalizedCheckpoint
) {
}
