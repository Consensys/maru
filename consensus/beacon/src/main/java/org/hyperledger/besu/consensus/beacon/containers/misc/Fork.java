package org.hyperledger.besu.consensus.beacon.containers.misc;

import org.hyperledger.besu.consensus.beacon.types.Epoch;
import org.hyperledger.besu.consensus.beacon.types.Version;

public record Fork(
        Version previousVersion,
        Version currentVersion,
        Epoch epoch

) { }
