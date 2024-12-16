package maru.beacon.spec

import org.hyperledger.besu.datatypes.Address

interface Validator {
  val address: Address
}
