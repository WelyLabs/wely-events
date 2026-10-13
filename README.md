# wely-events

Service de gestion des **événements et des abonnements** de la plateforme [Wely Calendar](https://github.com/WelyLabs/wely-platform).

C'est le service le plus simple du projet, et celui dont l'architecture hexagonale est la plus lisible : trois couches, un port, un adaptateur.

---

## Rôle

| | |
|---|---|
| **Port** | 8086 |
| **Préfixe** | `/events-service` (exposé via la gateway sur `/api/v1/events-service/**`) |
| **Base** | MongoDB (driver réactif) |

---

## Stack

Java 25 · Spring Boot 4 · WebFlux · Spring Data MongoDB réactif · MapStruct · Bean Validation · Lombok

---

## Architecture

```
                 ┌──────────────────────────────────────────┐
  HTTP           │             application/                 │
  ──────────────▶│  rest/    EventController                │
                 │  dtos/    EventRequest · EventResponse   │
                 │  mappers/ EventRestMapper (MapStruct)    │
                 └────────────────────┬─────────────────────┘
                                      │
                 ┌────────────────────▼─────────────────────┐
                 │                domain/                   │
                 │   models/   Event                        │
                 │   services/ EventService (POJO)          │
                 │   ports/    EventRepository              │
                 └────────────────────▲─────────────────────┘
                                      │
                 ┌────────────────────┴─────────────────────┐
                 │            infrastructure/               │
                 │   MongoEventRepositoryAdapter            │
                 │     → ReactiveEventMongoRepository       │
                 │   EventPersistenceMapper (MapStruct)     │
                 └────────────────────┬─────────────────────┘
                                      ▼
                                   MongoDB
```

### Trois modèles, trois responsabilités

C'est le service où la séparation est la plus visible, et c'est délibéré :

| Modèle | Couche | Rôle |
|---|---|---|
| `EventRequest` | application | Contrat d'entrée, porte les contraintes de validation |
| `EventResponse` | application | Contrat de sortie — **n'expose pas `participantIds`** |
| `Event` | domain | Modèle métier, indépendant du transport et du stockage |
| `EventEntity` | infrastructure | Document MongoDB |

Deux mappers MapStruct assurent les traductions (`EventRestMapper`, `EventPersistenceMapper`). Le coût est réel — quatre classes pour une même notion — mais chaque couche peut évoluer sans contaminer les autres : ajouter un champ de stockage n'impose pas de l'exposer dans l'API.

---

## Visibilité et feed

Chaque événement a une **visibilité**, choisie à la création :

| Visibilité | Qui le voit, en plus de ceux qui y participent |
|---|---|
| `PUBLIC` | Tout le monde |
| `FRIENDS` *(défaut)* | Les amis de l'organisateur |
| `PRIVATE` | Personne |

Un événement enregistré avant l'existence de ce champ n'en a pas : il compte comme `FRIENDS` (`Event.effectiveVisibility()`), sans migration.

```
                 ┌──────────────────────────────────┐
                 │      tous les événements         │
                 └──────────────────────────────────┘
                     │                        │
    participantIds   │                        │  pas inscrit, ET : à moi,
    CONTIENT userId  ▼                        ▼  PUBLIC, ou FRIENDS d'un ami
        ┌────────────────────┐      ┌────────────────────┐
        │  /me/subscribed    │      │     /me/feed       │
        │  « mon calendrier »│      │  « à découvrir »   │
        └────────────────────┘      └────────────────────┘
```

Le filtre du feed est **dans la requête Mongo** (`ReactiveEventMongoRepository.findFeed`), avec la liste d'amis lue dans `wely-social` : rien n'est chargé puis trié en mémoire. Si `wely-social` est injoignable, le feed se réduit aux événements publics et aux siens plutôt que d'échouer — il ne montre rien que l'amitié aurait été nécessaire pour voir.

La même règle (`Event.isVisibleTo`) protège `GET /events/{id}` et l'inscription : un événement qu'on ne peut pas voir répond **404**, pour ne pas révéler qu'il existe, et on ne peut pas s'y inscrire en devinant son id. Se désinscrire reste toujours possible.

L'abonnement est un simple `toggle` : le même endpoint inscrit ou désinscrit selon l'état courant, ce qui évite au client de connaître l'état avant d'agir.

---

## API

Préfixées par `/events-service`, exposées sur `/api/v1/events-service/**`. L'organisateur et le participant sont toujours déduits du claim `businessId` du JWT.

| Méthode | Route | Description |
|---|---|---|
| `GET` | `/events/me/subscribed` | Événements auxquels l'utilisateur est inscrit |
| `GET` | `/events/me/feed` | Événements visibles auxquels il n'est pas inscrit |
| `GET` | `/events/me/summary` | Le résumé de la page de profil : sorties de l'année et du mois (heure de Paris), événements organisés, lieux, 3 prochaines sorties, 6 derniers souvenirs, les 5 personnes avec qui l'on sort le plus, les lieux les plus fréquentés et le solde de chaque événement aux comptes non soldés. Calculé uniquement sur les événements de l'appelant |
| `GET` | `/events/{id}` | Détail d'un événement, s'il est visible (404 sinon) |
| `GET` | `/events/{id}/participants` | Participants d'un événement visible (404 sinon) : `count`, `friendsGoingIds` (les amis de l'appelant parmi eux) et `participantIds`, la liste complète, réservée à l'organisateur et aux participants (vide pour les autres) |
| `GET` | `/events/map?south=&west=&north=&east=` | Événements à venir que l'appelant peut voir, dans la zone affichée par la carte « Explorer » : requête `$geoWithin` sur l'index `2dsphere`, 200 au plus, les plus proches dans le temps d'abord. Zone de 90° au plus de côté (400 sinon) |
| `POST` | `/events` | Crée un événement (201). `visibility` facultatif, `FRIENDS` par défaut |
| `POST` | `/events/{id}/subscribe` | Inscrit ou désinscrit l'utilisateur |
| `DELETE` | `/events/{id}` | Supprime un événement (204) |
| `POST` | `/events/me/free-slots` | Créneaux où l'utilisateur et des amis sont tous libres (voir plus bas) |
| `GET` | `/events/users/{friendId}/calendar?from=&to=` | Calendrier d'un ami, dévoilé selon la proximité (voir plus bas) |
| `GET` | `/events/{id}/expenses` | Dépenses partagées de l'événement, les plus récentes d'abord (membres seulement, 404 sinon) |
| `POST` | `/events/{id}/expenses` | Ajoute une dépense (201) |
| `PUT` | `/events/{id}/expenses/{expenseId}` | Modifie une dépense : son auteur ou l'organisateur (403 sinon), `version` lue pour refuser une modification concurrente (409) |
| `DELETE` | `/events/{id}/expenses/{expenseId}` | Supprime une dépense (204), mêmes droits |
| `GET` | `/events/{id}/expenses/ledger` | Soldes de chacun et remboursements proposés |
| `POST` | `/events/{id}/expenses/settlements` | Enregistre un remboursement (201), demandé par l'un des deux concernés |

