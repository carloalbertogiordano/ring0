# Backlog / da ricordare

## Respirazione (frequenza respiratoria) – da indagare
Non esposta dal protocollo noto. Strade, in ordine di costo:
1. **Cercare un comando/big-data type non documentato**: analisi APK QRing (jadx) e cattura HCI mentre l'app mostra "respirazione"/"stress".
   Gadgetbridge non la espone per questi ring.
2. **Derivarla dal PPG grezzo** (respiratory-induced variations: RIIV/RIAV/RIFV, oppure RSA da RR). Serve PPG >= 25-50 Hz
   (su R02 e' ~4 Hz, insufficiente). Verificare se R09 ha stream raw piu' veloce o se si sblocca via firmware.
3. **Derivarla dall'accelerometro** a riposo (movimento toracico/polso, 0.1-0.5 Hz) se lo stream accel e' esposto
   (Edge Impulse example-data-collection-colmi-r02 mostra come ottenerlo).
Algoritmi candidati: stima spettrale su PPG band-pass 0.1-0.5 Hz, fusione di piu' segnali respiratori (smart fusion), validazione contro riferimento.

## Altro
- Fascia cardio BLE (0x180D, RR) come ground truth per HR/HRV.
- Gateway notturno T-Embed -> MQTT/HTTP verso homelab.
