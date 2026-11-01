# Catalogue des métros IDFM

## Données embarquées

| Fichier dans `app/src/main/assets/` | Rôle |
|---|---|
| `metro_catalog.json` | Export public des arrêts et lignes, filtré sur le mode Metro : 803 enregistrements de quais, 16 lignes et 405 couples ligne–station. |
| `metro_station_order.json` | Parcours ordonnés, branches des lignes 7 et 13, boucles des lignes 10 et 7 bis. |
| `idfm_metro_stops.json` | Correspondance avec le périmètre temps réel : 804 références de quais pour les 405 couples ligne–station. |

Les noms sont regroupés par ligne après normalisation des accents, espaces et tirets. Une station de correspondance conserve des références distinctes pour chaque ligne. Le catalogue des arrêts et le périmètre temps réel peuvent publier des ensembles de quais différents : seuls les quais présents dans le périmètre sont interrogés.

L'application lit les assets de sa version installée, sans télécharger un catalogue supplémentaire sur le téléphone. Les passages et les messages PRIM ont leurs propres caches privés.

## Sources

Données vérifiées le 31 octobre 2026 :

- [Arrêts et lignes — Île-de-France Mobilités](https://prim.iledefrance-mobilites.fr/fr/jeux-de-donnees/arrets-lignes), licence ODbL indiquée sur la fiche source.
- [Export public filtré sur le métro](https://data.iledefrance-mobilites.fr/api/explore/v2.1/catalog/datasets/arrets-lignes/exports/json?where=mode%3D%22Metro%22).
- [Périmètre temps réel — copie officielle régionale](https://data.iledefrance.fr/explore/dataset/perimetre-des-donnees-temps-reel-disponibles-sur-la-plateforme-dechanges-stif/).
- [Référentiel des lignes](https://data.iledefrance-mobilites.fr/explore/dataset/referentiel-des-lignes/) : couleurs `colourweb_hexa` et `textcolourweb_hexa` utilisées par `MetroLineStyles`.
- [Plans officiels Transilien](https://www.transilien.com/fr/page-deplacements/plan-des-lignes), avec complément [RATP pour la ligne 11](https://www.bonjour-ratp.fr/lignes-metro/ligne-11/) : ordre des stations.

La couverture du référentiel atteste les références publiées, sans garantir qu'un prochain passage soit annoncé à chaque instant.

## Actualiser les données

Sous Windows, à la racine du projet :

```powershell
.\actualiser-catalogue.cmd
.\actualiser-references.cmd
```

Ou directement :

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Update-MetroCatalog.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Update-MetroReferences.ps1
```

Le premier script valide l'export avant de remplacer le catalogue. Le second nécessite Node.js et refuse le remplacement si une station n'a aucune référence temps réel vérifiée. Aucune clé PRIM n'est requise.

Validation du catalogue sans réseau :

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Update-MetroCatalog.ps1 -ValidateOnly
```

Pour réutiliser un export du périmètre déjà téléchargé, fournir `-PerimeterFile chemin\vers\perimetre.json` au second script.

Après un prolongement ou un renommage, vérifier également `metro_station_order.json` et `MetroLineReferences.kt`. Les stations absentes du parcours ordonné restent visibles dans une section dédiée, sans position inventée. Les tests de couverture vérifient la cohérence du catalogue et des références ; adapter les effectifs attendus si le réseau évolue.

Recompiler et réinstaller l'application pour distribuer la nouvelle copie des assets.

## Sélection et directions

La sélection et le thème sont conservés dans les préférences privées `metro11_preferences`, dont le nom historique est maintenu pour préserver les installations existantes. Aucune ligne ou station n'est sélectionnée par défaut.

`MetroDirections` calcule les directions nominales à partir des parcours et classe les destinations réelles selon leur accessibilité. Les services partiels conservent leur destination annoncée. Une destination contradictoire, inaccessible ou ambiguë reste non résolue ; les horaires ne sont pas placés arbitrairement dans une colonne.