### OpenAPI

La spécification est générée par `springdoc-openapi` et servie sans jeton :

| | |
|---|---|
| Spec JSON | `http://localhost:8086/v3/api-docs` |
| Swagger UI | `http://localhost:8086/swagger-ui.html` |

Ces deux chemins ne sont **pas** routés par la gateway, et le Service est en `ClusterIP` : rien
hors du cluster ne peut les atteindre. La documentation reste donc active en permanence — c'est
la topologie réseau qui la protège, pas un drapeau.

> Le préfixe de chemin du service est appliqué par package (`…application.rest`) et non par
> annotation. Sélectionner sur `@RestController` attrapait aussi le contrôleur de springdoc, ce
> qui déplaçait la spec en `/events-service/v3/api-docs` derrière l'authentification.

### Contrat d'entrée

```java
public class EventRequest {
    @NotBlank(message = "Title is required")    private String title;
    @NotNull (message = "Date is required")     private Instant startDate;
                                                private Instant endDate;
    @NotBlank(message = "Location is required") private String location;
    @Valid                                      private CoordinatesDto coordinates; // facultatif
                                                private String image;
                                                private String description;
    private boolean subscribeByDefault;   // inscrit l'organisateur à la création
}
```

Les dates sont des `Instant` — stockage en UTC, conversion en fuseau local à l'affichage.

`coordinates` (`{ "latitude": 45.764, "longitude": 4.8357 }`) est renseigné quand le lieu a été choisi sur la carte, absent quand il a été tapé. Hors bornes, la requête est refusée en 400. En base, le point est stocké en **GeoJSON** (`[longitude, latitude]`, dans cet ordre), ce qui permettra un index `2dsphere` pour une recherche « près de chez moi ».

### Modèle de domaine

```java
public class Event {
    private String id;
    private String title;
    private String organizerId;
    private Instant startDate;
    private Instant endDate;
    private String location;
    private GeoPoint coordinates;   // null si le lieu a été tapé
    private String image;
    private String description;
    private List<String> participantIds;
}
```

---

## Trouver un créneau avec des amis

`POST /events/me/free-slots` reçoit une période continue, des amis et une durée :

```json
{ "friendIds": ["…", "…"], "start": "2026-10-16T14:00:00Z", "end": "2026-10-18T20:00:00Z", "durationMinutes": 120 }
```

