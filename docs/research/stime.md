# Ricerca sulle stime (fase B2)

Data: 2026-11-10. Ricerca web su paper e fonti prima di implementare. Per ogni stima: metodo scelto, evidenza, limiti, e come la
mostriamo nell'app. Regola generale: ogni numero stimato porta l'etichetta "stima" e, dove l'evidenza e' debole, "indicativo".
Non e' un dispositivo medico.

Segnali disponibili dall'anello: HR ogni 5 min, HRV e stress ogni 30 min, SpO2 oraria (min/max), temperatura cutanea, fasi del sonno, batteria; **RR battito per battito solo durante le misure live** (non in continuo).

## 0. Profilo utente (prerequisito)
Eta', sesso, peso, altezza, HR max opzionale, scritti nelle Impostazioni, solo in locale (SharedPreferences, mai nel DB di export
senza consenso). HR max se assente: Tanaka, 208 - 0,7 x eta' (meta-analisi di 351 studi, 18.712 soggetti, nessuna differenza fra
sessi; errore standard circa 10-11 bpm sul singolo individuo). Fonte: [Tanaka 2001, JACC](https://pubmed.ncbi.nlm.nih.gov/11153730/).

## 1. Carico di allenamento (strain) - TRIMP e rapporto acuto/cronico
- TRIMP di Banister: durata x frazione di riserva HR x peso esponenziale. Frazione = (HR - HR riposo)/(HR max - HR riposo).
  Peso: uomo 0,64 e^(1,92 x frazione), donna 0,86 e^(1,67 x frazione) (le fonti divergono sulla costante femminile: alcune usano 0,64;
  da verificare sul paper originale prima di fissarla). Il peso esponenziale evita di sovrastimare le sedute lunghe e leggere.
- Limiti: la HR e' un indicatore imperfetto dell'intensita' (deriva cardiaca, caldo, emozioni). Con campioni ogni 5 minuti la
  risoluzione e' grossolana: accettabile per un carico giornaliero, non per sedute brevi.
- Rapporto acuto:cronico (7 gg / 28 gg): **contestato** (finestre arbitrarie, correlazioni spurie perche' la finestra acuta e'
  dentro la cronica). Decisione: mostrare "carico recente rispetto al tuo solito" come tendenza, **non** come predittore di infortunio,
  e dirlo nel testo. Fonti: [Consensus statement monitoraggio carichi, IJSPP](https://journals.humankinetics.com/view/journals/ijspp/12/s2/article-pS2-161.pdf),
  [Editorial ACWR](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8138569/).

## 2. Calorie totali e attive
- Basale: Mifflin-St Jeor (10 x kg + 6,25 x cm - 5 x eta' + 5 uomo; - 161 donna). E' l'equazione piu' affidabile nella revisione
  sistematica di Frankenfield (entro il 10% del misurato in piu' persone di qualunque altra), con cautela negli anziani e nelle
  minoranze, poco rappresentati. [Frankenfield 2005](https://pubmed.ncbi.nlm.nih.gov/26210376/) (coefficienti da verificare su Am J Clin Nutr 1990;51:241-7).
- Da HR: Keytel 2005 (J Sports Sci), uomo -55,0969 + 0,6309 HR + 0,1988 kg + 0,2017 eta'; donna -20,4022 + 0,4472 HR - 0,1263 kg + 0,074 eta'
  (kJ/min, da dividere per 4,184). Correlazione 0,913 sul campione originale, ma **studi indipendenti trovano errori maggiori**,
  specie nella corsa; nessuna validazione su dispositivi da polso/anello trovata.
- Decisione: Keytel solo sopra una soglia di HR (es. 60% HR max), sotto si usa il basale (approccio ibrido usato anche da altri
  dispositivi); confronto con le calorie dell'anello e mostrare entrambe, l'incertezza e' alta.

## 3. "Batteria corporea" 0-100
Concetto di Garmin/Firstbeat; **l'algoritmo non e' pubblico**. Documento primario: white paper Firstbeat "Stress and Recovery Analysis
Method Based on 24-hour HRV" (stress = attivazione fisiologica, recupero = dominanza parasimpatica, durante il sonno HRV alta e HR
bassa). Decisione: modello nostro, trasparente e spiegato, non una copia: livello che scende con stress e attivita' (strain) e sale
con riposo/sonno, ancorato alla baseline personale di ln RMSSD (Plews/Laursen: medie su 7 giorni di ln RMSSD piu' affidabili del
singolo giorno, minimo 3 misure valide a settimana). Va dichiarato come "stima euristica".

## 4. Sonno esteso
- Raccomandazione AASM/SRS 2015: adulti almeno 7 ore, senza limite superiore netto. Il debito si recupera lentamente (fonti divulgative:
  fino a 4 giorni per 1 ora persa); non e' chiaro se dormire di piu' nel weekend compensi davvero.
- Pisolini: la HR cala e l'HRV sale all'addormentamento; la sola attivita' sbaglia le fasi di veglia quieta. Metodo con HR normalizzata
  sulla distribuzione giornaliera della persona (Sci Rep 2022). Con HR ogni 5 min: pisolino = finestra >= 20 min con HR sotto la
  tua soglia personale e variabilita' bassa. Stima, con falsi positivi (lettura da fermo).
- Fabbisogno personale: non c'e' un metodo validato dai soli dati dell'anello; mostrare il debito rispetto a un obiettivo scelto
  dall'utente (default 8 h) e la relazione notte-lunga -> HRV del giorno dopo come correlazione, non come "fabbisogno".

## 5. Autonomia batteria
Regressione lineare robusta sul livello di batteria nel log, escludendo i tratti in carica. Nessuna letteratura necessaria.

## 6. Anello non indossato
Buchi con HR = 0 / nessun dato; da solo non distingue il non indossato dal sensore non agganciato. Per polsi/fianchi temperatura +
accelerazione danno 99%/97% ([Sensors 2022](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8838557/)); per un anello con PPG non abbiamo trovato
validazioni, solo brevetti. Decisione: regola prudente (HR 0 per piu' slot consecutivi e temperatura cutanea vicina all'ambiente se
disponibile), usata solo per escludere dati da medie e alert.

## 7. Inattivita' (ANNULLATA: i passi dell'anello non sono affidabili e sono stati tolti dall'app)
Le linee guida dicono di interrompere il tempo seduto ma **senza soglie numeriche supportate**; una recensione BJSM 2018 sostiene che
l'evidenza non regge numeri precisi. Un crossover (Duran 2024) trova benefici con 1-5 min di camminata ogni 30-60 min. Decisione:
promemoria configurabile (default dopo 60 min senza passi in orario di veglia), presentato come abitudine, non come soglia clinica.

## 8. Correlazioni con gli incidenti
Confronto per categoria (caffe', alcol, esercizio, malessere...) su HRV, HR a riposo, sonno nelle 24 h successive, con differenza e
numero di casi. Con pochi casi niente conclusioni: mostrare "dati insufficienti" sotto una soglia (es. < 5 eventi per categoria).

## 9. Recupero HR dopo sforzo
Cole 1999 (NEJM): calo nel primo minuto <= 12 bpm dopo test al tappeto = predittore indipendente di mortalita' (rischio relativo
aggiustato 2,0). Con campioni ogni 5 minuti non si misura il primo minuto. Decisione: "recupero a 5 minuti dal picco" come
indicatore grossolano e personale (tendenza), senza soglia clinica di Cole. Si puo' migliorare con una misura live dopo l'allenamento.

## 10. Riepilogo mattutino/serale
Nessuna ricerca: aggregazione di quanto sopra.

## 11. VO2max (cos'e')
Massimo volume di ossigeno che il corpo usa in un minuto per kg (ml/kg/min): il miglior indicatore di forma cardiorespiratoria.
- Uth 2004: VO2max = 15,3 x HRmax / HRriposo. Su 46 uomini ben allenati errore standard 4,5% con HRmax **misurata**, **7,8%** con
  HRmax da eta'. In adulti medio-anziani l'accordo e' scarso (rc <= 0,40) e il fattore cambia con la popolazione (circa 12 in uomini
  di 50 anni sani). La stima si riduce a una funzione di eta' e HR a riposo: nessuna misura di ossigeno.
  [Uth 2004](https://pubmed.ncbi.nlm.nih.gov/14624296/), [adulti medio-anziani](https://www.researchgate.net/publication/354447939_Efficacy_of_estimating_VO2max_with_the_Heart_Rate_Ratio_Method_in_middle-aged_and_older_adults).
- Decisione: mostrare un intervallo (valore +/- errore) con etichetta "indicativo", utile come **tendenza** nel tempo (HR a riposo che
  scende = forma che migliora), non come numero assoluto. 

## 12. Notti sospette per apnea
Gli anelli con SpO2 continua (Circul) con ODI 3% distinguono AHI >= 5 con sens. 87,8% / spec. 93,8% (piccoli studi). Il numero di
eventi dipende dalla frequenza di campionamento: **con SpO2 oraria** (min/max per ora) il campionamento e' troppo grezzo per un ODI vero.
Decisione: solo indicatore "notte con cali ripetuti sotto il tuo normale" (min nottura vs baseline personale e conteggio di ore sotto
94%), mai un indice AHI/ODI diagnostico. Se in futuro l'R09 darà SpO2 piu' fitta durante il sonno, rifare la valutazione.

## 13. Fase del ciclo dalla temperatura (opzionale)
Oura (Maijala 2019, 22 donne): ovulazione rilevata con sensibilita' 83,3% nella finestra da -3 a +2 giorni usando la temperatura
cutanea notturna del dito. Rassegne recenti: il minimo puo' cadere >5 giorni prima dell'ovulazione, confronti con temperatura
centrale limitati. Decisione: funzione opzionale e disattivata di default, solo "fase probabile" con intervallo; mai uso contraccettivo.

## 14. Irregolarita' del ritmo (fibrillazione atriale) - screening, non diagnosi
- Metodi consolidati su sequenze RR: Tateno-Glass (istogrammi di delta-RR, Kolmogorov-Smirnov), e per PPG da polso/dito RMSSD + entropia
  campionaria (Lee 2013 IEEE TBME su segmenti di 64 battiti, Bashar 2019 Sci Rep su finestre di 30 s), sensibilita'/specificita'
  riportate fra 0,95 e 0,99 su dataset di riferimento.
- Apple Heart Study: valore predittivo positivo di una notifica 0,84 sull'ECG simultaneo, ma solo il 34% dei notificati aveva FA
  nel patch ECG di controllo; 0,52% dei partecipanti notificato. Il PPV dipende dalla prevalenza: **gli avvisi sono molti falsi
  positivi se si guarda a lungo**.
- **Vincolo specifico**: abbiamo gli RR solo durante una misura live (~1-3 minuti da fermi). Lo screening e' quindi un "controllo del ritmo"
  a richiesta (e, se l'utente vuole, programmato di notte/a riposo), non un monitor continuo.
- Piano: controllo di 2 minuti a riposo, pulizia artefatti, caratteristiche normalizzate (RMSSD/RR medio, entropia campionaria,
  frazione di punti di svolta), soglie **calibrate su PhysioNet (MIT-BIH Atrial Fibrillation DB, scaricabile)** piu' i tuoi ritmi sinusali
  reali, e risultato a tre livelli (regolare / irregolare, riprova / irregolare ripetuto: sentire un medico). Extrasistoli e
  movimento danno falsi positivi: ripetere prima di avvisare. Testo sempre: "non e' una diagnosi; se hai palpitazioni o sintomi, ECG".
- **Vincolo scoperto sull'anello (2026-11-11, log per pacchetto)**: lo stream real-time dell'R09 manda un pacchetto ogni ~0,5 s, il primo RR
  arriva a ~13,8 s, e l'anello **chiude da solo lo stream a ~30 s** senza errori (17-20 battiti utili per sessione). Riavviando lo stream il
  tempo di aggancio si ripete (~14 s ogni volta). Quindi non esistono 64 battiti contigui: un controllo = 6 sessioni da ~30 s (circa 3 minuti)
  e ogni sessione e' un segmento da 18 battiti.
- **Algoritmo adottato**: segmento segnalato se nRMSSD (RMSSD/RR medio) > 0,21 e piu' della meta' dei battiti sono punti di svolta (TPR > 0,5);
  controllo irregolare se almeno 4 segmenti (e 2/3 di quelli utili) sono segnalati. Soglie scelte su meta' delle registrazioni del MIT-BIH
  Atrial Fibrillation Database (PhysioNet, ODC-By; 23 registrazioni, 16.366 finestre da 64 battiti da cui 49.098 finestre da 18), valutate sull'altra
  meta'. Su 6 segmenti contigui: con "almeno 4 su 6" sensibilita' 67-73% e specificita' 93-97% (piu' rumore di temporizzazione, specificita'
  piu' bassa); con "almeno 3 su 6" 81-83% e 89-94%. E' un risultato su pazienti con FA (caso difficile: i tratti "normali" contengono molta
  ectopia), quindi la specificita' su persone sane e' probabilmente migliore. Una variante con 128 battiti contigui (RMSSD + entropia campionaria)
  dava 87% / 99,6% ma non e' realizzabile con questo anello.
- **Prova reale sul tuo anello (2026-11-11)**: 6 segmenti da 17-21 battiti, nRMSSD 0,04-0,06, TPR 0,62-0,75, nessun segmento segnalato -> "polso
  regolare". Il ritmo sinusale a riposo non viene scambiato per irregolare. Resta da verificare con un ritmo irregolare reale.
- Il test Kotlin riproduce i valori di riferimento di Python e usa 20 estratti casuali (10 FA, 10 sinusali) divisi in segmenti da 18 battiti:
  nessun sinusale irregolare, 7 FA su 10 irregolari.
- Rischio: non possiamo validare la sensibilita' su FA reale con questo anello, solo l'algoritmo su dataset pubblici. Va detto.

## 15. Idratazione: quanto e' stimabile?
Conclusione della ricerca: **non in modo affidabile dai soli dati di questo anello**.
- Evidenza che esiste: disidratazione lieve (circa 2% del peso) associata a HR piu' alta e HRV piu' bassa in piccoli studi di
  laboratorio (7-17 persone per gruppo, per lo piu' dopo esercizio al caldo). Bere acqua puo' anche cambiare l'HRV senza disidratazione
  (RMSSD +13 ms dopo 500 ml in due trial). La variabilita' giorno-per-giorno dell'RMSSD e' alta (ICC 0,75, CV 30%).
- L'unico metodo con numeri: risposta HR agli spostamenti posturali (Kamran 2021, *Sensors*, 20 adulti attivi, dehydration indotta in
  serra, fascia toracica Polar H10): AUROC 0,89 con alzata da supino 2 min o tocco punte 30 s, 0,79 su tutti i movimenti; sensibilita'
  e specificita' non riportate, non validato sul campo, due partecipanti a livello del caso.
- Confondenti: alcol, caffe', malessere, stress, temperatura ambientale, sonno spostano HR e HRV allo stesso modo.
- Decisione: **nessun "livello di idratazione" nell'app**. Possibile, piu' avanti e con etichetta "sperimentale", un test ortostatico
  guidato (da sdraiato ad in piedi con misura live HR) confrontato con la tua baseline: indicativo, non un valore di idratazione.
  Un suggerimento tipo "HR a riposo piu' alta e HRV piu' bassa del solito: puo' dipendere da poca acqua, alcol, stress o malessere"
  e' gia' coperto dall'allarme malessere, senza attribuirlo all'acqua.

## Pressione arteriosa (esclusa)
PPG senza calibrazione non e' affidabile: nessun numero.

## Prossimi passi tecnici
1. Profilo utente nelle Impostazioni (en/it) e modello dominio.
2. Dominio in TDD: HR max (Tanaka), zone, TRIMP, calorie (Mifflin + Keytel ibrido), VO2max (Uth con intervallo), HRR a 5 min,
   pisolini, batteria corporea, autonomia, non indossato, inattivita', apnea (indicatore), ciclo, correlazioni.
3. Scaricare MIT-BIH AF DB (PhysioNet) per calibrare/verificare il controllo del ritmo prima di esporlo.
4. UI: schede nelle schermate esistenti, con etichette "stima"/"indicativo" e spiegazione breve di ogni numero.
