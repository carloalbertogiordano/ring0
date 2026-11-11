# Archivio, webapp zero-knowledge e cifratura (progetto, 2026-11-11)

Stato: **progetto da approvare**, nessun codice ancora. Decisioni gia' prese dall'utente: l'app puo' avere il permesso INTERNET (build
`offline` senza permesso e build `sync`); webapp self-hosted raggiungibile (container Proxmox, dietro NPM, firewall e Cloudflare); TLS
obbligatorio sempre; **zero-knowledge** (il server non decifra mai); cifratura forte e resistente ai computer quantistici; il telefono tiene
6 mesi di dati grezzi; l'app manda solo cio' che il server non ha; backup e ripristino cifrati; tutto cifrato, anche gli incidenti.

## 1. Cosa significa "AES-512" e che cosa useremo
AES esiste solo a 128, 192 e 256 bit: **AES-256 e' il massimo**. Contro un computer quantistico l'algoritmo di Grover dimezza i bit di
sicurezza di una chiave simmetrica, quindi AES-256 resta a circa 128 bit, un margine ritenuto largamente sufficiente (NIST non chiede di
cambiare le dimensioni delle chiavi simmetriche; vedi [Valsorda](https://words.filippo.io/128-bits/)). Per avere comunque il margine piu' alto:
- cifratura dei dati: **AES-256-GCM**; derivazione e hash: **HKDF-SHA-512** (qui sta il "512");
- il rischio quantistico vero e' sulla cifratura **asimmetrica** (scambio di chiavi): per quella si usa un **KEM ibrido post-quantum**.

## 2. Primitive (nessuna invenzione: solo costruzioni standard)
| Scopo | Scelta | Note |
|---|---|---|
| Scambio di chiavi / chiave pubblica dell'archivio | **X-Wing** (ML-KEM-768 + X25519, NIST livello 3) dentro **HPKE (RFC 9180)** | X-Wing e' ancora un draft CFRG; HPKE-PQ lo chiama `MLKEM768-X25519`. Librerie: Bouncy Castle 1.86 (Android) e `@noble/post-quantum` (`ml_kem768_x25519`, browser). Alternativa piu' alta: ibrido ML-KEM-1024 + P-384 della bozza HPKE-PQ (livello 5): richiede di verificare il supporto nelle librerie. |
| Cifratura dei dati | **AES-256-GCM**, chiave per blocco derivata con HKDF-SHA-512 | nonce da 96 bit casuali; ogni blocco ha chiave e nonce propri |
| Derivazione da password | **Argon2id** (RFC 9106: >= 64 MiB, t >= 3, p = 4) | WASM nel browser, libreria nativa su Android |
| Autenticazione dispositivo | firma ECDSA P-256 con chiave nel **Keystore/StrongBox** (non esportabile) | le firme non hanno bisogno di protezione "harvest now, decrypt later"; le firme PQ (ML-DSA) non sono nel Keystore |
| Autenticazione utente web | **passkey WebAuthn**; recupero con codice da 256 bit e seconda passkey | |
| Trasporto | **TLS 1.3 sempre**; scambio ibrido `X25519MLKEM768` quando entrambi i lati lo supportano | il contenuto resta protetto anche se il trasporto fosse intercettato, perche' e' gia' cifrato end-to-end |

## 3. Gerarchia delle chiavi (zero-knowledge)
- Il **browser** genera la coppia di chiavi dell'archivio (X-Wing). La chiave privata non lascia mai il browser in chiaro.
- La chiave privata viene cifrata ("wrapped") con una **KEK** derivata da: (a) l'uscita **PRF** della passkey WebAuthn (dove disponibile:
  Chrome/Edge >= 128, Firefox recente, Safari 18+ con passkey di piattaforma; non su NFC) e/o (b) una **passphrase** con Argon2id; piu' (c) un
  **codice di recupero** da 256 bit mostrato una sola volta. Il blob cifrato sta sul server, che non puo' aprirlo.
- Al pairing la webapp mostra un **QR** con: chiave pubblica dell'archivio, impronta della chiave TLS del server, URL e token di pairing monouso. Il
  telefono tiene **solo la chiave pubblica** + la propria chiave di firma nel Keystore. Un telefono rubato non puo' leggere l'archivio.
- Ogni blocco inviato = `HPKE(chiave pubblica archivio, DEK casuale AES-256)` + `AES-256-GCM(DEK, dati)`; i metadati minimi (tipo, giorno) restano in
  chiaro per l'indicizzazione, oppure si cifrano anche quelli se l'indice si costruisce nel browser (da decidere: costa piu' trasferimento).
- Nel browser: caricamento dei blocchi, decifratura con WebCrypto + noble, grafici (uPlot), **CSV generato nel browser**, report settimanale calcolato
  nel browser dai riepiloghi giornalieri.

## 4. Backup e ripristino (sul telefono)
File unico versionato: contenuto = database + impostazioni (profilo, soglie), **senza** le chiavi del Keystore (ci si riassocia con un nuovo QR).
Cifratura: **Argon2id + AES-256-GCM** con passphrase scelta dall'utente (simmetrica, quindi gia' sicura contro i quantistici). Formato semplice e
documentato, con un piccolo strumento Python/JS di verifica che lo decifra senza l'app.

## 5. Protocollo di sincronizzazione (idempotente, con watermark)
1. `GET /v1/state` (autenticato con firma del dispositivo) -> per ogni tipo di dato, l'ultimo timestamp che il server ha; per gli incidenti gli id con `updatedAt`.
2. L'app invia, in blocchi giornalieri **riprendibili**, tutto cio' che e' successivo ai watermark. Chiavi `(tipo, ts)`: reinvio = nessun duplicato.
3. Il server risponde con l'esito per blocco; solo allora l'app avanza il proprio watermark locale.
4. **Primo collegamento dopo un mese di uso locale** = watermark vuoti = si invia tutto, a blocchi. Stesso percorso per un ripristino.
5. **Potatura**: dopo 6 mesi si cancellano i campioni grezzi, solo se confermati dal server. Riepiloghi per notte e per giorno restano per sempre (servono alle baseline).
6. Modifiche e cancellazioni (incidenti, sonno) viaggiano con `updatedAt` e *tombstone*.

## 6. Esposizione e TLS (container Proxmox -> NPM -> Cloudflare)
- **La webapp termina sempre TLS** (nessuna modalita' HTTP): se sono impostati `TLS_CERT`/`TLS_KEY` usa quelli, altrimenti **genera un
  certificato autofirmato** (ECDSA P-256) al primo avvio, lo conserva e ne stampa l'impronta. NPM deve parlare TLS verso di lei (end-to-end).
- **Cloudflare in mezzo:** il certificato client del telefono (mTLS) **non** arriva all'origine, quindi l'autenticazione del dispositivo
  e' a livello applicativo (firme sulle richieste: metodo, percorso, hash del corpo, timestamp, nonce). Cloudflare vede solo cifrato.
- Il *pinning* della chiave TLS dell'origine non e' possibile attraverso Cloudflare (termina lui); lo si usa solo in modalita' diretta/VPN.
  Con Cloudflare, la protezione e' la cifratura end-to-end + le firme, non il canale.
- Difese: rate-limit e blocco dopo tentativi errati, intestazioni HSTS/CSP rigorose, cookie `__Host-`, nessuna dipendenza esterna a runtime, log
  senza dati sensibili, aggiornamenti dei pacchetti, backup del database del server (sono gia' blocchi cifrati).
- Server: **Go** (binario unico, TLS e WebAuthn robusti) oppure Python (FastAPI); SQLite per i blocchi e le chiavi avvolte. Da decidere.

## 7. Limiti dichiarati
- X-Wing e HPKE-PQ sono ancora bozze IETF; le librerie vanno fissate per versione e riverificate prima di ogni release.
- PRF di WebAuthn e' disomogeneo tra browser e autenticatori: per questo la passphrase e il codice di recupero sono sempre previsti.
- Zero-knowledge significa che se perdi passkey, passphrase **e** codice di recupero i dati archiviati sono persi (il telefono ne tiene comunque 6 mesi).
- Cloudflare vede i metadati di rete e i blocchi cifrati, non i contenuti.

## 8. Ordine di lavoro proposto
1. Schema dati v9: riepiloghi per notte/giorno, watermark di sync, sequenza ordinata delle fasi del sonno **+ test delle migrazioni** (prerequisito).
2. Backup/ripristino cifrato (autonomo, utile subito).
3. Server (blob store + WebAuthn + pairing) e client di sync con firme dispositivo.
4. Webapp: decifratura, grafici, CSV, report settimanale.
5. Flavor `offline` / `sync` e CI per entrambi.
