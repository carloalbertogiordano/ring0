# Protocollo Colmi R09 (verificato sul ring reale)

Firmware di test: `RT09CR2_3.00.03_260929` (HW `RT09CR2_V3.0`). Canale V1: pacchetti da 16 byte,
`[cmd][14 byte dati][crc]`, crc = somma dei primi 15 byte & 0xFF, bit 7 di `cmd` = flag errore.
Implementazione: `proto/ringproto/packet.py`.

## 0x03 Batteria
Request: `03 00*14 03`
Response: `03 <percentuale> <in_carica> 00*12 <crc>`

| Esempio | Hex | Significato |
|---|---|---|
| req | `03000000000000000000000000000003` | |
| rsp | `0307000000000000000000000000000a` | 7%, non in carica (2026-11-10) |
| rsp | `03` `17` `01` ... | 23%, in carica (lettura live, `ringproto battery`) |

Codice: `proto/ringproto/commands/battery.py`. Test: `proto/tests/test_battery.py`.

## 0x01 Set Time + capability
Request: `01 <yy> <mm> <dd> <hh> <mi> <ss> 01 00*7 <crc>`, campi in **BCD**, ora **UTC**, byte 7 = lingua (1 = inglese).
> colmi-docs dice "binario", tahnok usa BCD: **BCD confermato** dai timestamp del log HR (vedi 0x15).

Response: `01 <payload 14 byte> <crc>` = mappa capability. Offset nel payload:

| Offset | Campo |
|---|---|
| 0 | supportsTemperature (==1) |
| 3 | bit1 SpO2, bit2 pressione |
| 8 | nuovo protocollo sonno (==1) |
| 11 | bit0 manual HR, bit7 glicemia |
| 13 | bit4 stress ("pressure"), bit5 HRV |

Risposta reale del tuo R09 (2026-11-10): `01 01 00 00 02 00 00 00 00 01 00 20 00 00 30 55`
-> temperatura SI, SpO2 SI, stress SI, HRV SI, nuovo sleep SI; pressione NO, glicemia NO, manual HR NO.
Byte non decodificati: payload[10] = 0x20 (flags2, bit5 non documentato), payload[13] = 0x30.

Codice: `proto/ringproto/commands/set_time.py`. Test: `proto/tests/test_set_time.py`.

