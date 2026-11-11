# Firmware: primi appunti (2026-11-11)

Obiettivo: capire se il limite dello stream real-time dell'R09 (~30 s, con ~14 s di aggancio; vedi docs/research/stime.md) sta nel
firmware e se e' modificabile. Solo analisi a freddo: nessun flash senza ok esplicito, un solo anello.

## Materiale gia' presente (refs/ATC_RF03_Ring, repo di atc1441, GPL-3.0)
- SoC dell'R02: BlueX RF03 (datasheet nel repo), accelerometro STK8321. Dump flash (3.0.02 e 3.0.06), ROM con bootloader e stack BLE,
  immagini OTA di vari modelli (R01, R02, RY02, SR1), SDK BLE, descrizione dell'header OTA.
- **Nessuna immagine dell'R09** (RT09CR2_3.00.03_260929): serve ottenerla.
- Una patch della community, `R02_3.00.06_FasterRawValuesMOD.bin`: confrontata con l'originale `R02_3.00.06_240523.bin` cambia **un solo
  byte del codice** (offset 0x9b0a: 0x7d -> 0x20, cioe' 125 -> 32, probabilmente un intervallo di campionamento) piu' i 4 byte di
  controllo dell'header (offset 4-7). Quindi piccole patch possono cambiare il comportamento di acquisizione.
- NON si flasha mai un'immagine di un altro modello sull'R09.

## Passi previsti
1. Ottenere l'immagine OTA dell'R09 dal server di aggiornamento del produttore (sola lettura, senza inviare dati personali) oppure dall'app.
2. Installare Ghidra (senza sudo, in ~/tools) e caricare l'immagine; identificare architettura, vettori, tabella dei comandi BLE (0x69/0x6A).
3. Trovare il timer/contatore che chiude lo stream a ~30 s e la sequenza di aggancio di ~14 s.
4. Documentare; ogni modifica resta teorica finche' l'utente non approva un piano di recupero.
