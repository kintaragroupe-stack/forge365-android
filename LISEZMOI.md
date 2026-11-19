# Application Android FORGE 365

- Tout le site forge365.space dans une WebView (le membre se connecte lui-même sur /connexion/).
- L'appli s'annonce avec « FORGE365App » dans son user-agent et donne aux pages le pont `window.F365App`.
- Les 4 salles visio du site (Espace 2.4.0) appellent `F365App.ouvrirVisio(json)` : la réunion s'ouvre dans le
  SDK Jitsi Meet natif (13.3.3) sur visio.forge365.space, avec partage d'écran du téléphone.
- À la fin de la réunion native, la page reçoit l'événement `f365e-app-fin`.

Fabrication : GitHub Actions (`.github/workflows/apk.yml`) à chaque envoi sur `main` ; l'APK est déposé dans « Releases ».
Version Play Store (plus tard) : `gradle bundleRelease` avec la clé fournie par les secrets F365_KEYSTORE, F365_KEYSTORE_MDP,
F365_CLE_ALIAS, F365_CLE_MDP.
