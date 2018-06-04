package com.infowings.catalog.data.aspect

import com.infowings.catalog.common.AspectData
import com.infowings.catalog.common.AspectPropertyCardinality
import com.infowings.catalog.common.AspectPropertyData
import com.infowings.catalog.data.MeasureService
import com.infowings.catalog.loggerFor
import com.infowings.catalog.storage.*
import com.orientechnologies.orient.core.id.ORecordId
import com.orientechnologies.orient.core.record.ODirection
import com.orientechnologies.orient.core.record.OEdge
import com.orientechnologies.orient.core.record.OVertex
import notDeletedSql

/** Should be used externally for query building. */
const val selectWithNameDifferentId =
    "SELECT from $ASPECT_CLASS WHERE name = :name and (@rid <> :aspectId) and $notDeletedSql"
const val selectWithName =
    "SELECT from $ASPECT_CLASS WHERE name = :name and $notDeletedSql"
const val selectFromAspectWithoutDeleted = "SELECT FROM $ASPECT_CLASS WHERE $notDeletedSql"
const val selectFromAspectWithDeleted = "SELECT FROM $ASPECT_CLASS"


class AspectDaoService(private val db: OrientDatabase, private val measureService: MeasureService) {

    fun createNewAspectVertex() = db.createNewVertex(ASPECT_CLASS).toAspectVertex()

    fun getVertex(id: String): OVertex? = db.getVertexById(id)

    fun getAspectVertex(aspectId: String) = db.getVertexById(aspectId)?.toAspectVertex()

    fun createNewAspectPropertyVertex() = db.createNewVertex(ASPECT_PROPERTY_CLASS).toAspectPropertyVertex()

    fun getAspectPropertyVertex(aspectPropertyId: String) = getVertex(aspectPropertyId)?.toAspectPropertyVertex()

    fun findByName(name: String): Set<AspectVertex> = db.query(selectWithName, mapOf("name" to name)) { rs ->
        rs.map { it.toVertex().toAspectVertex() }.toSet()
    }

    fun remove(vertex: OVertex) {
        db.delete(vertex)
    }

    fun fakeRemove(vertex: AspectVertex) {
        session(db) {
            vertex.deleted = true
            vertex.save<OVertex>()
        }
    }


    fun getAspects(): Set<AspectVertex> = db.query(selectFromAspectWithDeleted) { rs ->
        rs.mapNotNull { it.toVertexOrNull()?.toAspectVertex() }.toSet()
    }

    fun saveAspect(aspectVertex: AspectVertex, aspectData: AspectData): AspectVertex = session(db) {
        logger.debug("Saving aspect ${aspectData.name}, ${aspectData.measure}, ${aspectData.baseType}, ${aspectData.properties.size}")

        aspectVertex.name = aspectData.name?.trim() ?: throw AspectNameCannotBeNull()
        aspectVertex.description = aspectData.description?.trim()

        aspectVertex.baseType = when (aspectData.measure) {
            null -> aspectData.baseType
            else -> null
        }

        aspectVertex.measureName = aspectData.measure

        val measureVertex: OVertex? = aspectData.measure?.let { measureService.findMeasure(it) }

        measureVertex?.let {
            if (!aspectVertex.getVertices(ODirection.OUT, ASPECT_MEASURE_CLASS).contains(it)) {
                aspectVertex.addEdge(it, ASPECT_MEASURE_CLASS).save<OEdge>()
            }
        }

        aspectVertex.getEdges(ODirection.OUT, ASPECT_SUBJECT_EDGE).toList().forEach { it.delete<OEdge>() }
        aspectData.subject?.id?.let {
            aspectVertex.addEdge(db[it], ASPECT_SUBJECT_EDGE).save<OEdge>()
        }

        return@session aspectVertex.save<OVertex>().toAspectVertex()
    }

    fun saveAspectProperty(
        ownerAspectVertex: AspectVertex,
        aspectPropertyVertex: AspectPropertyVertex,
        aspectPropertyData: AspectPropertyData
    ): AspectPropertyVertex = transaction(db) {

        logger.debug("Saving aspect property ${aspectPropertyData.name} linked with aspect ${aspectPropertyData.aspectId}")

        val aspectVertex: OVertex = db.getVertexById(aspectPropertyData.aspectId)
                ?: throw AspectDoesNotExist(aspectPropertyData.aspectId)

        val cardinality = try {
            AspectPropertyCardinality.valueOf(aspectPropertyData.cardinality)
        } catch (exception: IllegalArgumentException) {
            throw AspectInconsistentStateException("Property has illegal cardinality value")
        }

        aspectPropertyVertex.name = aspectPropertyData.name.trim()
        aspectPropertyVertex.aspect = aspectPropertyData.aspectId
        aspectPropertyVertex.cardinality = cardinality.name
        aspectPropertyVertex.description = aspectPropertyData.description

        // it is not aspectPropertyVertex.properties in mind. This links describe property->aspect relation
        if (!aspectPropertyVertex.getVertices(ODirection.OUT, ASPECT_ASPECT_PROPERTY_EDGE).contains(aspectVertex)) {
            aspectPropertyVertex.addEdge(aspectVertex, ASPECT_ASPECT_PROPERTY_EDGE).save<OEdge>()
        }

        if (!ownerAspectVertex.properties.contains(aspectPropertyVertex)) {
            ownerAspectVertex.addEdge(aspectPropertyVertex, ASPECT_ASPECT_PROPERTY_EDGE).save<OEdge>()
        }

        return@transaction aspectPropertyVertex.save<OVertex>().toAspectPropertyVertex().also {
            logger.debug("Saved aspect property ${aspectPropertyData.name} with temporary id: ${it.id}")
        }
    }


    fun getAspectsByNameAndSubjectWithDifferentId(name: String, subjectId: String?, id: String?): Set<AspectVertex> {
        val baseQuery = if (id != null) selectWithNameDifferentId else selectWithName

        val q = if (subjectId == null) {
            baseQuery
        } else {
            "$baseQuery and (@rid in (select out.@rid from $ASPECT_SUBJECT_EDGE WHERE in.@rid = :subjectId))"
        }

        val args: Map<String, Any?> =
            mapOf("name" to name, "aspectId" to ORecordId(id), "subjectId" to ORecordId(subjectId))

        return db.query(q, args) { it.map { it.toVertex().toAspectVertex() }.toSet() }
    }

    /**
     * @param aspectId aspect id to start
     * @return list of the current aspect and all its parents
     */
    fun findParentAspects(aspectId: String): List<AspectData> = session(db) {
        val q = "traverse in(\"$ASPECT_ASPECT_PROPERTY_EDGE\").in() FROM :aspectRecord"
        return@session db.query(q, mapOf("aspectRecord" to ORecordId(aspectId))) {
            it.mapNotNull { it.toVertexOrNull()?.toAspectVertex()?.toAspectData() }.toList()
        }
    }

}

private val logger = loggerFor<AspectDaoService>()