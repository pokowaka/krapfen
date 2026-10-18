#!/usr/bin/env bash
# Builds and runs the offline dough measurements on the host.
set -euo pipefail
cd "$(dirname "$0")/../.."
out=build/dough-bench
mkdir -p build
# dough_impl.c is the exact translation unit the app compiles (allocator
# workaround included).
cc -O2 -std=gnu11 -w -I third_party/dough \
  -o "$out" tools/dough-bench/bench.c app/src/main/cpp/dough_impl.c -lm
"$out" "${@:-all}"
