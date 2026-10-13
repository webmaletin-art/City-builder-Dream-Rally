package dev.dreamrally.citybuilder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dreamrally.citybuilder.AppModel
import dev.dreamrally.citybuilder.model.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: AppModel) {
    var showNew by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<java.io.File?>(null) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        Text("City Builder", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Creá ciudades en el teléfono y exportalas para Dream Rally", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { showNew = true }, Modifier.weight(1f)) { Text("＋ Nueva ciudad") }
            OutlinedButton(onClick = { vm.createProject("Ciudad de prueba", Biome.TEMPERATE, Relief.GENTLE, demo = true) }, Modifier.weight(1f)) { Text("Ciudad de ejemplo") }
        }
        vm.catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        vm.toast?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Text("Tus ciudades", fontWeight = FontWeight.SemiBold)
        if (vm.projects.isEmpty()) Text("Todavía no hay ciudades guardadas.", Modifier.padding(top = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            items(vm.projects) { (dir, cfg) ->
                Card(onClick = { vm.openProject(dir) }, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cfg.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("${cfg.biome.label} • ${cfg.relief.label} • ${cfg.unlocked.size} sectores", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                        TextButton(onClick = { deleteTarget = dir }) { Text("Borrar") }
                    }
                }
            }
        }
        Text("Catálogo: ${vm.catalog.all.size} edificios propios. Para sumar modelos GLB autorizados: catálogo ampliado en Android/data/dev.dreamrally.citybuilder/files/catalog_ext", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    }
    deleteTarget?.let { d ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("¿Borrar ciudad?") }, text = { Text("Se elimina del teléfono. Los ZIP ya exportados no se tocan.") },
            confirmButton = { TextButton(onClick = { vm.deleteProject(d); deleteTarget = null }) { Text("Borrar") } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } })
    }
    if (showNew) NewCityDialog(onDismiss = { showNew = false }) { n, b, r -> showNew = false; vm.createProject(n, b, r, demo = false) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewCityDialog(onDismiss: () -> Unit, onCreate: (String, Biome, Relief) -> Unit) {
    var name by remember { mutableStateOf("Mi ciudad") }
    var biome by remember { mutableStateOf(Biome.TEMPERATE) }
    var relief by remember { mutableStateOf(Relief.FLAT) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Nueva ciudad") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                Text("Bioma", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Biome.entries.forEach { FilterChip(biome == it, { biome = it }, { Text(it.label) }) } }
                Text("Relieve", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Relief.entries.forEach { FilterChip(relief == it, { relief = it }, { Text(it.label) }) } }
                Text("Terreno inicial: 4 sectores de 128 m. Podés ampliarlo desde el editor.", fontSize = 12.sp)
            }
        },
        confirmButton = { Button(onClick = { onCreate(name, biome, relief) }) { Text("Crear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}
