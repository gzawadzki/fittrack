# M3 history handoff

- `HistoryViewModel` korzysta bezpośrednio z M1 `WorkoutRepository`; tymczasowy `HistoryRepository` został usunięty.
- `HistoryRoute` zbiera stan ViewModelu, a `HistoryScreen` pozostaje czystym ekranem Compose.
- Ukończone sesje są mapowane razem z `ExerciseSession` i `SetLog`. Sesje aktywne i anulowane nie trafiają do historii siłowej.
- M1 nie udostępnia jeszcze centralnych kontraktów cardio ani próbek/agregatów HR. Do czasu dodania ich przez właściciela kontraktów oba obszary pokazują jawny neutralny stan i nie zgadują wartości.
- Wykres HR skaluje oś X na podstawie UTC `recordedAt`, a nie indeksu próbki; nieregularne odstępy są widoczne poprawnie.
