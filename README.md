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

The feed filter runs **in the Mongo query** (`ReactiveEventMongoRepository.findFeed`), with the friend list read from the local `friendships` projection (see "Friendships without a synchronous call"): nothing is loaded and then sorted in memory, and the feed keeps working while `wely-social` is down.

La même règle (`Event.isVisibleTo`) protège `GET /events/{id}` et l'inscription : un événement qu'on ne peut pas voir répond **404**, pour ne pas révéler qu'il existe, et on ne peut pas s'y inscrire en devinant son id. Se désinscrire reste toujours possible.

### Friends going

Every event response carries who of the caller's friends go to it, computed from the `friendships` projection with one read per response (`ViewerFriends`):

```json
"friendsGoing": [ { "userId": "…", "closeness": "INTIMATE" } ],
"friendsGoingCount": 7,
"affinity": 3
```

- `friendsGoing`: the caller's friends among the organizer and the participants, INTIMATE first, then CLOSE, then FRIEND, the organizer then join order within a level, 5 at most. `friendsGoingCount` counts them all. `affinity` is the closest level among them: 0 none, 1 FRIEND, 2 CLOSE, 3 INTIMATE. The caller is never among their own friends.
- The three fields are `null` on a BIRTHDAY, which nobody goes to (and which no listing but `/me/birthdays` serves).
- Every endpoint that returns events fills them: feed, subscribed, map, by id, community, create, update, subscribe, leave, cover, polls, conflicts, reschedule, a friend's overview and the profile summary.
- `GET /events/me/feed?rank=affinity` sorts on `affinity` descending, then `startDate` ascending, in memory after the Mongo filter: the feed is not paginated (see "Known limits").

L'abonnement est un simple `toggle` : le même endpoint inscrit ou désinscrit selon l'état courant, ce qui évite au client de connaître l'état avant d'agir.

---

## API

Préfixées par `/events-service`, exposées sur `/api/v1/events-service/**`. L'organisateur et le participant sont toujours déduits du claim `businessId` du JWT.

