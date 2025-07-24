helm-clean-releases:
	@echo "Cleaning up Helm releases"
	-@helm --kubeconfig $(KUBECONFIG) uninstall besu-sequencer
	-@helm --kubeconfig $(KUBECONFIG) uninstall besu-follower
	-@helm --kubeconfig $(KUBECONFIG) uninstall maru-validator
	-@helm --kubeconfig $(KUBECONFIG) uninstall maru-follower-0
	-#@helm --kubeconfig $(KUBECONFIG) uninstall maru-follower-1
	KUBECONFIG=$(KUBECONFIG) kubectl delete pvc --all
	KUBECONFIG=$(KUBECONFIG) kubectl delete pv --all

helm-deploy-besu:
		@echo "Deploying Besu"
		@sleep 3 # Wait for a second to ensure the previous release is fully uninstalled
		@helm --kubeconfig $(KUBECONFIG) upgrade --install besu-sequencer ./helm/charts/besu --force -f ./helm/charts/besu/values.yaml -f ./helm/values/besu-local-dev-sequencer.yaml
		@helm --kubeconfig $(KUBECONFIG) upgrade --install besu-follower ./helm/charts/besu --force -f ./helm/charts/besu/values.yaml -f ./helm/values/besu-local-dev-follower.yaml

helm-redeploy-besu:
		@echo "Redeploying Besu"
		-@helm --kubeconfig $(KUBECONFIG) uninstall besu-sequencer
		-@helm --kubeconfig $(KUBECONFIG) uninstall besu-follower
		@sleep 3 # Wait for a second to ensure the previous release is fully uninstalled
		@$(MAKE) -f $(firstword $(MAKEFILE_LIST)) helm-deploy-besu

helm-redeploy-maru:
	@echo "Redeploying Maru"
	-@helm --kubeconfig $(KUBECONFIG) uninstall maru-validator
	-@helm --kubeconfig $(KUBECONFIG) uninstall maru-follower-0
	-@helm --kubeconfig $(KUBECONFIG) uninstall maru-follower-1
	@sleep 2 # Wait for a second to ensure the previous release is fully uninstalled
	@echo "Deploying Maru Followers"
	@helm --kubeconfig $(KUBECONFIG) upgrade --install maru-follower-0 ./helm/charts/maru --force -f ./helm/charts/maru/values.yaml -f ./helm/values/maru-local-dev-follower-0.yaml
	@#helm --kubeconfig $(KUBECONFIG) upgrade --install maru-follower-1 ./helm/charts/maru --force -f ./helm/charts/maru/values.yaml -f ./helm/values/maru-local-dev-follower-1.yaml
	@sleep 3 # wait for followers to start
	@echo "Deploying Maru Validator"
	@helm --kubeconfig $(KUBECONFIG) upgrade --install maru-validator ./helm/charts/maru --force -f ./helm/charts/maru/values.yaml -f ./helm/values/maru-local-dev-validator.yaml

helm-redeploy-maru-and-besu:
	@echo "Redeploying Besu and Maru"
	$(MAKE) -f $(firstword $(MAKEFILE_LIST)) helm-clean-releases
	$(MAKE) -f $(firstword $(MAKEFILE_LIST)) helm-redeploy-besu
	@sleep 10
	# Wait for Besu to be fully deployed,
	# otherwise Maru will fail to start because it cannot connect to Besu
	# then will miss P2P messages from validator
	$(MAKE) -f $(firstword $(MAKEFILE_LIST)) helm-redeploy-maru

wait-maru-follower-is-syncing:
	@echo "Waiting for Maru follower $* to be ready..."
	@until kubectl get pods -n default -l app.kubernetes.io/instance=maru-follower-0 | grep -q '1/1'; do \
		sleep 1; \
	done
	@echo "Maru follower $* is ready."
	@echo "Waiting for sync 'blockNumber=2 received' in maru-follower-0 pod..."
	@until kubectl logs -n default -l app.kubernetes.io/instance=maru-follower-0 | grep -q ' received'; do \
		sleep 1; \
	done
