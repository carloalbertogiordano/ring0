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
> Discrepanza: colmi-docs dice "binario", tahnok usa BCD. Il ring accetta il pacchetto in entrambi i casi (risponde);
> quale sia interpretato correttamente va verificato con i timestamp dei log HR (slice 4).

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
Da verificare con dati reali: convenzione del timestamp (UTC vs locale) e codifica BCD/binario dell'ora.

Codice: `proto/ringproto/commands/hr_log.py`, `hr_settings.py`.
