# Configuration des annonces Twitch — Citizen Discord

## 1. Créer une application Twitch

1. Ouvrir https://dev.twitch.tv/console/apps
2. Créer une application Twitch.
3. Choisir une catégorie d'application adaptée et définir une URL OAuth de redirection (une URL locale suffit pour une application utilisant le flux Client Credentials, elle n'est pas utilisée pour connecter les streameurs).
4. Récupérer le **Client ID** et générer le **Client Secret**.

Les streameurs n'ont pas à fournir leur mot de passe Twitch ni à autoriser leur compte. Le bot utilise un jeton d'application Twitch (Client Credentials) uniquement pour vérifier les chaînes publiques.

## 2. Configurer Twitch depuis les messages privés (staff)

Un membre du staff (Administrateur, Gérer les rôles ou rôle déclaré dans `roles_staff`) peut envoyer au bot en MP :

`configurer twitch`

Le bot demande successivement le Client ID puis le Client Secret et enregistre les identifiants dans `config/citizendiscord/config.json`. La configuration est appliquée sans redémarrer le bot. Écris `annuler` pour interrompre la procédure.

**Sécurité :** les identifiants transitent dans les messages privés Discord. N'utilise cette méthode que depuis un compte staff sécurisé et ne partage pas la conversation. Pour éviter de transmettre le secret par Discord, tu peux toujours renseigner les deux propriétés directement dans le fichier local `config/citizendiscord/config.json` :

- `id_role_streameur` : ID du rôle Discord autorisé à s'inscrire.
- `id_salon_annonces_live` : ID du salon où publier les annonces.
- `twitch_client_id` : Client ID Twitch.
- `twitch_client_secret` : Client Secret Twitch.
- `twitch_intervalle_verification_secondes` : fréquence de vérification (60 secondes par défaut, minimum 30 et maximum 300).

Les valeurs par défaut des IDs Citizen sont déjà renseignées dans le mod :
- Rôle Streameur : `1574470919462592644`
- Salon des annonces : `1443225866220863601`

Après modification manuelle du fichier, redémarrer le serveur ou utiliser `/cdiscord reload`.

## 3. Permissions Discord

Le bot doit pouvoir :
- lire les membres et leurs rôles (Server Members Intent activé dans le portail Discord) ;
- utiliser les commandes d'application (`applications.commands`) ;
- envoyer des messages privés aux streameurs (selon leurs paramètres de confidentialité) ;
- voir le salon d'annonces et y envoyer des messages.

Aucun droit de gestion des rôles n'est requis pour publier les annonces Twitch.

## 4. Inscription des streameurs

Quand un membre reçoit le rôle Streameur, le bot lui envoie un MP avec les instructions.

Le membre répond directement au message privé du bot avec son pseudo Twitch (ou l'URL de sa chaîne). Il peut envoyer un nouveau pseudo pour le modifier, ou écrire `supprimer` pour se désinscrire. Après inscription, le bot renvoie le lien cliquable de la chaîne et un récapitulatif des informations publiques récupérées via Twitch : nom affiché, description, type de compte, nombre de vues, date de création et photo de profil. Le streamer n'a aucune autre information à fournir.

Si l'intégration Twitch est déjà configurée, le bot vérifie que la chaîne existe avant de l'enregistrer. Si la configuration technique n'est pas encore faite, le pseudo est tout de même enregistré et l'inscription reste en attente : le streameur n'a pas besoin de recommencer lorsque l'administrateur activera l'API Twitch. Les chaînes en attente sont vérifiées automatiquement dès que l'intégration est configurée. Les commandes sont également protégées côté bot : un membre sans le rôle configuré ne peut pas inscrire ou retirer une chaîne.

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


## 7. Attribution du rôle par le staff

Le staff peut utiliser la commande Discord **`/streameur membre:@utilisateur`**. Le bot attribue lui-même le rôle STREAMEUR, puis envoie au membre un MP indiquant que le staff lui a activé ce statut et lui demandant uniquement son pseudo Twitch. Le bot récupère ensuite les informations publiques et gère les annonces automatiquement.

Le membre du staff n'a donc pas à attribuer manuellement le rôle ni à enregistrer la chaîne. Le bot doit toutefois disposer de la permission **Gérer les rôles**, et son rôle Discord doit être placé au-dessus du rôle STREAMEUR.
