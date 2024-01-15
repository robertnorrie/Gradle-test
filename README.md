Set-up Instructions
===================

1. Clone the repository
2. Run `git submodule update --init --recursive` to populate `lib/models`

Users:

Run `./gradlew distZip` to build and package the tool.
The package can be found under `build/distributions/`.

Note: PRISM can be a bit tedious to compile.
If the process fails, go into the directory and try to compile it manually.
Running `make JAVA_DIR=<some path to jdk-11>` may fix some issues.