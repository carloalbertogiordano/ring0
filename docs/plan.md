# Piano di sviluppo

## Metodo
- **Red-Green-Refactor** per ogni slice: test rosso (su pacchetti reali catturati), minimo codice per il verde, refactor a verde.
- **Prima di ogni commit di codice**: `proto/check.sh` (lint, tipi, code smell, duplicati, complessità, dead code, security, coverage).
- Un solo slice alla volta, commit piccoli. Nessun comando di tipo reboot/OTA/factory reset senza ok esplicito di Carlo.
- Python prototipo in `proto/` (venv uv), poi porting Kotlin in `android/` con gli stessi vettori di test.

## Tooling qualità
| Scopo | Python (`proto/`) | Kotlin (`android/`, dopo) |
|---|---|---|
| Lint/format | ruff | ktlint |
| Tipi | mypy --strict | compilatore |
| Code smell | pylint | detekt |
| Duplicati | pylint R0801 (similarities) | detekt / CPD |
| Complessità | radon cc (fallisce da rank C) | detekt ComplexMethod |
| Dead code | vulture | detekt UnusedPrivate* |
| Security | bandit | lint Android |
| Property test | hypothesis | kotest-property |
| Coverage | pytest-cov (soglia) | kover |

## Cosa sappiamo del protocollo (fonti: colmi-docs, Gadgetbridge letto per capire, cattura reale)
- Canale V1 (UART, pacchetti 16 byte, CRC = somma & 0xFF): set time 0x01, batteria 0x03, HR log 0x15,
  auto-HR 0x16, real-time HR 0x1E, auto-SpO2 0x2C, stress 0x36/0x37, HRV 0x38/0x39, auto-temp 0x3A,
  passi 0x43, manual HR 0x69/0x6A.
- Canale V2 big-data (`de5bf728…`, header 0xBC, id, len16, crc16/MODBUS): **temperatura 0x25**, sleep 0x27, SpO2 0x2A.
- Temperatura: per giorno `days_ago`, 1 byte (0x1E = intervallo 30 min), poi 48 byte (24 h x 2); °C = byte/10 + 20; 0 = nessun dato.
- Stress/HRV: slot da 30 min, 13 valori per pacchetto, 0 = nessun dato. Da verificare sul ring reale.
- Set Time risponde con flag di capability (temperatura, HRV, pressure/stress): primo comando da provare, conferma cosa supporta questo R09.

## Architettura Python (prototipo)
`ringproto/`: `packet.py` (frame V1) -> `bigdata.py` (frame V2 + crc16) -> `commands/*` (encoder/decoder per comando)
-> `client.py` (bleak, coda seriale, timeout) -> `store.py` (SQLite). Il livello pacchetti e i decoder sono puri (niente I/O).

## Algoritmi (livello `domain`, dopo aver raccolto dati veri)
Principio: partire dai valori elaborati dal ring, calcolare in proprio solo dove ci sono dati grezzi sufficienti.
- **HRV**: il ring dà un valore ogni 30 min (ms). Se avremo RR/PPG: RMSSD come metrica primaria (robusta su finestre brevi),
  SDNN di contorno; pulizia artefatti con soglia relativa (scarto battiti > 20% dalla mediana mobile, Malik) prima di calcolare;
  finestra notturna (sonno profondo) per il valore di riferimento giornaliero. Normalizzazione: ln(RMSSD) vs baseline personale
  mobile (14-28 giorni), espressa come z-score.
- **Stress**: valore del ring (0-100) in prima battuta; indicatore proprio = deviazione di ln(RMSSD) e HR a riposo dalla baseline
  personale (stile "readiness"); Baevsky Stress Index solo se avremo RR a risoluzione sufficiente.
- **Sonno**: il ring fornisce gia' fasi (light/deep/REM/awake) e inizio/fine. Metriche derivate: TST, latenza, efficienza,
  WASO, % fasi, regolarita' (Sleep Regularity Index). Algoritmi su accelerometro (Cole-Kripke/Sadeh) solo se otteniamo accel grezzo.
- **SpO2**: min/max per campione; filtro mediano per spike; ODI (indice di desaturazione >= 3-4% da baseline) notturno come proxy
  di respirazione disturbata. Non e' un dispositivo medico.
- **Temperatura**: valore assoluto cutaneo poco significativo; usare scostamento notturno dalla baseline personale (mediana mobile).
- **Respirazione**: non esposta dal protocollo noto. Possibile solo via RSA da PPG grezzo ad alta frequenza (obiettivo fase 2, incerto).
