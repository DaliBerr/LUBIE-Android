Documentation technique — Application Android LUBIE
Branche analysée : QTT
1. Introduction générale

L’application LUBIE est une application Android native dont le rôle principal est de servir d’interface de supervision, d’enregistrement et de relecture pour un système de suivi du regard exécuté à distance sur un autre appareil, en pratique un Raspberry Pi.

Autrement dit, l’intelligence principale du suivi visuel n’est pas réalisée sur le téléphone ou la tablette Android elle-même. L’application Android reçoit :

un flux vidéo RTSP provenant du système distant ;
des données de regard transmises séparément en UDP sous forme de messages JSON ;
puis elle les affiche ensemble, les enregistre localement et permet ensuite une relecture synchronisée.

Cette architecture est particulièrement adaptée à un prototype de démonstration ou à un MVP (Minimum Viable Product), car elle sépare clairement :

la production des données (Raspberry Pi),
et la consommation / visualisation / archivage (Android).

Sources principales :
README.md
 · MainActivity.kt
 · RemoteTrackingController.kt

2. Objectif fonctionnel de l’application

Pour un utilisateur non technique, l’application fait essentiellement quatre choses :

2.1 Se connecter à un flux distant

L’utilisateur saisit :

une adresse RTSP ;
un port UDP.

Après cela, l’application tente de :

lire la vidéo distante ;
écouter les messages de regard ;
superposer le point de regard sur l’image.
2.2 Afficher le regard sur la vidéo

Quand les données reçues indiquent que le suivi est valide, l’application dessine un point rouge au bon endroit sur la vidéo affichée.

2.3 Enregistrer une session

L’application enregistre localement :

les images vidéo capturées,
les métadonnées associées,
les échantillons de regard,
les marqueurs temporels ajoutés par l’utilisateur.
2.4 Relire la session

L’utilisateur peut rouvrir la dernière session enregistrée et :

lire / mettre en pause ;
avancer / reculer image par image ;
changer la vitesse ;
naviguer sur une barre temporelle ;
revoir les marqueurs posés pendant l’enregistrement.

Sources :
README.md
 · activity_main.xml
 · RemoteModels.kt

3. Architecture générale du système
3.1 Vue d’ensemble

Le fonctionnement global peut être résumé ainsi :

Raspberry Pi
→ envoie une vidéo RTSP
→ envoie des données de regard en UDP/JSON

Application Android
→ lit la vidéo
→ reçoit les données de regard
→ affiche l’overlay
→ enregistre la session
→ relit la session localement

Le point important est que la vidéo et le regard n’arrivent pas par le même canal :

RTSP sert à transporter l’image ;
UDP sert à transporter les mesures de regard.

Cela simplifie le prototype : la vidéo reste gérée par un lecteur multimédia standard, tandis que les données de regard restent légères, rapides et faciles à parser.

3.2 Philosophie du design

L’application a été conçue comme un client léger :

elle ne réalise pas le calcul complexe principal du suivi du regard ;
elle se concentre sur l’interaction utilisateur, la synchronisation visuelle, l’archivage et la relecture.

C’est un choix cohérent pour un MVP, car il permet :

de réduire la charge de calcul côté Android ;
de garder le téléphone ou la tablette comme interface de contrôle ;
de simplifier l’évolution future du système distant sans devoir réécrire toute l’interface Android.

Sources :
README.md
 · RemoteTrackingController.kt
 · UdpGazeReceiver.kt

4. Technologies, outils et bibliothèques
4.1 Outils de développement
Android Studio

Le projet est conçu pour être ouvert dans Android Studio, qui sert d’environnement principal de développement, d’édition, de compilation et de débogage.

Gradle

Le système de build est Gradle, avec une configuration centralisée des versions dans libs.versions.toml.
Gradle sert à :

gérer les dépendances ;
compiler l’application ;
exécuter les tests ;
générer l’APK.
Android Gradle Plugin

Le projet utilise l’Android Gradle Plugin 9.1.0.

SDK et compatibilité

Le module app est configuré avec :

minSdk = 30
targetSdk = 36
compileSdk = 36
Java / Kotlin

Le code source observé est écrit en Kotlin.
La compatibilité Java est réglée sur Java 11.

Sources :
settings.gradle.kts
 · build.gradle.kts
 · app/build.gradle.kts
 · gradle/libs.versions.toml

4.2 Bibliothèques réellement actives dans le flux principal
AndroidX Core KTX

Cette bibliothèque apporte des extensions Kotlin modernes pour simplifier le code Android de base.

AndroidX Activity KTX

Elle facilite la gestion d’une activité Android moderne, ici MainActivity.

AndroidX Lifecycle Runtime KTX

Elle aide à intégrer le comportement Android dans un cycle de vie propre.

Media3 ExoPlayer

