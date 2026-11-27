# GreenAttack-System 3.5

Paper plugin source code for the GreenAttack Minecraft server. The plugin is compiled against the Paper API 1.21.11 and Java 21 bytecode, targeting Paper 1.21.11 through 26.3.

## Download

The installable JAR is published in [GitHub Releases](https://github.com/GreenAttack-Source/GreenAttack-System/releases).

This version adds free SMP chunk claims with trust lists and per-claim title/subtitle text. Rank offers are configurable and centered in the rank menu, `/invsee` shows armor and off-hand without duplicating the main-hand item, and sell values use a configurable 1.5x default multiplier.

## Build from source

Building requires JDK 21 or newer. On Windows, run this from the repository folder:

```powershell
.\gradlew.bat build
```

The generated JAR is written to `lib/GreenAttack-System-3.5.jar`.

The Paper server itself must use the Java version required by that Paper release: Java 21 for 1.21.11 and Java 25 for Paper 26.1 and newer.
