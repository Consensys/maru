redeploy-besu:
		@echo "Redeploying Besu"
		-@helm --kubeconfig $(KUBECONFIG) uninstall besu
		@sleep 3 # Wait for a second to ensure the previous release is fully uninstalled
		@helm --kubeconfig $(KUBECONFIG) upgrade --install besu ./charts/besu --force

redeploy-maru:
	@echo "Redeploying Maru"
	-@helm --kubeconfig $(KUBECONFIG) uninstall maru
	@sleep 2 # Wait for a second to ensure the previous release is fully uninstalled
	@helm --kubeconfig $(KUBECONFIG) upgrade --install maru ./charts/maru --force -f ./values/maru-local-dev.yaml

redeploy:
	@echo "Redeploying Besu and Maru"
	$(MAKE) -f $(firstword $(MAKEFILE_LIST)) redeploy-besu
	$(MAKE) -f $(firstword $(MAKEFILE_LIST)) redeploy-maru
