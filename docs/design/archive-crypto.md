# Archivio, webapp zero-knowledge e cifratura (progetto, 2026-11-11)

Stato: **progetto approvato nelle linee generali (2026-11-11), nessun codice ancora; decisione: niente client web che decifra, si implementa subito il decifratore nativo (`ring0-viewer`, in Go)**; aggiornato dopo la richiesta di difendersi anche da un server compromesso e di avere almeno la forward secrecy. Decisioni gia' prese dall'utente: l'app puo' avere il permesso INTERNET (build
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
| Scambio di chiavi / chiave pubblica dell'archivio | **X-Wing** (ML-KEM-768 + X25519, NIST livello 3) dentro **HPKE (RFC 9180)** | X-Wing e' ancora un draft CFRG; HPKE-PQ lo chiama `MLKEM768-X25519`. Librerie: Bouncy Castle 1.86 (Android) e `@noble/post-quantum` (`ml_kem768_x25519`, browser). Deciso: X-Wing e' sufficiente. (Alternativa non scelta: ibrido ML-KEM-1024 + P-384 della bozza HPKE-PQ, livello 5.) |
| Cifratura dei dati | **AES-256-GCM**, chiave per blocco derivata con HKDF-SHA-512 | nonce da 96 bit casuali; ogni blocco ha chiave e nonce propri |
| Derivazione da password | **Argon2id** (RFC 9106: >= 64 MiB, t >= 3, p = 4) | WASM nel browser, libreria nativa su Android |
| Autenticazione dispositivo | firma ECDSA P-256 con chiave nel **Keystore/StrongBox** (non esportabile) | le firme non hanno bisogno di protezione "harvest now, decrypt later"; le firme PQ (ML-DSA) non sono nel Keystore |
| Autenticazione utente web | **passkey WebAuthn**; recupero con codice da 256 bit e seconda passkey | |
| Trasporto | **TLS 1.3 sempre**; scambio ibrido `X25519MLKEM768` quando entrambi i lati lo supportano | il contenuto resta protetto anche se il trasporto fosse intercettato, perche' e' gia' cifrato end-to-end |

## 3. Modello di minaccia (si assume che il server possa essere compromesso)
| Avversario | Cosa puo' fare | Difesa |
|---|---|---|
| A1: ruba il disco/DB del server o i backup | legge tutti i blocchi e le chiavi avvolte | tutto cifrato end-to-end; la chiave privata e' avvolta da una KEK a **due fattori**, quindi niente attacco offline sulla sola passphrase |
| A2: **server compromesso attivo** (modifica risposte, **serve JavaScript malevolo**, scambia chiavi, cancella o ripropone dati vecchi) | e' l'avversario piu' pericoloso per un'app web zero-knowledge | **bundle web firmato e verificato** (3.2), **pairing con codice di confronto** (3.3), **manifesti firmati dal telefono** con contatore anti-rollback (3.4), metadati autenticati (AAD) |
| A3: rete, Cloudflare, intermediari | vede traffico e metadati | TLS 1.3 + cifratura end-to-end; blocchi di dimensioni fisse (padding) e identificatori opachi |
| A4: telefono rubato | legge il database locale | cifratura a riposo (Keystore) + il telefono ha solo la chiave pubblica dell'archivio |
| A5: browser o sessione compromessi | legge cio' che e' decifrato in quel momento | chiavi epoca (3.5): limita il danno alle epoche calde; sessioni brevi; niente chiavi in `localStorage` |
| A6: ruba i fattori dell'utente (passkey e passphrase e codice) | decifra tutto | non c'e' difesa crittografica; e' il limite dichiarato |

**Risposta onesta alla domanda "un server compromesso puo' leggere i dati?"**: se il server e' solo *letto* (A1), no. Se e' *attivo* (A2), puo' provare a
servire al browser codice malevolo che ruba le chiavi: e' il problema noto della cifratura end-to-end consegnata via web. Non si annulla del tutto, si
riduce con 3.2 (il codice del client non lo decide piu' il server) o evitando il web (client nativo, vedi 3.2).

### 3.1 Gerarchia delle chiavi
- Il **browser** genera l'identita' dell'archivio: coppia X-Wing (cifratura) e coppia di firma (Ed25519, in seguito anche ML-DSA). Le chiavi private non
  escono mai in chiaro.
- Le chiavi private sono avvolte da una **KEK a due fattori**: `KEK = HKDF-SHA-512(PRF_passkey || Argon2id(passphrase))`. Serve la passkey (PRF a 256 bit,
  non indovinabile) **e** la passphrase; il server non puo' fare tentativi offline. In piu' un **codice di recupero da 256 bit** (cartaceo) avvolge la
  stessa chiave come seconda via di accesso. Se il browser non supporta PRF, si usa la passphrase piu' un segreto locale esportabile (compromesso dichiarato).
- Al pairing il telefono riceve solo le **chiavi pubbliche** e conserva la propria chiave di firma nel Keystore. Un telefono rubato non legge l'archivio.
- Ogni blocco = `HPKE(chiave pubblica di epoca, DEK casuale AES-256)` + `AES-256-GCM(DEK, dati)` con AAD = (tipo, giorno, id, versione, epoca).

### 3.2 Contro il codice malevolo servito dal server (A2)
- Il client web e' un **bundle statico firmato**: un guscio minimo con service worker che **verifica la firma del manifesto** (Ed25519 e ML-DSA, chiave
  offline del maintainer) prima di eseguire una versione nuova; l'impronta della chiave e' pubblicata nelle release (attestate dalla CI). Il server consegna
  solo cifrato e il bundle; non puo' cambiare il codice senza la chiave di firma. Limite: fiducia al primo uso (si verifica l'impronta) e storage del browser.
