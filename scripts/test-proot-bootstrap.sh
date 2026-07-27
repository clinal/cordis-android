#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

runtime_link="$tmp_dir/runtime"
nix build "$repo_root/bootstrap#runtime" --out-link "$runtime_link"

unzip -p "$runtime_link/bootstrap.zip" proot-static > "$tmp_dir/proot-static"
chmod +x "$tmp_dir/proot-static"

proot_info="$(file "$tmp_dir/proot-static")"
if [[ "$proot_info" != *"ELF 64-bit LSB executable, ARM aarch64"* ]] ||
   [[ "$proot_info" != *"Android"* ]] ||
   [[ "$proot_info" != *"statically linked"* ]]; then
  printf 'unexpected proot-static binary format:\n%s\n' "$proot_info" >&2
  exit 1
fi

help_output="$(qemu-aarch64 "$tmp_dir/proot-static" --help)"
if [[ "$help_output" != *"proot 5.1.0"* ]] ||
   [[ "$help_output" != *"chroot"* ]] ||
   [[ "$help_output" != *"mount --bind"* ]]; then
  printf 'unexpected proot --help output:\n%s\n' "$help_output" >&2
  exit 1
fi

printf 'proot bootstrap smoke test passed\n'
