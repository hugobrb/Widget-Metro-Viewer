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
- Réponse par ligne réutilisable pendant une minute. Le bouton manuel autorise une nouvelle récupération après 30 secondes ; les clics rapprochés ne multiplient pas les appels.
- Messages et circulation mis en cache pendant deux minutes. Une réponse réseau incomplète ne remplace pas un cache complet.
- Conservation des dernières données en cas d'échec, avec un affichage explicitement ancien. Aucune donnée fictive n'est injectée après un échec réseau.
- Respect des plafonds locaux et des délais de nouvelle tentative, notamment après un refus ou un quota dépassé.

Le widget demande une actualisation toutes les minutes via une chaîne WorkManager, avec un filet de reprise périodique de quinze minutes. Le système Android peut différer son exécution. Aucun appel n'est effectué lorsque le téléphone est verrouillé ; le verrouillage interrompt aussi les requêtes en cours. Le réveil/déverrouillage relance la synchronisation si la configuration est éligible.

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
6. Verrouiller le téléphone : vérifier l'arrêt des appels, puis la reprise au déverrouillage.
7. Vérifier les informations trafic, les pastilles regroupées, leur rotation et les trois modes de thème.

La disponibilité réelle des passages et des messages dépend du producteur PRIM. Les tests simulés et la compilation ne remplacent pas la validation authentifiée sur un téléphone.
