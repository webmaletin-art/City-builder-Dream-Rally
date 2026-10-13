package dev.dreamrally.citybuilder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalViewConfiguration
import dev.dreamrally.citybuilder.AppModel
import dev.dreamrally.citybuilder.Tool
import dev.dreamrally.citybuilder.model.*

@Composable
fun EditorCanvas(vm: AppModel, modifier: Modifier = Modifier) {
    val slop = LocalViewConfiguration.current.touchSlop
    Canvas(
        modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var multi = false; var moved = false; var last = down.position; var drawing = false
                val erasePath = ArrayList<Vtx>()
                if (vm.tool == Tool.BUILD || (vm.tool == Tool.SELECT && vm.moving)) vm.updateGhost(vm.cellAt(down.position.x, down.position.y))
                if (vm.tool == Tool.ROAD && !vm.roundaboutMode) { drawing = true; vm.roadPreview = listOf(vm.vertexAt(down.position.x, down.position.y)) }
                if (vm.tool == Tool.ERASE) erasePath += vm.vertexAt(down.position.x, down.position.y)
                do {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.count { it.pressed }
                    if (pressed >= 2) {
                        if (!multi) { multi = true; if (drawing) { vm.roadPreview = emptyList(); drawing = false }; erasePath.clear() }
                        val z = ev.calculateZoom(); val p = ev.calculatePan()
                        vm.cam.zoom = (vm.cam.zoom * z).coerceIn(14f, 220f); vm.cam.panByScreen(p.x, p.y); vm.camTick++
                        ev.changes.forEach { it.consume() }
                    } else if (!multi) {
                        val c = ev.changes.firstOrNull() ?: continue
                        val pos = c.position
                        if (!moved && (pos - down.position).getDistance() > slop) moved = true
                        if (moved) {
                            when {
                                drawing -> {
                                    val v = vm.vertexAt(pos.x, pos.y); val prev = vm.roadPreview.lastOrNull()
                                    if (prev != null && v != prev) vm.roadPreview = vm.roadPreview + v
                                }
                                vm.tool == Tool.ERASE -> { val v = vm.vertexAt(pos.x, pos.y); if (erasePath.lastOrNull() != v) erasePath += v }
                                vm.tool == Tool.BUILD || (vm.tool == Tool.SELECT && vm.moving) -> vm.updateGhost(vm.cellAt(pos.x, pos.y))
                                else -> { vm.cam.panByScreen(pos.x - last.x, pos.y - last.y); vm.camTick++ }
                            }
                            c.consume()
                        }
                        last = pos
                    }
                } while (ev.changes.any { it.pressed })
                if (!multi) {
                    when {
                        drawing -> { if (moved) vm.commitRoad() else vm.roadPreview = emptyList() }
                        vm.tool == Tool.ERASE -> { if (moved) vm.eraseRoad(erasePath) else vm.eraseAt(down.position.x, down.position.y) }
                        !moved -> when (vm.tool) {
                            Tool.SELECT -> if (!vm.moving) vm.tapSelect(down.position.x, down.position.y)
                            Tool.ROAD -> if (vm.roundaboutMode) vm.placeRoundabout(down.position.x, down.position.y)
                            Tool.EXPAND -> vm.unlockAt(down.position.x, down.position.y)
                            else -> {}
                        }
                    }
                }
                vm.loadVisible()
            }
        }
    ) {
        vm.revision; vm.camTick // suscribe el dibujo a cambios
        val cam = vm.cam; cam.w = size.width; cam.h = size.height
        drawScene(vm, cam)
    }
}

