package org.hyperledger.besu.consensus.beacon.containers.misc;


import org.hyperledger.besu.consensus.beacon.types.BLSPubKey;
import org.hyperledger.besu.consensus.beacon.types.Bytes32;
import org.hyperledger.besu.consensus.beacon.types.Gwei;
import org.hyperledger.besu.consensus.beacon.types.UInt64;

// https://ethereum.github.io/consensus-specs/specs/phase0/beacon-chain/#validator
public record Validator (
    BLSPubKey pubkey,
    Bytes32 withdrawalCredentials,
    Gwei effectiveBalance,
    boolean slashed,
    UInt64 activationEligibilityEpoch,
    UInt64 activationEpoch,
    UInt64 exitEpoch,
    UInt64 withdrawableEpoch
) {}