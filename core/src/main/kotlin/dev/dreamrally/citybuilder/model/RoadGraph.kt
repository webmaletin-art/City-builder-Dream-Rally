package dev.dreamrally.citybuilder.model

/** Segmento lógico: cadena de aristas elementales entre dos nodos de grado != 2 (o cambio de atributos). */
data class RoadSegment(
    val id: String,
    val type: RoadType,
    val nodes: List<Vtx>,       // en sentido de circulación si oneWay
    val edgeIds: List<String>,
    val oneWay: Boolean,
    val group: String?
) {
    val start get() = nodes.first()
    val end get() = nodes.last()
    val isLoop get() = start == end
}

enum class NodeKind { ENDPOINT, THROUGH, INTERSECTION }

data class RoadNode(val v: Vtx, val degree: Int, val kind: NodeKind, val segments: List<String>)

data class Intersection(val id: String, val node: Vtx, val kind: String, val segments: List<String>, val roundabout: String?)

class RoadGraph(val edges: Collection<RoadEdge>) {
    val nodes: Map<Vtx, RoadNode>
    val segments: List<RoadSegment>
    val intersections: List<Intersection>
    /** Componentes conexas (para avisar de redes aisladas). */
    val components: Int
    private val adjacency: Map<Vtx, List<RoadEdge>>
    fun edgesAt(v: Vtx): List<RoadEdge> = adjacency[v] ?: emptyList()

    init {
        val adj = HashMap<Vtx, MutableList<RoadEdge>>()
        for (e in edges) { adj.getOrPut(e.a) { ArrayList() }.add(e); adj.getOrPut(e.b) { ArrayList() }.add(e) }
        fun other(e: RoadEdge, v: Vtx) = if (e.a == v) e.b else e.a
        /** +1: solo permite from->to, -1: solo to->from, 0: ambos */
        fun flag(e: RoadEdge, from: Vtx) = e.oneWay * (if (e.a == from) 1 else -1)
        fun compatible(c: RoadEdge, n: RoadEdge, via: Vtx): Boolean {
            if (c.type != n.type || c.group != n.group) return false
            return flag(c, other(c, via)) == flag(n, via)   // c viaja hacia via; n sale desde via
        }
        adjacency = adj
        val visited = HashSet<String>()
        val segs = ArrayList<RoadSegment>()
        val sortedEdges = edges.sortedBy { it.id }
        for (start in sortedEdges) {
            if (!visited.add(start.key)) continue
            val chainEdges = ArrayDeque<RoadEdge>(); chainEdges.addLast(start)
            val nodesL = ArrayDeque<Vtx>(); nodesL.addLast(start.a); nodesL.addLast(start.b)
            // adelante
            var cur = start; var node = start.b
            while (adj[node]!!.size == 2) {
                val nxt = adj[node]!!.first { it.key != cur.key }
                if (nxt.key in visited || !compatible(cur, nxt, node)) break
                visited += nxt.key; chainEdges.addLast(nxt); node = other(nxt, node); nodesL.addLast(node); cur = nxt
            }
            // atrás
            cur = start; node = start.a
            while (adj[node]!!.size == 2) {
                val prv = adj[node]!!.first { it.key != cur.key }
                if (prv.key in visited) break
                // prv viaja hacia node; cur sale desde node
                if (prv.type != cur.type || prv.group != cur.group || flag(prv, other(prv, node)) != flag(cur, node)) break
                visited += prv.key; chainEdges.addFirst(prv); node = other(prv, node); nodesL.addFirst(node); cur = prv
            }
            var ns = nodesL.toList(); var es = chainEdges.toList()
            val dir = flag(es.first(), ns[0])
            val oneWay = dir != 0
            if (dir < 0) { ns = ns.reversed(); es = es.reversed() }
            val id = "seg_" + es.minOf { it.id }.removePrefix("e_")
            segs += RoadSegment(id, start.type, ns, es.map { it.id }, oneWay, start.group)
        }
        segments = segs.sortedBy { it.id }

        val segAt = HashMap<Vtx, MutableList<String>>()
        for (s in segments) for (v in s.nodes) segAt.getOrPut(v) { ArrayList() }.add(s.id)
        nodes = adj.mapValues { (v, es) ->
            val deg = es.size
            RoadNode(v, deg, when { deg == 1 -> NodeKind.ENDPOINT; deg == 2 -> NodeKind.THROUGH; else -> NodeKind.INTERSECTION }, segAt[v]?.distinct() ?: emptyList())
        }
        val inter = ArrayList<Intersection>()
        for ((v, n) in nodes) if (n.kind == NodeKind.INTERSECTION || (n.kind == NodeKind.THROUGH && adj[v]!!.map { it.group }.distinct().size > 1)) {
            val rb = adj[v]!!.mapNotNull { it.group }.firstOrNull()
            val kind = when {
                rb != null && n.degree >= 3 -> "roundabout_entry"
                n.degree == 3 -> "T"; n.degree == 4 -> "cross"; else -> "complex"
            }
            inter += Intersection("int_${v.x}_${v.y}", v, kind, n.segments, rb)
        }
        intersections = inter.sortedBy { it.id }

        // componentes conexas por BFS
        val seen = HashSet<Vtx>(); var comps = 0
        for (v in adj.keys) {
            if (!seen.add(v)) continue
            comps++; val q = ArrayDeque<Vtx>(); q.add(v)
            while (q.isNotEmpty()) { val c = q.removeFirst(); for (e in adj[c]!!) { val o = other(e, c); if (seen.add(o)) q.add(o) } }
        }
        components = comps
    }
}
