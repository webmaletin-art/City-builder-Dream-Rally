# City Builder — Dream Rally

App Android (Kotlin + Jetpack Compose) para construir ciudades en el teléfono, guardarlas, ampliarlas y exportarlas como **DreamCity Package** (`.zip` con GLB + JSON) para importarlas en Dream Rally (Godot 4).

> Recursos 100 % propios (procedurales). **No** incluye ni usa recursos de SimCity BuildIt — ver [`docs/research/urban-builder-analysis.md`](docs/research/urban-builder-analysis.md).

## Descargar el APK
GitHub Actions compila en cada push (workflow *Android CI*):
- **Artifact** `CityBuilder-apk` en la pestaña *Actions* del repositorio (requiere sesión en GitHub).
- **Pre-release `debug-latest`** en *Releases*: descarga directa del APK de depuración y de la ciudad de ejemplo.

Si el APK debug de una build se instala sobre otra, se actualiza sin desinstalar (clave de depuración fija en `app/debug.keystore`; es una clave pública de depuración, no un secreto). Aceptá "instalar de orígenes desconocidos" en el teléfono.

## Qué hace
- Crear ciudad eligiendo **bioma** (templado, desierto, nieve, tropical, otoño) y **relieve** (llano, ondulado, colinas).
- **Experiencia tipo constructor móvil**: arrastrar el edificio con contorno blanco/rojo y burbuja ✓ ⟳ ✕, **auto-giro hacia la calle**, animación al construir, sombras, aceras e intersecciones, arrastrar el edificio seleccionado para moverlo.
- Isométrico táctil: mover (1 dedo, herramienta ✋), zoom/desplazar (2 dedos), girar vista (⟲ ⟳), cuadrícula.
- **Catálogo** de 45 edificios propios en 10 categorías: colocar (con vista previa válida/inválida), girar, mover, reemplazar, eliminar.
- **Carreteras** arrastrando el dedo: tierra, calle, avenida, autopista, un sentido, **rotondas**; intersecciones y conexiones automáticas; cambiar tipo/eliminar tramos.
- **Ampliar terreno** por sectores de 128 m (límite configurable por proyecto, 400 por defecto).
- Deshacer/rehacer, autoguardado, guardado incremental por sector, carga diferida.
- Botón **EXPORTAR CIUDAD PARA DREAM RALLY**: valida → genera GLB/texturas/JSON → ZIP → verifica → guardar en el teléfono. Con progreso y cancelación, en segundo plano.
- "Ciudad de ejemplo" lista para exportar.

## Motor de construcción
Calles como grafo con curvas, **manzanas y parcelas derivadas de la red vial**, ajuste a la calle, reglas de colocación por datos, niveles/variantes visuales y reemplazo de modelos sin tocar la lógica: ver [`docs/design/building-engine-spec.md`](docs/design/building-engine-spec.md). REA + JADX se ejecutaron sobre nuestra propia APK; **la APK de SimCity no se analizó** (ver `docs/research/urban-builder-analysis.md` §7).

## Estructura
```
core/   Kotlin JVM puro: modelo, red vial, catálogo, guardado, GLB, exportador/verificador/importador, pruebas
app/    Android: UI Compose, editor isométrico (Canvas), ViewModel
docs/   research/ (análisis), export/ (especificación del formato)
examples/demo-city.dreamcity.zip   paquete real generado por el exportador
.github/workflows/android.yml
```
Decisión técnica: **sin motor 3D** (ni Filament ni libGDX); el editor es isométrico 2.5D en Canvas, y el 3D real (GLB) se genera solo al exportar. Menos dependencias y buen rendimiento en GPU Mali; a cambio no hay vista 3D libre dentro de la app.

## Compilar
```
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
./gradlew :core:run --args="$PWD/mi-ciudad.zip"     # regenera la ciudad de ejemplo
```
JDK 17+, Android SDK 34.

## APK de distribución (firma)
`assembleRelease` firma solo si existen los secretos de GitHub `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (o `release.properties` local, ignorado por git). Sin ellos solo se produce `app-release-unsigned.apk` y el workflow lo indica. Generá la clave una vez, guardala fuera del repo y reutilizala siempre para poder actualizar sin desinstalar:
`keytool -genkeypair -v -keystore release.jks -alias citybuilder -keyalg RSA -keysize 2048 -validity 36500` → `base64 -w0 release.jks`.

## Catálogo ampliado (modelos GLB propios/autorizados)
Poné `catalog.json` (formato de `metadata/catalog.json` de un paquete exportado) y los GLB en `Android/data/dev.dreamrally.citybuilder/files/catalog_ext/`. Una entrada con `"model": {"kind":"glb","glbPath":"models/x.glb", ...}` se copia al paquete. Requisitos: GLB 2.0 con **texturas embebidas**. En el editor se muestra como caja de su huella/altura (la app no renderiza GLB). Si falta el GLB, la exportación se bloquea con un error claro.

## Estado de verificación (honesto)
| Cosa | Estado |
|---|---|
| Pruebas del núcleo (`:core:test`, 37) y de cámara (`:app:testDebugUnitTest`, 3) | Ejecutadas y pasan localmente |
| Compilación APK debug/release | Compila localmente |
| Los 53 GLB del ejemplo | Cargan en un lector independiente (trimesh) |
| UI en dispositivo/emulador | **No probada** (sin emulador en el entorno de desarrollo). Esperá fallos de usabilidad en la primera prueba |
| FPS en Mali-G57 | **No medidos** |
| Importador Godot | **No implementado** (solo especificado) |

## Limitaciones conocidas
- Carreteras sobre rejilla (rectas y diagonales a 45°); sin curvas libres salvo rotondas. Sin puentes/túneles (`elevation` siempre sigue el terreno).
- Colisiones, tráfico y físicas: no incluidos (responsabilidad de Dream Rally).
- Ciudad "grande" ≠ ilimitada: sectores cargados bajo demanda, pero exportar mantiene en memoria los datos ligeros de todos los sectores y el ZIP en disco temporal; el límite lo ponen RAM y almacenamiento. No se midió cuántos sectores soporta un teléfono.
- El relieve solo afecta a la altura (sin edición manual de terreno ni agua).
- Con relieve fuerte, las carreteras pueden atravesar levemente el terreno entre vértices.
