# Streamio Cast

Cliente Android propio, estilo Stremio, para ver contenido de los addons de Stremio
(Cinemeta, Torrentio…) y **enviarlo a un Chromecast**. Nace porque la app oficial de
Android quitó el botón de transmitir. Una sola app, adaptable a celular y tablet.

Código desde cero: Kotlin, Jetpack Compose (Material 3), OkHttp y Coil.

## Estado

| Etapa | Qué incluye | Estado |
|---|---|---|
| 1 | Base de la app: inicio con catálogos, búsqueda, detalle (temporadas y episodios), login con cuenta Stremio, addons por URL | ✅ escrita |
| 2 | Streams de Torrentio con filtro 1080p H.264 + audio compatible con Chromecast 2ª gen | pendiente |
| 3 | Motor de torrents (descarga secuencial + servidor HTTP local) y reproductor Media3 | pendiente |
| 4 | Google Cast SDK: botón de transmitir y controles | pendiente |
| 5 | Continuar viendo, subtítulos, pulido de tablet | pendiente |

## Compilar

El APK se compila en GitHub Actions (no hace falta Android Studio):

1. Entra a la pestaña **Actions** del repo → última corrida de **Build APK**.
2. Descarga el artefacto `streamio-cast-debug-apk` (contiene `app-debug.apk`).
3. Instálalo en el celular (hay que permitir "instalar apps desconocidas").

Para compilar en local: JDK 17, Android SDK 35 y `gradle :app:assembleDebug` (Gradle 8.9).

## Uso

- **Ajustes → Cuenta de Stremio**: inicia sesión para importar tus addons. Si tu cuenta
  es de Facebook o Apple, usa la opción de abajo.
- **Ajustes → Añadir addon**: pega la URL del `manifest.json` (o un enlace `stremio://`).
  Para Torrentio, configúralo en torrentio.strem.fun y copia la URL final.
- Sin cuenta ni addons, la app instala Cinemeta para tener catálogos desde el primer arranque.