private fun DrawScope.drawScene(vm: AppModel, cam: Camera) {
    val city = vm.city ?: return
    val cfg = city.config
    drawRect(Color(0xFF16222C))
    val r = cam.visibleCells(4)
    val x0 = r[0]; val y0 = r[1]; val x1 = r[2]; val y1 = r[3]
    val ground = colorOf(cfg.biome.groundRgb); val ground2 = colorOf(cfg.biome.groundRgb2)
    val relief = cfg.relief != Relief.FLAT
    fun hAt(x: Int, y: Int) = (Heights.at(cfg.relief, cfg.reliefSeed, x, y) / Grid.CELL_M).toFloat() * 0.8f

    // ---- terreno (celdas visibles de sectores desbloqueados, de atrás hacia delante) ----
    val cells = ArrayList<Long>()
    val maxCells = 2600
    outer@ for (x in x0..x1) for (y in y0..y1) {
        if (Grid.sectorKey(x, y) !in cfg.unlocked) continue
        cells += (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
        if (cells.size > maxCells) break@outer
    }
    cells.sortBy { k -> cam.depth((k shr 32).toInt() + 0.5f, k.toInt() + 0.5f) }
    val hs = FloatArray(4)
    for (k in cells) {
        val x = (k shr 32).toInt(); val y = k.toInt()
        var col = if ((x + y) and 1 == 0) ground else ground2
        if (relief) { hs[0] = hAt(x, y); hs[1] = hAt(x + 1, y); hs[2] = hAt(x + 1, y + 1); hs[3] = hAt(x, y + 1)
            val shade = 1f + (hs[0] - hs[2]) * 0.45f; col = Color((col.red * shade).coerceIn(0f, 1f), (col.green * shade).coerceIn(0f, 1f), (col.blue * shade).coerceIn(0f, 1f))
            drawCell(cam, x, y, col, hs) } else drawCell(cam, x, y, col)
    }
    // ---- cuadrícula y bordes de sector ----
    if (vm.grid && cam.zoom >= 28f && cells.size <= maxCells) {
        val gc = Color.Black.copy(alpha = 0.10f)
        for (k in cells) { val x = (k shr 32).toInt(); val y = k.toInt()
            drawLine(gc, cam.project(x.toFloat(), y.toFloat(), hAt(x, y).takeIf { relief } ?: 0f), cam.project(x + 1f, y.toFloat(), hAt(x + 1, y).takeIf { relief } ?: 0f), 1f)
            drawLine(gc, cam.project(x.toFloat(), y.toFloat(), hAt(x, y).takeIf { relief } ?: 0f), cam.project(x.toFloat(), y + 1f, hAt(x, y + 1).takeIf { relief } ?: 0f), 1f) }
    }
    val sc = Color.White.copy(alpha = 0.35f)
    for (sx in Grid.sectorOf(x0)..Grid.sectorOf(x1)) for (sy in Grid.sectorOf(y0)..Grid.sectorOf(y1)) {
        val id = SectorId(sx, sy); val ax = sx * Grid.SECTOR_CELLS.toFloat(); val ay = sy * Grid.SECTOR_CELLS.toFloat(); val s = Grid.SECTOR_CELLS.toFloat()
        if (id in cfg.unlocked) { if (cam.zoom > 20f) {
            val p = androidx.compose.ui.graphics.Path().apply { val a = cam.project(ax, ay); moveTo(a.x, a.y); val b = cam.project(ax + s, ay); lineTo(b.x, b.y); val c = cam.project(ax + s, ay + s); lineTo(c.x, c.y); val d = cam.project(ax, ay + s); lineTo(d.x, d.y); close() }
            drawPath(p, sc, style = Stroke(width = 2f)) } }
        else if (vm.tool == Tool.EXPAND && id in city.expandable()) {
            val p = androidx.compose.ui.graphics.Path().apply { val a = cam.project(ax, ay); moveTo(a.x, a.y); lineTo(cam.project(ax + s, ay).x, cam.project(ax + s, ay).y); lineTo(cam.project(ax + s, ay + s).x, cam.project(ax + s, ay + s).y); lineTo(cam.project(ax, ay + s).x, cam.project(ax, ay + s).y); close() }
            drawPath(p, Color(0xFF4FC3F7).copy(alpha = 0.25f)); drawPath(p, Color(0xFF4FC3F7), style = Stroke(width = 4f))
            drawCircle(Color(0xFF4FC3F7), cam.zoom * 0.9f, cam.project(ax + s / 2, ay + s / 2)); drawLine(Color.White, cam.project(ax + s / 2 - 1.5f, ay + s / 2) , cam.project(ax + s / 2 + 1.5f, ay + s / 2), 6f); drawLine(Color.White, cam.project(ax + s / 2, ay + s / 2 - 1.5f), cam.project(ax + s / 2, ay + s / 2 + 1.5f), 6f)
        }
    }

    // ---- carreteras ----
    val edges = city.edgesIn(x0, y0, x1, y1)
    fun vp(v: Vtx) = cam.project(v.x.toFloat(), v.y.toFloat(), if (relief) hAt(v.x, v.y) else 0f)
    val wScale = cam.zoom * 0.36f / 8f
    for (e in edges) drawLine(Color(0xFF2A2D32), vp(e.a), vp(e.b), strokeWidth = (e.type.width.toFloat() * wScale * 1.25f).coerceAtLeast(3f), cap = StrokeCap.Round)
    for (e in edges) drawLine(colorOf(e.type.rgb), vp(e.a), vp(e.b), strokeWidth = (e.type.width.toFloat() * wScale).coerceAtLeast(2f), cap = StrokeCap.Round)
    if (cam.zoom > 30f) for (e in edges) {
        if (e.type == RoadType.DIRT) continue
        drawLine(if (e.type == RoadType.AVENUE) Color(0xFFE8C53A) else Color.White.copy(alpha = 0.8f), vp(e.a), vp(e.b), strokeWidth = (cam.zoom * 0.02f).coerceAtLeast(1f))
        if (e.oneWay != 0) { val m = Offset((vp(e.a).x + vp(e.b).x) / 2, (vp(e.a).y + vp(e.b).y) / 2); drawCircle(Color(0xFF4FC3F7), cam.zoom * 0.05f, m) }
    }
    val selE = vm.selectedEdgeObj()
    if (selE != null) for (e in city.chainFrom(selE)) drawLine(Color(0xFFFFEB3B), vp(e.a), vp(e.b), strokeWidth = (e.type.width.toFloat() * wScale * 0.5f).coerceAtLeast(3f), cap = StrokeCap.Round)
    for (rb in cfg.roundabouts) if (rb.cx in x0..x1 && rb.cy in y0..y1) drawCircle(Color(0xFF5E9E3F), cam.zoom * 0.75f, cam.project(rb.cx.toFloat(), rb.cy.toFloat(), if (relief) hAt(rb.cx, rb.cy) else 0f))

    // vista previa de trazado
    val prev = vm.roadPreview
    if (prev.size >= 2) for ((p, q) in City.expandPath(prev)) {
        val ok = city.canBuildEdge(p, q).ok
        drawLine(if (ok) Color(0xFF4FC3F7).copy(alpha = 0.85f) else Color(0xFFEF5350).copy(alpha = 0.85f), vp(p), vp(q), strokeWidth = (vm.roadType.width.toFloat() * wScale).coerceAtLeast(3f), cap = StrokeCap.Round)
    }

    // ---- edificios (orden por profundidad) ----
    val bs = city.buildingsIn(x0, y0, x1, y1).sortedBy { b -> val s = city.catalog[b.catalogId]; cam.depth(b.x + (s?.footprintW(b.rot) ?: 1) / 2f, b.y + (s?.footprintD(b.rot) ?: 1) / 2f) }
    for (b in bs) {
        val spec = city.catalog[b.catalogId] ?: continue
        val fw = spec.footprintW(b.rot); val fd = spec.footprintD(b.rot)
        val base = if (relief) hAt(b.x, b.y) else 0f
        val isMoving = vm.moving && vm.selectedBuilding == b.id
        drawBuilding(cam, spec, b.x, b.y, fw, fd, alpha = if (isMoving) 0.35f else 1f, baseH = base)
        if (b.id == vm.selectedBuilding) drawFootprint(cam, b.x, b.y, fw, fd, Color(0x33FFEB3B), Color(0xFFFFEB3B), base)
    }

    // ---- fantasma ----
    val g = vm.ghost
    if (g != null && (vm.tool == Tool.BUILD || vm.moving)) {
        val id = if (vm.moving) city.building(vm.selectedBuilding ?: "")?.catalogId else vm.selectedCatalogId
        val spec = id?.let { city.catalog[it] }
        if (spec != null) {
            val fw = spec.footprintW(vm.rot); val fd = spec.footprintD(vm.rot)
            val ok = vm.ghostValid
            drawFootprint(cam, g.first, g.second, fw, fd, (if (ok) Color(0x554CAF50) else Color(0x55F44336)), if (ok) Color(0xFF4CAF50) else Color(0xFFF44336))
            drawBuilding(cam, spec, g.first, g.second, fw, fd, alpha = 0.7f, tint = null)
        }
    }
}