## 0x16 Impostazioni HR automatico
Lettura: req `16 01 00*13 17` -> rsp `16 01 <abilitato: 1=si 2=no> <intervallo_min> <?> 00*10 <crc>`
Scrittura: req `16 02 <abilitato> <intervallo_min> 00*11 <crc>`; la risposta alla scrittura (`16 02 01 00…`) **non e' affidabile**:
si rilegge lo stato.
payload[3] = 5 in entrambe le letture: significato ignoto (candidato: intervallo minimo/granularita').

| Esempio reale | Hex | Significato |
|---|---|---|
| rsp lettura | `1601021e05000000000000000000003c` | disabilitato, 30 min (stato di fabbrica) |
| req scrittura | `1602010500000000000000000000001e` | abilita, 5 min |
| rsp scrittura | `16020100000000000000000000000019` | (inattendibile) |
| rsp lettura | `16010105050000000000000000000022` | abilitato, 5 min |

## 0x15 Log HR giornaliero
Req: `15 <unix ts LE u32 inizio giorno> 00*9 <crc>`. Provato con mezzanotte **UTC** di oggi (`15 80 5e f2 6a …`).
Risposta a piu' pacchetti, `payload[0]` = sub-tipo: 0 = header (`[size][intervallo_min]`), 1 = `[ts LE u32][9 valori]`,
>=2 = 13 valori, 0xFF = nessun dato. 0 = slot senza misura. `size` pacchetti in totale; log troncato a 1440/intervallo slot.
Reale: `15ff0000000000000000000000000014` = nessun dato (auto-HR era disattivo).
**Verificato (2026-11-10)**: il ring ha restituito 24 pacchetti, `day_start` = mezzanotte UTC richiesta, una misura 96 bpm
nello slot 173 (14:25 UTC = 15:25 CET = ora reale). Quindi l'ora impostata (BCD, UTC) e' corretta e il
timestamp di inizio giorno e' epoch UTC. Slot da 5 min; 288 al giorno; risposta = header + 22 pacchetti da 13 valori.

Codice: `proto/ringproto/commands/hr_log.py`, `hr_settings.py`.

## Canale big-data V2 (`de5bf728…`: write `de5bf72a`, notify `de5bf729`)
Frame: `BC <type> <len LE u16> <crc16 LE u16> <payload>`, crc16 = CRC-16/MODBUS (poly 0xA001, init 0xFFFF) del payload.
Le risposte lunghe arrivano **frammentate** su piu' notifiche (MTU 23 -> 20 byte) e vanno ricomposte fino a `len + 6`.
Richieste (come in Gadgetbridge; colmi-docs dice payload vuoto, ma il ring risponde a queste):

| Tipo | Dato | Richiesta | Risposta reale a ring senza storico |
|---|---|---|---|
| 0x25 | temperatura | `bc 25 01 00 3e 81 02` | `bc 25 02 00 81 b8 00 1e` (payload `00 1e`) |
| 0x27 | sonno | `bc 27 01 00 ff 00 ff` | `bc 27 01 00 bf 40 00` (payload `00` = 0 giorni) |
| 0x2a | SpO2 | `bc 2a 01 00 ff 00 ff` | `bc 2a 00 00 ff ff` (payload vuoto) |

Verificato: CRC16/MODBUS corretto sul ring reale (check standard `"123456789"` = 0x4B37).
Codice: `proto/ringproto/bigdata.py`, `bigdata_client.py`. CLI: `python -m ringproto.cli bigdata <MAC> --type 0x25 --payload 02`.
Decoder dei dati (temperatura/sonno/SpO2): da scrivere con dati reali (slice 6).

## Preferenze di monitoraggio automatico (0x2C SpO2, 0x36 stress, 0x38 HRV, 0x3A temperatura)
Lettura: `[cmd] 01 ...`; scrittura: `[cmd] 02 <0|1> ...`; la **temperatura ha un byte extra `03`**:
lettura `3a 03 01`, scrittura `3a 03 02 <0|1>`. Flag "abilitato" nella risposta: byte 2 (temperatura: byte 3). La
risposta alla scrittura non e' affidabile (eco dello stesso comando): si rilegge.

Stato di fabbrica del tuo R09: **tutti e 4 disattivi** (anche l'HR automatico). Reale:

| Monitor | Lettura prima | Lettura dopo scrittura 1 |
|---|---|---|
| SpO2 | `2c 01 00 1e 00…` (byte3=0x1e: intervallo 30 min?) | `2c 01 01 00…` |
| Stress | `36 01 00…` | `36 01 01…` |
| HRV | `38 01 00…` | `38 01 01…` |
| Temperatura | `3a 03 01 00…` | `3a 03 01 01…` |

Notifica non richiesta osservata: `73 0c <%>` = livello batteria (0x0c), es. `73 0c 1d…` = 29%.
Codice: `proto/ringproto/commands/auto_monitor.py`.

## 0x37 Stress / 0x39 HRV (log a slot da 30 min) - VERIFICATO (stress)
Stress: req `37 00…` (nessun argomento). HRV: req `39 <giorni fa u32 LE>` (come Gadgetbridge; il ring risponde `ff` = nessun dato).
Risposta a 5 pacchetti: `[0]` header `37 00 <5 pacchetti> <30 min>`, `[1]` `<?> + 12 valori`, `[2..4]` 13 valori ciascuno
(12+13*3 = 51, troncati a 48 slot). Valore 0 = nessuna misura. Slot 0 = 00:00 **UTC**.
Reale: stress 35 nello slot 29 = 14:30 UTC (pacchetto 3, valore a offset 4: 12 + 13 + 4 = 29).
HRV: formato assunto uguale (stesso codice), ancora senza dati reali.
Codice: `proto/ringproto/commands/slot_log.py`.

## Big-data 0x25 Temperatura - VERIFICATO
Req `bc 25 01 00 3e 81 02`. Risposta = blocchi da 50 byte `[giorni fa][passo=0x1e][48 valori]`, °C = valore/10 + 20, 0 = nessun dato.
Reale: `a9` (169) nello slot 29 -> 36.9 C alle 14:30 UTC (pelle del dito). Con payload di richiesta `07` risponde uguale (un solo
giorno, nessuno storico ancora): il significato del byte di richiesta resta da chiarire. Risposta senza storico: `00 1e`.
Codice: `proto/ringproto/commands/temperature.py`. CLI: `python -m ringproto.cli temperature <MAC> --days N`.

## 0x69 / 0x6A Real-time (HR) - parzialmente verificato
Start: `69 01 01 00…` (tipo 1 = HR, azione 1 = start). Stop: `6a 01 00 00 00…` (nessuna risposta).
Risposte `69 01 <errore> <bpm> <?> <?> <RR lo> <RR hi> 00…`. **Scoperta (app Android, 2026-11-10)**: i primi ~29 pacchetti sono tutti a 0
(il byte `bpm` resta 0 anche dopo), poi compaiono nei byte 5-6 del payload valori u16 LE come `ee 02` = 750, `7c 02` = 636,
`d3 02` = 723 ... = **intervalli RR in millisecondi** (750 ms = 80 bpm, coerente con l'HR a riposo misurato). Lo stesso RR viene
ripetuto in pacchetti vicini (un pacchetto/s, un battito ogni ~0.7 s) -> scartare i duplicati consecutivi. Quindi il ring espone
i **battiti singoli in tempo reale**: HRV vera (RMSSD/SDNN) calcolabile lato app. Il flag "manual HR" di set-time e' 0 ma il
comando funziona. Implementazione: `android/protocol/.../RealTime.kt`, `Hrv.kt`.

## 0x2A SpO2 - VERIFICATO (2026-11-10, app Android)
Risposta reale `bc 2a 31 00 <crc> 00 …`: payload 49 byte = `[giorni fa][24 x (min,max)]` orari. Sul ring: SpO2 96% (media di min/max).
## 0x25 Temperatura (app): risposta reale `bc 25 32 00 …` payload 50 byte, conferma il layout a blocchi.
## 0x27 Sonno: ancora senza dati (risposta `bc 27 01 00 bf 40 00`); layout da Gadgetbridge, non verificato.

## 0x69 / 0x6A real-time SpO2 (kind 3) - VERIFIED (2026-11-10, Android app)
Start `69 03 01 00…`, stop `6a 03 00 00…`. Replies `69 03 <error> <spO2 %> 00…`: about 25 s of zeros while the sensor locks on,
then values like 96, 97, 97, 99 (error byte 0). The app takes the median of 5 consecutive readings within 2 points.
The ring has a single PPG sensor, so SpO2 and the heart/RR measurement (kind 1) must be run one after the other.
Stress (kind 8) is untested on the R09.

## 0x43 Steps and 0x21 Goals - VERIFIED (2026-11-10), NOT USED by the app
The app no longer syncs or shows steps: the ring's step counting was judged unreliable, so the Kotlin code and its tests were
removed (see git history). The protocol notes below stay as reference.
Steps request `43 <day offset> 0f 00 5f 01` (0 = today). Reply: header `43 f0 <n packets> 01` (the last byte = new calorie
protocol, calories x10), then one packet per active 15-minute slot:
`43 <yy bcd> <mm bcd> <dd bcd> <slot 0..95> <idx> <total> <cal lo> <cal hi> <steps lo> <steps hi> <dist lo> <dist hi>`.
Slot time is on the ring clock (UTC). `43 ff` = no data (e.g. yesterday after a fresh start).
Real R09 slot: `43 26 11 10 38 00 04 f0 01 b8 00 72 00` = 2026-11-10 14:00 UTC, 184 steps, 114 m.
Goals request `21 01`; reply `21 01 <steps u24 LE> <calories u24 LE> <u24 ?>`: `21 01 88 13 00 e0 93 04 b8 0b 00` = 5000 steps,
300000 cal, 3000 (probably distance in m). Reading only: writing goals is not implemented.

## Alarms, push messages and vibration: NOT supported by the R09 (2026-11-10)
The R09 has **no vibration motor**: confirmed on the physical ring (push message and alarm produce nothing; only the green LED of
find-device 0x50 works). The commands below exist in the official QRing app (jadx on
`refs/ATC_RF03_Ring/QRing_Patched_to_disable_certificate_pinning.apk`, `com.oudmon.ble.base.communication`) for other models, but
the R09 (FW RT09CR2_3.00.03_260929) never answers them, so the app code was removed (see git history, commit "Remove alarms...").
- Big-data alarm (type 0x2C): read = payload `01`; write = `02 <count>` + records `<len> <repeat|enable> <minutes lo> <minutes hi> <name>`.
- V1 alarm slots: set = CMD `0x23` `[index 0..4][enable 0..2][hour BCD][minute BCD][7 bytes 0/1 = weekdays]`, read = CMD `0x24` `[index]`.
- Push message CMD `0x72`: `[type][total chunks][chunk 1..n][up to 11 text bytes]`.
- On the R09 every one of these gets **no reply at all**, while every other command answers within a second.

## 0x27 Sleep - VERIFIED with a real night (2026-11-11)
First recorded night on the R09 (FW RT09CR2_3.00.03_260929): the Gadgetbridge layout decodes correctly. Session `daysAgo=0 start=21
end=472` (minutes after UTC midnight, i.e. 01:21-08:52 local) with stages `LIGHT 22, DEEP 42, LIGHT 14, REM 18, LIGHT 19, DEEP 9, ...`
that sum exactly to the session length. The structure is textbook sleep architecture (about 90-minute cycles, deep sleep early, REM
growing towards the morning) and matches the 5-minute heart rate log (49-56 bpm from 02:00 to 08:00, rising again at wake-up).
Observations: the ring reported **no AWAKE stage** in the whole night (the app therefore scores sleep without efficiency and continuity), and the
last two stages are LIGHT (49 and 25 min) while the heart rate was already rising.

## 0x25 Temperature: no block for the current UTC day (2026-11-11, to be re-checked)
At 09:00 local the ring returned a single block (`daysAgo=1`, last non-zero slot 47 = 23:30 UTC) and none for the current UTC day, although
heart rate, HRV, stress and SpO2 existed for it. Either the ring does not measure temperature while you sleep or the new day's block is
created late. To be verified with a daytime sync.
