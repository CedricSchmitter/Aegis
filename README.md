# Aegis

Private Haushaltszentrale für Dokumente, Aufgaben, Termine und einen späteren persönlichen KI-Assistenten.

## Baseline

- [Roadmap](ROADMAP.md): Produktplan, Architektur, Datenhoheit und überprüfbare Entwicklungsphasen.
- [VM-Zugang und Einrichtungsstand](docs/VM-ACCESS.md): verifizierte Entwicklungsumgebung auf Proxmox.
- [VM-Bootstrap](ops/bootstrap-vm.sh): reproduzierbare Installation der Entwicklungswerkzeuge in der dedizierten VM.

## Technische Richtung

Java 21 und Spring Boot als modularer Backend-Kern, PostgreSQL und React/TypeScript als Weboberfläche. Paperless übernimmt das Dokumentenarchiv. Kalender und KI-Anbieter werden über Adapter angebunden. Die Roadmap beschreibt die einzelnen Entscheidungen und Erweiterungskriterien.

## Aktueller Stand

Planung und VM-Grundausstattung sind vorhanden. Fachanwendung, Datenbankdienste, externes Backup und Wiederherstellungstest sind noch nicht umgesetzt.

Die Quelltexte werden lokal in diesem Repository gepflegt. Die Aegis-VM dient als Entwicklungs- und Testumgebung. Private Schlüssel, Tokens, produktive Dokumente und Dienstedaten gehören nicht in Git.

## Nächster Meilenstein

Spring-Grundgerüst mit PostgreSQL, Datenbankmigrationen und überprüfbarem Betrieb gemäß Phase 1 der Roadmap. Mailanbieter, Kalenderkonten und externes Backupziel sind noch festzulegen.