| Méthode | Route | Description |
|---|---|---|
| `GET` | `/events/me/subscribed` | Événements auxquels l'utilisateur est inscrit |
| `GET` | `/events/me/feed?rank=affinity` | Visible events the caller does not take part in. With `rank=affinity` (any case), the events their closest friends go to first, then the soonest; without it, the usual order; any other rank is `EVT-BUS-035` (400) |
| `GET` | `/events/me/summary` | Le résumé de la page de profil : sorties de l'année et du mois (heure de Paris), événements organisés, lieux, 3 prochaines sorties, 6 derniers souvenirs, les 5 personnes avec qui l'on sort le plus, les lieux les plus fréquentés et le solde de chaque événement aux comptes non soldés. Calculé uniquement sur les événements de l'appelant. The year in review adds `newFriendsThisYear` (`[{userId, since}]`, friends since 1 January in Paris and still friends, most recent first), `upgradesThisYear` (`[{userId, closeness, at}]`, each current friend's latest move closer this year, from `friendship_history`, oldest first) and `topFriendsThisYear` (`[{userId, outingsTogether}]`, the 3 current friends on most of this year's outings) |
| `GET` | `/events/{id}` | Détail d'un événement, s'il est visible (404 sinon) |
| `GET` | `/events/{id}/participants` | Participants d'un événement visible (404 sinon) : `count`, `friendsGoingIds` (les amis de l'appelant parmi eux) et `participantIds`, la liste complète, réservée à l'organisateur et aux participants (vide pour les autres) |
| `GET` | `/events/map?south=&west=&north=&east=` | Événements à venir que l'appelant peut voir, dans la zone affichée par la carte « Explorer » : requête `$geoWithin` sur l'index `2dsphere`, 200 au plus, les plus proches dans le temps d'abord. Zone de 90° au plus de côté (400 sinon) |
| `POST` | `/events` | Crée un événement (201), l'organisateur toujours inscrit. `visibility` facultatif, `FRIENDS` par défaut. Avec `candidateSlots` (2 à 5 `{start, end}`), ouvre un sondage de date (voir plus bas) |
| `GET` | `/events/me/birthdays?from=&to=` | The caller's birthday and their friends' in the range (400 days at most, `EVT-BUS-032` otherwise), by UTC date: `{userId, name, date, mine, closeness, canOrganize, surprise}`. The only endpoint that serves birthdays (see "Birthdays and surprises") |
| `GET` | `/events/me/polls` | Sondages ouverts que l'appelant organise, rejoint ou a votés |
| `PUT` | `/events/{id}/poll/ballot` | Remplace le bulletin de l'appelant, `{"answers": {"<slotId>": "YES"\|"MAYBE"\|"NO"}}` ; vide, il le retire. Renvoie le `PollResponse` |
| `POST` | `/events/{id}/poll/fix` | `{"slotId"}` : fixe la date, organisateur seulement. Inscrit les votants « Oui » de ce créneau |
| `GET` | `/events/{id}/poll/stream` | `text/event-stream` : l'état du sondage puis chaque vote, événements `poll` ; se termine une fois la date fixée |
| `POST` | `/events/{id}/subscribe` | Inscrit ou désinscrit l'utilisateur. L'organisateur ne s'y désinscrit pas (`EVT-BUS-020`, 409) : il passe par `/leave` |
| `POST` | `/events/{id}/leave` | Quitte l'événement, corps facultatif `{newOrganizerId?, deleteIfLast?}`. Répond `{deleted, event}`, `event` absent si l'événement a été supprimé (voir « Quitter un événement ») |
| `DELETE` | `/events/{id}` | Supprime un événement (204), avec ses dépenses, sa couverture et ses justificatifs, et prévient wely-chat |
| `POST` | `/events/{id}/cover` | Multipart, partie `file` : remplace la couverture (organisateur seulement, 403 sinon, 404 si l'événement n'est pas visible). Renvoie l'`EventResponse`, couverture signée |
| `DELETE` | `/events/{id}/cover` | Retire la couverture (204), organisateur seulement |
| `POST` | `/events/me/free-slots` | Créneaux où l'utilisateur et des amis sont tous libres (voir plus bas) |
| `GET` | `/events/users/{friendId}/calendar?from=&to=` | Calendrier d'un ami, dévoilé selon la proximité (voir plus bas) |
| `GET` | `/events/me/circle?from=&to=` | "My circle": the busy and visible entries of all the caller's INTIMATE friends, 62 days at most (`EVT-BUS-037` otherwise), as a friend's calendar entry plus `userId` (see "A friend's calendar") |
| `GET` | `/events/users/{friendId}/overview` | La page d'un ami : nombre de sorties passées ensemble, 5 prochains événements communs, 6 derniers souvenirs communs, et les 5 prochains événements de l'ami que l'appelant peut voir et où il n'est pas encore (sondages de date ouverts compris). Réservé aux amis (`EVT-BUS-003`, 403) ; sa propre page est refusée (`EVT-BUS-005`, 400) |
| `GET` | `/events/{id}/expenses` | Dépenses partagées de l'événement, les plus récentes d'abord (membres seulement, 404 sinon) |
| `POST` | `/events/{id}/expenses` | Ajoute une dépense (201) |
| `PUT` | `/events/{id}/expenses/{expenseId}` | Modifie une dépense : son auteur ou l'organisateur (403 sinon), `version` lue pour refuser une modification concurrente (409) |
| `DELETE` | `/events/{id}/expenses/{expenseId}` | Supprime une dépense (204), mêmes droits, et son justificatif |
| `PUT` | `/events/{id}/expenses/{expenseId}/receipt` | Multipart, partie `file` : photo du justificatif, mêmes droits qu'une modification ; refusé sur un remboursement (400). Renvoie l'`ExpenseResponse`, `receiptUrl` signée |
| `DELETE` | `/events/{id}/expenses/{expenseId}/receipt` | Retire le justificatif (204), mêmes droits |
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
                                                private String description;
}
```

Pas de `subscribeByDefault` : l'organisateur est toujours inscrit, et le champ envoyé par un ancien client est ignoré.

Pas de champ `image` : une couverture ne se désigne pas par URL, elle se téléverse (voir « Photos »). Sinon n'importe qui pourrait faire charger une URL de son choix aux navigateurs des autres.

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
    private String image;           // clé wely-media (events/…), chemin servi par le web (/demo/…) ou null
    private String description;
    private List<String> participantIds;   // l'organisateur compris
    private Long groupVersion;             // lu en base seulement, pour la version de la suppression
}
```

---

## Quitter un événement

Sur le modèle d'un groupe de messagerie : les **membres** sont l'organisateur et les participants, et l'organisateur est inscrit dès la création. `POST /events/{id}/leave` :

