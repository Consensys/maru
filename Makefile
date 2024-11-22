docker-start:
	docker compose -f docker/compose.yaml up -d

docker-stop:
	docker compose -f docker/compose.yaml down

docker-clean:
	make docker-stop
	docker compose -f docker/compose.yaml rm -v

patch-genesis:
	./docker/patch_genesis.sh docker/genesis-besu.json
	./docker/patch_genesis.sh docker/genesis-geth.json

docker-clean-start:
	make docker-clean
	make patch-genesis
	make docker-start