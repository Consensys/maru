
redeploy-besu:
		@echo "Redeploying Besu"
		-@helm uninstall besu
		@sleep 3 # Wait for a second to ensure the previous release is fully uninstalled
		@helm upgrade --install besu ./charts/besu --force

redeploy-maru:
	@echo "Redeploying Maru"
	-@helm uninstall maru
	@sleep 2 # Wait for a second to ensure the previous release is fully uninstalled
	@helm upgrade --install maru ./charts/maru --force

redeploy:
	@echo "Redeploying Besu and Maru"
	$(MAKE) -f $(firstword $(MAKEFILE_LIST)) redeploy-besu
	$(MAKE) -f $(firstword $(MAKEFILE_LIST)) redeploy-maru
