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
