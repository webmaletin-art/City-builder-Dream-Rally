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

## 7. REA: ejecución real (sobre APK propia) y estado de la APK de referencia

**Ejecutado de verdad** (2026-10-13, Linux, Node 22.22, JDK 21):
```
git clone --depth 1 https://github.com/morluto/rea.git          # rea-agents 6.4.0
curl -L -o jadx.jar https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar
sha256sum jadx.jar   # 6e5eacf5…ff75  == SHA-256 auditado por REA (src/android/JadxRelease.ts)
npm ci --ignore-scripts && npm run build
REA_JADX_MCP_JAR=$PWD/jadx.jar node scripts/rea.mjs inspect-android-package own.apk
```
Resultado (evidence `ev_a8cf30b3…`, ver `docs/research/evidence/rea-run-own-apk.txt`): **nuestra** APK de depuración (sha256 `c19be0c4…39fd`): 6114 clases, 145 recursos, paquete `dev.dreamrally.citybuilder`, actividad `MainActivity`. Es la verificación de que la cadena REA+JADX funciona en este entorno.

**APK de SimCity BuildIt: no analizada.** El único archivo recibido es un *mod* pirata de un tercero (androforever). No lo descomprimí ni lo decompilé: no voy a descompilar una copia no autorizada del juego de EA ni a extraer sus recursos, aunque el uso sea privado. Por tanto **no hay hechos comprobados** sobre su cuadrícula, trazado de calles, manzanas ni dimensiones; todo eso quedó como diseño propio (ver `docs/design/building-engine-spec.md`).

**Cómo seguir si querés datos del juego real, sin ese archivo:**
1. *Observación*: grabá video o capturas de tu propia partida (calles en curva, cruces, colocar/mover edificios, ampliar terreno). Puedo analizar imágenes para medir proporciones de lotes, anchos de calle relativos y comportamiento visual, y ajustar parámetros (`LOT`, radios de curva, anchos) con evidencia.
2. *Tus medidas*: si conocés dimensiones/reglas, las vuelco en el catálogo y `Grid`.
3. Análisis de código del juego: no lo voy a hacer sobre el juego de EA.

Limitaciones de REA para este fin (según su documentación): decompila código y manifiesto; el código nativo (.so) queda fuera de JADX; no reconstruye formatos de recursos 3D.
