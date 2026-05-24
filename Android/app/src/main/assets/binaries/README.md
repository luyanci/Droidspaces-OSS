# Droidspaces Binaries Directory

This directory contains the **droidspaces** core binary files for different architectures.

## Required Files

Place the compiled droidspaces binaries here, named according to their architecture:

- `droidspaces-aarch64` - ARM64 (ARMv8-A) architecture
- `droidspaces-armhf` - ARM (32-bit, ARMv7) architecture  
- `droidspaces-x86_64` - x86_64 (AMD64) architecture
- `droidspaces-x86` - x86 (32-bit) architecture
- `droidspaces-riscv64` - RISC-V 64-bit architecture (optional)

## Build Instructions

1. Build droidspaces binaries using the Makefile in the project root:
   ```bash
   make all-build
   ```

2. Copy the compiled binaries from the build output to this directory:
   ```bash
   cp /path/to/build/droidspaces-* ./
   ```

3. Ensure binaries are executable and properly named.

## Important Notes

- **DO NOT** place busybox or magiskpolicy binaries here anymore
- These tools are now dynamically detected at runtime:
  - System PATH is checked first
  - Falls back to bundled versions if needed
- All droidspaces binaries should be statically compiled with musl libc
- Keep binary sizes reasonable (< 500KB each)

## File Size Reference

Typical sizes for static musl-compiled droidspaces binaries:
- aarch64: ~350-400 KB
- armhf: ~300-350 KB
- x86_64: ~380-420 KB
- x86: ~350-390 KB
- riscv64: ~360-400 KB