| Qui part | Effet |
|---|---|
| Pas membre | Rien ne change, l'événement est renvoyé tel quel |
| Un participant | Retiré par un `$pull` conditionné à `organizerId ≠ lui` ; jamais le dernier, l'organisateur compte |
| L'organisateur, avec d'autres participants | `newOrganizerId` obligatoire (`EVT-BUS-020`, 409), et ce doit être un autre participant (`EVT-BUS-021`, 400). La passation est **une seule écriture** : `$set organizerId`, `$pull` de l'ancien, `$inc groupVersion` |
| L'organisateur, seul | `deleteIfLast: true` obligatoire (`EVT-BUS-022`, 409), puis suppression de l'événement, de ses dépenses et de ses photos, y compris pour un événement passé |

- **Chaque écriture est conditionnée à ce qui a été lu.** La passation ne passe que si le successeur participe encore et l'organisateur l'est toujours ; sinon la réponse est `EVT-BUS-021` si le successeur est parti, `EVT-BUS-020` autrement. La suppression du dernier membre filtre sur `participantIds` vide ou réduit à l'organisateur : si quelqu'un vient de rejoindre, rien n'est supprimé et la réponse est `EVT-BUS-020`.
- **A deleted account does not count.** Left on a past event's list (see "Account deletion"), it cannot be named successor (`EVT-BUS-021`), and the last-member deletion also matches a list holding only the organizer and deleted accounts.
- **Un client périmé ne supprime jamais par accident** : sans `deleteIfLast`, le dernier départ est refusé.
- **Un participant nommé organisateur pendant sa demande** n'est pas retiré : sa demande est réévaluée comme celle d'un organisateur.

### Suppression et chat

Supprimer un événement (par `DELETE` ou par le dernier départ) emporte son document, donc son outbox de groupe. La suppression laisse une **pierre tombale** dans la collection `event_deletions` (`{_id: eventId, title, version, deletedAt}`), avec `version` = la dernière `groupVersion` + 1, lue sur le document effectivement supprimé (`findAndRemove`).

`EventDeletionRelay` l'envoie sur le **même topic** `EVENT_GROUP_CHANGED`, avec la même clé `eventId`, donc après les listes de membres précédentes sur la même partition : `{deleted: true, memberIds: [], organizerId: null, version}`. La pierre tombale est effacée une fois l'envoi confirmé. Un échec d'écriture de la pierre tombale est journalisé sans faire échouer la suppression, déjà faite. Les messages de groupe ordinaires portent `deleted: false`.

---

## Account deletion

`wely-users` publishes `USER_DELETED` (`{userId, deletedAt}`) when an account is deleted. This service consumes it (group `events-service-group`; a new group starts from the earliest retained record) and erases what it holds of that account. The consumer is a `Function<Flux<Message<byte[]>>, Mono<Void>>`: Spring Cloud Stream subscribes to it itself, and the code never calls `subscribe()`. A record that cannot be read, or whose `userId` is not a canonical UUID, is logged and skipped; a failing erasure is retried 5 times (1 s to 30 s), then logged, and the stream goes on.

`AccountErasureService` runs these steps in order. Each one is conditioned on the state it changes, so a replay changes nothing:

1. **Marker** in `deleted_users` (`{_id: businessId, deletedAt, erasedAt}`), kept forever. The first recorded `deletedAt` is the reference for every later step: a replay a week later classifies the events the same way. An older record without `deletedAt` uses the processing time.
2. **The user's poll SSE streams** are closed.
3. **Ballots withdrawn from open polls**, one event at a time, with the new tally published. This runs before any hand-over, so a date fixed by the successor cannot subscribe the account again. Ballots on closed polls are kept.
4. **Events the user organizes** go to the earliest participant who is neither deleted nor a demo account. A **past** event (over before `deletedAt`, poll closed) keeps the account among its participants; an **upcoming or ongoing** event (or one with an open poll) removes it. With no possible successor, the event is **deleted with its usual cascade** (tombstone, expenses, cover, receipts). Each write is a compare-and-set, decided again up to 3 times (`EVT-TEC-006` beyond).
5. **Upcoming participations** removed in one write, which flags each group for the relay.
6. **Maybe / No answers** to upcoming community events removed.
7. **Receipts the user attached to their own expenses** deleted: the file in `wely-media` first, then the expense's key, only if it has not changed. Each expense records who attached its receipt (`receiptAddedBy`), so a receipt someone else attached, such as the organizer after the erasure, is never dropped, not even by a replay. A receipt attached before that field existed counts as the author's. Amounts and ids stay.
8. **`erasedAt`** set last: a failed step leaves it empty.

