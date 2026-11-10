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
