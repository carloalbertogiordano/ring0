# Ring recon (fase 0) – work in progress

## Dispositivo
- Nome BLE: `COLMI Ring 2` (scatola: R09, "Ring 2" = nome commerciale)
- MAC: vedi `captures/` (non riportato qui)
- Adv: service `0xFEE7`, manufacturer data company id 0x1234 (4660) = `fee7` + MAC reversed
- HW revision: `RT09CR2_V3.0` (0x2A27) -> R09 confermato
- FW revision: `RT09CR2_3.00.03_260929` (0x2A26)
- System ID: `REDACTED` (derived from the MAC address)
- Serial (0x2A25): stringa vuota
- Device Name (0x2A00): `COLMI Ring 2`
- Batteria misurata alla prima prova: 7% (CMD 0x03, non in carica)

## GATT (captures/gatt_dump.txt)
| Service | Note |
|---|---|
| `6e40fff0-b5a3-f393-e0a9-e50e24dcca9e` | UART: RX `6e400002` (write/wwr), TX `6e400003` (notify) |
| `de5bf728-d711-4e47-af26-65e3012a5dc7` | big data: write `de5bf72a`, notify `de5bf729` |
| `0x180A` Device Information | serial, fw, hw, system id |
| `0x1812` HID | report map, 4x report, boot mouse |
| `0xFEE7` | Tencent: fea1 (read/notify), fea2 (read/write/indicate), fec9 |
| `0x1800`, `0x1801` | standard |

## Problema di connessione (Linux/BlueZ 5.87, Intel AX211)
Sintomi: connessioni che cadono, ATT che restano appesi ~30 s, `Unlikely Error`, timeout in discovery.

Causa (da cattura HCI, btmon): dopo la connessione **bluetoothd avvia SMP Pairing Request**
(DisplayYesNo, MITM, SC). Il ring risponde NoInputNoOutput/Legacy/No MITM; BlueZ emette
`User Confirmation Request` ma nessun agent risponde. Dopo ~30 s il ring chiude con
`Disconnect Reason: Authentication Failure (0x05)`. Durante il pairing pendente le richieste ATT non
vengono servite. Trigger probabile: service HID (HOG) + auto-connect di bluetoothd
(`MGMT Add Device: Auto-connect`), che riconnette il ring in background e ne contende la connessione.

Cattura: `captures/hci_probe.btsnoop` (non tracciata, contiene il MAC).

## Soluzione
Pairing "Just Works" con agent NoInputNoOutput:
```
bluetoothctl remove <MAC>
# scan bleak per metterlo in cache, poi:
bluetoothctl: agent NoInputNoOutput; default-agent; pair <MAC>
bluetoothctl untrust <MAC>   # evita auto-reconnect di bluetoothd che contende il link
```
Con ring bonded (e connesso a bluetoothd) il ring non pubblicizza piu': bleak non lo trova.
Fare `bluetoothctl disconnect <MAC>`, attendere ~3 s, poi `BleakScanner.find_device_by_address` + connect.
Dopo pairing: letture Device Information ok, `start_notify` su `6e400003` ok.

## Primo scambio verificato (CMD 0x03 batteria)
```
TX 03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03
RX 03 07 00 00 00 00 00 00 00 00 00 00 00 00 00 0a   -> byte1 = 7 (%), checksum ok
```

## Prossimi passi
- Caricare il ring (7%).
- Fase 1: client Python (set time, batteria, HR real-time, SpO2, passi, log HR).
