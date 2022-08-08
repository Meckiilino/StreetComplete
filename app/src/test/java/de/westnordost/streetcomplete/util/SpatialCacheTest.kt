package de.westnordost.streetcomplete.util

import de.westnordost.streetcomplete.data.download.tiles.asBoundingBoxOfEnclosingTiles
import de.westnordost.streetcomplete.data.download.tiles.enclosingTilePos
import de.westnordost.streetcomplete.data.download.tiles.enclosingTilesRect
import de.westnordost.streetcomplete.data.download.tiles.minTileRect
import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.osm.mapdata.Node
import de.westnordost.streetcomplete.data.osm.mapdata.NodeDao
import de.westnordost.streetcomplete.testutils.any
import de.westnordost.streetcomplete.testutils.mock
import de.westnordost.streetcomplete.testutils.node
import de.westnordost.streetcomplete.testutils.on
import de.westnordost.streetcomplete.util.ktx.containsExactlyInAnyOrder
import de.westnordost.streetcomplete.util.math.contains
import de.westnordost.streetcomplete.util.math.enclosingBoundingBox
import de.westnordost.streetcomplete.util.math.intersect
import de.westnordost.streetcomplete.util.math.isCompletelyInside
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.verify

internal class SpatialCacheTest {

    @Test fun `update doesn't put if tile doesn't exist`() {
        val node = node(1)
        val cache = SpatialCache<Long, Node>(
            16, 4, null, { emptyList() }, Node::id, Node::position
        )
        cache.update(updatedOrAdded = listOf(node))
        assertNull(cache.get(node.id))
    }

    @Test fun `get adds tile`() {
        val cache = SpatialCache<Long, Node>(
            16, 4, null, { emptyList() }, Node::id, Node::position
        )
        assertTrue(cache.getTiles().isEmpty())
        val tile = LatLon(1.0, 1.0).enclosingTilePos(16)
        cache.get(tile.asBoundingBox(16))
        assertTrue(cache.getTiles().contains(tile))
    }

    @Test fun `update puts if tile exists`() {
        val node = node(1)
        val nodeTile = node.position.enclosingTilePos(16)
        val cache = SpatialCache<Long, Node>(
            16, 4, null, { emptyList() }, Node::id, Node::position
        )
        cache.get(nodeTile.asBoundingBox(16))
        cache.update(updatedOrAdded = listOf(node))
        assertEquals(cache.get(node.id), node)
    }

    @Test fun `update replaces item`() {
        val node = node(1, LatLon(1.0, 0.0))

        val nodeTile = node.position.enclosingTilePos(16)
        val movedNode = node(1, LatLon(0.0, 1.0))
        val movedNodeTile = movedNode.position.enclosingTilePos(16)
        val cache = SpatialCache<Long, Node>(
            16, 4, null, { emptyList() }, Node::id, Node::position
        )
        cache.get(nodeTile.asBoundingBox(16))
        cache.update(updatedOrAdded = listOf(node))
        assertEquals(cache.get(node.id), node)

        assertEquals(cache.get(movedNodeTile.asBoundingBox(16)), emptyList<Node>())
        cache.update(updatedOrAdded = listOf(movedNode))
        assertEquals(cache.get(node.id), movedNode)
        assertEquals(cache.get(movedNodeTile.asBoundingBox(16)), listOf(movedNode))
        assertEquals(emptyList<Node>(), cache.get(nodeTile.asBoundingBox(16)))
    }

    @Test fun `get fetches data that is not in cache`() {
        val node = node(1, LatLon(1.0, 1.0))
        val nodeTile = node.position.enclosingTilePos(16)
        val nodeBBox = nodeTile.asBoundingBox(16)
        val nodeDB: NodeDao = mock()
        on(nodeDB.getAll(nodeTile.asBoundingBox(16))).thenReturn(listOf(node))
        val cache = SpatialCache<Long, Node>(
            16, 4, null, { nodeDB.getAll(it) }, Node::id, Node::position
        )
        assertEquals(cache.get(LatLon(1.0, 1.0).enclosingBoundingBox(0.0)), listOf(node))
        verify(nodeDB).getAll(nodeBBox)
    }