- **Pseudonym.** Past events, closed-poll ballots, past answers and expenses keep the `businessId`, which the web app labels as a deleted user. Balances stay exact.
- **Propagation.** Hand-overs and departures go through `EVENT_GROUP_CHANGED` (version bumped, `groupSynced: false`): wely-chat and wely-notifications follow without any extra call. The successor is not notified.
- **After the marker**, every write (`POST`, `PUT`, `PATCH`, `DELETE`) carried by the deleted account's still-valid token answers `EVT-BUS-025` (410), from a filter in the security chain; reads go through. The poll stream cannot be reopened either (`EVT-BUS-025`), and a stream checks the marker again once it listens, so an erasure landing between the check and the subscription still ends it. A "Yes" vote that outlived the erasure never subscribes the account when the date is fixed.
- **Birthday and friendships.** Right after the marker, the account's birthday, the surprises for it and its pending notices are erased, then every pair involving it in `friendships`, and its entries in `friendship_history`.
- **Demo accounts** (`ACCOUNT_DELETION_PROTECTED_USER_IDS`) are never erased, and never made successor automatically, since anyone can sign in to them.

### Reconciliation

Kafka is not the only path to an erasure. `DeletedUsersReconciliationLoop` pages wely-users' tombstones (`GET /user-service/internal/deleted-users`, 500 per page, header `X-Internal-Secret` from `DELETED_USERS_READER_SECRET`), on the wely-users Service directly: the gateway answers 404 for `/api/v1/*/internal/**`. The first pass runs 1 min after startup; the next one 24 h after a clean pass, or 1 h after a pass that failed or counted a failed erasure. A pass reads the next page only once the current one is reconciled, so a failed read never cuts an erasure short; a pass still running after 24 h (a hung query) is cut and retried 1 h later. Each pass runs the same `AccountErasureService` for every tombstone whose marker is missing or has no `erasedAt`, and for every tombstone deleted less than 48 h ago, even if erased, which sweeps a write that raced the marker. The tombstone's `deletedAt` is passed on, so the past/upcoming split is the one Kafka would have given. This repairs a `USER_DELETED` lost by a crash, skipped after its retries, or expired from the topic before it was read. Each pass logs one INFO line with its counts, and a failed erasure logs only the `businessId`. With a blank `USERS_API_URL` or `DELETED_USERS_READER_SECRET`, every pass fails without any call and the service runs on; `ACCOUNT_ERASURE_RECONCILIATION_ENABLED=false` turns the loop off.

---

## Photos : couvertures et justificatifs

Les fichiers passent par **`wely-media`**, service interne qui prépare l'image (redimensionnée, réencodée en JPEG, métadonnées retirées), la range dans un bucket privé et signe des URL de courte durée. Ce service n'en voit que les clés.

| Usage | Profil | Clé | Qui |
|---|---|---|---|
| Couverture d'événement | `COVER` (1600 px de large au plus) | `events/{id}/cover-{uuid}.jpg` | L'organisateur |
| Justificatif de dépense | `RECEIPT` (2000 px de côté au plus, pour rester lisible) | `events/{eventId}/receipts/{expenseId}-{uuid}.jpg` | L'auteur de la dépense ou l'organisateur |

- **10 Mo au plus** par fichier, refusés avant l'appel à `wely-media` (`EVT-BUS-012`, 413).
- **Stocker d'abord, pointer ensuite.** Le fichier est rangé, puis le document le référence par une requête filtrée sur l'événement *et* l'organisateur (couverture) ou l'événement *et* la dépense (justificatif). Si cette écriture échoue, le fichier est supprimé ; l'ancien l'est ensuite, au mieux.
- **Une signature par réponse.** Chaque réponse qui porte des événements (feed, inscrits, carte, détail, création, inscription, résumé de profil) ou des dépenses signe toutes ses clés en **un seul appel** (par paquets de 200). Si `wely-media` ne répond pas, la réponse part quand même, sans URL pour les fichiers stockés : une image manquante plutôt qu'une page en erreur.
- **`EventResponse.image`** vaut donc une URL signée (couverture téléversée), un chemin `/demo/…` que l'application web sert elle-même (événements de démonstration), ou `null`. **`ExpenseResponse.receiptUrl`** vaut une URL signée ou `null`.
- **Le justificatif fait monter la `version`** de la dépense (`$inc`). Une modification ouverte avant l'ajout du justificatif reçoit donc `EVT-BUS-010` au lieu de réécrire l'ancienne clé par-dessus la nouvelle ; la réponse de l'envoi porte la nouvelle version.
- **Suppressions au mieux.** Supprimer un événement, une dépense ou remplacer une image retire les fichiers concernés sans jamais faire échouer l'opération : au pire, un fichier orphelin reste dans le bucket.

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
- **Friends only.** The caller's friends come from the `friendships` projection, keyed by the caller's own `businessId`: the answer can only be about their own friends. A non-friend gives `EVT-BUS-003` (403), and no event is read.
- **Rien d'autre que des plages libres.** Ni titre, ni lieu, ni participants des événements qui occupent les autres.
- Les plages commencent et finissent au quart d'heure, comme la sélection du calendrier. 1 à 20 amis, au moins 15 min, période de 62 jours au plus — six semaines, la grille du mois (`EVT-BUS-004`).
- Le calcul (`FreeSlotFinder`) est du Java pur : fusion des plages occupées, puis trous de la période. Deux index `{participantIds, startDate}` et `{organizerId, startDate}` servent la recherche.

