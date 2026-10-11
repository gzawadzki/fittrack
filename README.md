# FitTrack

Osobista aplikacja treningowa na Androida (siłownia) z coachem AI. Plany, prowadzony trening i historia działają lokalnie (Room, offline-first) i synchronizują się z Supabase. Dane regeneracyjne (sen, tętno spoczynkowe, kroki, tętno z treningu) pochodzą z Health Connect. Coach opiera rekomendacje progresji na deterministycznym silniku, a model językowy (Gemini) tylko je wyjaśnia, z cytatami z lokalnej bazy wiedzy. Aplikacja jest jednoosobowa: rejestrację ogranicza allowlista.

## Funkcje

Nawigacja: **Dziś · Plany · Historia · Trener · Ustawienia**.

**Onboarding i profil**
- Cel, doświadczenie, sprzęt i harmonogram są wymagane; profil zapisuje się dopiero po ekranie podsumowania i potwierdzeniu.

**Plany treningowe**
- Edytor planu: dzień i godzina, serie rozgrzewkowe i robocze, zakres powtórzeń, docelowy RIR, ciężar, przerwa, przyrost ciężaru, wzorzec ruchu, rola mięśniowa, sprzęt, notatki.
- Wersjonowanie planów, biblioteka ćwiczeń (`app/src/main/assets/exercise_library.json`) i przykładowy plan importowany z JSON.

**Prowadzony trening i logowanie serii**
- Jeden aktywny trening naraz, wznawiany po zabiciu procesu i działający offline.
- Logowanie serii: powtórzenia, ciężar, RIR (z walidacją) oraz flagi: ból, problem z techniką, niepokojący objaw. Serię można pominąć i cofnąć.
- Podgląd ostatniego wyniku dla danego ćwiczenia, zamiana ćwiczenia (max 3 propozycje), potwierdzane wcześniejsze zakończenie treningu.

**Ekran „Dziś” i historia**
- Start treningu z planu; rekordy osobiste tylko dla trójboju (przysiad, wyciskanie, martwy ciąg).
- Historia ukończonych sesji z ćwiczeniami i seriami, wykres tętna, usuwanie treningu długim przytrzymaniem.

**Przypomnienia**
- Powiadomienie 60 min przed planowanym treningiem i przypomnienie 120 min po, jeśli trening się nie zaczął. Akcje: Rozpocznij, Odłóż 15 min, Przełóż.
- Harmonogram w czasie lokalnym, odtwarzany po restarcie telefonu oraz zmianie godziny lub strefy czasowej.

**Health Connect**
- Odczyt (tylko odczyt): kroki, sen, tętno spoczynkowe, sesje ćwiczeń, tętno.
- Tętno dopasowywane do poszczególnych ćwiczeń treningu (średnie/maks., spadek tętna); na serwer trafiają tylko agregaty.
- Wcześniejsza integracja z Fitbit Web API została zastąpiona Health Connect (`docs/adr/0001-fitbit-health-connect-pivot.md`).

**Coach AI** (zakładka Trener, funkcja `supabase/functions/coach`)
- Tryby: **briefing** przed treningiem (z lokalnym cache), **czat**, **podsumowanie** (debrief) po treningu.
- Gotowość (GREEN/YELLOW/RED) z snu i tętna spoczynkowego względem własnej linii bazowej.
- Rekomendacje progresji z deterministycznego silnika (`ProgressionEngine`): zwiększ, utrzymaj lub zmniejsz ciężar, zmniejsz objętość, zatrzymaj i przejrzyj (np. przy bólu lub niepokojącym objawie). Model tylko je wyjaśnia, a warstwa bezpieczeństwa nadpisuje sprzeczne odpowiedzi.
- Sugestie zmian planu (ciężar, objętość, zamiana ćwiczenia, przełożenie) zapisywane jako „oczekujące”; plan zmienia się dopiero po akceptacji użytkownika (`suggestion-decision`).
- Notatki z treningu traktowane jako niezaufany tekst (odporność na prompt injection). Gdy brak klucza API albo odpowiedź modelu jest nieprawidłowa, zwracana jest odpowiedź awaryjna z deterministycznej logiki.