et renvoie les plages, dans cette période, où l'utilisateur **et** tous ces amis sont libres, chacune d'au moins la durée demandée :

```json
[ { "start": "2026-10-16T14:00:00Z", "end": "2026-10-17T09:00:00Z" }, … ]
```

- **Occupé** = inscrit à un événement **ou** organisateur, même non inscrit. Un événement sans fin dure 1 h, comme dans le calendrier.
- **Seulement des amis.** La liste vient de `wely-social`, interrogé avec le jeton de l'utilisateur : la réponse ne peut porter que sur ses propres amis. Un non-ami donne `EVT-BUS-003` (403), et aucun événement n'est lu.
- **Rien d'autre que des plages libres.** Ni titre, ni lieu, ni participants des événements qui occupent les autres.
- Les plages commencent et finissent au quart d'heure, comme la sélection du calendrier. 1 à 20 amis, au moins 15 min, période de 62 jours au plus — six semaines, la grille du mois (`EVT-BUS-004`).
- Le calcul (`FreeSlotFinder`) est du Java pur : fusion des plages occupées, puis trous de la période. Deux index `{participantIds, startDate}` et `{organizerId, startDate}` servent la recherche.

## Dépenses partagées

Un « Tricount » attaché à chaque événement : ses membres (organisateur et participants) notent qui a payé quoi, pour qui, et voient qui doit combien à qui.

- **Montants en centimes**, dans un `long` : un `double` ne représente pas 0,10 exactement, et une répartition doit tomber juste au centime. Devise unique `EUR` pour l'instant, stockée pour ne pas avoir à migrer le jour où il y en aura une deuxième.
- **Trois répartitions**, calculées par le modèle `Expense` (Java pur) :

  | Mode | Exemple | Arrondi |
  |---|---|---|
  | `EQUAL` | 10,00 € pour 3 → 3,34 / 3,33 / 3,33 | Les centimes restants vont un par un, dans l'ordre des identifiants : toujours la même réponse |
  | `SHARES` | 9,00 € pour un couple (2 parts) et Bob (1) → 6,00 / 3,00 | Méthode du plus fort reste |
  | `EXACT` | 12,50 € → 10,00 / 2,50 | Refusé si la somme ne fait pas le montant (`EVT-BUS-008`) |

  Quel que soit le mode, la somme des parts vaut le montant. Toute dépense qui ne tombe pas juste est refusée à la construction : elle ne peut exister nulle part.
- **Soldes et remboursements** (`Ledger`) : chacun a payé moins ce qu'il doit. Les remboursements proposés apparient le plus gros débiteur et le plus gros créancier jusqu'à épuisement — au plus n−1 virements, et toujours les mêmes pour les mêmes dépenses.
- **Un remboursement est une dépense** de type `SETTLEMENT` : payée par le débiteur, entièrement pour le créancier. Les soldes se mettent à jour par le même calcul, sans cas particulier, et le total de l'événement ne le compte pas.
- **Droits.** Hors membres, l'événement répond 404, comme partout. Payeur et bénéficiaires doivent être membres (`EVT-BUS-008`). Seul l'auteur d'une dépense ou l'organisateur la modifie ou la supprime (`EVT-BUS-009`) ; un remboursement ne se modifie pas, il se supprime.
- **Chaque requête nomme l'événement** (`findByIdAndEventId`) : l'identifiant d'une dépense d'un autre événement ne trouve rien, même pour un membre.
- **Concurrence.** `@Version` sur le document : une modification faite sur une version déjà dépassée est refusée (`EVT-BUS-010`, 409) au lieu d'écraser celle d'un autre.
- **Cascade.** Supprimer l'événement supprime ses dépenses.

## Le calendrier d'un ami

`GET /events/users/{friendId}/calendar?from=…&to=…` renvoie les événements qui occupent cet ami — ceux qu'il organise ou auxquels il participe — sur 62 jours au plus. Chaque entrée a toujours son début et sa fin ; le reste dépend de ce que l'appelant a le droit de voir (`CalendarDisclosure`) :

| Événement dans le calendrier de Bob, vu par Alice | Ami | Proche | Intime |
|---|---|---|---|
| Qu'Alice peut voir de toute façon : elle y participe, il est public, ou partagé entre amis par quelqu'un dont elle est amie (Bob lui-même, typiquement) | complet | complet | complet |
| Partagé entre amis par quelqu'un qu'Alice ne connaît pas | occupé | titre | complet |
| Privé | occupé | occupé | occupé |

« Complet » = titre, lieu, description et id ; « occupé » = début et fin seulement. Cacher dans le calendrier ce qu'Alice voit déjà dans son feed ne protégerait rien : la proximité ne joue que sur ce qu'elle ne pourrait pas voir autrement.

