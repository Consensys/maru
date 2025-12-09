# Maru
A consensus layer client implementing QBFT protocol adhering to Eth 2.0 CL / EL separation and API

## Requirements

- Java 21+
- Make 3.81

## Quick Start

```sh
docker-run-stack
```

## Build from sources

To build Maru from source code:

```sh
# Create a distribution ready to run
./gradlew :app:installDist
```

After building, you can run Maru using:

```sh
./app/build/install/app/bin/app [options]
```

The distribution will be created in `app/build/install/app/` with all necessary dependencies included.

### Build Docker Image Locally

```sh
docker-build-local-image
MARU_TAG=local make docker-run-stack
```

### Contribution
* Please stick to https://www.conventionalcommits.org/en/v1.0.0/#specification for the ease of changelog maintenance
when creating PRs

### Release process
Release process is not automated ATM, so this section aims to help to streamline it

⚠️To speed up the hotfixes, release process doesn't enforce tests. Always ensure the tests are passing, before
releasing a new version of Maru! ⚠️

* Each released version must follow the template `<semver>-<product-label-ver>?-<date>-<commit-hash>`
e.g. `v2.0.1-betav4-20251027155452-cd25bfd` OR `v2.0.1-20251027155452-cd25bfd`
* Check the changelog for the changes. Cheatsheet:
  * ! / BREAKING CHANGE = major version update
  * feat = minor version update
  * fix = patch version update
* MAJOR.MINOR.PATCH should be incremented manually. Changelog and conventional commits are aimed to help to make it easy
* Tag the latest commit on main with the new version, like `git tag v2.0.1` or `v2.0.1-major-upgrade`
* Push tag to main with git push --tags
* This will trigger a release draft with the dist artifact created and it will push a new docker image to Dockerhub
* Changelog will be pulled automatically into the release description. Review it and publish the release if it's Ok
* Make a PR to clean up the changelog