## Sondage de date

Au lieu d'imposer une date, l'organisateur propose de 2 à 5 créneaux ; ceux qui voient l'événement (même règle que pour s'inscrire) votent Oui / Peut-être / Non pour chacun, puis l'organisateur fixe la date.

- **Le sondage est embarqué dans le document de l'événement**, bulletins indexés par `businessId` : un vote est un seul `$set poll.ballots.<userId>`, deux votants ne s'écrasent jamais.
- **Fixer la date est une comparaison-échange** sur `poll.status: 'OPEN'` : une seule mise à jour déplace les dates, ferme le sondage, inscrit les votants « Oui » et marque le groupe de chat et l'annonce pour leurs relais. Une seconde fixation ne trouve rien (409).
- **Tant que le sondage est ouvert**, l'événement occupe le créneau le plus tôt, ce qui garde le tri, le fil et la carte inchangés ; en revanche il **ne rend personne indisponible** dans la recherche de créneaux libres ni dans le calendrier d'un ami.
- **Le direct passe par SSE** (`Flux<ServerSentEvent>`), avec un commentaire toutes les 20 s pour que les proxys ne coupent pas une connexion calme, et `X-Accel-Buffering: no`. Les mises à jour circulent dans un sink en mémoire par événement : une seule réplique de wely-events est supposée.
- **La date fixée part sur Kafka** (`EVENT_DATE_FIXED`, JSON `{eventId, title, start, end, organizerId}`, dates ISO-8601) par le même outbox que les groupes : le document est marqué `dateFixSynced: false` dans la mise à jour qui fixe la date, et `EventDateFixedRelay` l'envoie. wely-chat l'annonce dans le groupe.

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
- **Deleted accounts.** A deleted account already named in the event's expenses stays a valid party when an expense is updated or a settlement recorded, so its balance can still be settled. A new expense cannot name it (`EVT-BUS-008`).
- **Chaque requête nomme l'événement** (`findByIdAndEventId`) : l'identifiant d'une dépense d'un autre événement ne trouve rien, même pour un membre.
- **Concurrence.** `@Version` sur le document : une modification faite sur une version déjà dépassée est refusée (`EVT-BUS-010`, 409) au lieu d'écraser celle d'un autre.
- **Justificatif.** Une photo du ticket peut être jointe à une dépense (voir « Photos ») ; `ExpenseRequest` ne la porte pas, une modification la conserve.
- **Cascade.** Supprimer l'événement supprime ses dépenses et leurs justificatifs.

## Le calendrier d'un ami

`GET /events/users/{friendId}/calendar?from=…&to=…` renvoie les événements qui occupent cet ami — ceux qu'il organise ou auxquels il participe — sur 62 jours au plus. Chaque entrée a toujours son début et sa fin ; le reste dépend de ce que l'appelant a le droit de voir (`CalendarDisclosure`) :

| Événement dans le calendrier de Bob, vu par Alice | Ami | Proche | Intime |
|---|---|---|---|
| Qu'Alice peut voir de toute façon : elle y participe, il est public, ou partagé entre amis par quelqu'un dont elle est amie (Bob lui-même, typiquement) | complet | complet | complet |
| Partagé entre amis par quelqu'un qu'Alice ne connaît pas | occupé | titre | complet |
| Privé | occupé | occupé | occupé |

« Complet » = titre, lieu, description et id ; « occupé » = début et fin seulement. Cacher dans le calendrier ce qu'Alice voit déjà dans son feed ne protégerait rien : la proximité ne joue que sur ce qu'elle ne pourrait pas voir autrement.

