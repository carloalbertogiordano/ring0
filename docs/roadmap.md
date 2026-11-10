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
26. Fase del ciclo dalla temperatura cutanea notturna, opzionale  [dati, indicativo]
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
13. PPG grezzo ad alta frequenza (firmware / stream modificato); firmware OTA in Ghidra  [hw]
