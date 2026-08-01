# FitTrack Coach — modularny plan implementacji dla wielu agentów

## 1. Stan wyjściowy i pierwsza akcja

**Pierwsza akcja koordynatora:** utworzyć repozytorium Git (jeśli nadal go nie ma), zapisać obecny stan jako commit `chore: snapshot initial prototype`, a następnie przydzielić każdemu agentowi osobny worktree/branch i wyłączną własność ścieżek z tego dokumentu.

W katalogu istnieje częściowy, monolityczny prototyp w `app/`:

- konfiguracja Android/Compose/Room oraz zasoby;
- encje Room, DAO, repozytorium local-first i JSON przykładowego planu;
- reguły progresji i pierwsze testy jednostkowe;
- wstępny onboarding, plany, trening, historia i ekran sugestii;
- brak Supabase, Fitbit, Gemini, synchronizacji, reminderów i pełnej weryfikacji buildu.

Prototyp **nie był kompilowany**: lokalnie nie ma JDK, Gradle ani Android SDK. Obecnie są też referencje do jeszcze nieistniejących klas `ReminderScheduler`, `BackgroundWork` i `BootReceiver`, więc build nie może być uznany za zielony. Kod jest materiałem do przeniesienia, nie finalną architekturą.

## 2. Docelowa struktura modułów

```text
fittrack/
├── app/                         # Application, DI, root navigation
├── core/
│   ├── model/                   # czyste typy Kotlin i kontrakty repozytoriów
│   ├── training/                # progresja, readiness, safety rules
│   ├── database/                # Room, encje lokalne, DAO, mapery, outbox
│   ├── network/                 # Supabase auth/sync API, DTO, token storage
│   └── designsystem/            # theme i współdzielone komponenty Compose
├── feature/
│   ├── onboarding/              # rozmowa, karta profilu, akceptacja
│   ├── plans/                   # lista, kreator, wersjonowanie planów
│   ├── workout/                 # guided flow, sets, timer, swap/skip/comments
│   ├── history/                 # historia, cardio, wykres/agregaty HR
│   ├── coach/                   # briefing/chat/debrief, pending suggestions
│   └── settings/                # logowanie, Fitbit, status synchronizacji
├── platform/
│   └── reminders/               # WorkManager, notification actions, boot/timezone
├── supabase/
│   ├── migrations/              # schemat, RLS, retention, cron
│   └── functions/               # sync, Fitbit, Gemini, suggestions, knowledge
├── knowledge/                   # kuratorowane źródła i ich manifest
├── evals/                       # ≥40 przypadków zachowania trenera
└── docs/                        # setup, decyzje, kontrakty i release checklist
```

Zależności są jednokierunkowe:

```text
app -> feature/* + platform/reminders
feature/* -> core/model + core/designsystem (+ wybrane interfejsy core/network)
core/training -> core/model
core/database -> core/model
core/network -> core/model
supabase/functions -> wspólny kontrakt JSON opisany w sekcji 4
```

Żaden moduł `feature/*` nie może zależeć od innego modułu feature. Typy Room nie mogą wyciekać poza `core/database`.

## 3. Pakiety robocze dla agentów

### M0 — Integrator i build foundation

**Właściciel:** Agent 0  
**Wyłączne ścieżki:** `settings.gradle.kts`, root `build.gradle.kts`, `gradle/`, `gradlew*`, `app/`, `docs/architecture.md`

Zadania:

- utworzyć wymienione moduły Gradle i przenieść root navigation/DI do `app`;
- przypiąć JDK 17, `compileSdk/targetSdk 36`, `minSdk 28`, AGP/Gradle/Kotlin/Compose bez dynamicznych wersji;
- utworzyć Gradle Wrapper i doprowadzić `assembleDebug` do zielonego stanu;
- dodać prosty manualny kontener zależności lub Hilt; jeden mechanizm w całym projekcie;
- pilnować, aby sekrety nigdy nie trafiały do APK ani repozytorium.

Definition of done: `./gradlew assembleDebug test lintDebug` uruchamia się z root projektu, a `app` nie zawiera logiki domenowej ani SQL.

### M1 — Model domenowy, Room i silnik treningowy

**Właściciel:** Agent 1  
**Wyłączne ścieżki:** `core/model/`, `core/training/`, `core/database/`

Zadania:

- przenieść i oczyścić obecne modele, encje, DAO, outbox oraz `ProgressionEngine`;
- wystawić interfejsy `ProfileRepository`, `PlanRepository`, `WorkoutRepository`, `HealthRepository` i implementacje Room;
- zapewnić transakcje: tylko jeden aktywny trening, snapshot planu, resume po ubiciu procesu, idempotentne UUID i tombstone;
- zaimplementować import planu z wersjonowanego JSON oraz mapery domain ↔ database;
- pokryć reguły progresji/readiness testami tabelarycznymi.

