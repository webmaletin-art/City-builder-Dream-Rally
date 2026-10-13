# Especificación del motor de construcción (implementación propia)

Estado: **implementado y probado** (`core/`, 37 pruebas). Basado en: diseño propio + hechos públicos (ver `docs/research/urban-builder-analysis.md`). **No** se derivó de la APK de SimCity BuildIt (no se analizó), así que no se afirma equivalencia con el juego original.

## 1. Arquitectura
`:core` (Kotlin JVM puro, sin Android) = lógica; `:app` = vista/gestos. La geometría visual (`ModelSpec`) está separada de la lógica (`BuildingSpec`: huella, reglas, categoría): cambiar el modelo no cambia la colocación (`Catalog.withModel`, probado en `replacingAModelKeepsLogicAndExportsTheNewGlb`).

## 2. Coordenadas, rotaciones, colisiones
- Celda de 8 m; sector 16×16. Vértice (x,y) = esquina noroeste de la celda (x,y). +x este, +y sur (= +Z glTF/Godot).
- Edificio: celda ancla = esquina mínima de su huella; `rot` 0..3 (cuartos de vuelta, giros impares intercambian ancho/profundo). Frente del modelo = +Z local; con rot 0/1/2/3 mira a S/E/N/O.
- Colisión: ocupación por celda (`HashMap`); una arista vial choca con una huella si pasa por su interior (axial: ambas celdas vecinas dentro; diagonal: celda dentro). Isla de rotonda (4×4 celdas) reservada.

## 3. Carreteras
- Modelo lógico: grafo de aristas elementales entre vértices vecinos (8 direcciones), tipo, sentido, grupo (rotonda). IDs deterministas (`e_ax_ay_bx_by`).
- Trazado: el recorrido del dedo se redondea al vértice más cercano; saltos se rellenan con pasos elementales (`City.expandPath`); inválidos se omiten y se informan (fuera de terreno, sobre edificio, cruce de diagonales sin vértice).
- Conexión: nodos compartidos ⇒ cruces e intersecciones automáticos (`RoadGraph`: segmento = cadena entre nodos de grado≠2 o cambio de atributos; T/cruz/complejo/entrada de rotonda).
- **Curvas**: la topología es de rejilla; la geometría **exportada y mostrada** redondea las esquinas con arcos Bézier de radio ≤ 1 celda (`Curves.fillet`). Diferencia: no hay curvas de radio libre.
- Deshacer/rehacer por operaciones atómicas (`Op`).

## 4. Manzanas y parcelas (derivadas, nunca fijas)
`BlockBuilder` sobre la red vial real:
1. Celdas edificables = desbloqueadas, sin diagonal vial, fuera de islas de rotonda.
2. Manzana = componente 4-conexa; una arista vial entre dos celdas las separa. `open` si toca terreno bloqueado o el borde de la ventana.
3. Parcelas: lotes ≤3×3 pegados a la calle (frente = primer lado con calle en orden S,E,N,O; una diagonal vial cuenta como calle), luego interior sin frente. Cubren cada celda exactamente una vez (probado).
4. Se recalculan al editar (`City.blocks(rect)`), por lo que añadir/quitar calles divide/une manzanas (probado). Id de parcela `p_x_y`, de manzana `blk_x_y` (primera celda).

## 5. Colocación
`canPlace`: terreno desbloqueado, sin solape, sin vía en la huella, no isla, rotación permitida, reglas `needsRoad`/`frontOnRoad` (aviso, o bloqueo con `strict`). **Ajuste a la calle** (`alignToRoad`): ±3 celdas × 4 giros, posición válida más cercana con el frente sobre una carretera. Mover/girar/reemplazar/eliminar/mejorar son operaciones deshacibles.

## 6. Catálogo basado en datos
`BuildingSpec` (JSON): id, categoría, huella, `PlacementRules` (needsRoad, frontOnRoad, strict, allowedRotations, maxSlopeM), `ModelSpec` (procedural | glb + materiales por GLB), `appearances` (nivel/variante → modelo), `display`, `meta`. Se amplía con `catalog_ext/catalog.json` sin tocar el editor. El GLB propio debe: ir centrado en su huella, base en y=0, metros, texturas embebidas; el exportador avisa si excede/descentra.

## 7. Cámara y táctil
Isométrica 2.5D (diamante 2:1), zoom por pellizco (14–220 px/celda), desplazamiento con dos dedos o herramienta ✋, giro de vista en pasos de 90°. Un dedo: según herramienta (trazar calle, ghost de edificio, borrar). Un segundo dedo cancela el gesto de un dedo en curso, así la cámara no corrompe un trazado.

## 8. Diferencias con SimCity BuildIt (lo que no se puede afirmar igual)
Sin economía/misiones/tiempos; rejilla de 8 direcciones con esquinas redondeadas; lotes de hasta 3×3; estética propia; cámara sin inclinación libre. **Sin análisis de la APK original**: tamaños, reglas de parcelas y trazado del juego real son desconocidos para este proyecto.