The closeness (friend < close < intimate) is read from the `friendships` projection, so a downgrade takes effect once `FRIENDSHIP_CHANGED` has been consumed, a few seconds later. Not friends: `EVT-BUS-003` (403).

### My circle

`GET /events/me/circle?from=…&to=…` merges the calendars of every INTIMATE friend of the caller, read from the projection, in one `findOccupying` over all of them: `[{userId, start, end, allDay, eventId, title, location, description}]`, soonest first. Each event is disclosed as it would be in that friend's own calendar to an intimate friend (`CalendarDisclosure` with closeness INTIMATE): full, except private and community events, which stay busy. An event that keeps two intimate friends busy gives one entry per friend, so each lane is complete. Nothing hidden from the caller is ever read, not even as busy. Without an intimate friend, no event is read.

## Birthdays and surprises

- **All-day events.** `allDay: true` (in `EventRequest` and every response) means whole UTC days: `startDate` at 00:00Z of the first day, `endDate` at 00:00Z of the day after the last; a missing end makes it one day. Anything else is `EVT-BUS-034`. The `.ics` export writes them as `VALUE=DATE`.
- **Kinds.** Every event has a `kind` (`STANDARD`, `BIRTHDAY`, `SURPRISE`; absent counts as `STANDARD`) and a `honoreeId`.
- **BIRTHDAY.** One per user, `_id` `birthday-<userId>`, upserted from wely-users' `USER_BIRTHDAY` (`{userId, displayName, month, day}`, never the year): all day on the placeholder year 2000, `birthMonth`/`birthDay`, FRIENDS. It is excluded from every read query (feed, map, by id, subscribed, free slots, conflicts, friend calendar, overview, profile summary, group relay) and served only by `/me/birthdays`, so it cannot be joined, edited or deleted through the API. A 29 February falls on the 28th in common years.
- **SURPRISE.** `POST /events` with `kind: SURPRISE` and `honoreeId`; the location is optional for it only. The organizer must be a close or intimate friend of the honoree and not the honoree (`EVT-BUS-028`), the honoree must have a birthday (`EVT-BUS-029`), and one surprise per birthday: a second within 60 days answers `EVT-BUS-030` (409) with the existing id in the ProblemDetail property `eventId`, and a unique `occurrenceKey` (`honoreeId:year`) settles two creations racing. It is stored with `hiddenFrom: [honoreeId]`, and **every query filters on it in the database**: the honoree never gets it from the feed, the map, a community, a friend's calendar (not even as busy), an overview, a profile summary, its participants or `GET /events/{id}` (404, as are subscribing and leaving). It is visible to its members and to the honoree's friends, any of whom may join.
- **Notices.** A daily job (08:00 Paris time, plus an hourly catch-up from a minute after startup, never before 08:00) records `BIRTHDAY_NOTICES` for wely-notifications: `SURPRISE_PROPOSAL` to intimate friends from 21 days ahead and close friends from 14 (naming the surprise if one exists), `FRIEND_BIRTHDAY` to every friend and `BIRTHDAY_TODAY` to the honoree on the day; creating a surprise records `SURPRISE_CREATED` for the honoree's other close friends. The `birthday_notices` collection is both outbox and idempotency key (`_id` = `honoreeId:recipientId:year:type`, TTL 400 days); `BirthdayNoticeRelay` sends it, keyed by recipient.
- **Friends without a token.** The reminder job and the `SURPRISE_CREATED` fan-out run on nobody's behalf; like every user-facing path, they read the friends from the `friendships` projection, never from wely-social.

### The `friendships` projection

wely-social publishes every friendship change on `FRIENDSHIP_CHANGED` from a transactional outbox, keyed by the pair (`idA:idB`, sorted), so one pair's records stay in order on one partition: `{type, fromUserId, fromUserName, toUserId, closeness, since, version, occurredAt}`. `FriendshipChangedConsumer` (binding `friendships-in-0`, its own group `events-friendships-group`, from the earliest retained record) keeps one document per pair in `friendships`:

```
{ _id: "<idA>:<idB>", a: "<idA>", b: "<idB>", friends: true|false, closeness: "FRIEND|CLOSE|INTIMATE"|null, since, version }
```

