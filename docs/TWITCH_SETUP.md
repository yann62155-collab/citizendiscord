# Configuration des annonces Twitch — Citizen Discord

## 1. Créer une application Twitch

1. Ouvrir https://dev.twitch.tv/console/apps
2. Créer une application Twitch.
3. Choisir une catégorie d'application adaptée et définir une URL OAuth de redirection (une URL locale suffit pour une application utilisant le flux Client Credentials, elle n'est pas utilisée pour connecter les streameurs).
4. Récupérer le **Client ID** et générer le **Client Secret**.

Les streameurs n'ont pas à fournir leur mot de passe Twitch ni à autoriser leur compte. Le bot utilise un jeton d'application Twitch (Client Credentials) uniquement pour vérifier les chaînes publiques.

## 2. Renseigner la configuration du serveur

Démarrer une fois le serveur avec cette version du mod, puis ouvrir :

`config/citizendiscord/config.json`

Renseigner les propriétés suivantes sans publier le fichier :

- `id_role_streameur` : ID du rôle Discord autorisé à s'inscrire.
- `id_salon_annonces_live` : ID du salon où publier les annonces.
- `twitch_client_id` : Client ID Twitch.
- `twitch_client_secret` : Client Secret Twitch.
- `twitch_intervalle_verification_secondes` : fréquence de vérification (60 secondes par défaut, minimum 30 et maximum 300).

Les valeurs par défaut des IDs Citizen sont déjà renseignées dans le mod :
- Rôle Streameur : `1574470919462592644`
- Salon des annonces : `1443225866220863601`

Après modification, redémarrer le serveur ou utiliser la procédure habituelle de rechargement de Citizen Discord.

## 3. Permissions Discord

Le bot doit pouvoir :
- lire les membres et leurs rôles (Server Members Intent activé dans le portail Discord) ;
- utiliser les commandes d'application (`applications.commands`) ;
- envoyer des messages privés aux streameurs (selon leurs paramètres de confidentialité) ;
- voir le salon d'annonces et y envoyer des messages.

Aucun droit de gestion des rôles n'est requis pour publier les annonces Twitch.

## 4. Inscription des streameurs

Quand un membre reçoit le rôle Streameur, le bot lui envoie un MP avec les instructions.

Sur le serveur Discord, le membre utilise :

`/streamer-inscription twitch:NomDeChaîne`

Pour se désinscrire :

`/streamer-retirer`

Le bot vérifie que la chaîne existe avant de l'enregistrer. Les commandes sont également protégées côté bot : un membre sans le rôle configuré ne peut pas inscrire ou retirer une chaîne.

## 5. Fonctionnement des annonces

Le bot vérifie les chaînes enregistrées à intervalle régulier :
- passage hors ligne → en ligne : annonce de début de live ;
- passage en ligne → hors ligne : annonce de fin de live.

Le premier relevé initialise l'état de la chaîne et n'envoie pas de fausse notification de démarrage au redémarrage du bot. Une nouvelle inscription est initialisée hors ligne afin qu'un live déjà en cours soit annoncé au prochain relevé.

## 6. Protection des données

- Les inscriptions Twitch sont stockées séparément de la whitelist Minecraft dans `config/citizendiscord/streamers.json`.
- Le Client Secret et le token du bot restent dans la configuration locale du serveur ; ils ne sont jamais envoyés dans les messages Discord.
- Le jeton d'accès Twitch est conservé uniquement en mémoire et renouvelé automatiquement ; il n'est pas écrit dans un fichier.
- Sur les systèmes POSIX/Linux, le mod applique les permissions propriétaire uniquement (`0600`) à `config.json` et `streamers.json`.
- Sur Windows, vérifier les ACL NTFS du dossier de configuration pour que seul le compte qui exécute le serveur et les administrateurs autorisés puissent lire ces fichiers.

**Important :** les secrets sont protégés par les permissions d'accès au fichier, mais ne sont pas chiffrés dans le fichier JSON. Ne partage jamais `config.json`, ne le joins pas à un ticket public et ne publie jamais le Client Secret ou le token Discord.