Definition of done: moduły są czystymi źródłami prawdy dla danych lokalnych; wszystkie operacje treningu działają bez internetu; testy obejmują increase/hold/decrease, brak RIR, ból, technikę i readiness.

### M2 — Android UI: onboarding, plany i guided workout

**Właściciel:** Agent 2  
**Wyłączne ścieżki:** `feature/onboarding/`, `feature/plans/`, `feature/workout/`, odpowiednie testy UI

Zadania:

- zbudować onboarding rozmowa → karta profilu → jawne zatwierdzenie;
- dodać kreator i edycję planów z seriami rozgrzewkowymi/roboczymi, rep range, RIR, rest i increment;
- zbudować guided flow: ostatnia sesja, bieżący cel, szybki set logging, timer, dokładne timestampy i resume;
- wdrożyć swap do trzech kandydatów, skip z powodem oraz komentarze ćwiczenia/treningu;
- utrzymać maksymalnie dwa kliknięcia od wpisanych wartości do zapisania serii.

Definition of done: pełny trening można ukończyć w trybie samolotowym; obrót/ubicia procesu nie kasują sesji; swap domyślnie nie zmienia szablonu.

### M3 — Android UI: historia, trener i ustawienia

**Właściciel:** Agent 3  
**Wyłączne ścieżki:** `feature/history/`, `feature/coach/`, `feature/settings/`, `core/designsystem/`

Zadania:

- pokazać historię siłową/cardio, notatki i status importu Fitbit;
- narysować HR dla treningu oraz agregaty per ćwiczenie bez diagnozowania danych;
- zbudować briefing/chat/debrief oraz karty `PENDING` z akcjami akceptuj/odrzuć;
- dodać login e-mail/hasło, połączenie/odłączenie Fitbit i status ostatniego sync;
- ujednolicić komponenty, kolory readiness, loading/error/empty states i dostępność.

Definition of done: żadna propozycja AI nie zmienia planu przed akceptacją; brak danych Fitbit jest pokazany jako neutralny; UI nie wyświetla sekretów ani tokenów.

### M4 — Android platform: auth, synchronizacja i przypomnienia

**Właściciel:** Agent 4  
**Wyłączne ścieżki:** `core/network/`, `platform/reminders/`, testy tych modułów

Zadania:

- zaimplementować Supabase password auth i trwałą sesję chronioną Android Keystore;
- wdrożyć `POST /sync/batch`, cursor pull, idempotentny push outboxa i retry z backoff;
- dodać periodic sync, natychmiastowy sync po zakończeniu treningu i 90-dniowy cleanup HR;
- zbudować przypomnienie domyślnie −60 min oraz missed follow-up +120 min;
- obsłużyć Start, Odłóż 15 min, Przełóż, reboot, zmianę czasu/strefy i Android 13+ notification permission.

Definition of done: ponowienie identycznego batcha nie dubluje rekordów; offline nie blokuje UI; wszystkie sekrety serwerowe są nieobecne w zdekompilowanym APK.

### M5 — Supabase: schemat, RLS i synchronizacja

**Właściciel:** Agent 5  
**Wyłączne ścieżki:** `supabase/migrations/`, `supabase/functions/_shared/`, `supabase/functions/sync-batch/`, `supabase/functions/suggestion-decision/`

Zadania:

- utworzyć relacyjny schemat odpowiadający typom z sekcji 4, `updated_at`, `version`, `deleted_at` i cursor zmian;
- wyłączyć public signup, wprowadzić allowlist jednego UID/e-maila i RLS `auth.uid() = user_id` na każdej tabeli;
- walidować batch, deduplikować po operation UUID i rozstrzygać single-device conflict przez revision/version;
- stosować `service_role` tylko wewnątrz funkcji, nigdy w odpowiedzi ani logach;
- dodać testy SQL/RLS: anonymous, obcy UID, właściciel, retry tego samego batcha.

Definition of done: migracje działają od pustej bazy; użytkownik widzi wyłącznie swoje rekordy; powtórzony request daje ten sam rezultat.

### M6 — Fitbit backend i HR pipeline

**Właściciel:** Agent 6  
**Wyłączne ścieżki:** `supabase/functions/fitbit-*`, migracje oznaczone prefiksem modułu, test fixtures Fitbit

Zadania:

- wdrożyć OAuth Authorization Code po stronie backendu dla aplikacji Fitbit `Personal` i bezpiecznie szyfrować refresh token;
- importować sleep, resting HR, daily steps oraz walk/run activities;
- po treningu pobierać HR 1 s dla `[startedAt−5 min, endedAt+5 min]`, maksymalnie w oknie dobowym;
- dopasować próbki do exercise timestamps oraz policzyć average/max/recovery delta;
- retry dla `401`, `429` i danych jeszcze niezsynchronizowanych; retention usuwa raw HR po 90 dniach, nie agregaty.

