.PHONY: help start assembly test clean start-docker test-integration-docker clean-docker version env
SHELL := /bin/bash

VERSION=3.2.0

export ROOT_LOG_LEVEL ?= ERROR
export LOG_LEVEL ?= INFO
export PORT ?= 8089
export NODE_OPTIONS=--openssl-legacy-provider
export REDIS_PASSWORD ?= redis

help: ## Print help.
	@IFS=$$'\n' ; \
	help_lines=(`grep -Fh "##" $(MAKEFILE_LIST) | grep -Fv grep | sed -e 's/\\$$//' | sed -e 's/##/:/'`); \
	printf "%-30s %s\n" "target" "help" ; \
	printf "%-30s %s\n" "------" "----" ; \
	for help_line in $${help_lines[@]}; do \
		IFS=$$':' ; \
		help_split=($$help_line) ; \
		help_command=`echo $${help_split[0]} | sed -e 's/^ *//' -e 's/ *$$//'` ; \
		help_info=`echo $${help_split[2]} | sed -e 's/^ *//' -e 's/ *$$//'` ; \
		printf '\033[36m'; \
		printf "%-30s %s" $$help_command ; \
		printf '\033[0m'; \
		printf "%s\n" $$help_info; \
	done

setup: ## Setup (You need to set node version v18.20.0!)
	cd web && yarn install

start: assembly ## Run the application locally.
	./start.sh xatu.jar input.txt

assembly: NODE_ENV=production
assembly: clean ## Generate assembly xatu.jar in the root folder.
	cd web && yarn generate
	cp -r web/dist src/main/resources
	sbt assembly
	cp target/scala-2.13/xatu-observer-assembly-$(VERSION).jar xatu.jar

test: env clean ## Run all unit tests.
	sbt test

clean: ## Run sbt clean and delete generated files.
	rm output.json || true
	rm xatu.jar || true
	rm -rf src/main/resources/dist || true
	sbt clean

start-docker: env clean-docker assembly ## Start docker compose with all required services.
	docker compose down || true
	docker compose --profile all up --build

start-docker-daemon: clean-docker ## Start required services (minus xatu itself) daemon.
	docker compose --profile daemon up -d

test-integration-docker: env clean clean-docker assembly ## Start docker compose and check all integrations.
	@echo "Starting services..."
	docker compose --profile all up --build -d --wait
	@echo "Testing healthcheck..."
	@curl -o output.json -w 'Status Code: %{http_code}\n' --connect-timeout 20 --max-time 20 --fail-with-body http://localhost:8089/api/healthcheck || (cat output.json & $(MAKE) clean-docker & exit 1)
	$(MAKE) clean-docker

clean-docker: ## Stop and remove containers.
	@echo "Stopping and removing containers..."
	docker compose --profile all down --remove-orphans || true
	docker compose --profile daemon down --remove-orphans || true
	yes | docker compose rm

web-dev: export NODE_ENV := development
web-dev: export PORT := 8090
web-dev: ## Start development server for web gui.
	cd web && yarn dev

version: ## Print current version.
	@echo $(VERSION)

env: ## Print information about environment variables.
	@echo "You can change any environment variable prepending ENV=VALUE in front of the make command or exporting in your environment."
	@echo "PORT is set to $(PORT)."
	@echo "ROOT_LOG_LEVEL is set to $(ROOT_LOG_LEVEL)."
	@echo "LOG_LEVEL is set to $(LOG_LEVEL)."
