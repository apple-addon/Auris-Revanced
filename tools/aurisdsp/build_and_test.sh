#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../.." && pwd)"
source_dir="$repo_root/app/src/main/cpp/aurisdsp"
build_dir="$script_dir/build"
cxx="${CXX:-g++}"

mkdir -p -- "$build_dir"
flags=(
  -std=c++17
  -O2
  -Wall
  -Wextra
  -Wpedantic
  -Werror
  -fno-math-errno
  -pthread
  -I"$source_dir"
)
if [[ "${SANITIZE:-0}" == "1" ]]; then
  flags+=(-O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined)
fi

"$cxx" "${flags[@]}" \
  "$source_dir/AurisDspEngine.cpp" \
  "$source_dir/MsebMapping.cpp" \
  "$script_dir/aurisdsp_tests.cpp" \
  -o "$build_dir/aurisdsp_tests"

"$build_dir/aurisdsp_tests"