La proximité (ami < proche < intime) est **lue dans `wely-social`** (`GET /social-service/relationships/{friendId}`), avec le jeton de l'appelant. Ce service n'en garde aucune copie : une baisse de niveau y prend effet à la requête suivante. Pas amis : `EVT-BUS-003` (403).

## Gestion des erreurs

| Code | HTTP | Signification |
|---|---|---|
| `EVT-BUS-001` | 404 | Événement introuvable |
| `EVT-BUS-002` | 403 | Seul l'organisateur peut effectuer cette action |
| `EVT-BUS-003` | 403 | Créneau ou calendrier demandé pour quelqu'un qui n'est pas un ami |
| `EVT-BUS-004` | 400 | Recherche de créneau invalide (amis, durée ou période) |
| `EVT-BUS-005` | 400 | Demande de calendrier invalide (le sien, période inversée ou de plus de 62 jours) |
| `EVT-BUS-006` | 400 | Zone de carte invalide |
| `EVT-BUS-007` | 404 | Dépense introuvable dans cet événement |
| `EVT-BUS-008` | 400 | Dépense invalide : montant, membres, ou répartition qui ne tombe pas juste |
| `EVT-BUS-009` | 403 | Ni l'auteur de la dépense ni l'organisateur |
| `EVT-BUS-010` | 409 | Dépense modifiée entre-temps par quelqu'un d'autre |
| `EVT-TEC-001` | 502 | `wely-social` injoignable : les amitiés ne peuvent pas être vérifiées |
| `EVT-VAL-001` | 400 | Validation du corps de requête, détail par champ |
| `EVT-REQ-000` | *repris* | Chemin inconnu, méthode non autorisée |
| `EVT-TEC-000` | 500 | Erreur inattendue |

La validation du corps renvoie le détail champ par champ :

```json
{ "title": "Title is required", "location": "Location is required" }
```

Toutes les réponses d'erreur sont des `ProblemDetail` (RFC 7807), avec un `code` stable qu'un
client peut tester et un `timestamp` :

```json
{
  "type": "https://welylabs.app/problems/evt-bus-002",
  "title": "Not the organizer",
  "status": 403,
  "detail": "Only the organizer may perform this action.",
  "instance": "/events-service/events/7c9e…",
  "code": "EVT-BUS-002",
  "timestamp": "2026-09-30T19:23:43.598673Z"
}
```

> `EVT-REQ-000` rend le statut d'origine d'une `ResponseStatusException` — 404 sur un
> chemin inconnu, 405 sur une méthode non autorisée. Sans lui, le handler `Exception.class`
> les avalait toutes et **tout chemin inconnu répondait 500**. C'est le genre de défaut qu'un
> test de route nominale ne voit jamais.
---

## Configuration

| Variable | Description |
|---|---|
| `MONGODB_URI` | URI de connexion MongoDB |
| `KEYCLOAK_ISSUER_URI` | Issuer public — validation de l'émetteur |
| `KEYCLOAK_INTERNAL_JWK_SET_URI` | JWKS interne — récupération des clés |
| `SOCIAL_API_URL` | URL interne de `wely-social`, interrogé pour la liste d'amis |

---

## Démarrage

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Pour lancer **toute la plateforme** (bases, Keycloak, gateway, frontend, les quatre services) en une commande sur un Kubernetes local :

```bash
git clone https://github.com/WelyLabs/wely-gitops-infra && cd wely-gitops-infra
kubectl apply -k overlays/local --server-side
```

---

## Tests

```bash
./mvnw test
./mvnw test jacoco:report      # → target/site/jacoco/
```

8 classes de test : service de domaine, adaptateur, les deux mappers, contrôleur, configuration, gestion d'erreurs.

> Contrairement aux autres services Java du projet, celui-ci produit bien dans `target/`.

---

## Limites connues

- **Aucune pagination.** `/me/feed` et `/me/subscribed` renvoient l'intégralité du résultat.
- **Le modèle de domaine est anémique.** `Event` est un POJO à setters sans invariant : rien n'interdit `endDate` antérieure à `startDate`, et la logique de création (`organizerId`, `subscribeByDefault`) vit dans le contrôleur au lieu d'une fabrique de domaine.
- **`EventResponse` n'expose pas `participantIds`**, ce qui empêche le client de connaître l'état d'abonnement à la lecture.
- **Pas de gestion d'erreurs métier** : contrairement à `wely-users` et `wely-social`, seule la validation est traitée.
- **Le Dockerfile installe Maven via `apk`** alors que le wrapper `mvnw` est présent dans le dépôt — build non reproductible, à aligner sur les autres services.
