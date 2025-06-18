.PHONY: all build test clean run bench cli verify lint

all: build

build:
	./gradlew assemble

test:
	./gradlew test --info

clean:
	./gradlew clean
	rm -rf data tmp

run: build
	./gradlew run

bench: build
	./gradlew -q run --args="bench"

cli: build
	./bin/ferrite-cli

verify:
	./tools/verify_clean.sh

lint: verify
	./gradlew check
