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
4. Alert con contesto (FC alta solo da fermo, SpO2 bassa ripetuta/notturna), ODI notturno  [passi: Fase E; SpO2 ok]
5. Analisi sonno: durata, efficienza, latenza, risvegli, regolarita'  [dati]

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
13. Passi/attivita' (0x43), obiettivi (0x21); PPG grezzo ad alta frequenza (firmware / stream modificato); firmware OTA in Ghidra  [hw]
