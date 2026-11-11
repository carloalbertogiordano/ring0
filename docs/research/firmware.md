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

## Risultati dell'analisi (2026-11-11, firmware R02 3.00.06 in Ghidra 12.1.3 headless)
Importazione: payload dell'OTA senza l'header da 0x100 byte, caricato a **0x803000** (trovato cercando il payload nel dump della flash),
processore ARM Cortex (Thumb), funzioni create dalla tabella dei vettori; 898 funzioni, analisi in meno di un minuto.
Script riproducibili in `tools/ghidra/`.
- **Dispatcher dei comandi V1**: `FUN_008184f4` (0x8184f4) legge il primo byte del pacchetto. Tra gli altri: `0x69` -> `FUN_0080a2c8`
  (avvio real-time), `0x6a` -> `FUN_0080a240` (stop), `0x43` passi, `0x50` trova dispositivo, e **`0x72` (push message) e `0x39` ritornano
  subito senza fare nulla**: il firmware ignora i messaggi push, coerente con quanto osservato sull'R09.
- **Sessione real-time**: lo stato sta a RAM 0x105fac. All'avvio (`FUN_0080a2c8`) viene azzerato il contatore `stato+9`, memorizzati
  tipo (`+7`) e azione (`+8`), acceso il sensore (`FUN_00812560`: 1 per il cuore, 0x20 per la SpO2) e avviato un **timer software
  periodico da 500 ms** (`FUN_00818a94(timer, callback 0x808f50, 500)`): e' il pacchetto ogni 0,5 s che vediamo.
- **Callback del timer** `FUN_00808f50`: incrementa `stato+9` (un byte) a ogni tick e chiama `FUN_00808b74` (tutti i tipi tranne il 6).
  In `FUN_00808b74`: se il contatore arriva a **0x3b/0x3c (59-60 tick, cioe' ~30 s)** spegne il sensore (`FUN_00812610`), azzera il flag di
  sessione e **ferma il timer** (`FUN_00818ab4`). Il bpm calcolato dall'anello viene inviato solo da **0x33 (51 tick, ~25,5 s)** in poi
  (i nostri log sull'R09: primo bpm a 25,8 s). Se il sensore segnala "assenza di segnale" (`FUN_0081247c`) dopo 6 tick manda un pacchetto
  con codice di errore 1 e si ferma.
- Conclusione: il limite di ~30 s e' **una costante del firmware** (confronti con immediati 0x3b/0x3c/0x33/0x32 su un contatore a 8 bit), non
  un limite del sensore. Allungare la sessione richiede di cambiare poche istruzioni Thumb (immediati `cmp`/`movs`), con il vincolo che il
  contatore e' un byte (massimo 255 tick = ~127 s) e che l'invio di bpm/RR dipende da soglie intermedie; per sessioni piu' lunghe servirebbe
  anche azzerare il contatore (o ampliarlo) invece di spegnere.
- La patch della community "FasterRawValues" (1 byte a 0x80ca0a + CRC dell'header) mostra che il bootloader accetta immagini modificate
  controllandone solo il CRC32 (nessuna firma) - almeno sull'R02. Da verificare sull'R09.

## Cosa manca (bloccante)
L'immagine dell'**R09 (RT09CR2_3.00.03)** non e' pubblica. L'endpoint del produttore (`POST api1.qcwxkjvip.com/qcwx/app-update/last-ota`)
risponde `401 Not logged in yet or token has expired`: serve un account del produttore (token dopo registrazione/login nell'app).
Alternative: copiare il file dalla cache dell'app ufficiale se un aggiornamento viene scaricato; analizzare solo l'R02 e verificare
sull'R09 per comportamento (stessa famiglia di firmware 3.00.xx, stesso limite di 30 s e stesso periodo di 500 ms osservati).
