# Roadmap funzionalita'

Legenda dipendenze: [dati] serve una notte/giorni di dati reali, [hw] serve capire/sbloccare il ring, [ok] realizzabile ora.

## Fase A - Interfaccia (mockup approvato in docs/design/mockup.html)
- Tema terminale, icona, Home/Storico/Impostazioni nuovi, grafici con soglie, tile con sparkline  [ok]
- Primo avvio guidato (5 passi), permessi spiegati, Bluetooth spento, permesso negato, anello non trovato  [ok]
- Valori con "quanto e' vecchio" e banner batteria bassa  [ok]

## Fase B - Dominio (modulo Kotlin puro `domain`, con test su dati reali/sintetici)
1. Punteggio di recupero: ln(RMSSD) vs baseline mobile 14-28 gg (z-score) + FC a riposo + sonno  [ok per HRV/FC, sonno dopo [dati]]
2. Frequenza respiratoria da RR (RSA, spettro 0,1-0,5 Hz) sulla misura live  [ok]
3. Allarme precoce malessere: temperatura notturna vs baseline + FC a riposo + HRV, avviso solo se concordi  [dati: serve qualche giorno]
4. Alert con contesto (FC alta con margine, SpO2 bassa ripetuta/notturna), ODI notturno  [fatto; senza passi, il contesto 'da fermo' e' stato tolto]
5. Analisi sonno: durata, efficienza, latenza, risvegli, regolarita'  [dati]

## Fase B2 - Stime dai dati (prima di widget/export; ognuna dopo ricerca su paper e fonti, vedi docs/research/)
Prerequisito: profilo utente nelle Impostazioni (eta', sesso, peso, altezza, HR max opzionale), solo in locale.
14. Carico di allenamento (strain): minuti per zona HR, TRIMP giornaliero, rapporto acuto/cronico 7/28 gg  [ok]
15. Calorie totali e attive: metabolismo basale (Mifflin-St Jeor) + calorie da HR (Keytel) vs stima dell'anello  [ok]
16. "Batteria corporea" 0-100: sale con riposo/sonno, scende con stress e attivita'  [ok]
17. Sonno esteso: punteggio, orario ideale di letto, pisolini (solo HR bassa e stabile), fabbisogno stimato  [ok]
18. Autonomia batteria: ore residue dal log batteria  [ok]
19. Anello non indossato: buchi nei dati esclusi da medie e alert  [ok]
20. Promemoria di inattivita' come notifica sul telefono  [ANNULLATO: servivano i passi, tolti perche' poco affidabili]
21. Correlazioni con gli incidenti/categorie (caffe', alcol, esercizio, malessere vs HRV, FC, sonno)  [dati]
22. Recupero HR dopo sforzo (HRR) dal log a 5 minuti, indicativo  [ok]
23. Riepilogo mattutino e serale (notifica con recupero, sonno, consigli)  [ok]
24. VO2max stimato (indicativo): HR max / HR a riposo, [ok, indicativo]
25. Notti sospette per apnea (SpO2 oraria + ODI), solo indicatore  [ok, indicativo]
26. Fase del ciclo dalla temperatura cutanea notturna, opzionale  [fatto, sperimentale: solo dati sintetici, serve feedback di chi traccia il ciclo]
27. Irregolarita' del ritmo dagli RR (screening, non diagnosi, con avvisi chiari)  [ok, indicativo]
28. Idratazione: fattibilita' da valutare con la ricerca prima di implementare  [ricerca]
Non previsto: pressione arteriosa (PPG senza calibrazione non e' affidabile).

## Fase C - Controlli sull'anello
6. "Trova anello" (0x50, LED verde ~10 s)  [fatto, funziona]
7. Sveglie, vibrazione e notifiche sul ring  [ANNULLATO: l'R09 non ha il motorino di vibrazione]
8. Sessione di respirazione guidata con biofeedback HR/HRV (stream RR)  [ok]

## Fase D - Comodita' e dati
9. Widget home + tile impostazioni rapide
10. Esporta CSV, backup/ripristino cifrato del DB
11. Report settimanale locale e correlazioni  [dati]
12. Export verso homelab (MQTT/InfluxDB) in modulo separato, dietro interruttore; l'app base resta senza INTERNET

## Fase E - Ricerca sul ring
13. [IDEA, 2026-11-11] Superare il limite dello stream real-time (l'anello chiude da solo a ~30 s, ~14 s di aggancio, 17-20 battiti utili): capire dal firmware (OTA decompilato, Ghidra) se il limite e' nel firmware o nella configurazione del sensore PPG; solo analisi a freddo, nessun flash senza ok esplicito; un solo anello, rischio di brick. Se riuscisse: RR continui per un controllo del ritmo vero (64+ battiti, specificita' 99%), PPG grezzo.
13b. PPG grezzo ad alta frequenza (firmware / stream modificato); firmware OTA in Ghidra  [hw]

## Fase F - Archivio, webapp e cifratura (decisioni del 2026-11-11, da progettare)
Decisioni dell'utente: l'app puo' avere il permesso INTERNET, ma tutto va cifrato "a regola d'arte"; backup e ripristino cifrati; webapp
self-hosted con lo storico di sempre; sul telefono si tengono 6 mesi; l'app esporta solo i dati che il server non ha (handshake con
watermark, quindi anche dopo un mese di solo uso locale parte la sincronizzazione completa); export CSV solo dalla webapp; report
settimanale sia sull'app sia sulla webapp; niente widget.
- 27. Protocollo di sync incrementale e idempotente (chiavi (tipo, ts), watermark per tipo, ripresa dopo interruzione), potatura sul telefono
  solo dei dati gia' confermati dal server e piu' vecchi di 6 mesi; riepiloghi giornalieri e per notte tenuti per sempre.
- 28. Autenticazione dispositivo (coppia di chiavi nel Keystore, associazione con QR e codice di confronto SAS) e utente (passkey WebAuthn); TLS obbligatorio.
- 29. Cifratura end-to-end (X-Wing in HPKE + AES-256-GCM) verso la webapp, **chiavi di epoca con radice fredda** per la forward secrecy, manifesti firmati dal telefono, bundle web firmato; backup/ripristino cifrato con passphrase (Argon2id + AES-256-GCM). Modello di minaccia: server compromesso. Vedi docs/design/archive-crypto.md.
- 30. Server in Go (solo blob store cifrati + pairing + firme) e **decifratore nativo `ring0-viewer`** in Go con UI locale incorporata (grafici, confronto periodi, CSV, report settimanale). Nessun client web che decifra.
- 31. Report settimanale anche sul telefono.
- 32. [PREREQUISITO, da fare quando si cambia lo schema per l'archivio] Test delle migrazioni del database (Room: schema esportato e
  MigrationTestHelper), in particolare 6->7, 7->8 e le nuove (watermark di sync, riepiloghi giornalieri, sequenza ordinata delle fasi del sonno).
  Oggi i test sulle migrazioni NON esistono.
- 33. Schermata Sonno (dalla tile): ipnogramma con la sequenza reale delle fasi (serve salvare le fasi in ordine, cambio di schema insieme al punto 32).

## Fase G - Altro deciso il 2026-11-11
- 34. Respirazione guidata con pacer e misura prima/dopo (lo stream dell'anello dura 30 s, quindi niente biofeedback continuo finche' non cambia).
- 35. Firmware: analisi a freddo (nessun flash senza ok esplicito) per capire il limite dei 30 s dello stream; vedi docs/research/firmware.md.
- 36. Release firmate dalla CI con segreti in un Environment GitHub protetto e attestazione di provenienza.
- 37. Consumo della batteria: scelta degli intervalli di misura (profili) con autonomia stimata.
- 38. Rhythm check con ritmo irregolare reale e stima del ciclo: chiedere feedback su GitHub (README gia' aggiornato).
- Non previsti: widget e tile rapida, test di idratazione (per ora), CSV dall'app.

## Scoperte del 2026-11-11 che cambiano le priorita'
- 39. **PPG grezzo continuo a ~25 Hz** con i tipi real-time 13/14 (durata a scelta, fino a 65535 s), senza modificare il firmware. Da verificare con una
  registrazione a mano ferma. Se il segnale e' buono: RR calcolati da noi, HRV e controllo del ritmo veri (128+ battiti contigui, sensibilita' 87%,
  specificita' 99,6% sui dati di taratura), respirazione guidata con biofeedback continuo, HRV notturno. Costo atteso: batteria (LED sempre acceso).
- Il punto 35 (firmware) e' ridotto ad analisi: la modifica non serve per superare i 30 s.
