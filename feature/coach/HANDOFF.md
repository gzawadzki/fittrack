# M3 coach handoff

- `CoachRoute` zbiera stan z `CoachViewModel`; konstrukcja ViewModelu wymaga M1 `WorkoutRepository`, `PlanRepository` i centralnego `CoachGateway`.
- Sugestie oraz decyzje używają bezpośrednio `WorkoutRepository`. Akceptacja najpierw idempotentnie stosuje zmianę przez `PlanRepository`, a dopiero potem zapisuje decyzję. UI blokuje oba przyciski na czas zapisu, a ViewModel ignoruje ponowne kliknięcie tego samego ID.
- `CoachGateway` jest minimalną granicą dla `/coach/briefing`, `/coach/chat` i `/coach/debrief`. Implementacja sieciowa ma zwracać wyłącznie zwalidowane structured outputs.
- Draft wiadomości jest własnością ViewModelu: nie znika przy rekompozycji ani po błędzie; czyszczony jest dopiero po poprawnej odpowiedzi.
- Link otwiera się tylko, jeśli jest absolutnym adresem HTTPS z hostem i bez `userinfo`. Odrzucone URL-e pozostają widocznym, nieklikalnym tekstem.
- `CoachScreen` pozostaje stateless i nadaje się do preview/testów przez jawne callbacki.
