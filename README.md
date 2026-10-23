# Сканер (рабочее имя)

Офлайн-сканер документов для Android: камера или галерея → обрезка по краям листа → «белый фон без серости» → многостраничный PDF → «Поделиться». Без аккаунта, рекламы и интернета.

## Сборка

APK собирает GitHub Actions при каждом push: вкладка **Actions** → последний запуск → артефакт `skaner-debug-apk`.

Локально: Android Studio (JDK 17, Android SDK 35) → открыть папку → Run, или `./gradlew assembleDebug`.

## Структура

- `app/src/main/java/app/skaner/cv` — поиск листа, перспектива, фильтры (OpenCV)
- `app/src/main/java/app/skaner/pdf` — писатель PDF (JPEG без перекодирования)
- `app/src/main/java/app/skaner/data` — Room и файлы страниц
- `app/src/main/java/app/skaner/ui` — экраны Compose
- `proto/` — прототип алгоритмов на Python с тестами (`python3 proto/test_pipeline.py`)
- `replica/` — recon, архитектура, матрица функций, журнал сборки

## Релизы

Релиз на GitHub создаётся автоматически при сборке ветки `main`, если для текущей версии его ещё нет:

1. В `app/build.gradle.kts` поднять `versionCode` (+1) и `versionName` (например, `0.7.0`).
2. По желанию — описание в `release-notes/v0.7.0.md` (иначе GitHub соберёт список изменений сам).
3. Отправить коммит в `main`. Сборка создаст тег `v0.7.0` и релиз с обоими APK.

Коммиты без смены версии новых релизов не создают.