Definition of done: fixture opóźnionego Fitbit przechodzi z `PENDING` do `SYNCED`; brak próbek nie tworzy fałszywych zer; client secret nie opuszcza Edge Function.

### M7 — Gemini coach, wiedza i evale

**Właściciel:** Agent 7  
**Wyłączne ścieżki:** `supabase/functions/coach/`, `supabase/functions/knowledge-reindex/`, `knowledge/`, `evals/`

Zadania:

- użyć stabilnego `gemini-3.6-flash`, `thinking_level: medium` i structured output;
- przekazywać tylko minimalny profil, agregaty Fitbit, ostatnie treningi i deterministyczną rekomendację;
- indeksować wyłącznie kuratorowane dokumenty z manifestem źródła i zwracać cytaty;
- zapisywać proponowane akcje jako `PENDING`; model nie dostaje narzędzia wykonującego zapis planu;
- przygotować minimum 40 evali: progresja, sprzeczne dane, prompt injection w notatce, brak źródła i sygnały bezpieczeństwa.

Definition of done: 100% outputów przechodzi walidację schematu lub kontrolowany fallback; 100% przypadków safety blokuje progresję; odpowiedź oparta na wiedzy ma cytat albo jawnie mówi o braku źródła.

### M8 — Integracja, QA i prywatne wydanie

**Właściciel:** Agent 8 po scaleniu M0–M7  
**Wyłączne ścieżki:** `docs/`, testy E2E, konfiguracja CI; poprawki w cudzych modułach tylko przez uzgodniony commit

Zadania:

- uruchomić pełne testy, lint, debug/release build i sprawdzić release APK na fizycznym Androidzie;
- przejść E2E: onboarding → plan → reminder → offline workout → swap/skip → sync → HR → debrief → akceptacja;
- przeskanować APK/repo pod kątem Gemini key, Fitbit secret, service role i danych testowego użytkownika;
- udokumentować Supabase deploy, Fitbit app registration, Gemini File Search i podpisywanie APK;
- utworzyć release checklist, znane ograniczenia i procedurę backupu keystore.

Definition of done: podpisany APK instaluje się i przechodzi scenariusz E2E; dokumentacja prowadzi od pustego Supabase do działającej aplikacji bez domyślania się kroków.

## 4. Zamrożone kontrakty między modułami

### Typy domenowe

```kotlin
data class UserProfile(
    val id: String,
    val goal: String,
    val experience: String,
    val equipment: Set<String>,
    val schedule: String,
    val constraints: String,
    val preferences: String,
    val approved: Boolean,
    val version: Int,
)

data class PlannedSet(
    val type: SetType,
    val repMin: Int,
    val repMax: Int,
    val targetRir: Int?,
    val targetWeightKg: Double,
)

data class WorkoutSession(
    val id: String,
    val planSnapshot: WorkoutPlanSnapshot,
    val status: WorkoutStatus,
    val startedAt: Instant,
    val endedAt: Instant?,
    val note: String,
    val fitbitSyncStatus: FitbitSyncStatus,
)

data class CoachSuggestion(
    val id: String,
    val type: SuggestionType,
    val targetId: String,
    val before: String,
    val after: String,
    val reasonCode: String,
    val explanation: String,
    val signals: List<String>,
    val citations: List<Citation>,
    val confidence: Confidence,
    val status: SuggestionStatus,
)
```

Wszystkie timestampy na granicy modułu/API są UTC ISO-8601; lokalnie można przechowywać epoch millis. Ciężar domenowy to `Double` w kilogramach. Identyfikatory generuje klient jako UUID.

### API Edge Functions

| Metoda i endpoint | Wejście | Wyjście |
|---|---|---|
| `POST /sync-batch` | `batchId`, `cursor`, `operations[]` | `ackedOperationIds[]`, `changes[]`, `nextCursor` |
| `POST /fitbit-oauth-start` | `redirectUri` | `authorizeUrl` |
| `GET /fitbit-oauth-callback` | `code`, `state` | redirect `fittrack://auth/fitbit` |
| `POST /fitbit-sync` | `mode`, opcjonalnie `workoutSessionId` | `status`, `importedCounts`, `retryAfter` |
| `POST /coach` | `mode`, `message?`, `workoutSessionId?` | `message`, `citations[]`, `suggestions[]` |
| `POST /suggestion-decision` | `suggestionId`, `decision`, `reason?` | finalna sugestia i nowa wersja celu |
| `POST /knowledge-reindex` | chroniony manifest źródeł | `indexed`, `failed[]`, `storeId` |

Każdy endpoint poza OAuth callback wymaga Supabase JWT. Błędy mają wspólny kształt:

