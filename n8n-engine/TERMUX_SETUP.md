# Installation de test sur Termux / Debian

Ce guide ne modifie pas l'application Android existante. Utiliser uniquement la branche `n8n-python-engine`.

## 1. Récupérer les fichiers

Télécharger le ZIP de cette branche depuis GitHub, extraire le dossier `n8n-engine` et le placer dans un dossier de travail sur l'appareil. Ne pas fusionner avec la branche `main`.

## 2. Vérifier le moteur sans appeler Gemini

Depuis le dossier `n8n-engine` :

```sh
python --version
python -m unittest -v
```

Tous les tests doivent passer avant la suite.

## 3. Configurer Gemini sans écrire la clé dans un fichier

Dans le même terminal Debian qui servira à lancer le service :

```sh
read -s -p "Clé Gemini (saisie masquée) : " GEMINI_API_KEY
export GEMINI_API_KEY
printf '\\n'
```

Ne coller la clé ni dans GitHub, ni dans un fichier Python, ni dans un message. La variable reste disponible uniquement dans ce shell et ses processus enfants. Si n8n est démarré depuis un autre shell, il ne récupérera pas automatiquement cette variable.

Pour la recherche Exa, optionnelle :

```sh
read -s -p "Clé Exa (Entrée pour ignorer) : " EXA_API_KEY
export EXA_API_KEY
printf '\\n'
```

## 4. Démarrer l'API d'évolution

Dans ce même environnement, depuis le dossier `n8n-engine` :

```sh
python evolution_service.py
```

Laisser ce processus tourner. Depuis un autre terminal du même environnement, vérifier :

```sh
python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8767/health').read().decode())"
```

## 5. Importer le workflow dans n8n

Importer `IBRA-Evolution-48h.n8n.json` dans n8n. Tester d'abord le nœud Manual Trigger après avoir vérifié que l'API est lancée. Le workflow appelle `http://127.0.0.1:8767/run`.

Si n8n et l'API ne partagent pas le même environnement réseau, `127.0.0.1` ne désignera pas le même service : il faudra configurer un réseau local sécurisé. Ne pas exposer l'API publiquement.

Activer le workflow seulement après un test manuel réussi et l'inspection du fichier de proposition produit dans `n8n-engine/evolution-data/proposals/`.

## Limites

- Gemini et Exa ne peuvent être réellement testés qu'avec des clés configurées et des quotas disponibles.
- Les propositions sont archivées, mais jamais automatiquement installées ou fusionnées dans le moteur actif.
- Le service doit rester en marche pour que le Schedule Trigger puisse l'appeler.
- Le moteur AST restreint n'est pas une sandbox système complète.
