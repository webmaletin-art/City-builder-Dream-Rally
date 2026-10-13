# Análisis del constructor urbano (SimCity BuildIt) — estado real

Separa **hechos comprobados** de **hipótesis**, y explica qué NO se hizo y por qué.

## 1. Qué se recibió y qué se decidió

Se subieron 10 partes `.rar` de **"SimCity BuildIt Mod v1.73.2.149598 – androforever.com"**: una APK **modificada y redistribuida por un sitio de terceros** (no la oficial de Google Play).

**Decisión: el proyecto no extrae, copia ni reutiliza modelos, texturas, sonidos ni datos de SimCity BuildIt**, ni siquiera "solo para pruebas":
- Son propiedad de Electronic Arts; "no comercial" no otorga licencia de copia, y el repositorio/APK los redistribuiría.
- El archivo es una copia modificada de un tercero.
- El encargo del proyecto exige recursos "propios o autorizados" y no eludir protecciones.

Los archivos subidos **no se abrieron ni descomprimieron**; no están en el repositorio.

## 2. REA (Reverse Engineer Anything) — https://github.com/morluto/rea

| Punto | Estado |
|---|---|
| Qué es | Servidor MCP/CLI open source (MIT) para que agentes de código hagan ingeniería inversa local. **Comprobado** leyendo el repositorio. |
| Requisitos | Node.js 22.19+/24.11+/26+; para APK: **JADX headless y un JDK**; opcionales Apktool y adb. |
| Con una APK | Lee manifiesto, clases y métodos decompilados, referencias; decodifica strings/versión (Apktool); inspección de dispositivo vía adb. **No extrae mallas 3D ni clona un juego.** |
| ¿Se ejecutó aquí? | **No**, por la decisión de la sección 1. No hay resultados de REA que informar. |
| Desde Android/GitHub Actions | No está pensado para eso (requiere Node + JADX locales). |

Si tenés la APK **oficial** de tu propia cuenta de Google Play, podés ejecutar REA localmente y compartir *observaciones de mecánicas* (reglas, estructuras de datos); los recursos gráficos seguirían sin reutilizarse.

## 3. Mecánicas: hechos públicos y diseño propio

**Hechos tomados de documentación pública** (guía oficial de EA https://help.ea.com/en/articles/simcity/simcity-buildit/beginner-guide/ y guías de jugadores):

| Mecánica observable | Implementación propia |
|---|---|
| Mover un edificio: mantener y arrastrar, contorno blanco de previsualización, confirmar con tilde verde | Arrastrar el edificio seleccionado → fantasma con contorno blanco/rojo + burbuja ✓ ⟳ ✕ |
| Mover es gratis | Sin costos |
| Los edificios se auto-giran para mirar a la calle | `City.bestRotation` (probado en `AutoRotateTest`), "Auto-giro", marcador de frente |
| Los edificios necesitan conexión vial | Aviso en pantalla + `connectedToRoad` exportado |
| Las calles se mejoran de nivel | Cambiar tipo calle → avenida → autopista |
| Expansión de terreno por parcelas | Desbloqueo de sectores adyacentes |

**Sin comprobar** (sin fuente pública ni análisis): tamaño exacto de celda, reglas finas de trazado, rotondas, animaciones exactas, economía, ritmo de expansión. Se resolvieron con diseño propio:

| Parámetro propio | Valor | Estado |
|---|---|---|
| Celda | 8 m | Hipótesis de diseño |
| Sector | 16×16 celdas (128 m) | Diseño propio |
| Carreteras | Rejilla de 8 direcciones; calle/avenida/autopista/tierra; rotonda de 12 aristas, sentido único | Verificado por pruebas propias |
| Cruces | Nodos compartidos; diagonales opuestas en una celda rechazadas | Verificado |
| Guardado | Carpeta versionada, JSON por sector, escritura atómica | Verificado |

### Diferencias con el juego de referencia (documentadas)
- Sin economía, misiones ni temporizadores: es una herramienta de creación.
- Carreteras sin curvas libres; estética propia low-poly.
- Vista isométrica 2.5D en Canvas, giro en pasos de 90°, sin inclinación libre de cámara.

## 4. Recursos gráficos
- Procedencia **100 % propia**: mallas procedurales (`ModelFactory`) y texturas PNG procedurales (`Textures`).
- Catálogo ampliable con GLB autorizados (`catalog_ext`, ver README). No contiene edificios de SimCity BuildIt.

## 5. Limitaciones del análisis
- No se analizó ninguna APK ni se ejecutó REA.
- No se probó la app en dispositivo/emulador.

## 6. Implementación propia
Editor isométrico (Compose Canvas) + núcleo Kotlin (`:core`) con modelo, red vial, guardado por sectores y exportador `DreamCity Package`. Ver `README.md` y `docs/export/dream-city-package-spec.md`.
