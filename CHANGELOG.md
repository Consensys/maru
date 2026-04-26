# Changelog

### Upcoming Breaking Changes

### Breaking Changes

### Additions and Improvements

- Added `maru_p2p_network_peer_limit` metric exposing the configured maximum number of P2P peers (`p2p.max-peers`), enabling alerts when `maru_p2p_network_peer_count` approaches the limit. (#444)

### Bug Fixes

- Fixed #506: upgraded Hyperledger Besu from 25.11.0 to 26.2.0 to resolve `NoClassDefFoundError: org/web3j/abi/datatypes/CustomError`.

