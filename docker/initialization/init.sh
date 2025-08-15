#!/bin/zsh
echo "Checking if genesis files already exist."

if [[ ! -f "initialization/genesis-besu.json" && ! -f "initialization/genesis-geth.json" && ! -f "initialization/genesis-nethermind.json"  && ! -f "initialization/genesis-maru.json" ]]; then
    echo "Initialization of timestamp in genesis files for Maru, Besu, Geth, and Nethermind."
    date
    cd initialization || exit
    cp -T "genesis-maru.json.template" "genesis-maru.json"
    cp -T "genesis-besu.json.template" "genesis-besu.json"
    cp -T "genesis-geth.json.template" "genesis-geth.json"
    cp -T "genesis-nethermind.json.template" "genesis-nethermind.json"

    shanghai_timestamp=$(($(date +%s) + 60))
    prague_timestamp=$((shanghai_timestamp + 30))
    echo "Shanghai Timestamp: $shanghai_timestamp"
    echo "Prague Timestamp: $prague_timestamp"
    sed -i "s/%SHANGHAI_TIME%/$shanghai_timestamp/g" genesis-maru.json
    sed -i "s/%PRAGUE_TIME%/$prague_timestamp/g" genesis-maru.json
    sed -i "s/%SHANGHAI_TIME%/$shanghai_timestamp/g" genesis-besu.json
    sed -i "s/%PRAGUE_TIME%/$prague_timestamp/g" genesis-besu.json
    sed -i "s/%SHANGHAI_TIME%/$shanghai_timestamp/g" genesis-geth.json
    sed -i "s/%PRAGUE_TIME%/$prague_timestamp/g" genesis-geth.json
    shanghai_timestamp_hex=$(printf "0x%x" $shanghai_timestamp)
    prague_timestamp_hex=$(printf "0x%x" $prague_timestamp)
    sed -i "s/%SHANGHAI_TIME%/$shanghai_timestamp_hex/g" genesis-nethermind.json
    sed -i "s/%PRAGUE_TIME%/$prague_timestamp_hex/g" genesis-nethermind.json


    CREATE_EMPTY_BLOCKS="${CREATE_EMPTY_BLOCKS:-false}"
    sed -i "s/%CREATE_EMPTY_BLOCKS%/$CREATE_EMPTY_BLOCKS/g" genesis-besu.json
else
    echo "Genesis files already exist. Initialization skipped."
fi
