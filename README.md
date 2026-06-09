# AIChatApp

Kotlin CLI-чат с AI-ассистентом через DeepSeek API.

## Настройка

Создайте файл `local.properties` в корне проекта:

```properties
DEEPSEEK_API_KEY=your_api_key_here
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat
```

`local.properties` добавлен в `.gitignore`, не храните в репозитории настоящий API-ключ.

## Запуск

После изменений в коде соберите standalone-запуск:

```powershell
.\gradlew.bat installDist
```

Рекомендуемый запуск на Windows, чтобы русский текст в консоли отображался корректно:

```powershell
.\run-chat.bat
```

Если PowerShell разрешает локальные сценарии, можно использовать вариант `.ps1`:

```powershell
.\run-chat.ps1
```

Если запускаете вручную, сначала переключите консоль в UTF-8:

```powershell
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
chcp 65001
.\gradlew.bat run
```

```powershell
gradle run
```

Если используется локальный Gradle wrapper:

```powershell
.\gradlew run
```

В чате доступны команды `/help`, `/settings`, `/clear`, `/exit`.
