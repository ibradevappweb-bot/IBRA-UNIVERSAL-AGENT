# IBRA Capability Broker

## Objectif

Service local séparé qui applique les permissions enregistrées dans la version active du catalogue. Le code généré reste dans l'interpréteur AST restreint : il ne peut pas appeler directement le système de fichiers, urllib ou des modules externes. L'orchestrateur de confiance appelle le broker lorsqu'une tâche a besoin d'une capacité.

## Démarrage

Depuis n8n-engine, Python 3.10+ et bibliothèque standard uniquement :

    export IBRA_CATALOG_TOKEN='secret-du-catalogue'
    export IBRA_CAPABILITY_TOKEN='secret-different-pour-le-broker'
    python tool_catalog.py

Dans un second terminal :

    python capability_broker.py

Le broker refuse de démarrer si IBRA_CAPABILITY_TOKEN est absent. Il écoute sur 127.0.0.1:8769 et refuse les interfaces réseau externes. Toutes les routes du broker nécessitent Authorization: Bearer <IBRA_CAPABILITY_TOKEN>. N'inscris jamais ces secrets dans le dépôt ou dans une conversation.

## Permissions disponibles

- file.read : lire un fichier texte UTF-8 depuis l'espace de travail.
- file.write : écrire un fichier texte UTF-8 dans l'espace de travail.
- data.json : sélection/renommage de clés JSON et filtre d'égalité de tableaux d'objets.
- data.csv : sélection/renommage de colonnes CSV et filtre d'égalité.
- connector:<id> : appeler seulement les méthodes et chemins explicitement configurés pour ce connecteur.

Toutes les versions installées ont un champ capabilities. Les outils déjà présents sans ce champ ont zéro permission. Les permissions fournies à l'installation doivent être identiques à tuteur_review.capabilities. Les tests et la revue TUTEUR restent obligatoires. Un appel sans permission est refusé avant l'accès au fichier ou au réseau.

## Routes

Toutes les requêtes POST ont un corps JSON avec le champ tool contenant le nom d'un outil du catalogue.

- POST /file/read : { "tool": "file_agent", "path": "inbox/source.txt" }
- POST /file/write : { "tool": "file_agent", "path": "out/result.txt", "content": "texte" }
- POST /data/json : { "tool": "json_agent", "operation": "select", "data": {"name":"Ada","age":31}, "fields": ["name"] }
- POST /data/csv : { "tool": "csv_agent", "csv": "name,region\nAda,Conakry\n", "select": ["name"] }
- POST /connector/request : { "tool": "api_agent", "connector": "example_api", "method": "GET", "path": "/status" }

Le broker répond avec JSON. Les corps et réponses sont limités à 300 Ko. Les CSV sont limités à 5 000 lignes et 200 colonnes.

## Connecteurs externes

Aucun connecteur n'est configuré par défaut. IBRA_CONNECTORS_JSON doit être défini par l'administrateur dans l'environnement du processus. Exemple de structure (valeurs fictives) :

    export IBRA_CONNECTORS_JSON='{"example_api":{"base_url":"https://api.example.com/v1","methods":["GET","POST"],"paths":["/status","/submit"],"token_env":"IBRA_CONNECTOR_EXAMPLE_API_TOKEN","token_header":"Authorization","token_prefix":"Bearer "}}'
    export IBRA_CONNECTOR_EXAMPLE_API_TOKEN='secret-fourni-par-le-service'

La configuration lie un identifiant à une base HTTPS, des méthodes autorisées et une liste de chemins exacts. Aucun URL arbitraire, paramètre query, chemin non listé ou redirection n'est accepté. Les secrets sont lus depuis l'environnement et ne sont jamais envoyés dans les journaux par le broker. Pour une API utilisant un en-tête de clé, token_header peut être par exemple x-api-key et token_prefix peut être une chaîne vide.

Le domaine doit être vérifié par l'administrateur. L'allowlist ne remplace pas les règles réseau ou les protections contre les changements DNS : ne configure que des hôtes de confiance. Le broker limite le délai à 10 secondes et la réponse à 300 Ko.

## Limites

- Le broker est écrit, mais il n'est pas encore relié automatiquement au workflow n8n actif sur le téléphone.
- Les actions externes sont invoquées par l'orchestrateur de confiance, pas directement par du code généré.
- La revue TUTEUR reste une évaluation de modèle et non une preuve formelle.
- Le service est prévu pour localhost ; ne le publie pas sur Internet.
