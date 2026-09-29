# Copyright 2026 Ronny Trommer <ronny@no42.org>
# SPDX-License-Identifier: AGPL-3.0-or-later

MVN ?= ./mvnw -B -ntp

.PHONY: build test integration fixtures clean

# Builds the runnable jar target/rrdlift-<version>.jar.
build:
	$(MVN) package -DskipTests

# Unit tests only (surefire).
test:
	$(MVN) test

# Unit and integration tests (*IT.java via failsafe); needs Docker.
integration:
	$(MVN) verify

# Regenerates the committed RRD fixtures; needs Docker with arm64 and amd64 support.
fixtures:
	sh src/test/fixtures/generate.sh

clean:
	$(MVN) clean