    @Test fun `get returns all the data in the bbox`() {
        val tile = LatLon(1.0, 1.0).enclosingTilePos(16)
        val tileBBox = tile.asBoundingBox(16)
        val nodesInsideFetchBBox = listOf(
            node(1, LatLon(tileBBox.min.latitude + 0.0001, tileBBox.min.longitude + 0.0001)),
            node(2, LatLon(tileBBox.min.latitude + 0.0002, tileBBox.min.longitude + 0.0002))
        )
        val fetchBBox = nodesInsideFetchBBox.map { it.position }.enclosingBoundingBox()
        val nodesInTileButOutsideFetchBBox = listOf(
            node(3, LatLon(tileBBox.max.latitude - 0.0001, tileBBox.max.longitude - 0.0001)),
            node(4, LatLon(tileBBox.max.latitude - 0.0002, tileBBox.max.longitude - 0.0002))
        )
        val nodesInOtherTile = listOf(
            node(5, LatLon(tileBBox.max.latitude - 0.0001, tileBBox.max.longitude + 0.0001)),
            node(6, LatLon(tileBBox.max.latitude - 0.0002, tileBBox.max.longitude + 0.0002))
        )
        // assert the nodes are in the correct tiles and bboxes don't overlap, because depending
        // on zoom, this may not be correct
        assertTrue((nodesInsideFetchBBox + nodesInTileButOutsideFetchBBox).map { it.position }.enclosingBoundingBox().isCompletelyInside(tileBBox))
        assertFalse(fetchBBox.intersect(nodesInTileButOutsideFetchBBox.map { it.position }.enclosingBoundingBox()))
        assertFalse(tile.asBoundingBox(16).intersect(nodesInOtherTile.map { it.position }.enclosingBoundingBox()))

        val cache = SpatialCache<Long, Node>(
            16, 4, null, { emptyList() }, Node::id, Node::position
        )
        val nodes = nodesInsideFetchBBox + nodesInTileButOutsideFetchBBox + nodesInOtherTile
        cache.replaceAllInBBox(nodes, nodes.map { it.position }.enclosingBoundingBox().asBoundingBoxOfEnclosingTiles(16))
        assertTrue(cache.getKeys().containsExactlyInAnyOrder(nodes.map { it.id }))
        assertTrue(cache.get(tileBBox).containsExactlyInAnyOrder(nodesInsideFetchBBox + nodesInTileButOutsideFetchBBox))
        assertTrue(cache.get(fetchBBox).containsExactlyInAnyOrder(nodesInsideFetchBBox))
    }

    @Test fun `get fetches only minimum tile rect for data that is partly in cache`() {
        val fullBBox = LatLon(0.0, 0.0).enclosingTilePos(15).asBoundingBox(15)
        val tileList = fullBBox.enclosingTilesRect(16).asTilePosSequence().toList()
        val topHalf = tileList.subList(0, 2) // top/bottom relies on the order in TilesRect.asTilePosSequence
        val bottomHalf = tileList.subList(2, 4)
        assertEquals(topHalf.size, 2) // subList with exclusive indexTo is a bit counter-intuitive
        assertEquals(bottomHalf.size, 2)

        val nodeDB: NodeDao = mock()
        on(nodeDB.getAll(any<BoundingBox>())).thenReturn(listOf())
        val cache = SpatialCache<Long, Node>(
            16, 4, null, { nodeDB.getAll(it) }, Node::id, Node::position
        )
        cache.get(topHalf.minTileRect()!!.asBoundingBox(16))
        verify(nodeDB).getAll(topHalf.minTileRect()!!.asBoundingBox(16))
        cache.get(fullBBox)
        verify(nodeDB).getAll(bottomHalf.minTileRect()!!.asBoundingBox(16))
    }

    @Test fun `update removes element if moving to non-cached tile`() {
        val node = node(1)
        val nodeTile = node.position.enclosingTilePos(16)
        val cache = SpatialCache<Long, Node>(
            16, 4, null, { emptyList() }, Node::id, Node::position
        )
        cache.get(nodeTile.asBoundingBox(16))
        cache.update(updatedOrAdded = listOf(node))
        cache.update(updatedOrAdded = listOf(node.copy(position = LatLon(1.0, 1.0))))
        assertNull(cache.get(node.id))
    }

    @Test fun replaceAllInBBox() {
        val nodeInside = node(1)
        val nodeTile = nodeInside.position.enclosingTilePos(16)
        val nodeOutside = node(2, LatLon(1.0, 1.0))
        val cache = SpatialCache<Long, Node>(
            16, 4, null, { emptyList() }, Node::id, Node::position
        )
        cache.replaceAllInBBox(listOf(nodeInside, nodeOutside), nodeTile.asBoundingBox(16))

        assertTrue(cache.getKeys().contains(nodeInside.id))
        assertFalse(cache.getKeys().contains(nodeOutside.id))
    }

    @Test fun replaceAllInBBoxButPartialTileSupplied() { // TODO
        val nodeInside = node(1)
        val nodeTile = nodeInside.position.enclosingTilePos(16)
        val nodeOutside = node(2, LatLon(1.0, 1.0))
        val cache = SpatialCache<Long, Node>(
            16, 4, null, { emptyList() }, Node::id, Node::position
        )
        cache.replaceAllInBBox(listOf(nodeInside, nodeOutside), nodeTile.asBoundingBox(16))

        assertTrue(cache.getKeys().contains(nodeInside.id))
        assertFalse(cache.getKeys().contains(nodeOutside.id))
    }

    @Test fun remove() {
        val node = node(1)
        val nodeTile = node.position.enclosingTilePos(16)
        val cache = SpatialCache<Long, Node>(
            16, 4, null, { emptyList() }, Node::id, Node::position
        )
        cache.get(nodeTile.asBoundingBox(16))
        cache.update(updatedOrAdded = listOf(node))
        cache.update(deleted = listOf(node.id))
        assertNull(cache.get(node.id))
    }

    @Test fun automaticTrim() {
        val cache = SpatialCache<Long, Node>(
            16, 2, null, { emptyList() }, Node::id, Node::position
        )
        cache.replaceAllInBBox(emptyList(), LatLon(0.0, 0.0).enclosingTilePos(16).asBoundingBox(16))
        cache.replaceAllInBBox(emptyList(), LatLon(1.0, 1.0).enclosingTilePos(16).asBoundingBox(16))
        cache.replaceAllInBBox(emptyList(), LatLon(-1.0, -1.0).enclosingTilePos(16).asBoundingBox(16))
        assertEquals(cache.getTiles().size, 2)
    }

}
