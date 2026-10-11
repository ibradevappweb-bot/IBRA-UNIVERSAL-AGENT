# Catalogue d'outils IBRA — versionnement et intégration contrôlée

Ce module est ajouté à la branche `n8n-python-engine`. Il ne modifie pas la branche `main` ni les fichiers Kotlin Android.

## Démarrer
Depuis `n8n-engine/`, avec Python 3.10+ et aucune dépendance externe :

```sh
python -m unittest -v
python tool_catalog.py
```

Le service écoute par défaut sur `127.0.0.1:8768`. Données persistées dans `data/catalog/`. **Définis obligatoirement `IBRA_CATALOG_TOKEN` avant d'utiliser les routes POST** : sans jeton, `/install`, `/run` et `/rollback` répondent `503 IBRA_CATALOG_TOKEN_REQUIRED`. Avec un jeton configuré, chaque POST exige `Authorization: Bearer <jeton>`. Pour une écoute hors localhost, le jeton reste obligatoire.

## API
- `GET /health` : santé du service.
- `GET /tools` : liste des outils versionnés.
- `POST /install` : reçoit `name`, `code`, `tests` (3 à 20 cas input/expected) et `tuteur_review` (approved booléen, reasons et risks listes). Refuse l'installation si la syntaxe, un test ou la revue échoue.
- `POST /run` : reçoit `name` et `data`, exécute la version active avec l'interpréteur AST restreint.
- `POST /rollback` : réactive la version antérieure la plus récente sans effacer les versions conservées.

Exemple de corps JSON pour `POST /install` :

```json
{
  "name": "double_value",
  "capabilities": [],
  "code": "def run(data):\n    return data['value'] * 2\n",
  "tests": [
    {"input": {"value": 1}, "expected": 2},
    {"input": {"value": 3}, "expected": 6},
    {"input": {"value": -2}, "expected": -4}
  ],
  "tuteur_review": {
    "approved": true,
    "reasons": ["La logique et les cas fournis ont été examinés"],
    "risks": ["Une valeur non numérique fera échouer l'outil"],
    "capabilities": []
  }
}
```

## Limites de sécurité
La revue TUTEUR transmise à `/install` est une déclaration de confiance fournie par l'appelant ; le service ne peut pas prouver à lui seul qu'un agent TUTEUR indépendant l'a réellement produite. En production, il faudra relier ce point à une revue signée par le cycle d'évolution, avec un secret partagé ou un mécanisme de signature. L'interpréteur restreint réduit la surface d'attaque mais n'est pas un sandbox système complet. Il accepte désormais des boucles `for`, des compréhensions de liste à un seul niveau, `range()`, les fonctions de texte `lower`, `upper`, `strip`, `replace`, `split`, `join`, et les fonctions de collection `sorted`, `all`, `any`. Les opérations sur collections sont limitées à 1 000 éléments, avec un budget global de 5 000 étapes, une profondeur de bloc de 20 et des entrées/sorties de 300 Ko maximum. Les boucles `while`, les compréhensions imbriquées, les imports, l'accès aux attributs, les fichiers et le réseau restent interdits. Le service n'installe jamais de paquets.

Le catalogue est implémenté et testé en CI ; il n'est pas encore démarré sur le téléphone ni relié au workflow n8n actif.


## Permissions et broker de capacités

Le catalogue accepte maintenant un champ optionnel capabilities dans POST /install. Il est enregistré dans chaque version. Pour toute permission non vide, tuteur_review.capabilities doit correspondre exactement à la liste demandée ; sinon l'installation échoue. Les versions anciennes sans ce champ n'obtiennent aucune permission implicite.

Voir README.md, section « Capability broker » pour le service capability_broker.py, les routes fichiers/JSON/CSV/connecteurs, les limites et la configuration. Le broker est un service distinct, localhost uniquement, avec un jeton obligatoire. Les connecteurs externes sont désactivés par défaut et doivent être définis explicitement par l'administrateur dans IBRA_CONNECTORS_JSON. Le code généré n'a pas d'accès direct au réseau ou au système de fichiers : seul l'orchestrateur de confiance peut appeler le broker avec un outil dont la version active détient la permission.