C’est le moteur multimédia principal utilisé pour lire le flux vidéo.

Media3 ExoPlayer RTSP

Cette extension permet à ExoPlayer de lire un flux RTSP, ce qui est indispensable dans ce projet.

Media3 UI

Elle fournit le composant PlayerView, utilisé pour afficher la vidéo dans l’interface.

View Binding

Le projet active View Binding, ce qui permet d’accéder de manière sûre et claire aux éléments de l’interface XML via ActivityMainBinding.

org.json

Le projet utilise JSONObject / JSONArray pour parser et sérialiser les messages JSON de regard, ainsi que les fichiers de session.

API Android standard

Le projet s’appuie aussi sur des composants Android natifs, par exemple :

Handler et Looper pour le thread principal ;
ExecutorService pour le travail en arrière-plan ;
DatagramSocket pour l’UDP ;
TextureView pour capturer les images affichées ;
Bitmap pour stocker les frames ;
File et FileOutputStream pour l’écriture disque.

Sources :
app/build.gradle.kts
 · gradle/libs.versions.toml
 · MainActivity.kt
 · RemoteTrackingController.kt
 · GazeSampleJson.kt

4.3 Bibliothèques encore présentes dans le projet mais non centrales dans le flux actuel

Le fichier de build contient aussi :

CameraX
OpenCV
ONNX Runtime

Ces bibliothèques sont typiquement utilisées pour :

l’acquisition caméra locale ;
le traitement d’image ;
l’inférence de modèles d’IA.

Cependant, d’après la branche QTT, elles ne constituent plus le chemin principal exposé à l’utilisateur dans le MVP actuel. Elles semblent être liées à une ancienne chaîne locale de détection / segmentation encore conservée dans le dépôt, probablement pour de futures évolutions ou pour compatibilité historique.

Cette distinction est importante :
elles existent dans le dépôt, mais l’expérience utilisateur principale actuelle repose surtout sur RTSP + UDP + enregistrement + replay.

Sources :
README.md
 · app/build.gradle.kts
 · DetectionOverlayView.kt

5. Structure du code
5.1 MainActivity

MainActivity est l’entrée principale de l’application.
Elle :

charge l’interface ;
restaure l’URL RTSP et le port UDP précédemment utilisés ;
branche les boutons ;
transmet les actions utilisateur au contrôleur principal ;
affiche l’état courant de l’application.

Elle ne fait pas le travail réseau ni le travail de stockage elle-même. Elle joue le rôle de chef d’orchestre de l’interface.

5.2 RemoteTrackingController

C’est la pièce centrale du système.
Ce contrôleur gère :

la connexion au flux vidéo ;
l’écoute UDP ;
le démarrage et l’arrêt de l’enregistrement ;
l’écriture des marqueurs ;
la bascule vers le mode replay ;
la logique de relecture ;
la préparation de l’état à afficher dans l’interface.

On peut considérer cette classe comme le cœur métier de l’application.

5.3 UdpGazeReceiver

Cette classe ouvre un socket UDP sur le port demandé et écoute les messages reçus.
Chaque message est converti en texte puis interprété comme un JSON de regard.

5.4 GazeSampleJson et RemoteModels

Ces fichiers définissent :

la forme des données de regard ;
les types représentant une session, une frame, un marqueur ou l’état d’affichage.

Ils constituent la colonne vertébrale des données de l’application.

5.5 RemoteSessionStore

Cette classe gère l’enregistrement local :

création du dossier de session ;
sauvegarde des frames JPEG ;
écriture des fichiers JSON / JSONL ;
rechargement de la dernière session.
5.6 RemoteReplaySource

Cette classe relit les données enregistrées :

charge la dernière session ;
reconstruit les images ;
permet d’avancer, reculer ou chercher un instant précis.
5.7 Vues personnalisées
DetectionOverlayView

Dessine par-dessus l’image :

le texte d’état ;
le point de regard.
MarkerTimelineView

Dessine les marqueurs temporels sur la barre de progression du replay.

SixteenNineFrameLayout

Force l’affichage principal à respecter un ratio 16:9, même si l’écran Android a une autre forme.

Sources :
MainActivity.kt
 · RemoteTrackingController.kt
 · UdpGazeReceiver.kt
 · GazeSampleJson.kt
 · RemoteModels.kt
 · RemoteSessionStore.kt
 · RemoteReplaySource.kt
 · DetectionOverlayView.kt
 · MarkerTimelineView.kt
 · SixteenNineFrameLayout.kt

6. Fonctionnement détaillé de l’application
6.1 Démarrage

Quand l’application démarre :

l’interface XML est chargée ;
les derniers paramètres connus sont relus depuis les préférences locales ;
le contrôleur principal est créé ;
l’interface affiche un état initial “idle”.
6.2 Saisie des paramètres

