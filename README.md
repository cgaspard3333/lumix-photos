# Lumix Photos

Application Android pour récupérer les photos et vidéos d'un appareil Panasonic Lumix en Wi-Fi,
sans passer par l'application Panasonic. Développée et testée avec un **DMC-FZ1000** (firmware 2.60)
et un téléphone sous Android 14.

## Installation

Téléchargez l'APK de la dernière version dans les
[releases](https://github.com/cgaspard3333/lumix-photos/releases), puis ouvrez-le sur le téléphone
(Android 10 ou plus récent).

## Fonctionnalités

- **Connexion par QR code** : scanner le QR code affiché par l'appareil suffit. Le téléphone
  rejoint ensuite seul le Wi-Fi de l'appareil, et le rejoint de nouveau après une coupure.
- **Galerie** : grille regroupée par jour (2 à 8 colonnes, pincer pour zoomer), vue détails
  (nom, date, taille, type, 4K/1080p), défilement rapide par mois, filtre photos/vidéos/non téléchargés.
- **Plein écran** : toucher une vignette (zoom en pinçant ou par double appui, bouton « Ouvrir »
  pour une copie déjà sur le téléphone).
- **Sélection** : bouton « Sélectionner » ou appui long sur une vignette, puis glisser pour une
  série ; aussi par jour, par type ou « pas encore téléchargés ».
- **Téléchargement** en arrière-plan avec notification, par morceaux de 8 Mo (les vignettes
  restent fluides pendant une vidéo 4K), reprise automatique après une coupure Wi-Fi, photos en
  taille originale ou réduite (1620×1080, environ 8× plus rapide). Les fichiers déjà présents sur
  le téléphone sont ignorés, et l'espace libre est vérifié avant de commencer.
- **Plusieurs cartes SD** : chaque carte est reconnue à la connexion, avec son propre cache des
  dates et tailles.
- Les fichiers sont rangés dans `DCIM/Lumix/Image` et `DCIM/Lumix/Video`, avec la bonne date de
  prise de vue.

## Ce que l'appli fait (et ne fait pas) sur l'appareil

Elle ne fait **que lire** la carte SD : aucune suppression ni modification des fichiers de l'appareil.

## Particularités des Lumix découvertes en route

- L'appareil se fige s'il reçoit **deux requêtes HTTP simultanées** : toutes les requêtes sont faites
  une par une. Paralléliser n'apporterait rien de toute façon, le débit étant plafonné par
  l'appareil à environ 2 Mo/s.
- La liste DLNA (port 60606) ne donne ni date ni taille. Une requête `HEAD` sur chaque fichier
  (port 50001) les fournit dans des en-têtes propres à Panasonic : `X-REC_DATE_TIME`,
  `X-FILE_SIZE`, `X-ROTATE_INFO`.
- Les vidéos sont annoncées en `application/octet-stream`, et leur en-tête MP4 contient l'heure
  locale notée comme de l'UTC : sans correction, elles apparaissent décalées de 1 ou 2 h dans les
  galeries. L'appli corrige la date dans la copie.
- L'EXIF des photos ne précise pas le fuseau horaire : Android ne les date donc pas. L'appli
  complète l'EXIF de la copie (fuseau, et orientation pour les copies réduites).
- Le QR code affiché par l'appareil contient le nom et le mot de passe du Wi-Fi en clair
  (`SSID: …` / `PW: …`).

## Compilation

JDK 17 ou plus récent, et SDK Android 36 :

```sh
./gradlew testDebugUnitTest assembleRelease
```

L'APK est produit dans `app/build/outputs/apk/release/`. Sans clé de signature configurée, il est
signé avec la clé de débogage, pour pouvoir l'installer directement (`adb install`).

Pour signer avec la clé de publication, ajouter dans `~/.gradle/gradle.properties` (hors du dépôt) :
`LUMIX_KEYSTORE`, `LUMIX_KEYSTORE_PASSWORD` et `LUMIX_KEY_ALIAS`.

GitHub Actions lance les tests et construit l'APK à chaque modification ; un tag `v*` publie une
release. La signature utilise les secrets `LUMIX_KEYSTORE_BASE64` et `LUMIX_KEYSTORE_PASSWORD`.
