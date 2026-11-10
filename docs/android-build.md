# Build Android su questa macchina

Il loopback arriva ai server con indirizzo sorgente `192.168.1.49` (SNAT del gruppo di rete usato dal sandbox), quindi il daemon Gradle
e i worker di test rifiutano le connessioni ("Cannot accept connection from remote address"). Soluzioni adottate:

- `android/gw.sh`: Gradle **senza daemon e in-process**. Richiede `GRADLE_OPTS` identico a `org.gradle.jvmargs`
  (altrimenti Gradle forka un daemon) e il `gradle` di sistema (il wrapper non applica il javaagent -> fork).
- `kotlin.compiler.execution.strategy=in-process` in `gradle.properties`.
- Test: `./gw.sh :protocol:runTests` (JavaExec con JUnit Console) invece di `:protocol:test` (worker TCP).
- Quality gate Kotlin: `./check.sh` (ktlint CLI + detekt + test + build). `./gw.sh ktlintFormat` corregge lo stile. Config in `android/config/detekt.yml` e `.editorconfig`; il plugin ktlint per Gradle non si usa perche' richiede i worker daemon (loopback).
- Build/installazione: `./gw.sh :app:assembleDebug`, poi `adb -s <ip:porta> install -r app/build/outputs/apk/debug/app-debug.apk`.
- compileSdk 37 (richiesto dalle librerie), targetSdk 36, minSdk 31. Pixel 9a (Android 17).

## Velocizzare la build
- `gradle.properties`: configuration cache + build cache (la build a vuoto scende da ~70 s a ~19 s).
- `gw.sh` usa `--offline`; per scaricare plugin/dipendenze nuovi: `GW_OFFLINE= ./gw.sh ...` (una volta).
- Room/KSP vive nel modulo `:data`: una modifica alla sola UI non rifa' KSP (~12 s). Tempi tipici dopo un cambio in `ui/`:
  compileKotlin ~29 s, dex ~12 s, totale ~60 s.
- 4 core lasciati liberi (`taskset -c 0-11`, `nice 10`).