```json
{
  "error": {
    "code": "STABLE_MACHINE_CODE",
    "message": "Bezpieczny komunikat dla użytkownika",
    "retryable": false,
    "retryAfterSeconds": null
  }
}
```

### Structured output trenera

```json
{
  "message": "string",
  "citations": [{"title":"string","url":"https://...","claim":"string"}],
  "suggestions": [{
    "type": "LOAD|VOLUME|SWAP|RESCHEDULE|HOLD",
    "targetId": "uuid",
    "before": "string",
    "after": "string",
    "reasonCode": "string",
    "explanation": "string",
    "signals": ["string"],
    "confidence": "LOW|MEDIUM|HIGH"
  }],
  "safety": {"blocked": false, "reason": null}
}
```

Backend ignoruje dodatkowe pola, odrzuca brak wymaganych pól i nigdy nie wykonuje `suggestions` automatycznie.

## 5. Reguły biznesowe, których nie wolno zmieniać lokalnie

- Progresja zwiększa ciężar tylko o jeden skonfigurowany increment; bez RIR wymaga dwóch kolejnych udanych sesji.
- Pierwsze zejście poniżej minimum oznacza hold; drugie kolejne pozwala zaproponować jeden increment mniej.
- Ból, niepokojący objaw lub problem techniczny blokuje progresję; aplikacja nie diagnozuje.
- Readiness wymaga minimum 7 ważnych dni: sleep `<80%` mediany 14 dni lub RHR `≥ baseline+5` daje yellow; oba dają red.
- Kroki są kontekstem, nie samodzielnym powodem zmiany; każda zmiana pozostaje `PENDING` do jawnej decyzji użytkownika.

## 6. Kolejność pracy i scalania

### Fala 1 — można uruchomić równolegle

1. M0 tworzy moduły, wrapper i zielony pusty build.
2. M1 przygotowuje core/model, training i database według kontraktów powyżej.
3. M5 przygotowuje Supabase schema/RLS/sync bez czekania na Android UI.
4. M6 buduje Fitbit na fixture'ach, korzystając z kontraktu API.
5. M7 buduje coach/knowledge/evals, korzystając ze structured output.

### Fala 2 — po dostępności interfejsów M1

1. M2 implementuje onboarding/plans/workout na fake repositories.
2. M3 implementuje history/coach/settings na fake repositories.
3. M4 implementuje network/reminders na mock HTTP i kontraktach sekcji 4.

### Fala 3 — integracja

1. M0 scala core, potem feature, potem platform.
2. M5 scala backend core przed M6 i M7.
3. M8 podłącza Android do wdrożonego staging Supabase i prowadzi E2E.
4. Poprawki kontraktów wymagają jednego ADR w `docs/adr/` i jednoczesnej aktualizacji klienta, backendu oraz testów kontraktowych.

## 7. Zasady pracy równoległej

- Jeden agent jest właścicielem jednej ścieżki; nie edytuje root Gradle, manifestu `app` ani plików innego modułu bez uzgodnienia z M0.
- Każdy commit ma format `type(module): opis`, np. `feat(workout): add resumable guided flow`.
- Agent kończy pracę testem własnego modułu i krótkim `HANDOFF.md` w swoim katalogu: co działa, jak testować, znane ograniczenia.
- Fake repositories i fixtures są obowiązkowe, aby UI/backend rozwijały się bez wzajemnego blokowania.
- Nie logować tokenów, raw HR, notatek użytkownika ani pełnych promptów produkcyjnych; wszystkie sekrety tylko w lokalnym `.env`/Supabase secrets.

## 8. Globalne kryteria ukończenia MVP

1. Użytkownik zatwierdza profil, tworzy/importuje plan i kończy pełną sesję offline z timestampami workout/exercise/set.
2. Swap, skip, komentarze, timer i resume po ubiciu procesu zachowują się deterministycznie.
3. Fitbit po synchronizacji łączy HR z ćwiczeniami, a raw samples znikają po 90 dniach bez utraty agregatów.
4. Gemini zwraca cytowane, walidowane porady, a modyfikacja planu zachodzi wyłącznie po `ACCEPTED`.
5. `assembleRelease`, testy jednostkowe/integracyjne/UI, testy RLS, ≥40 evali i pełny E2E są zielone; podpisany prywatny APK nie zawiera sekretów.

## 9. Konfiguracja wymagana od właściciela

- Android Studio lub JDK 17 + Android SDK 36 do lokalnego builda;
- Supabase project URL i anon key dla APK oraz service role wyłącznie dla wdrożenia funkcji;
- Gemini API key i opcjonalny File Search store jako Supabase secrets;
- Fitbit Personal app: client ID, client secret i poprawny OAuth callback;
- jeden dozwolony e-mail Supabase oraz hasło ustawione poza repozytorium.
