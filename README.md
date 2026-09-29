# calendar-events-api

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

## Le feed

Deux vues complémentaires, construites par la même collection :

```
                 ┌──────────────────────────────────┐
                 │      tous les événements         │
                 └──────────────────────────────────┘
                     │                        │
    participantIds   │                        │  participantIds
    CONTIENT userId  ▼                        ▼  NE CONTIENT PAS userId
        ┌────────────────────┐      ┌────────────────────┐
        │  /me/subscribed    │      │     /me/feed       │
        │  « mon calendrier »│      │  « à découvrir »   │
        └────────────────────┘      └────────────────────┘
```

L'abonnement est un simple `toggle` : le même endpoint inscrit ou désinscrit selon l'état courant, ce qui évite au client de connaître l'état avant d'agir.

---

## API

Préfixées par `/events-service`, exposées sur `/api/v1/events-service/**`. L'organisateur et le participant sont toujours déduits du claim `businessId` du JWT.

| Méthode | Route | Description |
|---|---|---|
| `GET` | `/events/me/subscribed` | Événements auxquels l'utilisateur est inscrit |
| `GET` | `/events/me/feed` | Événements auxquels il n'est pas inscrit |
| `GET` | `/events/{id}` | Détail d'un événement |
| `POST` | `/events` | Crée un événement (201) |
| `POST` | `/events/{id}/subscribe` | Inscrit ou désinscrit l'utilisateur |
| `DELETE` | `/events/{id}` | Supprime un événement (204) |

### Contrat d'entrée

```java
public class EventRequest {
    @NotBlank(message = "Title is required")    private String title;
    @NotNull (message = "Date is required")     private Instant startDate;
                                                private Instant endDate;
    @NotBlank(message = "Location is required") private String location;
                                                private String image;
                                                private String description;
    private boolean subscribeByDefault;   // inscrit l'organisateur à la création
}
```

Les dates sont des `Instant` — stockage en UTC, conversion en fuseau local à l'affichage.

### Modèle de domaine

```java
public class Event {
    private String id;
    private String title;
    private String organizerId;
    private Instant startDate;
    private Instant endDate;
    private String location;
    private String image;
    private String description;
    private List<String> participantIds;
}
```

---

## Gestion des erreurs

Un `@RestControllerAdvice` traduit les échecs de validation en 400, avec le détail par champ :

```json
{ "title": "Title is required", "location": "Location is required" }
```

---

## Configuration

| Variable | Description |
|---|---|
| `MONGODB_URI` | URI de connexion MongoDB |
| `KEYCLOAK_ISSUER_URI` | Issuer public — validation de l'émetteur |
| `KEYCLOAK_INTERNAL_JWK_SET_URI` | JWKS interne — récupération des clés |

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

- **`toggleSubscription` n'est pas atomique.** La séquence lecture → modification de la liste → sauvegarde expose à une perte de mise à jour si deux inscriptions se croisent. À remplacer par `$addToSet` / `$pull` côté MongoDB.
- **Aucune pagination.** `/me/feed` et `/me/subscribed` renvoient l'intégralité du résultat.
- **Le modèle de domaine est anémique.** `Event` est un POJO à setters sans invariant : rien n'interdit `endDate` antérieure à `startDate`, et la logique de création (`organizerId`, `subscribeByDefault`) vit dans le contrôleur au lieu d'une fabrique de domaine.
- **`EventResponse` n'expose pas `participantIds`**, ce qui empêche le client de connaître l'état d'abonnement à la lecture.
- **Pas de gestion d'erreurs métier** : contrairement à `calendar-users-api` et `calendar-social-api`, seule la validation est traitée.
- **Le Dockerfile installe Maven via `apk`** alors que le wrapper `mvnw` est présent dans le dépôt — build non reproductible, à aligner sur les autres services.
