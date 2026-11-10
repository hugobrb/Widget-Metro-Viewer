# Intégration PRIM — état actuel

## Services utilisés

| Usage | Endpoint PRIM | Comportement |
|---|---|---|
| Prochains passages par ligne | `/marketplace/requete-ligne` | Source prioritaire, réponse partagée entre les stations de la même ligne. |
| Prochains passages par quai | `/marketplace/stop-monitoring` | Repli si la réponse par ligne n'est pas exploitable ; interroge les quais vérifiés de la station. |
| Messages des panneaux | `/marketplace/general-message` | Messages de la ligne sélectionnée. |
| Circulation et correspondances | `/marketplace/v2/navitia/line_reports/physical_modes/physical_mode%3AMetro/line_reports` | Informations du réseau métro, filtrées selon la ligne et ses stations de correspondance. |

Les droits doivent être activés sur le compte PRIM. Les quatre compteurs et plafonds locaux sont indépendants. Ils comptent les tentatives HTTP de cette installation ; ils ne consultent pas le total serveur du compte.

## Références et directions

Toutes les stations du catalogue sont couvertes par les références embarquées : 405 couples ligne–station sur 16 lignes. Voir [le catalogue](CATALOGUE-IDFM.md) pour les sources et les scripts de maintenance.

Les requêtes utilisent les références `STIF:Line::C…:` et `STIF:StopPoint:Q:…:`. La sélection associe toujours le nom de station à sa ligne. Une référence absente ne déclenche aucun appel vers une autre station.

`IdfmDeparturesParser` lit les visites StopMonitoring ; `IdfmLineDeparturesParser` adapte les réponses EstimatedTimetable. Les parseurs filtrent la ligne et les quais, excluent les courses annulées, traitent les fuseaux horaires et privilégient les heures estimées. Les heures théoriques sont identifiées dans l'affichage.

Le classement des destinations est centralisé dans `MetroDirections`, avec les branches et les boucles. Une destination inconnue ne peut être déduite que d'une preuve non ambiguë sur le même quai. Les codes DirectionRef ne sont pas arbitrairement assimilés à un sens. Les anciennes valeurs de sens présentes dans un cache de la ligne 11 restent lisibles.

## Cache et actualisation

- Cache des passages isolé par ligne et station, dans le stockage privé non sauvegardé.
- Réponse par ligne réutilisable pendant 30 secondes pour partager un appel entre le widget et l’aperçu.
- Messages et circulation mis en cache pendant deux minutes. Une réponse réseau incomplète ne remplace pas un cache complet.
- Conservation des dernières données en cas d'échec, avec un affichage explicitement ancien. Aucune donnée fictive n'est injectée après un échec réseau.
- Respect des plafonds locaux et des délais de nouvelle tentative, notamment après un refus ou un quota dépassé.

Le widget demande une actualisation toutes les 30 secondes via une chaîne WorkManager soumise à une contrainte réseau. Les appels API vérifient aussi que la connexion Internet est validée. L’app peut être en arrière-plan et l’écran peut être verrouillé. Android peut différer l’exécution, notamment en veille, et un travail périodique de secours est programmé toutes les quinze minutes.

Le bouton du widget reste accessible pendant une attente réseau. Un clic remplace une demande immédiate en attente sans interrompre une demande déjà en cours. À partir d'Android 12, la demande manuelle bénéficie du mode expedited, avec repli standard si le quota système est épuisé. Les receivers ne font pas de requête HTTP : ils délèguent au worker.

## Trafic et affichage

Le bandeau présente l'état de circulation de la ligne sélectionnée : trafic normal, ralenti, perturbé ou interrompu selon les informations disponibles. En l'absence de données suffisamment fiables, il ne suppose pas un trafic normal.

L'encart détaille les informations de la ligne et des arrêts de correspondance concernés. Une fermeture sur une correspondance ne devient pas une interruption de la ligne sélectionnée. Les avis proviennent de PRIM ; aucun message de travaux n'est ajouté en dur.

