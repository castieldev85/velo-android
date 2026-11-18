# Velo

Velo is a hardened XMPP client for Android, licensed under the GNU AGPLv3 (see LICENSE).
Copyright notices of the projects it derives from are in the app, under About.

## Compilare Velo dal sorgente / Building Velo from source

Sorgente completo della Velo Android (licenza GNU AGPLv3), compresi il fork di libsignal
(`deps/libsignal`) e il ponte JNI della traduzione offline (`native/velotranslate`).
Dominio, indirizzo .onion e impronte del certificato del server non sono nel codice: si leggono da
`../velo-local.properties` (`velo.login.domain`, `velo.onion.host`, `velo.server.pins`).
- Velo pubblica (quella negli store): `./gradlew assembleVeloFreeRelease -PveloPublic`
  (identificativo `eu.velochat.velo`, nessun server incorporato: ci si collega con un invito).
- Velo con un server incorporato: compila il file `velo-local.properties` e togli `-PveloPublic`.
La firma di release usa le variabili d'ambiente `VELO_KEYSTORE` e `VELO_KEYSTORE_PASSWORD`.

Complete source of Velo for Android (GNU AGPLv3). The server's domain, .onion address and
certificate pins are not in the code; they are read from `../velo-local.properties`.
Store build: `./gradlew assembleVeloFreeRelease -PveloPublic` (application id `eu.velochat.velo`,
no built-in server, joins servers by invite).
