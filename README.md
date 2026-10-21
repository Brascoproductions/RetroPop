# RetroPop

Application Android TV de rétro-gaming pensée pour le **Freebox Pop Player** (fonctionne sur toute box Android TV ARM).
Première version : **NES** et **Super Nintendo**.

- Interface TV navigable à la télécommande ou à la manette
- Envoi des jeux **depuis le navigateur d'un téléphone ou d'un PC**, sans câble ni clé USB
- Sauvegardes « en jeu » automatiques + sauvegarde rapide depuis le menu pause
- Manettes Bluetooth/USB reconnues automatiquement, jusqu'à 4 joueurs

> Les jeux ne sont pas fournis. Utilise des sauvegardes de tes propres cartouches ou des jeux libres (homebrews).

---

## 1. Obtenir l'APK (sans rien installer sur ton PC)

1. Crée un dépôt GitHub (privé si tu veux) et pousse ce dossier dedans.
2. GitHub compile automatiquement l'application (onglet **Actions**, ~5 min).
3. Récupère `RetroPop.apk` :
   - dans **Actions → dernier build → Artifacts**, ou
   - en poussant un tag (`git tag v0.1.0 && git push --tags`) : l'APK apparaît dans **Releases**, avec un lien direct pratique pour l'étape suivante.

En local, avec Android Studio : ouvre le dossier puis *Build → Build APK(s)*, ou `./gradlew assembleRelease`.

## 2. Installer sur le Freebox Pop Player

La méthode la plus simple, sans câble :

1. Sur la box, installe **Downloader** (Play Store).
2. *Paramètres → Préférences de l'appareil → Sécurité et restrictions → Sources inconnues* : autorise **Downloader**.
3. Dans Downloader, tape l'URL de l'APK (lien de la release GitHub) → Installer.

Alternative par le réseau (ADB) : active le *Débogage ADB* dans les options développeur de la box, puis depuis un PC :

```bash
adb connect <ip-de-la-box>:5555
adb install -r RetroPop.apk
```

Les mises à jour s'installent par-dessus l'ancienne version : les jeux et sauvegardes sont conservés (la clé de signature est fixe, dans `keystore/`).

## 3. Ajouter des jeux

Lance RetroPop : l'écran d'accueil affiche une adresse du type `http://192.168.1.42:8080`.
Ouvre-la sur un téléphone ou un PC connecté à la même Freebox, puis envoie tes fichiers.

| Console        | Extensions acceptées          |
|----------------|-------------------------------|
| NES            | `.nes` `.unf` `.unif`         |
| Super Nintendo | `.sfc` `.smc` `.fig` `.swc`   |
| (toutes)       | `.zip` (décompressé à l'envoi)|

La même page permet de supprimer un jeu (et ses sauvegardes).
Avec ADB, tu peux aussi déposer les fichiers dans `/sdcard/Android/data/fr.seb.retropop/files/roms/`.

## 4. Jouer

**Avec une manette** (recommandé) : appairage Bluetooth dans les paramètres de la box. Les boutons sont placés comme sur la manette d'origine (le bouton du bas = B, celui de droite = A).

**Avec la télécommande Freebox** (dépannage) : croix = déplacements, OK = action principale (saut), Lecture/Pause = Start.

**Menu pause** : bouton Retour, bouton central de la manette, ou Select + Start.
On y trouve Reprendre, Sauvegarde rapide, Charger, Redémarrer, Quitter.

Les sauvegardes internes des jeux (SRAM, ex. *Zelda*, *Mario World*) sont enregistrées toutes les 30 s, à la mise en pause et en quittant.

---

## Structure du projet

```
app/src/main/
├── java/fr/seb/retropop/
│   ├── MainActivity.kt     écran ludothèque (grille TV)
│   ├── GameActivity.kt     écran de jeu, entrées, menu pause, sauvegardes
│   ├── GameLibrary.kt      scan / import / suppression des jeux
│   ├── GameSystem.kt       consoles prises en charge (cœur + extensions)
│   └── UploadServer.kt     serveur web local (NanoHTTPD)
├── assets/upload.html      page d'envoi ouverte depuis le téléphone
└── jniLibs/                cœurs libretro précompilés (fceumm, snes9x)
```

### Ajouter une console (ex. Mega Drive)

1. Copier le cœur dans `app/src/main/jniLibs/arm64-v8a/` et `armeabi-v7a/` (ex. `libgenesis_plus_gx_libretro_android.so`, disponible dans le dépôt [LemuroidCores](https://github.com/Swordfish90/LemuroidCores)).
2. Ajouter une entrée dans `GameSystem.kt` (nom, fichier du cœur, extensions, couleur).
3. Ajouter les extensions dans `assets/upload.html` (attribut `accept`) et le tableau ci-dessus.

## Crédits et licences

- Émulation via [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) (GPL-3.0)
- Cœurs [FCEUmm](https://github.com/libretro/libretro-fceumm) (GPL-2.0) et [Snes9x](https://github.com/libretro/snes9x) (licence non commerciale), binaires issus de LemuroidCores
- Serveur web [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) (BSD)

Du fait de LibretroDroid, ce projet est distribué sous GPL-3.0. La licence de Snes9x interdit l'usage commercial.
