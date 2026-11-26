# GreenAttack-System 3.4

Paper plugin source code for the GreenAttack Minecraft server. The plugin is compiled against the Paper API 1.21.11 and Java 21 bytecode, targeting Paper 1.21.11 through 26.3.

## Download

The installable JAR is published in [GitHub Releases](https://github.com/GreenAttack-Source/GreenAttack-System/releases).

## Build from source

Building requires JDK 21 or newer. On Windows, run this from the repository folder:

```powershell
.\gradlew.bat build
```

The generated JAR is written to `lib/GreenAttack-System-3.4.jar`.

The Paper server itself must use the Java version required by that Paper release: Java 21 for 1.21.11 and Java 25 for Paper 26.1 and newer.

Clan owners start deletion with `/clan delete` and confirm it with `/clan confirm` within 60 seconds.