L’utilisateur remplit :

l’URL RTSP ;
le port UDP.

L’application vérifie que :

l’URL n’est pas vide ;
elle commence bien par rtsp:// ;
le port est un nombre valide compris entre 1 et 65535.
6.3 Connexion au flux vidéo

Quand l’utilisateur appuie sur Connect :

l’application construit un MediaItem à partir de l’URL RTSP ;
ExoPlayer prépare la lecture ;
PlayerView affiche le flux ;
le contrôleur passe en mode LIVE.
6.4 Réception des données de regard

En parallèle, le récepteur UDP s’ouvre sur le port choisi.
Chaque paquet reçu :

est converti en texte UTF-8 ;
est interprété comme un JSON ;
est transformé en objet GazeSample.

Si la donnée est valide, elle devient l’échantillon courant utilisé par l’interface.

6.5 Affichage de l’overlay

Le point de regard n’est dessiné que si :

tracking_valid == true
et si screen_uv contient bien des coordonnées.

Autrement dit, l’application ne “devine” pas.
Elle n’affiche le point que lorsque les données disent explicitement qu’il est fiable.

6.6 Enregistrement automatique

Le contrôleur active l’enregistrement dans le flux live.
Lorsque le lecteur est prêt et qu’une image est disponible :

l’application récupère l’image actuellement affichée ;
crée une session si nécessaire ;
enregistre l’image ;
associe à cette image le snapshot de regard courant.

Le rythme visé est d’environ 30 images par seconde, avec un intervalle de 33 ms.

6.7 Ajout de marqueurs

Quand l’utilisateur appuie sur Add Marker, l’application enregistre un repère temporel associé au dernier instant capturé.
Ces marqueurs servent ensuite à retrouver rapidement un moment important pendant la relecture.

6.8 Passage en mode replay

Quand l’utilisateur appuie sur Replay :

la lecture live s’arrête ;
le flux vidéo et l’UDP sont arrêtés ;
la dernière session enregistrée est chargée ;
l’application passe en mode REPLAY.
6.9 Relecture

En replay, l’application :

lit les images JPEG depuis le stockage local ;
recharge pour chaque image les métadonnées correspondantes ;
réaffiche le point de regard sauvegardé ;
met à jour la timeline et les marqueurs.

Cela signifie que le replay ne recalcule pas le regard :
il réutilise exactement les données enregistrées pendant la session.

Sources :
MainActivity.kt
 · RemoteTrackingController.kt
 · UdpGazeReceiver.kt
 · RemoteSessionStore.kt
 · RemoteReplaySource.kt
 · RemoteModels.kt

7. Les données échangées
7.1 Le flux vidéo

La vidéo arrive via RTSP.
Dans l’application, elle est affichée par PlayerView avec ExoPlayer.

7.2 Les données de regard

Les données de regard arrivent sous forme de JSON UDP.
Parmi les champs importants, on trouve notamment :

screen_uv : position normalisée du regard ;
tracking_valid : indique si la mesure est fiable ;
fps : vitesse de traitement ;
inference_ms : latence de calcul ;
calibration_state : état de la calibration ;
status_message : message textuel.

Cette structure est pratique car elle transporte à la fois :

le résultat principal (où regarde l’utilisateur),
et l’état du système (est-ce calibré ? est-ce valide ? est-ce rapide ?).
7.3 Pourquoi cette séparation est utile

Séparer vidéo et métadonnées présente plusieurs avantages :

la vidéo peut rester gérée par un lecteur standard ;
les données de regard restent simples à traiter ;
l’enregistrement local peut associer chaque frame à son propre échantillon de regard.

Sources :
GazeSampleJson.kt
 · RemoteModels.kt
 · README.md

8. Enregistrement local et organisation des fichiers

Les sessions sont stockées dans le dossier privé de l’application, sous la forme :

files/remote_sessions/<sessionId>/

Chaque session contient :

session.json

Description générale de la session :

identifiant ;
type de source ;
dimensions d’image ;
URL RTSP ;
port UDP ;
informations générales de session.
metadata.jsonl

Une ligne JSON par image enregistrée.
Chaque ligne contient :

l’identifiant de frame ;
le timestamp ;
le nom du fichier image ;
la largeur / hauteur ;
le type de source ;
le snapshot de regard.
markers.jsonl

Une ligne JSON par marqueur temporel.

frames/000000.jpg, 000001.jpg, etc.

Les images de la session, sauvegardées au format JPEG.

8.1 Pourquoi une séquence JPEG au lieu d’une vidéo MP4 ?

Dans l’état actuel du MVP, ce choix simplifie plusieurs choses :

sauvegarder rapidement une image affichée ;
associer précisément chaque image à ses métadonnées ;
relire image par image de façon déterministe.

En contrepartie :

