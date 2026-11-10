# FlappyBoards ⌨️🐦

**Flappy Bird × Monkeytype внутри IntelliJ IDEA** — плагин для JetBrains IDE (IntelliJ IDEA, WebStorm, PyCharm, GoLand и др.).

Отличие от классики: птица flap'ает **только по нажатию клавиши, которая показана на экране**, и после каждого нажатия клавиша меняется. Промах — птица проседает вниз. Соревнуйся с другими разрабами на глобальном лидерборде (Supabase).

## Механика

- `0–9` очков — только буквы `a–z`
- `10–19` — добавляются цифры
- `20+` — программистские символы: `! @ # $ % ^ & * ( ) - _ = + [ ] { } ; : ' " , . < > / ? \ | \` ~`
- Неверная клавиша → красная вспышка + птица проседает (спамить все клавиши подряд — плохая стратегия)
- На экране Game Over — точность нажатий (accuracy), как в Monkeytype
- Раскладка учитывается: печатать надо на латинице (RU-раскладка не считается — переключайся, это же тренажёр)

## Графика

Переключение в **Settings → Tools → FlappyBoards → Graphics** (работает на лету):

- **Terminal (ASCII)** — дефолт. Вся игра нарисована символами шрифтом редактора: шапка
  `flappyboards — zsh`, гуттер с номерами строк (номер строки птицы подсвечивается),
  приглушённо-зелёные трубы `█`, янтарная птица `>`. Издалека — просто ещё одна
  терминальная панель в IDE 🙂
- **Boss screen**: пока фокус в редакторе (авто-пауза), вместо игры рисуется живой
  фейковый Gradle-лог с ползущим прогресс-баром — на экране только «сборка»
- **Classic** — прежний векторный минимализм

## Управление

| Клавиша | Действие |
|---|---|
| `Ctrl+Alt+F` | открыть/активировать игру |
| показанная клавиша | flap (в игре) |
| `Space` / клик | старт из меню, рестарт после смерти |
| `L` | лидерборд |
| `O` | настройки (ник) |
| `Esc` | назад в меню |

Игра **сама встаёт на паузу**, когда фокус уходит в редактор — код не пострадает.

## Запуск для разработки

```bash
./gradlew runIde
```

Скачает IntelliJ IDEA Community 2024.2 (~1 ГБ, один раз) и запустит песочницу с плагином.

## Настройка лидерборда (Supabase)

1. Создай бесплатный проект на [supabase.com](https://supabase.com)
2. Открой **SQL Editor → New query**, вставь содержимое `supabase/schema.sql`, нажми **Run**
3. В **Project Settings → API** возьми `Project URL` и `anon public` ключ
4. Вставь их в `src/main/kotlin/dev/flappyboards/leaderboard/LeaderboardService.kt`:

```kotlin
const val SUPABASE_URL = "https://<твой-проект>.supabase.co"
const val SUPABASE_ANON_KEY = "<anon public key>"
```

Пока ключи — заглушки, плагин работает в **локальном режиме**: рекорды хранятся только на твоей машине.

### Как это работает

- Таблица `scores` с RLS: чтение и insert — всем, update/delete — никому
- Сабмит идёт через RPC `submit_score(nickname, score)` с серверными проверками:
  длина ника, границы счёта, не более 10 сабмитов в минуту с одного ника
- Лидерборд — view `leaderboard` (лучший счёт каждого игрока), плагин читает top-20
- Anon-ключ публичен по дизайну Supabase, так что это защита от мусора, а не от целенаправленного чита

## Сборка и публикация

```bash
./gradlew buildPlugin        # zip в build/distributions/
./gradlew verifyPlugin       # проверка совместимости
```

Публикация на [JetBrains Marketplace](https://plugins.jetbrains.com):

1. Заведи аккаунт и создай плагин `FlappyBoards` (Vendor → upload token)
2. `./gradlew publishPlugin -PintellijPublishToken=...` (token в `gradle.properties` или env)
3. Модерация занимает обычно 1–3 дня

Иконка плагина — `src/main/resources/icons/pluginIcon.svg` (80×80), иконка тулwindow — `flappyboards.svg` (16×16).

## Структура проекта

```
src/main/kotlin/dev/flappyboards/
├── FlappyBoardsToolWindowFactory.kt   # регистрация ToolWindow
├── OpenFlappyBoardsAction.kt          # пункт меню Tools + хоткей
├── game/
│   ├── GamePanel.kt                   # состояние, физика, ввод, механика печати
│   ├── GameTypes.kt                   # GameState, GameSnapshot, интерфейс GameRenderer
│   ├── AsciiRenderer.kt               # терминальный рендер + boss screen
│   └── VectorRenderer.kt              # классический векторный рендер
├── leaderboard/
│   ├── LeaderboardService.kt          # REST-клиент Supabase (JDK HttpClient)
│   └── LocalScores.kt                 # локальный топ-офлайн
└── settings/
    ├── FlappyBoardsSettings.kt        # persistent state (ник, публикация, режим графики)
    └── FlappyBoardsConfigurable.kt    # страница в Settings
supabase/schema.sql                    # схема БД: таблица + view + RPC + RLS
```

## Идеи на потом

- Режим «слова» (как в Monkeytype) или ввод фрагментов реального кода
- Realtime-уведомление «тебя обогнали» (Supabase Realtime over WebSocket)
- Недельные сезоны (таблица сбрасывается по cron)
- Отдельный лидерборд по точности (accuracy), а не только по очкам
