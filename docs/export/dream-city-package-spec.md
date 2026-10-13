# DreamCity Package — especificación v1.0.0 (schemaVersion 1)

Contrato entre **City Builder** (exportador) y el futuro importador de **Dream Rally (Godot 4)**.
Ejemplo real y verificado: [`examples/demo-city.dreamcity.zip`](../../examples/demo-city.dreamcity.zip) (9 sectores, 116 edificios, 36 segmentos, 83 archivos).

> Un ZIP no se importa solo en Godot: Dream Rally necesita un importador propio que lea `manifest.json`, cargue los GLB y cree nodos/estructuras (ver §9).

## 1. Convenciones

- Unidades: **metros**; ángulos en **grados**. Escala global 1.0.
- Sistema de coordenadas: **diestro, Y arriba, +X este, +Z sur** — idéntico a glTF y a Godot (no hay que convertir ejes).
- Celda de edición = 8 m. Sector = 16×16 celdas = 128 m. El origen (0,0) es la esquina noroeste de `sec_0_0`.
- Rutas internas: relativas a la raíz del ZIP, `/` como separador, sin `..`, sin rutas absolutas, sin `\`, sin `:`.
- IDs estables (se conservan entre exportaciones del mismo proyecto): edificios `b<N>`, nodos viales `n_<x>_<y>` (celda), aristas `e_<ax>_<ay>_<bx>_<by>`, sectores `sec_<x>_<y>`, rotondas `rb<N>`. Los ids de **segmento** (`seg_<arista menor>`) son estables mientras no cambie la topología del tramo.
- Orden del ZIP: `manifest.json` primero; sin marcas de tiempo (exportación reproducible byte a byte).

## 2. Estructura

```
manifest.json
city.json
roads.json
buildings.json
materials/materials.json
terrain/sec_<x>_<y>.json            alturas por sector
geometry/models/<catalogId>.glb     un modelo por tipo de edificio (instanciado N veces)
geometry/sectors/sec_<x>_<y>_terrain.glb
geometry/sectors/sec_<x>_<y>_roads.glb
textures/*.png                      solo las usadas; los GLB las referencian por URI relativa
metadata/catalog.json               especificaciones de los edificios usados
metadata/export-report.json
metadata/README.txt
```
Las texturas **no se duplican**: viven en `textures/` y los GLB las referencian con `../../textures/<n>.png` (glTF `images[].uri`). Un GLB de terceros (modelo de catálogo ampliado) debe traer sus texturas **embebidas**.

## 3. manifest.json

Campos: `format` ("DreamCityPackage"), `packageVersion`, `schemaVersion`, `cityId`, `name`, `generator`, `dimensions`, `units`, `coordinateSystem`, `entryPoints`, `compatibility`, `dependencies`, `files[]` (`path`, `sha256`, `size` de **todos** los archivos excepto el propio manifiesto).

```json
{ "format": "DreamCityPackage", "packageVersion": "1.0.0", "schemaVersion": 1,
  "cityId": "demo-city", "name": "Ciudad de prueba",
  "dimensions": { "sectors": 9, "widthMeters": 384.0, "depthMeters": 384.0 },
  "units": { "length": "meters", "angle": "degrees", "scale": 1.0 },
  "coordinateSystem": { "handedness": "right", "up": "+Y", "east": "+X", "south": "+Z", "gltfCompatible": true },
  "compatibility": { "gltf": "2.0", "godotMinimum": "4.2", "minReaderSchemaVersion": 1 },
  "files": [ { "path": "buildings.json", "sha256": "bacb4f…", "size": 233775 } ] }
```
Un lector debe **rechazar** `schemaVersion` mayor al que soporta. Cambios compatibles hacia atrás solo añaden campos; cambios incompatibles incrementan `schemaVersion`.

## 4. city.json

Configuración general, límites (`bounds.minMeters/maxMeters` en [x,z]), `globalTransform`, `terrain` (bioma, relieve, semilla, amplitud), `mapConfig` (lado de conducción `right`) y `sectors[]` con las rutas de terreno/geometría de cada uno. Los sectores no desbloqueados no existen aunque caigan dentro de `bounds`.

```json
{ "sectors": [ { "id": "sec_-1_-1", "sx": -1, "sy": -1, "originMeters": [-128.0, -128.0], "sizeMeters": 128.0,
    "terrainHeights": "terrain/sec_-1_-1.json", "terrainGeometry": "geometry/sectors/sec_-1_-1_terrain.glb",
    "roadsGeometry": "geometry/sectors/sec_-1_-1_roads.glb", "buildingCount": 32, "roadSegmentCount": 6 } ],
  "terrain": { "biome": "temperate", "relief": "gentle", "reliefSeed": 42, "reliefAmplitudeMeters": 3.0 } }
```
Biomas: `temperate, desert, snow, tropical, autumn`. Relieves: `flat, gentle, hills`.

## 5. terrain/sec_x_y.json y terreno GLB

`heights` = matriz 17×17 (filas por z/celda y, columnas por x), metros, vértices cada 8 m, compartidos con los sectores vecinos. El GLB del terreno usa esos mismos vértices (2 triángulos por celda, diagonal v00–v11). Carreteras y edificios se apoyan en estas alturas.

## 6. roads.json

- `nodes[]`: `id, cell, position[x,y,z], sector, degree, kind (endpoint|through|intersection), segments[]`.
- `segments[]`: `id, type (dirt|street|avenue|highway), sector, startNode, endNode, nodeIds[], edgeIds[], polyline[[x,y,z]…], lengthMeters, widthMeters, lanes, oneWay, loop, elevation{mode:"terrain_following",bridge,tunnel,liftMeters}, connections{atStart[],atEnd[]}, intersections[], roundabout, geometry ("<glb>#<nombre de nodo glTF>"), gps{roadClass,speedLimitKmh,surface}, traffic{lanesForward,lanesBackward,laneWidthMeters}`.
  - Si `oneWay` es true, `polyline`/`nodeIds` van **en sentido de circulación**.
  - La polilínea es la geometría de conducción para generar `Path3D`/carriles/GPS; la malla visible está en el GLB del sector, en el nodo glTF cuyo `name` es el id del segmento.
- `intersections[]`: `id, node, kind (T|cross|complex|roundabout_entry), position, segments[], roundabout`.
- `roundabouts[]`: `id, centerCell, centerPosition, type, direction ("counterclockwise_from_above"), islandGeometry`.

```json
{ "id": "seg_-10_0_-9_0", "type": "avenue", "startNode": "n_-16_0", "endNode": "n_-8_0",
  "lengthMeters": 64.0, "widthMeters": 8.0, "lanes": 4, "oneWay": false,
  "connections": { "atStart": [], "atEnd": ["seg_-1_0_0_0", "seg_-8_-1_-8_0", "seg_-8_0_-8_1"] },
  "gps": { "roadClass": "primary", "speedLimitKmh": 60, "surface": "asphalt" },
  "geometry": "geometry/sectors/sec_-1_0_roads.glb#seg_-10_0_-9_0" }
```
Nodos extra en el GLB de carreteras: `n_<x>_<y>` (parches de unión) e `island_<rbId>` (isla de rotonda).
Las velocidades y clases son valores por tipo de vía definidos por la app (no datos medidos); el importador puede reemplazarlos.

## 7. buildings.json

Por edificio: `id, catalogId, name, buildingType, category, sector, cell[x,y], rot (0..3), position[x,y,z], rotationYDegrees, scale, dimensions{width,depth,height}, footprint{widthCells,depthCells,cells,polygon}, model, materials[], connectedToRoad, meta{}`.
- `position` = centro de la huella a la altura mínima del terreno bajo la huella. El modelo GLB está centrado en su huella (origen = centro, base a y=0) y trae 3 m de cimentación bajo el suelo para esconder pendientes.
- `rotationYDegrees` = giro alrededor de +Y con la regla de la mano derecha. En Godot: `node.rotation_degrees.y = rotationYDegrees`.
- **Frente del edificio**: el lado **+Z local** del modelo. Con `rot` 0/1/2/3 mira a sur/este/norte/oeste (+Z, +X, −Z, −X).
- `dimensions` es la huella sin girar; `footprint` ya viene girada.

## 8. materials/materials.json y texturas

Materiales PBR metal-rough: `name, baseColorFactor, baseColorTexture (ruta o null), roughness, metallic, doubleSided, textureWrap:"repeat"`. El factor se **multiplica** por la textura. Los GLB ya los incluyen; este archivo permite reconstruirlos o reemplazarlos por materiales propios de Dream Rally. Texturas: PNG RGBA 64×64 repetibles (fachadas, tejados, asfalto con marcas, suelo por bioma).

## 9. Guía para el importador de Godot 4 (pendiente en Dream Rally)

1. Abrir ZIP (`ZIPReader`), leer `manifest.json`; verificar `schemaVersion`, y `sha256`/`size` de cada archivo.
2. Extraer a `user://cities/<cityId>/` conservando rutas (los GLB resuelven texturas por ruta relativa).
3. Por sector: `GLTFDocument.append_from_file(...)` del terreno y las carreteras → añadir como hijos de un `Node3D` "City".
4. `buildings.json`: cargar cada `model` una vez (cache), instanciar `position` + `rotation_degrees.y`.
5. `roads.json`: crear `Path3D`/`Curve3D` desde `polyline`; usar `connections`/`intersections` para el grafo de tráfico y GPS; colisión: `StaticBody3D` con `ConcavePolygonShape3D` del terreno/carreteras (no incluida en el paquete).
6. `terrain/*.json` sirve para `HeightMapShape3D` o muestreo de altura.
No hay físicas, colisiones ni tráfico en el paquete: son responsabilidad del importador.

## 10. Verificación (implementada y probada)

`PackageVerifier` comprueba: ZIP legible (CRC), rutas seguras, manifiesto y esquema, tamaño y SHA-256 de cada archivo, archivos no declarados, JSON válidos, sectores y materiales referenciados, texturas existentes y PNG válidos, cabeceras GLB, URIs de textura de cada GLB, modelos de cada edificio, nodos/segmentos/intersecciones coherentes y que cada segmento exista como nodo en su GLB. `PackageImporter` reconstruye edificios y red vial desde el ZIP (prueba de ida y vuelta).