cela occupe plus d’espace disque ;
ce n’est pas un format final aussi compact qu’une vraie vidéo.

Le README précise d’ailleurs que c’est une limite connue du MVP.

Sources :
README.md
 · RemoteSessionStore.kt

9. Interface utilisateur

L’interface est définie en XML, pas en Jetpack Compose.
Elle est simple, compacte et orientée usage terrain.

9.1 Organisation visuelle

L’écran contient :

un en-tête de statut ;
un champ pour l’URL RTSP ;
un champ pour le port UDP ;
des boutons de contrôle ;
une zone vidéo principale ;
une barre de timeline pour le replay ;
des boutons de relecture ;
une zone d’informations texte.
9.2 Orientation et format

L’application est :

verrouillée en mode portrait ;
avec une zone vidéo forcée au format 16:9.

Le PlayerView est configuré en mode fit, ce qui signifie que l’application privilégie l’affichage complet de l’image plutôt que son recadrage.

9.3 Overlay

Le DetectionOverlayView dessine :

le texte d’état ;
le point de regard rouge.

Même si cette vue contient encore des capacités plus riches héritées d’anciennes versions (ROI, ellipse, overlay de segmentation), dans le flux principal actuel elle est utilisée de manière simplifiée pour l’overlay distant.

9.4 Timeline

Le MarkerTimelineView superpose visuellement :

la position courante du replay ;
les marqueurs enregistrés.

Cela améliore fortement la relecture, car l’utilisateur peut repérer rapidement les instants importants.

Sources :
activity_main.xml
 · AndroidManifest.xml
 · SixteenNineFrameLayout.kt
 · DetectionOverlayView.kt
 · MarkerTimelineView.kt

10. Gestion des threads et robustesse

Même si ce détail est technique, il est important pour comprendre la qualité du design.

L’application sépare plusieurs responsabilités :

thread principal : interface utilisateur et affichage ;
thread UDP : réception réseau ;
executor mono-thread : écriture des images, lecture de replay, travail disque.

Cette séparation évite que :

l’interface se bloque ;
la réception réseau gèle l’écran ;
l’enregistrement perturbe directement l’affichage.

On observe aussi plusieurs protections :

timeout sur le socket UDP ;
arrêt propre des boucles ;
drapeau frameWriteInFlight pour éviter plusieurs écritures simultanées ;
gestion d’erreurs utilisateur avec messages courts.

C’est un bon signe de maturité pour un MVP : le code ne se limite pas à “faire fonctionner le happy path”, il prévoit aussi les cas d’échec.

Sources :
RemoteTrackingController.kt
 · UdpGazeReceiver.kt

11. Permissions, sécurité et limites connues
11.1 Permission

Le manifeste déclare uniquement :

android.permission.INTERNET

Cela confirme que le scénario principal actuel ne repose pas sur la caméra locale Android.

11.2 Limites actuelles

Le projet reconnaît plusieurs limites :

seule la dernière session peut être relue ;
il n’existe pas encore de liste complète des sessions ;
l’enregistrement est une suite d’images JPEG, pas une vidéo MP4 ;
si l’appareil Android manque de performance, l’écriture peut sauter des frames pour préserver la fluidité.

Ces limites sont cohérentes avec un MVP :
elles montrent que le projet privilégie d’abord la validation de la chaîne complète avant l’optimisation produit.

Sources :
AndroidManifest.xml
 · README.md
 · RemoteTrackingController.kt

12. Tests et validation

D’après le README du projet, les vérifications suivantes ont été validées :

compilation Kotlin ;
tests unitaires ;
assemblage debug.

Le README mentionne aussi une couverture de tests sur :

le parsing JSON UDP ;
le comportement lorsque screen_uv est nul ;
l’encodage / décodage des sessions ;
la logique temporelle du replay ;
la recherche par position dans la timeline ;
le calcul de durée de session.

Je n’affirme pas ici avoir réexécuté ces commandes moi-même ; je rapporte ce qui est documenté dans la branche QTT.
Pour un rapport de projet, cela montre néanmoins une intention claire de fiabiliser les composants critiques du système.

Source :
README.md

13. Conclusion

La branche QTT de LUBIE-Android correspond à une application Android native centrée sur un rôle précis :
recevoir, afficher, enregistrer et relire un flux de suivi du regard produit à distance.

Ses forces principales sont :

une architecture simple à comprendre ;
une séparation propre entre vidéo, données de regard et stockage ;
une interface légère mais fonctionnelle ;
une chaîne complète de bout en bout : connexion, overlay, enregistrement, marquage, replay.

D’un point de vue projet, cette application n’est pas encore un produit final pleinement industrialisé, mais elle constitue un MVP solide et cohérent.
Elle remplit bien son objectif principal : transformer un flux technique distant en une expérience de supervision et de relecture exploitable par un utilisateur humain.