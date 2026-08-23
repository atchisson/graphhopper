# Mode « couverture maximale » — éviter de repasser deux fois sur la même route

**Date :** 2026-07-10
**Portée :** fork GraphHopper (`gh`) + UI (`mmghr`)
**Objectif :** maximiser la couverture photo Panoramax d'un trajet en évitant que la route
n'emprunte deux fois le même edge (ex. aller-retour vers un waypoint proche de la route
principale). Au lieu d'un demi-tour, le routeur revient par une boucle.

## Contexte

- Un plus court chemin ne repasse jamais deux fois sur le même edge *à l'intérieur* d'un
  tronçon (leg). Le problème n'existe qu'*entre* les tronçons, c'est-à-dire dès qu'il y a
  des waypoints intermédiaires.
- Décisions validées :
  - Solution **serveur garantie** (pas de heuristique client par zones d'évitement).
  - **Pénalité réglable** (slider UI), jamais d'interdiction stricte : une impasse reste
    franchissable, le routeur ne repasse qu'en dernier recours.
  - La pénalité s'applique **dans les deux sens** de l'edge (cohérent capture 360°).

## API (fork GraphHopper)

Deux nouveaux paramètres de la requête POST `/route` (hints, comme `pass_through`) :

| Paramètre | Type | Défaut | Description |
|---|---|---|---|
| `avoid_traversed_edges` | boolean | `false` | Active le mode |
| `traversed_edge_factor` | number | `0.1` | Multiplicateur de priorité appliqué aux edges déjà empruntés. Sémantique identique aux règles photo : `1.0` = neutre, `0.05` = quasi-interdit. Le poids de l'edge est multiplié par `1/factor`. Valide : `0 < factor <= 1`. |

- Sans waypoint intermédiaire (2 points), le mode est sans effet.
- Nécessite `ch.disable=true` (déjà le cas pour toutes les requêtes custom_model de l'UI).
- Compatible LM : la pénalité ne fait qu'augmenter les poids, l'admissibilité des
  landmarks est préservée. Aucun réimport du graphe.

## Implémentation Java

1. **`TraversedEdgePenaltyWeighting`** (nouveau, décorateur de `Weighting`) :
   détient un `Set<Integer>` d'edge IDs ; `calcEdgeWeight` multiplie le poids par
   `1/factor` si l'edge est dans le set, quel que soit le sens (`reverse` ignoré).
   Délègue tout le reste au weighting décoré.
2. **`ViaRouting.calcPaths`** (`core/src/main/java/com/graphhopper/routing/ViaRouting.java`) :
   quand le mode est actif, calcul séquentiel des tronçons ; après chaque tronçon,
   collecte des edge IDs du chemin (`Path.calcEdges()` / `EdgeIteratorState.getEdge()`)
   dans le set partagé utilisé par les tronçons suivants.
3. **`Router`** : lecture des hints `avoid_traversed_edges` / `traversed_edge_factor`,
   validation (factor dans `(0, 1]`, erreur 400 sinon), injection du weighting décoré.

## UI (mmghr)

- **Toggle** « Ne pas repasser deux fois » + **slider** d'intensité dans le panneau de
  routing, sur le modèle des contrôles existants (photo coverage / unpaved / pushing) :
  `routingUI.js`, `toggleHandlers.js`, i18n fr/en.
- État persisté dans `routeState`, recalcul de la route au changement (comme les
  autres options).
- `buildPostRequestBodyWithCustomModel()` (`js/routing/customModel.js`) ajoute
  `avoid_traversed_edges: true` et `traversed_edge_factor: <slider>` quand le toggle
  est actif.
- Slider : échelle 0–100 avec le même mapping exponentiel que le slider photo coverage
  (`factor = 0.5 × 0.02^(s/100)`, soit 0.5 à s=0, ~0.07 à s=50, 0.01 à s=100), défaut 50.
- Disponible pour les trois profils (car, bike, foot). Affiché en permanence ; note
  d'aide indiquant qu'il agit avec au moins un point de passage intermédiaire.

## Tests

- **Java, boucle** : graphe en grille avec un via en antenne courte sur la route
  principale — sans le mode, l'aller-retour réutilise les edges ; avec le mode, le
  retour se fait par une boucle (aucun edge dupliqué).
- **Java, impasse** : via au fond d'une impasse sans boucle possible — la route aboutit
  quand même (les edges sont réutilisés en dernier recours).
- **Java, deux sens** : vérifier que l'edge emprunté en sens A→B est pénalisé aussi en
  sens B→A.
- **Java, validation** : `traversed_edge_factor` hors `(0, 1]` → erreur.
- **UI, manuel** : cas réel du waypoint à côté de la route principale ; vérifier le
  body de la requête et la boucle sur la carte.

## Hors périmètre

- Optimisation d'ordre des waypoints tenant compte de la pénalité.
- Path detail exposant les edges répétés.
- Interaction avec le mode CH (profiles_ch est vide dans la config de production).