- **Types.** `ACCEPTED`, `CLOSENESS_CHANGED` and `SNAPSHOT` (wely-social's one-time backfill of the friendships that existed before the outbox) store the pair as friends, with its closeness. `REQUESTED`, `REJECTED`, `CANCELLED` and `REMOVED` store `friends: false`: the document stays as a **version tombstone**.
- **Ordering.** A write is a conditional overwrite (`version` lower than the record's), then an insert for a new pair; the insert hitting the unique `_id` means the stored pair is as new or newer, so a record whose `version` is not above the stored one is ignored. A replay or a rebalance cannot bring back an older state, and a removal cannot be undone by a late acceptance.
- **Reads.** `friendsOf(userId)` matches `friends: true` on `a` or `b`, both indexed.
- **Deleted accounts.** A record naming an account in `deleted_users` is ignored, and the erasure deletes every pair involving the account, tombstones included, after the marker.
- **Errors.** Each record is isolated: an unreadable one, an unknown type or closeness, ids that are not two distinct canonical UUIDs, a missing `version`, or friends without a closeness is logged and skipped; a failing write is retried 5 times (1 s to 30 s), then logged, and the stream goes on. That pair is then stale until its next change.
- **Freshness.** The projection is eventually consistent with wely-social: it lags by the outbox relay and the consumer, and it is empty until wely-social's snapshot has been consumed.
- **History.** Every applied change is also appended to `friendship_history` (`{_id: "<pairId>:<version>", pairId, a, b, type, closeness, previousCloseness, at, version}`, `at` = wely-social's `occurredAt`, or the processing time without one), unique per pair and version, so a replay appends nothing. The year in review reads it; the account erasure purges it.
- **A friendship that ends.** When a record turns a pair from friends to not friends (a `REMOVED`), neither side stays in a surprise for the other: a participant is removed, and an organizer hands the surprise to the earliest remaining participant who is still the honoree's friend (and not a deleted account), or deletes it, with the usual cascade, when there is none. Each write flags the event's group, so wely-chat follows through `EVENT_GROUP_CHANGED`. The other side's FRIENDS events it never joined disappear from its feed on their own, since visibility reads the same projection; events it joined stay.
- **Order of the steps.** Deleted-account guard, staleness check against the stored version, surprise release, history, then the pair itself, last: a failure midway leaves the pair stale, so the retry runs every step again, each of which is safe to replay.

### Friendships without a synchronous call

Every request path that needs friendships — the feed, the map, `GET /events/{id}` and its participants, a friend's calendar and overview, the circle, the profile summary, `/me/birthdays`, the closeness check on surprise creation, free slots — reads the `friendships` projection through the `SocialGraph` port (`MongoSocialGraphAdapter`). Nothing calls wely-social for a friendship any more.

- **The trade-off.** A change made in wely-social reaches this service a few seconds later (outbox relay, then the consumer): a friend just accepted may not see a FRIENDS event for that long, and a friend just removed may still see one. In exchange, wely-social being down, slow or redeployed costs this service nothing: no timeout, no circuit breaker, no degraded feed.
- **Still remote.** Community membership (`CommunityDirectory`) is still asked of wely-social, with the caller's token.

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
| `EVT-BUS-011` | 400 | Couverture absente ou illisible (JPEG, PNG, GIF ou BMP) |
| `EVT-BUS-012` | 413 | Image de plus de 10 Mo (couverture ou justificatif) |
| `EVT-BUS-013` | 400 | Justificatif absent ou illisible |
| `EVT-BUS-014` | 400 | Sondage invalide : 2 à 5 créneaux distincts, à venir, qui finissent après avoir commencé |
| `EVT-BUS-015` | 409 | Pas de sondage ouvert : la date est déjà fixée |
| `EVT-BUS-016` | 400 | Réponse pour un créneau que le sondage ne propose pas |
| `EVT-BUS-017` | 400 | Nouvelle date invalide : fin avant le début, plus de 62 jours, ou dans le passé |
| `EVT-BUS-018` | 409 | Sondage de date ouvert : la date se fixe dans le sondage |
| `EVT-BUS-019` | 409 | Date modifiée entre-temps par quelqu'un d'autre |
| `EVT-BUS-020` | 409 | L'organisateur part en laissant des participants : il doit nommer son successeur (ou quelqu'un vient de rejoindre) |
| `EVT-BUS-021` | 400 | Successeur invalide : ce doit être un autre participant de l'événement |
| `EVT-BUS-022` | 409 | Dernier membre : partir supprime l'événement, à confirmer avec `deleteIfLast` |
| `EVT-BUS-025` | 410 | Account deleted: no more writes, and no reopening of a poll stream |
| `EVT-BUS-028` | 403 | Surprise refused: not a close or intimate friend of the honoree, or the honoree themselves |
| `EVT-BUS-029` | 409 | The honoree has no birthday |
| `EVT-BUS-030` | 409 | A surprise already exists for this birthday; its id is in the property `eventId` |
| `EVT-BUS-031` | 400 | Invalid kind: `BIRTHDAY` through the API, or a `SURPRISE` without `honoreeId` |
| `EVT-BUS-032` | 400 | Birthday range backwards or longer than 400 days |
| `EVT-BUS-033` | 400 | Update without a location, on anything but a surprise |
| `EVT-BUS-034` | 400 | All-day event not on whole UTC days, or with a date poll |
| `EVT-BUS-035` | 400 | Unknown feed rank: only `affinity` is known |
| `EVT-BUS-036` | 404 | Not a friend of the caller (reunions, memories) |
| `EVT-BUS-037` | 400 | Circle range backwards or longer than 62 days |
| `EVT-TEC-001` | 502 | `wely-social` unreachable: a community's membership cannot be checked (friendships never call it) |
| `EVT-TEC-002` | 502 | Kafka injoignable pour le relais des groupes (en arrière-plan, jamais renvoyé à un client) |
| `EVT-TEC-003` | 502 | `wely-media` injoignable ou mal configuré : l'image n'a pas pu être enregistrée |
| `EVT-TEC-004` | 502 | `wely-users` unreachable, or refusing the deleted-accounts listing (background only, retried at the next pass) |
| `EVT-TEC-005` | 502 | Deleted-accounts register cannot be read or written |
| `EVT-TEC-006` | 503 | Account erasure incomplete: an event kept changing (background only, retried) |
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
| `SOCIAL_API_URL` | In-cluster URL of `wely-social`, asked for community membership only; friendships come from the projection |
| `MEDIA_API_URL` | URL interne de `wely-media` (`http://localhost:8085` en profil `dev`) |
| `EVENT_DATE_FIXED_TOPIC` | Topic des dates fixées, `EVENT_DATE_FIXED` par défaut |
| `EVENT_DELETION_RELAY_ENABLED` | Relais des suppressions vers wely-chat, `true` par défaut |
| `MEDIA_API_SECRET` | Secret partagé envoyé dans `X-Internal-Secret`. Vide par défaut : le service démarre, mais les envois répondent `EVT-TEC-003` et les images sortent sans URL |
| `USER_DELETED_CONSUMER_ENABLED` | Consumes `USER_DELETED`, `true` by default |
| `FRIENDSHIP_TOPIC` | Friendship changes from wely-social, `FRIENDSHIP_CHANGED` by default; `FRIENDSHIPS_CONSUMER_ENABLED` turns the consumer off |
| `USER_BIRTHDAY_TOPIC` | Birthdays from wely-users, `USER_BIRTHDAY` by default; `USER_BIRTHDAY_CONSUMER_ENABLED` turns the consumer off |
| `BIRTHDAY_NOTICES_TOPIC` | Notices for wely-notifications, `BIRTHDAY_NOTICES` by default; `BIRTHDAY_NOTICES_RELAY_ENABLED` turns the relay off |
| `BIRTHDAY_REMINDERS_ENABLED` | Runs the daily birthday job (and `@Scheduled`), `true` by default |
| `ACCOUNT_DELETION_PROTECTED_USER_IDS` | Comma-separated `businessId`s of the demo accounts, never erased nor made successor. Empty by default |
| `USERS_API_URL` | In-cluster URL of wely-users, read by the reconciliation (`http://localhost:8082` in the `dev` profile). Empty by default: every pass then fails without a call |
| `DELETED_USERS_READER_SECRET` | Sent as `X-Internal-Secret` to wely-users' deleted-accounts listing. Empty by default: every pass then fails without a call |
| `ACCOUNT_ERASURE_RECONCILIATION_ENABLED` | Runs the reconciliation loop, `true` by default |

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
- **Le modèle de domaine est anémique.** `Event` est un POJO à setters sans invariant : rien n'interdit `endDate` antérieure à `startDate`, et l'organisateur est encore posé par le contrôleur au lieu d'une fabrique de domaine (son inscription, elle, est une règle du domaine).
- **`EventResponse` n'expose pas `participantIds`**, ce qui empêche le client de connaître l'état d'abonnement à la lecture.
- **Pas de gestion d'erreurs métier** : contrairement à `wely-users` et `wely-social`, seule la validation est traitée.
- **Le Dockerfile installe Maven via `apk`** alors que le wrapper `mvnw` est présent dans le dépôt — build non reproductible, à aligner sur les autres services.
