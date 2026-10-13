package dev.dreamrally.citybuilder.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dreamrally.citybuilder.*
import dev.dreamrally.citybuilder.model.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: AppModel) {
    val city = vm.city ?: return
    val ctx = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val d = vm.exportUi
        if (uri != null && d is ExportUi.Done) vm.copyExportTo(d.file, ctx.contentResolver, uri)
    }
    var menu by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    // autoguardado diferido tras editar
    LaunchedEffect(vm.revision) { if (vm.revision > 0) { delay(2500); vm.saveNow() } }
    LaunchedEffect(vm.toast) { if (vm.toast != null) { delay(2600); vm.toast = null } }
    val rev = vm.revision

    Box(Modifier.fillMaxSize()) {
        EditorCanvas(vm)

        // ---- barra superior ----
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().background(Color(0xCC16222C)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.closeProject() }) { Text("‹ Ciudades") }
                Text(city.config.name, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1)
                TextButton(onClick = { vm.undo() }, enabled = city.canUndo) { Text("↶") }
                TextButton(onClick = { vm.redo() }, enabled = city.canRedo) { Text("↷") }
                TextButton(onClick = { vm.cam.rotK = (vm.cam.rotK + 3) and 3; vm.camTick++ }) { Text("⟲") }
                TextButton(onClick = { vm.cam.rotK = (vm.cam.rotK + 1) and 3; vm.camTick++ }) { Text("⟳") }
                Box { TextButton(onClick = { menu = true }) { Text("⋯") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Guardar ahora") }, { menu = false; vm.toast = if (vm.saveNow()) "Guardado" else "No hay nada que guardar" })
                        DropdownMenuItem({ Text(if (vm.grid) "Ocultar cuadrícula" else "Mostrar cuadrícula") }, { menu = false; vm.grid = !vm.grid; vm.bump() })
                        DropdownMenuItem({ Text("Centrar vista") }, { menu = false; vm.recenter() })
                        DropdownMenuItem({ Text("Bioma y relieve…") }, { menu = false; settings = true })
                    } }
            }
            Button(onClick = { vm.requestExport() }, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8F00), contentColor = Color.Black), shape = RoundedCornerShape(10.dp)) {
                Text("EXPORTAR CIUDAD PARA DREAM RALLY", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }
            val stats = "Sectores ${city.config.unlocked.size} • Edificios ${city.buildings().size} (cargados) • Tramos ${city.edges().size}"
            Text(if (vm.info.isNotEmpty()) "${vm.info}  |  $stats" else stats, fontSize = 11.sp, modifier = Modifier.background(Color(0x9916222C)).padding(horizontal = 8.dp, vertical = 2.dp))
        }

        // ---- burbuja de confirmación junto al edificio (✓ ⟳ ✗), como en el juego de referencia ----
        val g = vm.ghost
        if (g != null && (vm.tool == Tool.BUILD || vm.moving)) {
            vm.camTick; vm.revision
            val spec = (if (vm.moving) city.building(vm.selectedBuilding ?: "")?.catalogId else vm.selectedCatalogId)?.let { city.catalog[it] }
            if (spec != null) {
                val c = vm.cam.project(g.first + spec.footprintW(vm.rot) / 2f, g.second + spec.footprintD(vm.rot) / 2f, (spec.model.heightM / Grid.CELL_M).toFloat() + 0.8f)
                Row(Modifier.offset { androidx.compose.ui.unit.IntOffset((c.x - 76.dp.toPx()).toInt(), (c.y - 52.dp.toPx()).toInt()) }.background(Color(0xEE1F2F3B), RoundedCornerShape(24.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledIconButton(onClick = { vm.confirmGhost() }, enabled = vm.ghostValid && (vm.moving || vm.selectedCatalogId != null), modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF43A047))) { Text("✓", fontSize = 20.sp) }
                    FilledIconButton(onClick = { vm.autoRotate = false; vm.rot = (vm.rot + 1) and 3; vm.updateGhost(g) }, modifier = Modifier.size(44.dp)) { Text("⟳", fontSize = 20.sp) }
                    FilledIconButton(onClick = { vm.ghost = null; vm.moving = false }, modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE53935))) { Text("✕", fontSize = 18.sp) }
                }
            }
        }

        // ---- mensaje ----
        vm.toast?.let { Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(20.dp), color = Color(0xE6000000)) { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) } }

        // ---- panel inferior ----
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xE616222C)).navigationBarsPadding()) {
            when {
                vm.tool == Tool.BUILD || vm.replacing -> CatalogPanel(vm)
                vm.tool == Tool.ROAD -> RoadPanel(vm)
                vm.tool == Tool.SELECT -> SelectionPanel(vm)
                vm.tool == Tool.EXPAND -> Text("Tocá un sector azul para desbloquearlo (${city.config.unlocked.size}/${city.config.maxSectors}).", Modifier.padding(10.dp), fontSize = 13.sp)
                vm.tool == Tool.ERASE -> Text("Tocá un edificio o carretera para borrarlo. Arrastrá sobre una carretera para borrar el tramo.", Modifier.padding(10.dp), fontSize = 13.sp)
                else -> Text("Un dedo: mover • Dos dedos: zoom y desplazamiento • ⟲ ⟳: girar la vista", Modifier.padding(10.dp), fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Tool.entries.forEach { t ->
                    Column(Modifier.weight(1f).clickable { vm.setToolTo(t) }.background(if (vm.tool == t) Color(0x3366BB6A) else Color.Transparent, RoundedCornerShape(10.dp)).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t.icon, fontSize = 20.sp); Text(t.label, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    // ---- diálogos de exportación ----
    when (val e = vm.exportUi) {
        is ExportUi.Confirm -> AlertDialog(onDismissRequest = { vm.exportUi = null }, title = { Text("Exportar para Dream Rally") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (e.errors.isNotEmpty()) { Text("No se puede exportar:", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold); e.errors.forEach { Text("• $it", fontSize = 13.sp) } }
                else { Text("Se generará un paquete DreamCity (.zip) con geometría GLB, texturas, carreteras y edificios, y se verificará al terminar.")
                    if (e.warnings.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text("Avisos (${e.warnings.size}):", fontWeight = FontWeight.Bold); e.warnings.take(8).forEach { Text("• $it", fontSize = 12.sp) } } } } },
            confirmButton = { if (e.errors.isEmpty()) Button(onClick = { vm.runExport() }) { Text("Exportar") } },
            dismissButton = { TextButton(onClick = { vm.exportUi = null }) { Text(if (e.errors.isEmpty()) "Cancelar" else "Cerrar") } })
        is ExportUi.Running -> AlertDialog(onDismissRequest = {}, title = { Text("Exportando…") },
            text = { Column { Text(e.stage); Spacer(Modifier.height(10.dp)); LinearProgressIndicator(progress = { e.fraction }, Modifier.fillMaxWidth()); Text("${(e.fraction * 100).toInt()} %", fontSize = 12.sp) } },
            confirmButton = { TextButton(onClick = { vm.cancelExport() }) { Text("Cancelar") } })
        is ExportUi.Done -> AlertDialog(onDismissRequest = { vm.exportUi = null }, title = { Text("Exportación completada") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(e.summary, fontSize = 13.sp)
                if (e.warnings.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text("Avisos (${e.warnings.size}):", fontWeight = FontWeight.Bold); e.warnings.take(6).forEach { Text("• $it", fontSize = 12.sp) } } } },
            confirmButton = { Button(onClick = { save.launch(e.file.name) }) { Text("Guardar en el teléfono") } },
            dismissButton = { TextButton(onClick = { vm.exportUi = null }) { Text("Cerrar") } })
        is ExportUi.Failed -> AlertDialog(onDismissRequest = { vm.exportUi = null }, title = { Text("La exportación falló") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(e.message, fontSize = 13.sp) } }, confirmButton = { TextButton(onClick = { vm.exportUi = null }) { Text("Entendido") } })
        null -> {}
    }
    if (settings) {
        var biome by remember { mutableStateOf(city.config.biome) }; var relief by remember { mutableStateOf(city.config.relief) }
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("Bioma y relieve") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Biome.entries.forEach { FilterChip(biome == it, { biome = it }, { Text(it.label) }) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Relief.entries.forEach { FilterChip(relief == it, { relief = it }, { Text(it.label) }) } }
                Text("El relieve cambia la altura del terreno; edificios y carreteras se adaptan al exportar.", fontSize = 12.sp) } },
            confirmButton = { Button(onClick = { city.updateConfig { it.copy(biome = biome, relief = relief) }; vm.bump(); settings = false }) { Text("Aplicar") } },
            dismissButton = { TextButton(onClick = { settings = false }) { Text("Cancelar") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogPanel(vm: AppModel) {
    val replacing = vm.replacing
    Column {
        if (!replacing) Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(vm.ghostMessage ?: "Tocá o arrastrá en el mapa para ubicar", Modifier.weight(1f), fontSize = 12.sp, color = if (vm.ghost != null && !vm.ghostValid) Color(0xFFEF9A9A) else Color.White)
            FilterChip(vm.autoRotate, { vm.autoRotate = !vm.autoRotate; vm.ghost?.let { vm.updateGhost(it) } }, { Text("Auto-giro", fontSize = 11.sp) })
            TextButton(onClick = { vm.autoRotate = false; vm.rot = (vm.rot + 1) and 3; vm.ghost?.let { vm.updateGhost(it) } }) { Text("⟳ Girar") }
            Button(onClick = { vm.confirmGhost() }, enabled = vm.ghost != null && vm.ghostValid && vm.selectedCatalogId != null, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("✓ Colocar") }
        } else Text("Elegí el edificio de reemplazo", Modifier.padding(8.dp), fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.width(4.dp))
            Category.entries.forEach { c -> FilterChip(vm.category == c, { vm.category = c }, { Text(c.label, fontSize = 11.sp) }) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.catalog.byCategory(vm.category), key = { it.id }) { spec ->
                val sel = vm.selectedCatalogId == spec.id && !replacing
                Column(Modifier.width(92.dp).border(2.dp, if (sel) Color(0xFF66BB6A) else Color(0x33FFFFFF), RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                    .clickable {
                        if (replacing) { val id = vm.selectedBuilding; if (id != null) { val r = vm.city?.replaceBuilding(id, spec.id); vm.toast = if (r?.ok == true) "Reemplazado" else r?.message; vm.bump() }; vm.replacing = false }
                        else { vm.selectedCatalogId = spec.id; vm.ghost?.let { vm.updateGhost(it) } }
                    }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CatalogIcon(spec)
                    Text(spec.name, fontSize = 11.sp, maxLines = 2, lineHeight = 12.sp)
                    Text("${spec.widthCells}×${spec.depthCells}", fontSize = 10.sp, color = Color(0xFFB0BEC5))
                }
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.horizontalScroll(rememberScrollState())

@Composable
private fun CatalogIcon(spec: BuildingSpec) {
    // Previsualización ligera: un volumen isométrico dibujado en tiempo real, sin cargar ningún modelo.
    val cam = remember { Camera(zoom = 36f) }
    Canvas(Modifier.size(56.dp)) {
        cam.w = size.width; cam.h = size.height
        val w = spec.widthCells; val d = spec.depthCells
        cam.zoom = (size.width / (w + d).coerceAtLeast(2)).coerceAtMost(46f) * 1.5f
        cam.cx = w / 2f; cam.cy = d / 2f
        drawBuilding(cam, spec, 0, 0, w, d, baseH = -0.0f)
    }
}

@Composable
private fun RoadPanel(vm: AppModel) {
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoadType.entries.forEach { t -> FilterChip(vm.roadType == t, { vm.roadType = t }, { Text("${t.label} (${t.lanes}c)", fontSize = 11.sp) }) }
        }
        Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(vm.roundaboutMode, { vm.roundaboutMode = !vm.roundaboutMode }, { Text("◎ Rotonda") })
            FilterChip(vm.oneWay, { vm.oneWay = !vm.oneWay }, { Text("→ Un sentido") })
            Text(if (vm.roundaboutMode) "Tocá un vértice para crear la rotonda" else "Arrastrá el dedo para trazar. Se conecta y crea cruces solo.", fontSize = 12.sp)
        }
    }
}

@Composable
private fun SelectionPanel(vm: AppModel) {
    val c = vm.city ?: return
    val b = vm.selectedBuilding?.let { c.building(it) }
    val e = vm.selectedEdgeObj()
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (b != null) {
            val spec = c.catalog[b.catalogId]
            Text("${spec?.name ?: b.catalogId}  •  ${spec?.category?.label}", fontWeight = FontWeight.Bold)
            Text("id ${b.id} • celda ${b.x},${b.y} • giro ${b.rot * 90}° • ${spec?.widthCells}×${spec?.depthCells} celdas • ${spec?.model?.heightM} m • sector ${b.sector.name}", fontSize = 11.sp)
            if (vm.moving) Text("Arrastrá a la nueva posición y confirmá con ✓ (mover es gratis).", fontSize = 12.sp)
            else Text("Tip: arrastrá el edificio seleccionado para moverlo.", fontSize = 11.sp)
            Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (vm.moving) { OutlinedButton(onClick = { vm.rot = (vm.rot + 1) and 3; vm.ghost?.let { vm.updateGhost(it) } }) { Text("⟳") }
                    Button(onClick = { vm.confirmGhost() }, enabled = vm.ghost != null && vm.ghostValid) { Text("Mover aquí") }
                    TextButton(onClick = { vm.moving = false; vm.ghost = null }) { Text("Cancelar") } }
                else {
                    OutlinedButton(onClick = { val r = c.rotateBuilding(b.id); vm.toast = if (r.ok) "Girado" else r.message; vm.bump() }) { Text("⟳ Girar") }
                    OutlinedButton(onClick = { vm.moving = true; vm.autoRotate = false; vm.rot = b.rot; vm.updateGhost(b.x to b.y) }) { Text("Mover") }
                    OutlinedButton(onClick = { vm.replacing = true; spec?.let { vm.category = it.category } }) { Text("Reemplazar") }
                    OutlinedButton(onClick = { c.removeBuilding(b.id); vm.selectedBuilding = null; vm.bump() }) { Text("Eliminar") }
                }
            }
        } else if (e != null) {
            val chain = c.chainFrom(e)
            Text("Carretera: ${e.type.label}", fontWeight = FontWeight.Bold)
            Text("${chain.size} tramos (${chain.size * 8} m aprox.) • ${e.type.lanes} carriles • ancho ${e.type.width} m • ${if (e.oneWay != 0) "un sentido" else "doble sentido"}", fontSize = 11.sp)
            Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoadType.entries.filter { it != e.type }.forEach { t -> OutlinedButton(onClick = { c.retypeSegment(e, t); vm.bump() }) { Text("→ ${t.label}", fontSize = 11.sp) } }
                OutlinedButton(onClick = { c.removeSegment(e); vm.selectedEdge = null; vm.bump() }) { Text("Eliminar tramo") }
            }
        } else Text("Tocá un edificio o una carretera para ver su información y editarlos.", fontSize = 13.sp)
    }
}