Les messages similaires sont regroupés avec les pastilles des lignes concernées. Le widget utilise un ViewFlipper natif, avec changement toutes les dix secondes et transition de glissement. L'horloge vient du téléphone. Aperçu et widget partagent la même présentation.

## Clé et confidentialité

La clé est saisie dans l'application, chiffrée avec AndroidKeyStore/AES-GCM et stockée dans un fichier privé exclu des sauvegardes. Elle n'est incluse ni dans les assets, ni dans les préférences, ni dans les données WorkManager, ni dans le dépôt Git.

Les appels utilisent HTTPS, sans journalisation de la clé et sans redirection transmettant l'en-tête `apikey`. Les messages d'erreur affichés restent synthétiques. Les tests utilisent des valeurs factices et des connexions simulées.

## Vérifications

Les tests JVM couvrent les parseurs, annulations, dates, déduplication, directions de toutes les stations, branches, boucles, services partiels, regroupement des avis et présentation des caches anciens.

Les tests Android couvrent les clés chiffrées, les budgets, les caches, les requêtes simulées, le rafraîchissement manuel, les références du catalogue et la compatibilité des RemoteViews.

Pour une validation sur téléphone :

1. Sans clé, vérifier le mode démonstration et les sélections sans valeur par défaut.
2. Enregistrer une clé valide, choisir une ligne/station et vérifier les vrais passages.
3. Ajouter un widget et comparer ses directions et horaires à l'aperçu.
4. Attendre plus de 30 secondes, cliquer sur actualiser et vérifier le compteur de l'API concernée.
5. Couper le réseau : vérifier les données anciennes ou l'état indisponible, sans faux horaire.
6. Quitter l’app, puis vérifier la progression du compteur. Couper Internet et vérifier que le compteur reste stable, puis rétablir la connexion et attendre la reprise.
7. Vérifier les informations trafic, les pastilles regroupées, leur rotation et les trois modes de thème.

La disponibilité réelle des passages et des messages dépend du producteur PRIM. Les tests simulés et la compilation ne remplacent pas la validation authentifiée sur un téléphone.

## Suivi des courses et affichage à quai

Les passages sont suivis par identifiant de course SIRI et par quai. L'heure prévue ne fait plus partie de leur identité : une révision remplace la prévision de la même course. Pour les identifiants complets RATP, le même identifiant est utilisé entre les réponses par quai et par ligne.

Les mises à jour sont comparées via `RecordedAtTime`. Une version plus ancienne ne remplace pas une version connue plus récente. Une version de même date contenant un horaire contradictoire est également ignorée. Une nouvelle prévision dont l'heure est déjà passée n'est pas ajoutée, sauf présence explicitement signalée par l'API et encore valable. Les heures d'arrivée estimées sont privilégiées pour l'attente.

Une course momentanément absente reste disponible à partir de sa dernière observation, pendant au plus 90 secondes et sans dépasser sa fenêtre d'arrivée. Les annulations et départs explicites retirent immédiatement une course, sous réserve de ne pas être une version plus ancienne que celle déjà connue. Un historique local borné empêche qu'une ancienne réponse réintroduise une annonce retirée.

À l'heure d'arrivée estimée précédemment chargée, le panneau affiche « À QUAI » pendant dix secondes, puis passe au train suivant. Cette durée d’affichage est fixée à dix secondes. Elle ne représente pas une durée moyenne d’arrêt mesurée : l’indication peut être calculée à partir de la prévision ou confirmée par `VehicleAtStop`. Les détails du suivi ne sont pas affichés sous les horaires. Les horaires théoriques seuls ne produisent pas de présence estimée.

Le passage à quai et sa fin sont des transitions locales : aucun appel API supplémentaire n'est nécessaire. L'aperçu vérifie ses états chaque seconde lorsqu'il est visible et déverrouillé. Le widget programme un redessin local aux changements utiles ; Android peut suspendre le processus et différer ce redessin.

Le cache des passages utilise la version 2, avec des identités indépendantes des horaires. Le premier chargement réseau reconstitue les données ; les préférences et la clé restent conservées. Les métadonnées de suivi sont persistées avec les passages pour supporter un redémarrage du processus.
