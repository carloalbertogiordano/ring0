# ColmiSmartRing

Monitoraggio locale (sonno, HR/HRV, stress, SpO2, temperatura) con Colmi R09, senza cloud né abbonamenti.
App Android da zero che parla direttamente via BLE col ring.

- `docs/` – protocollo (`protocol.md`) e report ricognizione (`ring-recon.md`)
- `captures/` – catture grezze (GATT dump, btsnoop, log bleak)
- `proto/` – prototipi Python (bleak) per esplorare il protocollo
- `android/` – app Kotlin (più avanti)

## Regole
- Un solo ring: niente flash firmware / reboot / OTA / factory reset senza ok esplicito.
- Ogni comando scoperto va in `docs/protocol.md` con esempio hex reale.
- Niente dati personali nei repo pubblici.
