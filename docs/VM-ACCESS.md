# Aegis-VM: Zugang und Entwicklungsgrundlage

## Zielsystem

- Proxmox-VM: 102, `aegis-dev`
- Reservierte IPv4-Adresse: `192.168.2.159`
- Benutzer: `aegis-agent`, Administratorrechte über `sudo -n`
- Betriebssystem: Debian 13
- Ausstattung: 4 vCPU, 8 GiB RAM, 100 GiB virtuelle Platte
- Projektverzeichnis auf der VM: `/srv/aegis`

Der SSH-Zugang gilt für diese VM. Für Proxmox selbst sowie Home Assistant und Mealie wurde kein Zugang eingerichtet und keine Konfiguration geändert.

## Verbindung von diesem Windows-PC

```powershell
ssh -i C:\Users\cedri\.ssh\aegis_ed25519 -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=C:\Users\cedri\.ssh\aegis_known_hosts aegis-agent@192.168.2.159
```

Für automatische Befehle zusätzlich `-o BatchMode=yes` verwenden.

Der private Schlüssel liegt ausschließlich im persönlichen `.ssh`-Verzeichnis außerhalb des Repositorys. Die Passphrase wurde nach ausdrücklicher Bestätigung entfernt. Schlüsseldateien nicht in das Repository oder lesbare Haushalts-Exporte aufnehmen.

Verifizierter Server-Fingerabdruck, über VM-Konsole abgeglichen:

```text
ED25519 SHA256:K9ggmxG5kX94EhiY/psFbP9CCWxQwx3F2xy6jNCs0mQ
```

Fingerprint des öffentlichen Projektschlüssels:

```text
ED25519 SHA256:b4XOrh7SJnllBiyXs/mRSfhzJORKl3p2tcflPokp7mo
```

Bei geändertem Hostschlüssel zuerst Ursache und Fingerabdruck in der VM-Konsole prüfen. Die strikte Hostprüfung nicht deaktivieren.

## Entwicklungsgrundlage

Die Basis verwendet Debian-Pakete für Docker/Compose, OpenJDK 21, Maven, Git, Restic und rsync. Docker wird vorerst mit `sudo` ausgeführt. Es ist kein ungeschützter Docker-TCP-Zugang vorgesehen.

```text
/srv/aegis/
  source/           Arbeitskopie des Projekts
  deploy/           Deployment-Konfiguration
  data/             Dauerhafte Dienstedaten, Rechte dienstweise vergeben
  exports/          Lesbare Ausgaben
  backup-staging/   Vorbereitete konsistente Sicherungsartefakte
```

Installation reproduzieren: [bootstrap-vm.sh](../ops/bootstrap-vm.sh), ausschließlich innerhalb der vorgesehenen Aegis-VM ausführen.

## Noch nicht eingerichtet

- Fachanwendung, Datenbankcontainer und Paperless
- Mail- und Kalenderanbindung
- Externes Backupziel und getestete Wiederherstellung
- Öffentlicher Webzugang und produktive Haushaltsdaten

Restic allein ist noch kein Backup. Vor Nutzung als verlässliche Ablage muss ein separates Sicherungsziel angebunden und ein Wiederherstellungstest abgeschlossen werden.

## Verifizierter Einrichtungsstand

- Schlüsselanmeldung mit strikter Hostprüfung erfolgreich; `sudo -n whoami` ergibt `root`.
- OpenJDK 21.0.12.1, Maven 3.9.9, Docker Engine 26.1.5 (Debian-Paket), Compose 2.26.1 und Restic 0.18.0 geprüft.
- Docker-Testcontainer `hello-world` erfolgreich heruntergeladen und ausgeführt; Testcontainer automatisch entfernt.
- SSH, QEMU Guest Agent, Docker und systemd-timesyncd aktiv.
- Zeitzone Europe/Berlin und `NTPSynchronized=yes` bestätigt.
- Nach Installation rund 87 GiB frei auf `/`.
- Bootstrapskript auf der VM mit `sh -n` geprüft und unter `/srv/aegis/deploy/bootstrap-vm.sh` abgelegt.
- Keine Aegis-Webdienste oder Datenbankports veröffentlicht.

Dies schließt den Infrastrukturteil der Einrichtung ab, aber nicht Phase 1 der Roadmap: insbesondere Backup/Restore und Anwendungsgrundgerüst stehen noch aus.
