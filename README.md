# GreenAttack-System

GreenAttack-System ist ein Paper-Plugin mit SMP-Claims, Teleport- und Homesystem, Economy, Markt, Rängen, Clans, Inventarwerkzeugen und administrativen Serverfunktionen. Der Quellcode wird mit der Paper-API 1.21.11 und Java-21-Bytecode gebaut, und die Plugin-Version ist für Paper 1.21.11 bis 26.3 vorgesehen.

Die installierbare Plugin-Datei und die vollständigen Quellcodes werden getrennt bereitgestellt: Die JAR-Datei liegt bei den [GitHub-Releases](https://github.com/GreenAttack-Source/GreenAttack-System/releases), während das Haupt-Repository den lesbaren Quellcode und die Build-Dateien enthält.

## Befehle und Berechtigungen

Die folgenden Befehle sind die wichtigsten Spieler- und Teamfunktionen; jede angegebene Permission kann in LuckPerms einer Gruppe oder einem einzelnen Spieler zugewiesen werden, und administrative Sammelrechte wie `system.admin` oder `claim.admin` sollten nur an die dafür vorgesehenen Teamgruppen vergeben werden.

| Befehl | Permission | Funktion |
| --- | --- | --- |
| `/claim`, `/claims` oder `/land` | `claim.use` und je nach Spielerlimit ein passendes `claim.claims.N` | Der Spieler sichert den Chunk, in dem er gerade steht, und sieht nach erfolgreichem Speichern im Chat, wie viele eigene Claims er bereits besitzt und welches Limit für ihn gilt. |
| `/claim unclaim` | `claim.unclaim` | Der Besitzer gibt den aktuellen eigenen Chunk wieder frei, während das Entfernen fremder Claims ausschließlich über die dazugehörige Admin-Permission möglich ist. |
| `/claim trust add <Spieler>`, `/claim trust remove <Spieler>` und `/claim trust list` | `claim.trust.add`, `claim.trust.remove` und `claim.trust.list` | Der Besitzer verwaltet für den aktuellen Claim einzeln, wer dort bauen darf, wer aus der Liste entfernt wird und welche Spieler bereits Zugriff erhalten haben. |
| `/claim settings title <Text|reset>` und `/claim settings subtitle <Text|reset>` | `claim.settings.title` und `claim.settings.subtitle` | Der Besitzer stellt für seinen aktuellen Claim einen eigenen Titel und Untertitel ein, die beim Betreten dieses Bereichs angezeigt werden und mit `reset` wieder auf die Standardtexte zurückgesetzt werden. |
| `/claim info` und `/claim help` | `claim.info` und `claim.help` | Der Spieler sieht den Besitzer und die vertrauten Spieler seines aktuellen Claims oder erhält eine Übersicht über die verfügbaren Claim-Unterbefehle. |
| `/claim admin unclaim` | `claim.admin.unclaim` | Ein Teammitglied entfernt den Claim des Chunks, in dem es gerade steht, auch wenn dieser einem anderen Spieler gehört. |
| Schutzumgehung auf fremden Claims | `claim.admin.bypass` | Ein dazu berechtigtes Teammitglied darf die durch Claims geschützten Blöcke und Interaktionen umgehen, ohne den Claim eines Spielers löschen zu müssen. |
| Verwaltung fremder Claims | `claim.admin.trust`, `claim.admin.settings.title` und `claim.admin.settings.subtitle` | Ein Teammitglied kann Trust-Einträge und Titel oder Untertitel auf fremden Claims getrennt verwalten, sodass die Rechte für jede Einstellung einzeln vergeben werden können. |
| Claim-Limits und Admin-Sammelrecht | `claim.claims.N`, `claim.claims.unlimited` und `claim.admin` | Ein Zahlenrecht setzt das persönliche Limit auf den jeweiligen Wert N, `claim.claims.unlimited` hebt dieses Limit auf, und `claim.admin` bündelt die einzelnen Claim-Adminrechte für eine Teamgruppe. |
| `/tpa <Spieler>`, `/tpaaccept` und `/tpacancel` | `system.tpa` | Der Spieler sendet eine Teleportanfrage, nimmt eine eingegangene Anfrage an oder lehnt sie ab, wobei die Anfragebefehle gemeinsam über das TPA-Recht gesteuert werden. |
| `/tpaauto`, `/tpahere <Spieler>` und `/tpalookup` | `system.tpa.auto`, `system.tpa.here` und `system.tpa.admin` | Der Spieler schaltet das automatische Annehmen um oder bittet einen anderen Spieler zu sich, während die Adminberechtigung die Anzeige der letzten Teleportvorgänge und die TPA-Verwaltung ermöglicht. |
| `/tp <Spieler> [Zielspieler]` und `/tphere <Spieler>` | `system.tp` und `system.tp.here` | Das Team teleportiert sich zu einem Spieler, teleportiert einen Spieler zu einem anderen Ziel oder holt einen Spieler direkt zum eigenen Standort. |
| `/spawn [Spieler]` und `/setspawn [Gruppe]` | `system.spawn`, `system.spawn.others`, `system.spawn.set` und `system.spawn.set.group` | Der Spieler oder ein dazu berechtigtes Teammitglied teleportiert sich zum passenden Spawn, setzt den Hauptspawn oder verwaltet gruppenspezifische Spawnpunkte. |
| `/rtp` | `system.rtp` | Der Spieler wird an einen sicheren zufälligen Ort in der aktuellen Welt teleportiert, nachdem das RTP-Feature einen geeigneten Zielort gefunden hat. |
| `/fly` und `/fly <Spieler>` | `system.fly.use`, `system.fly.other` und `system.fly.admin` | `system.fly.use` erlaubt eigenes Fliegen, `system.fly.other` erlaubt die Verwaltung eines anderen Spielers, und `system.fly.admin` umfasst beide Flugfunktionen. |
| `/gamemode <Modus> [Spieler]` | `system.gamemode`, `system.gamemode.survival`, `system.gamemode.creative`, `system.gamemode.adventure`, `system.gamemode.spectator` und `system.gamemode.other` | Die Rechte legen getrennt fest, ob ein Spieler den eigenen Modus ändern darf, welche Modi erlaubt sind und ob ein Zielspieler angegeben werden darf. |
| `/msg <Spieler> <Nachricht>` und `/msglogs [Seite]` | `system.msg.use` und `system.msg.see` | Das MSG-Recht erlaubt private Nachrichten auf dem Backend-Server, während das separate See-Recht den Zugriff auf die gespeicherten Nachrichtenprotokolle freischaltet. |
| `/invsee <Spieler>` oder `/inv <Spieler>` | `system.invsee` und `system.invsee.modify` | Das erste Recht öffnet die Inventaransicht mit Rüstung und Nebenhand, und das zusätzliche Modify-Recht erlaubt Änderungen am angezeigten Inventar. |
| `/ec` und `/ecsee <Spieler>` | `system.ec`, `system.ecsee` und `system.ecsee.modify` | `system.ec` öffnet die eigene Endertruhe, `system.ecsee` zeigt die Endertruhe eines Spielers, und `system.ecsee.modify` erlaubt Änderungen an der geöffneten fremden Truhe. |
| `/balance`, `/pay <Spieler> <Betrag>` und `/payinfo` | `system.eco.balance`, `system.pay` und `system.pay.admin` | Die Economy-Rechte steuern die Anzeige des Guthabens, das Senden von Geld an einen Online-Spieler und den Zugriff des Teams auf die lokalen Zahlungsprotokolle. |
| `/eco <give|take|set|reset|balance> ...` | `system.eco` | Das Economy-Adminrecht verwaltet Kontostände und darf Guthaben vergeben, abziehen, setzen, zurücksetzen oder für einen Spieler anzeigen. |
| `/sell` | `system.sell` | Der Spieler öffnet das Verkaufsmenü und verkauft daraus nur die Materialien, die im Sell-Feature mit einem Preis versehen wurden. |
| `/market` oder `/markt` | `system.market.use` und `system.market.admin` | Das erste Recht öffnet den dynamischen Markt mit den Kategorien, während die Adminberechtigung das Nachladen der Konfiguration und das Zurücksetzen dynamischer Marktbestände ermöglicht. |
| `/rank` oder `/ränge` und `/rank reload` | `system.rank.use` und `system.rank` | Das Spielerrecht öffnet den Rangmarkt und erlaubt Rangkäufe, während das Adminrecht die Rangkonfiguration neu lädt. |
| `/aktionshaus` oder `/ah` | `system.aktionshaus.use` und `system.aktionshaus.admin` | Das Spielerrecht öffnet das Aktionshaus für Kauf und Verkauf, während das Adminrecht die administrative Verwaltung der Angebote freischaltet. |
| `/clan`, `/clan delete`, `/clan confirm`, `/cc <Nachricht>` und `/clanadmin ...` | `system.clan.use`, `system.clan.create`, `system.clan.color`, `system.clan.admin` und `clan.delete.confirm.bypass` | Das Clan-Recht erlaubt die normalen Clanbefehle und den Clan-Chat, während Erstellen, Farbänderungen, Teamverwaltung und das Umgehen der Ablaufzeit einer bereits angeforderten Löschbestätigung über separate Rechte gesteuert werden. |
| `/home [Name]`, `/sethome [Name]`, `/delhome [Name]` und `/renamehome <alt> <neu>` | `system.home`, `system.home.set` und `system.home.N` | Das Home-Recht verwaltet und besucht gespeicherte Homes, das Set-Recht erlaubt Änderungen, und ein Zahlenrecht wie `system.home.5` setzt das persönliche Speicherlimit. |
| `/homeadmin [set|remove] <Spieler> [Home]` | `system.home.admin` | Ein Teammitglied verwaltet oder besucht die Homes eines anderen Spielers, ohne dessen normale Home-Rechte ändern zu müssen. |
| `/kit set` | `system.kit.admin` | Ein Administrator speichert sein aktuelles Inventar einschließlich Rüstung und Nebenhand als Starterkit, das neuen Spielern beim Beitritt ausgegeben wird. |
| `/playtime [Spieler]` oder `/pt [Spieler]` | `system.playtime.use` und `system.playtime.admin` | Das Spielerrecht zeigt die eigene netzwerkweite Spielzeit an, während das Adminrecht die Abfrage der Spielzeit anderer Spieler erlaubt. |
| `/code <Code>`, `/codetop` und Code-Adminbefehle | `system.code.use`, `system.code.top` und `system.code.admin` | Spieler lösen Codes ein oder sehen die Rangliste der meistgenutzten Codes, während das Adminrecht Codes erstellt, konfiguriert, löscht und Einlösungen zurücksetzt. |
| `/monitoring`, `/mon`, `/performance` und `/perf` | `system.monitoring.use` und `system.monitoring.admin` | Das Monitoring-Recht konfiguriert die persönliche Actionbar-Anzeige, während das Adminrecht den globalen Performance-Anzeigemodus umschaltet. |
| `/booster` und `/boostergive <Spieler> <Typ> <Anzahl>` | `system.booster.use` und `system.booster.admin` | Das Spielerrecht öffnet das Booster-Menü, und das Adminrecht vergibt Abbau-, Drop- oder Erfahrungsbooster an andere Spieler. |
| `/db [hand]`, `/news`, `/newsconfig`, `/halloween` und `/isign` | `system.db.use`, `system.db.add`, `system.db.admin`, `system.news.view`, `system.news.config`, `system.halloween.user`, `system.halloween.set`, `system.halloween.reset` und `system.isign` | Diese Rechte steuern getrennt die Item-Datenbank, das Hinzufügen oder Löschen ihrer Einträge, die Anzeige und Bearbeitung von Neuigkeiten, das Sammeln und Verwalten von Halloween-Köpfen sowie das Signieren von Items. |
| `/one-klick` oder `/oneclick`, `/ka`, `/kristall` und `/kristalle` | `system.oneclick.admin`, `system.kristall.admin` und das Legacy-Recht `kristallsystem.admin` | Das Team markiert erlaubte Werkzeuge für One-Klick und verwaltet die Kristallkonten, wobei die frühere Kristall-System-Permission zur Kompatibilität weiterhin erkannt wird. |
| `/customcmd <list|reload|create|delete>` und `/performance` | `system.customcmd.admin` und `system.monitoring.admin` | Das Custom-Command-Recht verwaltet die in `features/create-cmds/config.yml` gespeicherten Zusatzbefehle, während die Performance-Berechtigung den globalen Anzeige-Modus ändert. |

Die Befehle `/greenattack reload` und `/slots <Anzahl>` sind mit `system.admin` geschützt, wobei der Reload die Hauptkonfiguration und die von GreenAttack verwalteten Features neu einliest und die Slotänderung das Spielerlimit dieses einzelnen Backend-Servers aktualisiert.

## Claims anpassen

Alle Claim-Einstellungen liegen in `plugins/GreenAttack-System/features/claims/config.yml`, wo `max-claims-per-player` als Standardlimit greift, solange ein Spieler kein Zahlenrecht `claim.claims.N` besitzt, der Abschnitt `cost.claim` den Preis pro Chunk festlegt, `disabled-worlds` Welten von der Claim-Erstellung ausschließt und `defaults.title` sowie `defaults.subtitle` die Anzeige beim Betreten eines Claims definieren.

Der Preis steht standardmäßig in der Claim-Config auf `free`, kann aber direkt dort durch einen Zahlenbetrag ersetzt werden, sodass bei jedem neuen Claim der konfigurierte Betrag aus der GreenAttack-Economy abgezogen wird und der Claim abgelehnt wird, wenn die Economy nicht verfügbar ist oder das Guthaben nicht reicht.

```yaml
cost:
  claim: free
```

Wenn du für jeden neuen Chunk stattdessen zum Beispiel 250 Economy-Dollar berechnen möchtest, trägst du unter `claim:` einfach den Zahlenwert `250` ein und startest anschließend den vorgesehenen Konfigurations-Reload oder den Backend-Server neu.

Die Actionbar wird über `actionbar.enabled` ein- oder ausgeschaltet und kann mit `actionbar.format` angepasst werden, wobei `%(plugin)prefix%` den Prefix aus der Hauptkonfiguration, `%spieler%` den Besitzer, `%count%` die Größe des zusammenhängenden Claim-Bereichs und `%claims%` den Zusatz mit der Gesamtzahl bei mehreren benachbarten Claims einsetzt.

Ein Beispiel wie `/lp group supporter permission set claim.claims.5 true` weist einer LuckPerms-Gruppe ein Limit von fünf Claims zu; wenn mehrere numerische Claim-Rechte gleichzeitig vergeben sind, verwendet das Plugin das höchste positive Limit, und `claim.claims.unlimited` hebt die Grenze ausdrücklich auf.

## Rangmarkt, Sell und Economy einstellen

Die Rangangebote werden in `features/rank/config.yml` unter `ranks:` in der gewünschten Reihenfolge gepflegt, wobei jeder Eintrag einen frei wählbaren Schlüssel, Anzeigenamen, vorhandenen LuckPerms-Gruppennamen, Kristallpreis und ein Minecraft-Material erhält; die Liste zeigt bis zu neun Ränge mittig in einer Reihe und weitere Angebote auf zusätzlichen Seiten.

Die Verkaufspreise stehen in `features/sell/config.yml`, wo `default-price` für nicht einzeln aufgeführte Materialien gilt, `prices.<MATERIAL>` einzelne Materialpreise überschreibt und `price-multiplier` alle Verkaufspreise gemeinsam anhebt oder senkt, wobei der Standardfaktor 1,5 beträgt.

Die Economy-Einstellungen wie Symbol, Nachkommastellen, Startguthaben und maximale Kontostände liegen in der Hauptdatei `plugins/GreenAttack-System/config.yml` unter `economy:`, während Befehlsmeldungen und der Chat-Prefix im Abschnitt `messages:` angepasst werden können.

## Feature-Konfigurationen

Jedes Feature besitzt seinen eigenen Ordner unter `plugins/GreenAttack-System/features/`, beispielsweise `claims/config.yml`, `rank/config.yml`, `sell/config.yml` oder `clans/config.yml`; dort können Aktivierung, Feature-Rechte, Preise, Nachrichten, Limits, Kategorien und Darstellung angepasst werden, ohne die Hauptkonfiguration für Feature-spezifische Werte zu verwenden.

Wenn eine Funktion in einem Feature-Config-Ordner geändert wurde, lädt `/greenattack reload` die Einstellungen des Plugins neu, während einzelne Features zusätzliche Befehle wie `/rank reload` oder `/market reload` anbieten können, sofern der aufrufende Spieler das passende Adminrecht besitzt.

## Bauen und installieren

Zum Bauen des JAR-Plugins wird ein JDK benötigt, und unter Windows kann der Build im Hauptordner des Repositorys mit `.\gradlew.bat jar sourcesJar` ausgeführt werden; die JAR-Datei wird danach unter `lib/GreenAttack-System-3.5.jar` abgelegt.

Das Quellcode-Archiv soll zusammen mit den Gradle-Dateien und dem kompletten `src`-Ordner verteilt werden, damit das Projekt ohne Dekompilierung geöffnet und verändert werden kann; für Paper 1.21.11 wird Java 21 benötigt, während neuere Paper-Versionen ihre jeweils unterstützte Server-Java-Version voraussetzen.
