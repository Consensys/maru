# Changelog

### Upcoming Breaking Changes

### Breaking Changes

### Additions and Improvements

- Runtime and build dependencies are aligned with the same versions as the Linea `linea-monorepo` catalog (including Vert.x 5, Teku 25.12, Besu 26.3.0-RC0, Jackson, Netty, Tuweni `io.consensys.tuweni`, and related test libraries). SLF4J stays on the 2.0.x BOM so Vert.x 5 and Log4j’s SLF4J 2.x bridge resolve cleanly at runtime. Set `LINEA_LIBS_VERSION` when you need `build.linea.internal` artifacts to match a specific linea-monorepo build.
### Bug Fixes

- Fixed #506: upgraded Hyperledger Besu from 25.11.0 to 26.2.0 to resolve `NoClassDefFoundError: org/web3j/abi/datatypes/CustomError`.

