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