**Baza wiedzy** (`knowledge/`)
- 7 źródeł: progresywne przeciążenie, RIR i autoregulacja, deload i zmęczenie, sen i tętno a gotowość, ból a zakwasy, landmarki objętości (MEV/MAV/MRV), zasady zamiany ćwiczeń.
- Coach cytuje tylko źródła z `knowledge/manifest.json`; nieznane cytaty są odrzucane. `knowledge-reindex` przesyła źródła (wymaga sekretu).

**Synchronizacja offline-first**
- Zmiany trafiają do lokalnej kolejki (outbox) i są wysyłane batchami do `sync-batch`: synchronizacja okresowa co 15 min (WorkManager), po treningu i ręcznie (pull-to-refresh).
- Idempotencja (cache batcha + rejestr operacji), optymistyczna kontrola wersji, soft delete, pobieranie zmian po wspólnym kursorze `sync_seq`.

**Bezpieczeństwo**
- Logowanie Supabase; sesja w `EncryptedSharedPreferences`, wyłączona z kopii zapasowej. Hasło trzymane jako `CharArray` i zerowane po użyciu.
- RLS `auth.uid() = user_id` na tabelach, bez polityk DELETE. Allowlista (`app_allowlist` + trigger na `auth.users`) blokuje rejestrację innych kont.
- Klucz service role używany wyłącznie w Edge Functions.

## Architektura

- **Android:** Kotlin, Jetpack Compose, Room, WorkManager, Health Connect; minSdk 28, compileSdk 36; ręczne DI (`app/di/AppContainer.kt`).
- **Moduły Gradle:**
  - `app`;
  - `core:model` (typy domenowe, interfejsy repozytoriów), `core:training` (progresja, gotowość, coach — czysty Kotlin), `core:database` (Room, outbox), `core:network` (Supabase, Health Connect, sync), `core:designsystem`;
  - `feature:onboarding|plans|workout|history|coach|settings`;
  - `platform:reminders`.
- **Backend (Supabase):**
  - Postgres z migracjami w `supabase/migrations`, test RLS w `supabase/tests/rls_and_sync.sql`.
  - Edge Functions (Deno): `sync-batch`, `coach` (Gemini), `suggestion-decision`, `knowledge-reindex`, `fitbit-sync` (tylko czyszczenie starych próbek HR).
- Dokumentacja: `docs/architecture.md`, `IMPLEMENTATION_PLAN.md`, pliki `HANDOFF.md` w modułach.

## Uruchomienie

Wymagania: JDK 17, Android SDK (platforma 36), opcjonalnie Deno 2 i Supabase CLI.

`local.properties` w katalogu głównym:

```properties
sdk.dir=/ścieżka/do/Android/sdk
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_ANON_KEY=<anon key>
```

Build i instalacja:

```sh
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/
./gradlew installDebug
```

Backend:

```sh
supabase link --project-ref <project-ref>
supabase db push                                        # migracje
psql "$DATABASE_URL" -f supabase/functions/_shared/allowlist_seed.sql   # ustaw swój e-mail w pliku
supabase secrets set GEMINI_API_KEY=... KNOWLEDGE_REINDEX_SECRET=...
supabase functions deploy sync-batch coach suggestion-decision knowledge-reindex fitbit-sync
```

`SUPABASE_URL`, `SUPABASE_ANON_KEY` i `SUPABASE_SERVICE_ROLE_KEY` są dostarczane funkcjom automatycznie. Bez `GEMINI_API_KEY` coach działa w trybie awaryjnym.

Evale coacha (42 przypadki, model mockowany):

```sh
deno run --allow-read evals/run.ts
# lub: node --experimental-strip-types evals/_node_shim_run.mjs   (Node 22.6+)
```

## Testy i CI

```sh
./gradlew test lintDebug                       # testy jednostkowe JVM + lint
deno check supabase/functions/*/index.ts
deno lint supabase/functions
deno test --allow-all supabase/functions
```

Testy jednostkowe obejmują m.in. `core:training`, repozytoria Room i outbox, orkiestrację synchronizacji, dopasowanie tętna, gateway coacha, ustawienia, przypomnienia i pipeline po treningu. Test RLS (`supabase/tests/rls_and_sync.sql`) wymaga lokalnego Supabase.

CI (GitHub Actions, `.github/workflows/ci.yml`) uruchamia Gradle `test lintDebug` oraz `deno check/lint/test` i evale. Workflow jest dodawany w PR #1.
