# Settings handoff

## Gotowe

- `SettingsRoute` i `SettingsViewModel` korzystają bezpośrednio z `core/network` `AuthRepository` i obsługują odtworzenie zapisanej sesji, login oraz logout.
- `AuthResult.Failure` jest pokazywany jako błąd akcji bez blokowania ponownego logowania; wyjątek podczas odczytu sesji ma osobny ekran retry.
- `isSubmitting` jest ustawiany przed uruchomieniem coroutine, więc blokuje wieloklik. Każdy przekazany `CharArray` hasła jest zerowany również przy błędzie, wyjątku i odrzuconym podwójnym submit.
- Pole hasła jest czyszczone w UI przed przekazaniem żądania. UI nie pokazuje tokenów, sekretów ani kluczy API.

## Luka integracyjna Fitbit

`core/network` nie udostępnia obecnie portu Fitbit OAuth/sync. Moduł nie tworzy tymczasowego repozytorium ani fikcyjnych akcji: karta pokazuje neutralny stan `UNAVAILABLE`. Po dodaniu produkcyjnego portu należy rozszerzyć ViewModel o jego stan i akcje, bez umieszczania client secret lub refresh tokenu w UI/APK.

## Podłączenie

Host przekazuje ten sam singleton `AuthRepository`, którego używa synchronizacja:

```kotlin
SettingsRoute(authRepository = appContainer.authRepository)
```
