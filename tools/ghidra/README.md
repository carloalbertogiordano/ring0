# Ghidra helpers for the ring firmware (headless)

Ghidra here is the Flatpak (`org.ghidra_sre.Ghidra`). From a terminal the documents portal must be off:

```
H="flatpak run --no-documents-portal --command=/app/lib/ghidra/support/analyzeHeadless org.ghidra_sre.Ghidra"
# 1. payload = OTA image without its 0x100-byte header; it is loaded at 0x803000 (found by matching it inside the flash dump)
# 2. import + analyse (the pre-script creates functions from the Cortex-M vector table)
$H <project-dir> R02 -import payload.bin -loader BinaryLoader -loader-baseAddr 0x803000 \
   -processor "ARM:LE:32:Cortex" -scriptPath tools/ghidra -preScript CortexVectors.java -overwrite
# 3. find the real-time command ids 0x69/0x6A and decompile their functions
$H <project-dir> R02 -process payload.bin -noanalysis -scriptPath tools/ghidra -postScript FindCmd.java out.txt
# 4. decompile given addresses and their callees (depth N)
$H <project-dir> R02 -process payload.bin -noanalysis -scriptPath tools/ghidra -postScript Decomp.java out.c 1 0080a2c8
```
Firmware images are third-party files and are never committed (they live under the git-ignored `refs/`).