- CSP rigorosa (`script-src` con hash, niente inline, Trusted Types), SRI, nessuna dipendenza esterna a runtime.
- **Deciso: decifratore nativo `ring0-viewer` (Go, binario firmato e attestato dalla CI).** Scarica solo blocchi cifrati, li decifra **in locale**, e
  mostra grafici, CSV e report settimanale con una UI web *incorporata nel binario* servita su `127.0.0.1` (porta e token casuali): il codice non arriva
  mai dal server e le chiavi restano nel processo Go, non nel JavaScript. Il server non ha nessuna interfaccia web con i dati. Il bundle web firmato e le
  passkey WebAuthn non servono piu'; l'utente si autentica con la chiave di firma del viewer (registrata al pairing, custodita dal keyring del sistema
  o da una chiave FIDO2 con hmac-secret per la KEK).

### 3.3 Pairing sicuro
Il QR contiene la chiave pubblica dell'archivio e l'URL. Il telefono invia la propria chiave di firma e **entrambi mostrano un codice di confronto breve
(SAS)** che l'utente verifica a vista: un server che sostituisce una chiave in mezzo produce codici diversi.

### 3.4 Integrita' e anti-rollback
Il telefono firma un **manifesto** per giorno (hash dei blocchi, numero di sequenza monotono, timestamp) e una catena di hash tra manifesti. Il browser
verifica le firme con le chiavi del pairing: un server non puo' falsificare, cancellare di nascosto o riproporre stati vecchi (puo' solo rifiutare il servizio).

### 3.5 Forward secrecy (richiesta: "almeno la PFS")
- **Trasporto:** TLS 1.3 con scambio effimero (e ibrido X25519MLKEM768 dove disponibile): la PFS del canale c'e' per costruzione.
- **Per messaggio:** HPKE usa gia' una chiave effimera del mittente per ogni blocco, ma questo non basta: se la chiave privata del destinatario viene rubata
  si aprono tutti i blocchi passati. Per un archivio che si vuole poter rileggere per anni, la PFS "classica" (chiavi cancellate dopo l'uso) e' in
  conflitto con lo scopo. La soluzione realistica:
- **Chiavi di epoca con radice fredda.** Una **radice fredda** (256 bit, solo nel codice di recupero cartaceo / chiave hardware, mai sul server ne' online)
  genera con HKDF una catena a senso unico: `radice_n+1 = HKDF(radice_n)` e da ogni radice la coppia X-Wing dell'epoca n (per esempio un mese). Il browser
  pubblica le **chiavi pubbliche delle epoche future**, firmate con la chiave di firma dell'archivio (il telefono le verifica: il server non puo' sostituirle).
  Online restano solo le chiavi delle **ultime K epoche calde** (K configurabile, per esempio 3); le epoche piu' vecchie si aprono **solo con la radice fredda**.
  Un compromesso del lato online (server piu' browser) non apre quindi i dati piu' vecchi di K epoche; per leggerli serve l'originale offline.
  Dopo un compromesso si ruota: nuova radice, nuove epoche (sicurezza post-compromesso), e le epoche vecchie restano protette dalla radice fredda.
- Costo dichiarato: per la storia piu' vecchia dell'ultimo periodo serve il codice offline. Si imposta K e la scelta e' dell'utente. Sul telefono i dati restano 6 mesi.
- Va revisionata con attenzione: sono primitive standard (HKDF, X-Wing, HPKE) in uno schema simile ai key schedule di TLS/Signal, ma lo schema e' nostro e andra'
  descritto in un documento di sicurezza e rivisto prima del rilascio.

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
- Server: **Go** (deciso): binario unico, TLS e WebAuthn robusti; SQLite per blocchi e chiavi avvolte.

## 7. Limiti dichiarati
- X-Wing e HPKE-PQ sono ancora bozze IETF; le librerie vanno fissate per versione e riverificate prima di ogni release.
- PRF di WebAuthn e' disomogeneo tra browser e autenticatori: per questo la passphrase e il codice di recupero sono sempre previsti.
- Zero-knowledge significa che se perdi passkey, passphrase **e** codice di recupero i dati archiviati sono persi (il telefono ne tiene comunque 6 mesi).
- Cloudflare vede i metadati di rete e i blocchi cifrati, non i contenuti.

## 8. Ordine di lavoro (aggiornato)
1. Schema dati v9: riepiloghi per notte/giorno, watermark di sync, sequenza ordinata delle fasi del sonno, **esportazione dello schema e test delle migrazioni** (prerequisito).
2. Backup/ripristino cifrato (autonomo, utile subito; Argon2id + AES-256-GCM).
3. Libreria crittografica condivisa e documentata (X-Wing/HPKE, AES-256-GCM, HKDF-SHA-512, firme) con **vettori di test ufficiali**; documento di sicurezza.
4. Server Go: blob store, WebAuthn, pairing con SAS, manifesti firmati, TLS obbligatorio (certificato dato o autofirmato).
5. Client di sync nell'app (flavor `sync`) con firme del dispositivo e chiavi di epoca.
6. Client web firmato (decifratura, grafici, CSV, report settimanale) e, dopo, il decifratore nativo.
7. Flavor `offline` / `sync` e CI per entrambi; revisione di sicurezza dello schema delle epoche prima del rilascio.
