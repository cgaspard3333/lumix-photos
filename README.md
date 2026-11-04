# Lumix Photos

Application Android pour récupérer les photos et vidéos d'un appareil Panasonic Lumix en Wi-Fi,
sans passer par l'application Panasonic. Développée et testée avec un **DMC-FZ1000** (firmware 2.60)
et un téléphone sous Android 14.

## Fonctionnalités

- **Connexion par QR code** : scanner le QR code affiché par l'appareil suffit. Le téléphone
  rejoint ensuite seul le Wi-Fi de l'appareil, et le rejoint de nouveau après une coupure.
- **Galerie** : grille regroupée par jour (2 à 8 colonnes, pincer pour zoomer), vue détails
  (nom, date, taille, type, 4K/1080p), défilement rapide par mois, filtre photos/vidéos/non téléchargés.
- **Sélection** : par jour, par type, « pas encore téléchargés », ou appui long puis glisser.
- **Plein écran** : appui long sur une vignette.
- **Téléchargement** en arrière-plan avec notification, reprise automatique après une coupure
  Wi-Fi, photos en taille originale ou réduite (1620×1080, environ 8× plus rapide).
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
./gradlew assembleRelease
```

L'APK est produit dans `app/build/outputs/apk/release/`. Il est signé avec la clé de débogage,
pour pouvoir l'installer directement (`adb install`).
